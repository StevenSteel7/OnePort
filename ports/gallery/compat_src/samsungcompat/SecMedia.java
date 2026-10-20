package samsungcompat;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stand-in for Samsung's extended MediaProvider ("secmedia"), which Gallery queries with raw SQL.
 * An in-memory SQLite db mirrors MediaStore into a "files" table; Samsung-only tables and columns are
 * created on demand (empty / default values) the first time a query asks for them.
 */
public final class SecMedia {
    public static final String AUTHORITY = "secmedia";
    private static final String TAG = "SecMediaCompat";
    private static final Uri MS_FILES = MediaStore.Files.getContentUri("external");

    /** Values for Samsung-only columns of "files"; an expression evaluated per row, or a constant. */
    private static final Map<String, String> FILES_DEFAULTS = new HashMap<>();
    static {
        FILES_DEFAULTS.put("media_id", "_id");
        FILES_DEFAULTS.put("is_cloud", "1");               // 1 = local only
        FILES_DEFAULTS.put("is_hide", "0");
        FILES_DEFAULTS.put("is_trashed", "0");
        FILES_DEFAULTS.put("is_pending", "0");
        FILES_DEFAULTS.put("is_favorite", "0");
        FILES_DEFAULTS.put("sef_file_type", "0");
        FILES_DEFAULTS.put("sef_file_sub_type", "0");
        FILES_DEFAULTS.put("recording_mode", "0");
        FILES_DEFAULTS.put("is_360_video", "0");
        FILES_DEFAULTS.put("is_drm", "0");
        FILES_DEFAULTS.put("burst_id", "0");
        FILES_DEFAULTS.put("group_id", "0");
        FILES_DEFAULTS.put("best_image", "1");
        FILES_DEFAULTS.put("local_time", "datetaken");
        FILES_DEFAULTS.put("sec_media_type", "media_type");
        FILES_DEFAULTS.put("storage_type", "0");
        // Samsung's capture time in ms (MediaStore: datetaken ms, date_modified s)
        FILES_DEFAULTS.put("datetime", "COALESCE(datetaken, date_modified * 1000, date_added * 1000)");
        FILES_DEFAULTS.put("burst_group_id", "0");
        FILES_DEFAULTS.put("group_type", "0");
        FILES_DEFAULTS.put("is_hdr10_video", "0");
        FILES_DEFAULTS.put("recordingtype", "0");
        FILES_DEFAULTS.put("resolution", "CAST(width AS TEXT) || 'x' || CAST(height AS TEXT)");
    }

    private static final Pattern INDEXED_BY = Pattern.compile("(?i)\\s+INDEXED\\s+BY\\s+\\w+");
    private static final Pattern NO_TABLE = Pattern.compile("no such table: (?:\\w+\\.)?(\\w+)");
    private static final Pattern NO_COLUMN = Pattern.compile("no such column: (?:(\\w+)\\.)?(\\w+)");

    private static SQLiteDatabase sDb;
    private static volatile boolean sDirty = true;
    private static long sGeneration;
    private static ContentObserver sObserver;

    private SecMedia() {}

    public static boolean handles(Uri uri) {
        return uri != null && AUTHORITY.equals(uri.getAuthority());
    }

    /**
     * Samsung's secmedia provider treats is_trashed as a database flag before Gallery moves the file itself.
     * AOSP MediaProvider instead interprets the same update as a filesystem move and rejects it on the generic
     * files collection. Keep that transient flag in the mirror so Gallery can complete its own trash operation.
     */
    public static boolean handlesTrashedUpdate(Uri uri, ContentValues values) {
        if (uri == null || values == null || !values.containsKey("is_trashed")
                || !"media".equals(uri.getAuthority())) return false;
        java.util.List<String> path = uri.getPathSegments();
        return path.size() >= 2 && "file".equals(path.get(1));
    }

