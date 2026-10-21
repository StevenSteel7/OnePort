package samsungcompat;

import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MediaStore-backed replacement for Samsung's missing "sectrash" provider. */
final class Trash {
    static final String AUTHORITY = "sectrash";
    static final Uri FILES = Uri.parse("content://sectrash/files");
    private static final long RETENTION_MS = 30L * 24 * 60 * 60 * 1000;
    private static final Pattern MISSING_COLUMN = Pattern.compile("no such column: ([\\w.]+)");
    private static final List<ContentObserver> observers = new CopyOnWriteArrayList<>();

    private Trash() {}

    private static final String DEFAULT_SORT = "date_deleted DESC, _id DESC";

    private static boolean isAggregate(String[] projection) {
        if (projection == null) return false;
        for (String column : projection) if (column != null && column.contains("(")) return true;
        return false;
    }

    static boolean handles(Uri uri) {
        return uri != null && AUTHORITY.equals(uri.getAuthority());
    }

    static Cursor query(ContentResolver cr, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return query(cr, projection, selection, selectionArgs, null, null, sortOrder, null);
    }

    static synchronized Cursor query(ContentResolver cr, String[] projection, String selection,
            String[] selectionArgs, String groupBy, String having, String sortOrder, String limit) {
        if ((sortOrder == null || sortOrder.isEmpty()) && groupBy == null && !isAggregate(projection)) {
            // Samsung's sectrash/gallery returns newest deletions first (most days left at the top).
            sortOrder = DEFAULT_SORT;
        }
        SQLiteDatabase db = buildSnapshot(cr);
        try {
            Cursor c = null;
            for (int attempt = 0; c == null; attempt++) {
                try {
                    c = db.query("files", projection, selection, selectionArgs, groupBy, having, sortOrder, limit);
                } catch (SQLiteException e) {
                    Matcher m = MISSING_COLUMN.matcher(String.valueOf(e.getMessage()));
                    if (attempt > 40 || !m.find()) throw e;
                    db.execSQL("ALTER TABLE files ADD COLUMN " + m.group(1));
                }
            }
            try {
                return copy(c);
            } finally {
                c.close();
            }
        } finally {
            db.close();
        }
    }

