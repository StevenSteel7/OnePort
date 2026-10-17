package samsungcompat;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Photo Editor's use of Samsung's MediaProvider ("secmedia"), on top of the Gallery-derived {@link SecMedia} mirror.
 * - secmedia/media[/id]: Samsung's view of MediaStore files. Mapped onto MediaStore.Files; reads that ask for
 *   Samsung-only columns (datetime, date_restored, hash, ...) fall back to the SecMedia mirror, writes drop the
 *   Samsung-only columns MediaStore rejects. _data is never written (MediaStore treats it as a move).
 * - secmedia/nondestruction: the editor's hash -> original-backup table used by Revert. Persisted in a small SQLite
 *   file in the app's database dir, schema grown on demand.
 */
public final class PeMedia {
    private static final String TAG = "PeMediaCompat";
    private static final Uri MS_FILES = MediaStore.Files.getContentUri("external");
    private static final String ND = "nondestruction";
    private static final Pattern BAD_COLUMN = Pattern.compile("(?:Invalid column|no column named|no such column:?) ?(\\w+)");

    private static SQLiteDatabase sNd;

    private PeMedia() {}

    public static boolean handles(Uri uri) {
        if (uri == null || !SecMedia.AUTHORITY.equals(uri.getAuthority())) return false;
        List<String> s = uri.getPathSegments();
        return !s.isEmpty() && ("media".equals(s.get(0)) || ND.equals(s.get(0)));
    }

    private static boolean isNd(Uri uri) {
        return ND.equals(uri.getPathSegments().get(0));
    }

    /** secmedia/media[/id] -> content://media/external/file[/id] */
    private static Uri toMediaStore(Uri uri) {
        List<String> s = uri.getPathSegments();
        String last = s.get(s.size() - 1);
        return s.size() > 1 && last.matches("\\d+") ? ContentUris.withAppendedId(MS_FILES, Long.parseLong(last)) : MS_FILES;
    }

    // ---- query ----

    public static Cursor query(ContentResolver cr, Uri uri, String[] projection, String selection, String[] args, String sort) {
        if (isNd(uri)) return nd().query(ND, projection, selection, args, null, null, sort);
        Uri ms = toMediaStore(uri);
        try {
            return cr.query(ms, projection, selection, args, sort);
        } catch (IllegalArgumentException e) {
            if (!SecMedia.isSamsungColumnRejection(ms, e)) throw e;
            String sel = selection;
            if (ms != MS_FILES) {
                String id = "_id=" + ContentUris.parseId(ms);
                sel = sel == null || sel.isEmpty() ? id : id + " AND (" + sel + ")";
            }
            return SecMedia.queryMediaFallback(cr, MS_FILES, projection, sel, args, sort);
        }
    }

    // ---- writes ----

    public static int update(ContentResolver cr, Uri uri, ContentValues values, String where, String[] args) {
        if (isNd(uri)) return ndWrite(() -> nd().update(ND, values, where, args), values);
        Bundle b = new Bundle();
        if (where != null) b.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where);
        if (args != null) b.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args);
        return update(cr, uri, values, b);
    }

    public static int update(ContentResolver cr, Uri uri, ContentValues values, Bundle extras) {
        if (isNd(uri)) {
            Bundle a = extras != null ? extras : Bundle.EMPTY;
            return update(cr, uri, values, a.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
                    a.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS));
        }
        ContentValues v = new ContentValues(values);
        v.remove(MediaStore.MediaColumns.DATA);
        Uri ms = toMediaStore(uri);
        int n = 0;
        while (v.size() > 0) {
            try {
                n = cr.update(ms, v, extras);
                break;
            } catch (IllegalArgumentException | SecurityException e) {
                String col = badColumn(e);
                if (col == null || !v.containsKey(col)) {
                    Log.w(TAG, "update " + ms + " " + v.keySet() + " failed: " + e);
                    return 0;
                }
                v.remove(col);
            }
        }
        SecMedia.markDirty();
        Log.i(TAG, "update " + uri + " -> " + ms + " " + v.keySet() + " (dropped " + dropped(values, v) + ") = " + n);
        return n;
    }

    public static Uri insert(ContentResolver cr, Uri uri, ContentValues values) {
        if (isNd(uri)) {
            long id = ndWrite(() -> (int) nd().insertOrThrow(ND, null, values), values);
            Log.i(TAG, "nondestruction insert " + values + " -> " + id);
            return id > 0 ? ContentUris.withAppendedId(uri, id) : null;
        }
        Log.w(TAG, "insert into " + uri + " not supported, ignored: " + values.keySet());
        return null;
    }

    public static int delete(ContentResolver cr, Uri uri, String where, String[] args) {
        if (isNd(uri)) return nd().delete(ND, where, args);
        Log.w(TAG, "delete on " + uri + " ignored (" + where + ")");
        return 0;
    }

    // ---- nondestruction table ----

    private interface Write { int run(); }

    private static int ndWrite(Write w, ContentValues values) {
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                return w.run();
            } catch (SQLiteException e) {
                String col = badColumn(e);
                if (col == null || !values.containsKey(col)) throw e;
                nd().execSQL("ALTER TABLE " + ND + " ADD COLUMN " + col);
            }
        }
        return 0;
    }

    private static synchronized SQLiteDatabase nd() {
        if (sNd == null) {
            Context ctx = app();
            File f = ctx != null ? ctx.getDatabasePath("compat_nondestruction.db") : null;
            if (f != null) f.getParentFile().mkdirs();
            sNd = f != null ? SQLiteDatabase.openOrCreateDatabase(f, null) : SQLiteDatabase.create(null);
            sNd.execSQL("CREATE TABLE IF NOT EXISTS " + ND
                    + " (_id INTEGER PRIMARY KEY AUTOINCREMENT, hash TEXT, path TEXT, _size INTEGER)");
        }
        return sNd;
    }

    private static final String SEC_DIR = "/data/sec/photoeditor/";

    /**
     * Samsung keeps Revert backups ("non-destructive" originals) and S Pen re-edit data under /data/sec/photoeditor/,
     * a system directory. Patched const-strings call this: same layout, inside the app's private files dir.
     */
    public static String secPath(String path) {
        if (path == null || !path.startsWith(SEC_DIR)) return path;
        Context ctx = app();
        if (ctx == null) return path;
        File base = new File(ctx.getFilesDir(), "photoeditor");
        base.mkdirs();
        return base.getAbsolutePath() + "/" + path.substring(SEC_DIR.length());
    }

    static Context app() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String badColumn(Exception e) {
        Matcher m = BAD_COLUMN.matcher(String.valueOf(e.getMessage()));
        return m.find() ? m.group(1) : null;
    }

    private static String dropped(ContentValues all, ContentValues kept) {
        StringBuilder sb = new StringBuilder();
        for (String k : all.keySet()) if (!kept.containsKey(k)) sb.append(sb.length() > 0 ? "," : "").append(k);
        return sb.toString();
    }
}