    public static synchronized int updateTrashed(ContentResolver cr, Uri uri, ContentValues values, Bundle extras) {
        sync(cr);
        Bundle a = extras != null ? extras : Bundle.EMPTY;
        String selection = a.getString(ContentResolver.QUERY_ARG_SQL_SELECTION);
        String[] args = a.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS);
        java.util.List<String> path = uri.getPathSegments();
        if ((selection == null || selection.isEmpty()) && path.size() >= 3 && path.get(2).matches("\\d+")) {
            selection = "_id=?";
            args = new String[] {path.get(2)};
        }
        ContentValues mirrorValues = new ContentValues();
        mirrorValues.put("is_trashed", values.getAsInteger("is_trashed"));
        int count = sDb.update("files", mirrorValues, selection, args);
        Log.i(TAG, "mirror is_trashed update [" + count + "] " + selection);
        return count;
    }

    public static synchronized Cursor query(ContentResolver cr, Uri uri, String[] projection, String selection, String[] args, String sort) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (!path.startsWith("/raw_sql")) {
            Log.w(TAG, "unhandled uri " + uri);
            return new MatrixCursor(projection != null ? projection : new String[] {"_id"});
        }
        sync(cr);
        debugProbe();
        String sql = INDEXED_BY.matcher(selection).replaceAll(" ");   // Samsung's indexes don't exist here
        for (int attempt = 0; attempt < 60; attempt++) {
            try (Cursor c = sDb.rawQuery(sql, args)) {
                if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "[" + c.getCount() + "] " + abbrev(sql));
                if (Log.isLoggable(TAG, Log.VERBOSE) && isEmptyResult(c)) {
                    Log.v(TAG, "EMPTY args=" + java.util.Arrays.toString(args));
                    for (int i = 0; i < sql.length(); i += 3000) Log.v(TAG, "EMPTY " + sql.substring(i, Math.min(sql.length(), i + 3000)));
                }
                return copy(c);
            } catch (SQLiteException e) {
                if (!repair(sql, e.getMessage())) {
                    Log.e(TAG, "query failed: " + e.getMessage() + "\n  sql=" + sql);
                    throw e;
                }
            }
        }
        throw new SQLiteException("SecMedia: gave up repairing schema for " + abbrev(sql));
    }

    /** True for MediaStore's rejection of a Samsung-only column ("Invalid token media_id", "Invalid column ..."). */
    public static boolean isSamsungColumnRejection(Uri uri, IllegalArgumentException e) {
        String msg = e.getMessage();
        return uri != null && "media".equals(uri.getAuthority()) && msg != null
                && (msg.startsWith("Invalid token") || msg.startsWith("Invalid column"));
    }

    /** Serve a MediaStore table query that uses Samsung columns from the mirror instead. */
    public static Cursor queryMediaFallback(ContentResolver cr, Uri uri, String[] projection, String selection,
            String[] args, String sort) {
        String type = uri.getPathSegments().size() > 1 ? uri.getPathSegments().get(1) : "";
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(projection == null || projection.length == 0 ? "*" : String.join(", ", projection)).append(" FROM files");
        String where = "images".equals(type) ? "media_type=1" : "video".equals(type) ? "media_type=3" : null;
        String id = uri.getPathSegments().size() > 3 ? uri.getLastPathSegment() : null;   // .../media/<id>
        if (id != null && id.matches("\\d+")) where = (where == null ? "" : where + " AND ") + "_id=" + id;
        if (selection != null && !selection.isEmpty()) where = (where == null ? "" : where + " AND ") + "(" + selection + ")";
        if (where != null) sql.append(" WHERE ").append(where);
        if (sort != null && !sort.isEmpty()) sql.append(" ORDER BY ").append(sort);
        Log.i(TAG, "MediaStore fallback for " + uri + ": " + abbrev(sql.toString()));
        return query(cr, Uri.parse("content://" + AUTHORITY + "/raw_sql"), null, sql.toString(), args, null);
    }

    // ---- schema repair ----

    private static boolean repair(String sql, String msg) {
        if (msg == null) return false;
        Matcher m = NO_TABLE.matcher(msg);
        if (m.find()) {
            String t = m.group(1);
            Log.i(TAG, "create empty table " + t);
            sDb.execSQL("CREATE TABLE IF NOT EXISTS " + t + " (_id INTEGER PRIMARY KEY)");
            return true;
        }
        m = NO_COLUMN.matcher(msg);
        if (m.find()) {
            String table = resolveTable(sql, m.group(1));
            String col = m.group(2);
            if (table == null || columns(table).contains(col)) return false;
            addColumn(table, col);
            return true;
        }
        return false;
    }

    /** Find the real table behind an alias ("FROM files A", "JOIN faces AS F"); unqualified -> first FROM table. */
    private static String resolveTable(String sql, String alias) {
        Set<String> tables = tables();
        if (alias != null && tables.contains(alias)) return alias;
        Pattern p = alias != null
                ? Pattern.compile("(?i)\\b(?:FROM|JOIN)\\s+(\\w+)\\s+(?:AS\\s+)?" + Pattern.quote(alias) + "\\b")
                : Pattern.compile("(?i)\\bFROM\\s+(\\w+)");
        Matcher m = p.matcher(sql);
        while (m.find()) {
            if (tables.contains(m.group(1))) return m.group(1);
        }
        return alias == null && tables.contains("files") ? "files" : null;
    }

    private static void addColumn(String table, String col) {
        String def = "files".equals(table) ? FILES_DEFAULTS.get(col) : null;
        Log.i(TAG, "add column " + table + "." + col + (def != null ? " = " + def : ""));
        sDb.execSQL("ALTER TABLE " + table + " ADD COLUMN " + col);
        if (def != null) sDb.execSQL("UPDATE " + table + " SET " + col + " = " + def);
        addedColumns(table).add(col);
    }

    private static final Map<String, Set<String>> sAdded = new HashMap<>();
    private static Set<String> addedColumns(String table) {
        return sAdded.computeIfAbsent(table, k -> new HashSet<>());
    }

    private static Set<String> tables() {
        Set<String> out = new HashSet<>();
        try (Cursor c = sDb.rawQuery("SELECT name FROM sqlite_master WHERE type IN ('table','view')", null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    private static Set<String> columns(String table) {
        Set<String> out = new HashSet<>();
        try (Cursor c = sDb.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (c.moveToNext()) out.add(c.getString(1));
        }
        return out;
    }

    // ---- MediaStore mirror ----

    private static void sync(ContentResolver cr) {
        if (sDb == null) {
            sDb = SQLiteDatabase.create(null);
            sObserver = new ContentObserver(null) {
                @Override public void onChange(boolean selfChange) { sDirty = true; }
            };
            cr.registerContentObserver(MS_FILES, true, sObserver);
        }
        if (!sDirty) return;
        sDirty = false;
        try (Cursor src = cr.query(MS_FILES, null,
                MediaStore.Files.FileColumns.MEDIA_TYPE + " IN (1,3)", null, null)) {
            if (src == null) return;
            String[] cols = src.getColumnNames();
            sDb.beginTransaction();
            try {
                sDb.execSQL("DROP TABLE IF EXISTS files");
                StringBuilder ddl = new StringBuilder("CREATE TABLE files (");
                // MediaStore returns some numeric columns (bucket_id, ...) as strings; NUMERIC affinity turns them
                // back into numbers so Samsung's "bucket_id=123" comparisons match.
                for (int i = 0; i < cols.length; i++) {
                    ddl.append(i == 0 ? "" : ", ").append(cols[i]).append(isTextColumn(cols[i]) ? " TEXT" : " NUMERIC");
                }
                ddl.append(")");
                sDb.execSQL(ddl.toString());
                StringBuilder ins = new StringBuilder("INSERT INTO files VALUES (");
                for (int i = 0; i < cols.length; i++) ins.append(i == 0 ? "?" : ",?");
                ins.append(")");
                android.database.sqlite.SQLiteStatement st = sDb.compileStatement(ins.toString());
                while (src.moveToNext()) {
                    st.clearBindings();
                    for (int i = 0; i < cols.length; i++) {
                        switch (src.getType(i)) {
                            case Cursor.FIELD_TYPE_INTEGER: st.bindLong(i + 1, src.getLong(i)); break;
                            case Cursor.FIELD_TYPE_FLOAT: st.bindDouble(i + 1, src.getDouble(i)); break;
                            case Cursor.FIELD_TYPE_STRING: st.bindString(i + 1, src.getString(i)); break;
                            case Cursor.FIELD_TYPE_BLOB: st.bindBlob(i + 1, src.getBlob(i)); break;
                            default: st.bindNull(i + 1);
                        }
                    }
                    st.executeInsert();
                }
                st.close();
                // re-add Samsung columns that queries asked for earlier
                Set<String> have = new HashSet<>(java.util.Arrays.asList(cols));
                for (String c : new ArrayList<>(addedColumns("files"))) {
                    if (have.contains(c)) continue;
                    sDb.execSQL("ALTER TABLE files ADD COLUMN " + c);
                    String def = FILES_DEFAULTS.get(c);
                    if (def != null) sDb.execSQL("UPDATE files SET " + c + " = " + def);
                }
                // Gallery suppresses MediaStore observer events when this data stamp does not change. Samsung's
                // provider always exposes one row; an empty table makes SQL string concatenation return NULL.
                sDb.execSQL("CREATE TABLE IF NOT EXISTS local_metadata (generation_modified INTEGER)");
                if (!columns("local_metadata").contains("generation_modified")) {
                    sDb.execSQL("ALTER TABLE local_metadata ADD COLUMN generation_modified INTEGER");
                }
                sDb.execSQL("DELETE FROM local_metadata");
                sDb.execSQL("INSERT INTO local_metadata (generation_modified) VALUES (?)",
                        new Object[] {++sGeneration});
                sDb.setTransactionSuccessful();
            } finally {
                sDb.endTransaction();
            }
            Log.i(TAG, "mirrored " + src.getCount() + " media rows, " + cols.length + " columns");
        } catch (RuntimeException e) {
            Log.e(TAG, "MediaStore mirror failed", e);
        }
    }

    // ---- helpers ----

    private static final Pattern TEXT_COLUMN = Pattern.compile(
            "_data|.*name|title|.*_path|mime_type|album|artist|composer|genre|description|.*document_id|instance_id|xmp|_hash|.*_type_name|resolution|.*_tag.*");

    private static boolean isTextColumn(String col) {
        return TEXT_COLUMN.matcher(col).matches();
    }

    private static Cursor copy(Cursor c) {
        String[] names = c.getColumnNames();
        MatrixCursor out = new MatrixCursor(names, Math.max(c.getCount(), 1));
        Object[] row = new Object[names.length];
        while (c.moveToNext()) {
            for (int i = 0; i < names.length; i++) {
                switch (c.getType(i)) {
                    case Cursor.FIELD_TYPE_INTEGER: row[i] = c.getLong(i); break;
                    case Cursor.FIELD_TYPE_FLOAT: row[i] = c.getDouble(i); break;
                    case Cursor.FIELD_TYPE_STRING: row[i] = c.getString(i); break;
                    case Cursor.FIELD_TYPE_BLOB: row[i] = c.getBlob(i); break;
                    default: row[i] = null;
                }
            }
            out.addRow(row);
        }
        return out;
    }

    private static String sLastProbe = "";

    private static final java.io.File PROBE_FILE =
            new java.io.File("/sdcard/Android/data/com.sec.android.gallery3d/files/probe.sql");

    /** Debug (tools/probe.sh): SQL written to PROBE_FILE runs against the mirror on the next query. */
    private static void debugProbe() {
        if (!PROBE_FILE.exists()) return;
        String sql;
        try {
            sql = new String(java.nio.file.Files.readAllBytes(PROBE_FILE.toPath())).trim();
        } catch (java.io.IOException e) {
            return;
        }
        if (sql.isEmpty() || sql.equals(sLastProbe)) return;
        sLastProbe = sql;
        try (Cursor c = sDb.rawQuery(sql, null)) {
            Log.i(TAG, "PROBE " + sql + " -> " + c.getCount() + " rows");
            for (int r = 0; r < 20 && c.moveToNext(); r++) {
                StringBuilder sb = new StringBuilder("PROBE  ");
                for (int i = 0; i < c.getColumnCount(); i++) sb.append(c.getColumnName(i)).append('=').append(c.getString(i)).append(' ');
                Log.i(TAG, sb.toString());
            }
        } catch (RuntimeException e) {
            Log.i(TAG, "PROBE failed: " + e.getMessage());
        }
    }

    /** No rows, or a single aggregate row whose first value is NULL. */
    private static boolean isEmptyResult(Cursor c) {
        if (c.getCount() == 0) return true;
        boolean empty = c.getCount() == 1 && c.moveToFirst() && c.isNull(0);
        c.moveToPosition(-1);
        return empty;
    }

    private static String abbrev(String s) {
        return s == null ? "null" : s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }
}
