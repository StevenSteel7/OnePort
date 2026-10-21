package samsungcompat;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.provider.MediaStore;
import android.util.Log;
import java.io.File;

/**
 * One Recycle bin shared with Gallery: photos and videos go to Android's MediaStore trash (as Gallery's port
 * does), everything else keeps using My Files' own Android/.Trash folder.
 * - Trash: LocalOperation.A()'s rename into the trash folder is routed through {@link #renameTo}.
 * - List: {@link Trash} adds MediaStore's trashed photos/videos ({@link #addTrashedMedia}). Their _data
 *   contains "/.trashed-", which My Files already treats as MediaStore trash (TrashFileInfo.Q).
 * - Restore: My Files' own RestoreOperator updates is_trashed=0 for those items ("MP trash restored").
 * My Files holds MANAGE_EXTERNAL_STORAGE, so MediaStore accepts the is_trashed updates without a dialog.
 */
public final class MediaTrash {
    private static final String TAG = "MediaTrashCompat";
    private static final String OWN_TRASH = "/Android/.Trash/com.sec.android.app.myfiles/";
    private static final Uri FILES = MediaStore.Files.getContentUri("external");
    private static final long RETENTION_MS = 30L * 24 * 60 * 60 * 1000;

    private MediaTrash() {}

    /** Replaces File.renameTo in LocalOperation.A() (tools/patch.py). */
    public static boolean renameTo(File src, File dst) {
        if (dst != null && dst.getPath().contains(OWN_TRASH) && src.isFile()) {
            try {
                if (trash(src.getAbsolutePath())) return true;
            } catch (Exception e) {
                Log.w(TAG, "MediaStore trash failed, using My Files trash", e);
            }
        }
        return src.renameTo(dst);
    }

    private static boolean trash(String path) {
        Context context = context();
        if (context == null) return false;
        ContentResolver cr = context.getContentResolver();
        Bundle args = new Bundle();
        args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE);
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.MediaColumns.DATA + "=? AND " + MediaStore.Files.FileColumns.MEDIA_TYPE + " IN (1,3)");
        args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[] {path});
        long id;
        try (Cursor c = cr.query(FILES, new String[] {MediaStore.MediaColumns._ID}, args, null)) {
            if (c == null || !c.moveToFirst()) return false;   // not a photo/video MediaStore knows about
            id = c.getLong(0);
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_TRASHED, 1);
        int n = cr.update(Uri.withAppendedPath(FILES, Long.toString(id)), values, null);
        boolean ok = n > 0 && !new File(path).exists();
        Log.i(TAG, "trash " + id + " -> " + ok);
        if (ok) Trash.notifyChanged(cr);
        return ok;
    }

    /** Adds Android's trashed photos and videos to the in-memory sectrash table. */
    static void addTrashedMedia(ContentResolver cr, SQLiteDatabase db) {
        if (cr == null) return;
        Bundle args = new Bundle();
        args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY);
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, MediaStore.Files.FileColumns.MEDIA_TYPE + " IN (1,3)");
        String[] projection = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_EXPIRES, MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.RELATIVE_PATH};
        try (Cursor c = cr.query(FILES, projection, args, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String data = c.getString(1);
                if (data == null || !data.contains("/.trashed-")) continue;
                long expires = millis(c.getLong(5));
                long deleted = expires > 0 ? Math.max(0, expires - RETENTION_MS) : System.currentTimeMillis();
                String volume = c.getString(7);
                db.execSQL("INSERT OR REPLACE INTO files VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", new Object[] {
                        c.getLong(0), data, originalPath(volume, c.getString(8), c.getString(2)), c.getString(2),
                        c.getLong(3), c.getString(4), expires, deleted, c.getLong(6), c.getLong(0), 1, 0, 0,
                        Process.myUid() / 100000, volume});
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot list MediaStore trash", e);
        }
    }

    private static long millis(long value) {
        return value > 0 && value < 100000000000L ? value * 1000L : value;
    }

    private static String originalPath(String volume, String relative, String name) {
        String root = volume == null || "external_primary".equals(volume)
                ? Environment.getExternalStorageDirectory().getAbsolutePath()
                : "/storage/" + volume.toUpperCase();
        StringBuilder out = new StringBuilder(root);
        if (relative != null && !relative.isEmpty()) out.append('/').append(relative);
        if (out.charAt(out.length() - 1) != '/') out.append('/');
        return out.append(name == null ? "" : name).toString();
    }

    private static Context context() {
        try {
            Object app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Exception e) {
            return null;
        }
    }
}