    static Cursor query(ContentResolver cr, String[] projection, Bundle args) {
        Bundle a = args != null ? args : Bundle.EMPTY;
        return query(cr, projection,
                a.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
                a.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS),
                a.getString(ContentResolver.QUERY_ARG_SQL_GROUP_BY),
                a.getString(ContentResolver.QUERY_ARG_SQL_HAVING),
                a.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER),
                a.getString(ContentResolver.QUERY_ARG_SQL_LIMIT));
    }

    static Bundle call(ContentResolver cr, String method) {
        notifyChanged(cr);
        return new Bundle();
    }

    static ContentProviderResult[] applyBatch(ContentResolver cr, int count) {
        ContentProviderResult[] results = new ContentProviderResult[count];
        for (int i = 0; i < count; i++) results[i] = new ContentProviderResult(1);
        notifyChanged(cr);
        return results;
    }

    static Uri insert(ContentResolver cr, Uri uri, ContentValues values) {
        notifyChanged(cr);
        Long mediaId = values != null ? values.getAsLong("sec_media_id") : null;
        long id = mediaId != null ? mediaId : System.currentTimeMillis();
        return uri.buildUpon().appendPath(Long.toString(id)).build();
    }

    static int update(ContentResolver cr, Uri uri, ContentValues values, String where, String[] args) {
        notifyChanged(cr);
        return 1;
    }

    static int delete(ContentResolver cr, Uri uri, String where, String[] args) {
        String restored = uri != null ? uri.getQueryParameter("restored") : null;
        ArrayList<Long> ids = selectIds(cr, where, args);
        boolean ok;
        if ("true".equals(restored)) {
            ok = ids.isEmpty() || MediaStoreTrash.setTrashedIds(cr, ids, false);
        } else {
            ok = ids.isEmpty() || MediaStoreTrash.deleteIds(cr, ids);
        }
        if (ok) notifyChanged(cr);
        return ok ? Math.max(1, ids.size()) : 0;
    }

    static void register(ContentObserver observer) {
        if (observer != null && !observers.contains(observer)) observers.add(observer);
    }

    static void unregister(ContentObserver observer) {
        observers.remove(observer);
    }

    static void notifyChanged(ContentResolver cr) {
        for (ContentObserver o : observers) o.dispatchChange(false, FILES);
    }

    private static SQLiteDatabase buildSnapshot(ContentResolver cr) {
        SQLiteDatabase db = SQLiteDatabase.create(null);
        db.execSQL("CREATE TABLE files ("
                + "_id INTEGER, _data TEXT, original_path TEXT, _display_name TEXT, title TEXT,"
                + "title_hash TEXT, _size INTEGER, mime_type TEXT, date_expires INTEGER, date_deleted INTEGER,"
                + "date_modified INTEGER, media_id INTEGER, sec_media_id INTEGER, is_cloud INTEGER, parent INTEGER,"
                + "is_dir INTEGER, user_id INTEGER, volume_name TEXT, media_type INTEGER, width INTEGER, height INTEGER,"
                + "duration INTEGER, orientation INTEGER, orientation_tag INTEGER, cloud_server_id TEXT, extra TEXT,"
                + "delete_package_name TEXT, original_file_hash TEXT, cloud_thumb_path TEXT, is_trashed INTEGER)");

        // Rows go in newest-deletion first and _id is not the rowid, so plain scans (Gallery's grid uses
        // group_concat(_id), which ignores ORDER BY) list the most days left first.
        Cursor c = MediaStoreTrash.queryTrashed(cr);
        if (c == null) return db;
        try {
            int id = c.getColumnIndex(MediaStore.MediaColumns._ID);
            int data = c.getColumnIndex(MediaStore.MediaColumns.DATA);
            int name = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            int size = c.getColumnIndex(MediaStore.MediaColumns.SIZE);
            int mime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);
            int expires = c.getColumnIndex(MediaStore.MediaColumns.DATE_EXPIRES);
            int modified = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
            int type = c.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE);
            int width = c.getColumnIndex(MediaStore.MediaColumns.WIDTH);
            int height = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT);
            int duration = c.getColumnIndex(MediaStore.MediaColumns.DURATION);
            int orientation = c.getColumnIndex(MediaStore.Images.ImageColumns.ORIENTATION);
            int volume = c.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME);
            int relative = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH);
            db.beginTransaction();
            try {
                while (c.moveToNext()) {
                    long rowId = c.getLong(id);
                    String display = string(c, name);
                    String volumeName = string(c, volume);
                    String relativePath = string(c, relative);
                    String original = originalPath(volumeName, relativePath, display);
                    long expiry = epochMillis(number(c, expires));
                    long deleted = expiry > 0 ? Math.max(0, expiry - RETENTION_MS) : System.currentTimeMillis();
                    String extra = MediaStoreTrash.restoreExtra(relativePath, string(c, mime), number(c, size));
                    db.execSQL("INSERT OR REPLACE INTO files VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            new Object[] {rowId, string(c, data), original, display, baseName(display), display,
                                    number(c, size), string(c, mime), expiry, deleted, number(c, modified), rowId,
                                    rowId, 1, 0, 0, 0, volumeName, number(c, type), number(c, width),
                                    number(c, height), number(c, duration), number(c, orientation), 0, null, extra,
                                    "N/A", null, null, 1});
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            c.close();
        }
        return db;
    }

    private static ArrayList<Long> selectIds(ContentResolver cr, String where, String[] args) {
        ArrayList<Long> out = new ArrayList<>();
        SQLiteDatabase db = buildSnapshot(cr);
        try (Cursor c = db.query("files", new String[] {"media_id"}, where, args, null, null, null)) {
            while (c.moveToNext()) out.add(c.getLong(0));
        } catch (Exception ignored) {
        } finally {
            db.close();
        }
        return out;
    }

    private static long number(Cursor c, int index) {
        return index >= 0 && !c.isNull(index) ? c.getLong(index) : 0;
    }

    private static String string(Cursor c, int index) {
        return index >= 0 && !c.isNull(index) ? c.getString(index) : null;
    }

    private static long epochMillis(long value) {
        return value > 0 && value < 100000000000L ? value * 1000L : value;
    }

    private static String originalPath(String volume, String relative, String name) {
        String root = "external_primary".equals(volume)
                ? Environment.getExternalStorageDirectory().getAbsolutePath()
                : "/storage/" + (volume == null ? "emulated/0" : volume);
        StringBuilder out = new StringBuilder(root);
        if (relative != null && !relative.isEmpty()) {
            if (out.charAt(out.length() - 1) != '/') out.append('/');
            out.append(relative);
        }
        if (out.charAt(out.length() - 1) != '/') out.append('/');
        return out.append(name == null ? "" : name).toString();
    }

    private static String baseName(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static Cursor copy(Cursor c) {
        MatrixCursor out = new MatrixCursor(c.getColumnNames(), c.getCount());
        int n = c.getColumnCount();
        while (c.moveToNext()) {
            Object[] row = new Object[n];
            for (int i = 0; i < n; i++) {
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
}
