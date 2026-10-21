package samsungcompat;

import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bridges Samsung Gallery's file-oriented trash implementation to Android MediaStore. */
public final class MediaStoreTrash {
    private static final String TAG = "MediaStoreTrash";
    private static final Uri FILES = MediaStore.Files.getContentUri("external");
    private static final long CONFIRM_TIMEOUT_MS = 120_000L;
    /* After the user allowed, MediaStore applies the change before returning the result; this is a safety margin. */
    private static final long APPLY_TIMEOUT_MS = 10_000L;
    private static final long POLL_MS = 250L;
    private static final String[] PROJECTION = {
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_EXPIRES,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DURATION,
            MediaStore.Images.ImageColumns.ORIENTATION,
            MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_TRASHED
    };

    /* Original path -> MediaStore's new .trashed path after a batched confirmation. */
    private static final Map<String, String> PREFLIGHT_TRASH_PATHS =
            Collections.synchronizedMap(new LinkedHashMap<>());

    private MediaStoreTrash() {}

    public static Cursor queryTrashed(ContentResolver cr) {
        Bundle args = new Bundle();
        args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY);
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.Files.FileColumns.MEDIA_TYPE + " IN (1,3)");
        args.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                MediaStore.MediaColumns.DATE_EXPIRES + " DESC, " + MediaStore.MediaColumns._ID + " DESC");
        return cr.query(FILES, PROJECTION, args, null);
    }

    /** Called by TrashDeleteHelper instead of File.renameTo(). */
    public static String trash(String originalPath) {
        if (originalPath == null) return null;
        String prepared = PREFLIGHT_TRASH_PATHS.remove(originalPath);
        if (prepared != null) return prepared;

        Context context = appContext();
        if (context == null) return null;
        ContentResolver cr = context.getContentResolver();
        Row row = findByPath(cr, originalPath, MediaStore.MATCH_INCLUDE);
        if (row == null) {
            Log.w(TAG, "MediaStore row not found for " + originalPath);
            return null;
        }
        if (!row.trashed && !requestAndWait(context, Collections.singletonList(row.uri), Operation.TRASH)) {
            return null;
        }
        Row changed = findById(cr, row.id, MediaStore.MATCH_INCLUDE);
        return changed != null && changed.path != null ? changed.path : originalPath;
    }

    public static boolean preflightTrash(Object[] items) {
        Context context = appContext();
        if (context == null) return false;
        ContentResolver cr = context.getContentResolver();
        LinkedHashMap<String, Row> rows = rowsForItems(cr, items, MediaStore.MATCH_INCLUDE);
        ArrayList<Uri> needsRequest = new ArrayList<>();
        for (Row row : rows.values()) if (!row.trashed) needsRequest.add(row.uri);
        if (!needsRequest.isEmpty() && !requestAndWait(context, needsRequest, Operation.TRASH)) return false;
        for (Map.Entry<String, Row> entry : rows.entrySet()) {
            Row updated = findById(cr, entry.getValue().id, MediaStore.MATCH_INCLUDE);
            if (updated != null && updated.trashed) {
                PREFLIGHT_TRASH_PATHS.put(entry.getKey(), updated.path != null ? updated.path : entry.getKey());
            }
        }
        return rows.isEmpty() || PREFLIGHT_TRASH_PATHS.keySet().containsAll(rows.keySet());
    }

    public static boolean preflightRestore(Object[] items) {
        return preflight(items, Operation.RESTORE);
    }

    public static boolean preflightDelete(Object[] items) {
        return preflight(items, Operation.DELETE);
    }

    public static boolean preflightDeleteAny(Object[] items) {
        Context context = appContext();
        if (context == null) return false;
        Collection<Row> rows = rowsForItems(context.getContentResolver(), items, MediaStore.MATCH_INCLUDE).values();
        ArrayList<Uri> uris = new ArrayList<>();
        for (Row row : rows) uris.add(row.uri);
        return uris.isEmpty() || requestAndWait(context, uris, Operation.DELETE);
    }

    private static boolean preflight(Object[] items, Operation operation) {
        Context context = appContext();
        if (context == null) return false;
        Collection<Row> rows = rowsForItems(context.getContentResolver(), items, MediaStore.MATCH_ONLY).values();
        ArrayList<Uri> uris = new ArrayList<>();
        for (Row row : rows) uris.add(row.uri);
        return uris.isEmpty() || requestAndWait(context, uris, operation);
    }

    public static boolean setTrashed(ContentResolver cr, Uri uri, String selection, String[] args, boolean trashed) {
        ArrayList<Uri> uris = selectUris(cr, uri, selection, args, MediaStore.MATCH_INCLUDE);
        uris.removeIf(candidate -> {
            Row row = findById(cr, Long.parseLong(candidate.getLastPathSegment()), MediaStore.MATCH_INCLUDE);
            return row != null && row.trashed == trashed;
        });
        if (uris.isEmpty()) return true;
        Context context = appContext();
        return context != null && requestAndWait(context, uris, trashed ? Operation.TRASH : Operation.RESTORE);
    }

    public static boolean setTrashedIds(ContentResolver cr, List<Long> ids, boolean trashed) {
        ArrayList<Uri> uris = urisForIds(cr, ids);
        uris.removeIf(candidate -> {
            Row row = findById(cr, Long.parseLong(candidate.getLastPathSegment()), MediaStore.MATCH_INCLUDE);
            return row != null && row.trashed == trashed;
        });
        if (uris.isEmpty()) return true;
        Context context = appContext();
        return context != null && requestAndWait(context, uris, trashed ? Operation.TRASH : Operation.RESTORE);
    }

    public static boolean deleteIds(ContentResolver cr, List<Long> ids) {
        ArrayList<Uri> uris = urisForIds(cr, ids);
        if (uris.isEmpty()) return true;
        Context context = appContext();
        return context != null && requestAndWait(context, uris, Operation.DELETE);
    }

    /**
     * Called first by LocalThumbnailLoaderImpl.getThumbnail(ReqInfo). Android keeps trashed files under
     * .trash-storage, which Gallery cannot open by path (EACCES), so load those through MediaStore instead.
     * Returns null for everything else so Gallery's own decoder runs.
     */
    public static Bitmap thumbnail(Object req) {
        try {
            Object path = req.getClass().getField("path").get(req);
            if (!(path instanceof String) || !isTrashedPath((String) path)) return null;
            Object item = req.getClass().getMethod("getItem").invoke(req);
            long id = ((Number) item.getClass().getMethod("getFileId").invoke(item)).longValue();
            boolean video = (Boolean) item.getClass().getMethod("isVideo").invoke(item);
            int size = Math.max(req.getClass().getField("targetSize").getInt(req), 96);
            Context context = appContext();
            if (context == null) return null;
            Uri uri = mediaUri(id, video ? MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    : MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE);
            return context.getContentResolver().loadThumbnail(uri, new Size(size, size), null);
        } catch (Exception e) {
            Log.w(TAG, "Trashed thumbnail failed", e);
            return null;
        }
    }

    /** MediaStore media URI for a (trashed) file path; FileNotFoundException if MediaStore has no row. */
    static Uri uriForPath(String path) throws java.io.FileNotFoundException {
        Context context = appContext();
        Row row = context != null ? findByPath(context.getContentResolver(), path, MediaStore.MATCH_INCLUDE) : null;
        if (row == null) throw new java.io.FileNotFoundException(path + ": no MediaStore row");
        return row.uri;
    }

    static Context context() {
        return appContext();
    }

    static boolean isTrashedPath(String path) {
        return path.contains("/.trash-storage/") || path.contains("/.trashed-");
    }

    public static String restoreExtra(String relativePath, String mimeType, long size) {
        String path = relativePath == null ? "" : relativePath;
        String mime = mimeType == null ? "" : mimeType;
        return "{\"relative_path\":\"" + json(path) + "\",\"mime_type\":\""
                + json(mime) + "\",\"size\":" + size + "}";
    }

    private static boolean requestAndWait(Context context, Collection<Uri> uris, Operation operation) {
        if (uris.isEmpty()) return true;
        try {
            ConfirmRequest.askManageMediaOnce(context);
            PendingIntent request;
            if (operation == Operation.DELETE) {
                request = MediaStore.createDeleteRequest(context.getContentResolver(), uris);
            } else {
                request = MediaStore.createTrashRequest(context.getContentResolver(), uris,
                        operation == Operation.TRASH);
            }
            int answer = ConfirmRequest.show(request.getIntentSender());
            if (answer == ConfirmRequest.UNAVAILABLE) {
                // No Gallery Activity to host the dialog: start it detached and watch MediaStore instead.
                context.startIntentSender(request.getIntentSender(), null,
                        Intent.FLAG_ACTIVITY_NEW_TASK, Intent.FLAG_ACTIVITY_NEW_TASK, 0);
            } else if (answer != android.app.Activity.RESULT_OK) {
                Log.i(TAG, operation + " declined count=" + uris.size());
                return false;
            }
            boolean ok = waitForState(context.getContentResolver(), uris, operation,
                    answer == ConfirmRequest.UNAVAILABLE ? CONFIRM_TIMEOUT_MS : APPLY_TIMEOUT_MS);
            Log.i(TAG, operation + " confirmed=" + ok + " count=" + uris.size());
            return ok;
        } catch (Exception e) {
            Log.e(TAG, operation + " request failed", e);
            return false;
        }
    }

    private static boolean waitForState(ContentResolver cr, Collection<Uri> uris, Operation operation, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean complete = true;
            for (Uri uri : uris) {
                Row row = findById(cr, Long.parseLong(uri.getLastPathSegment()), MediaStore.MATCH_INCLUDE);
                if (operation == Operation.DELETE ? row != null
                        : row == null || row.trashed != (operation == Operation.TRASH)) {
                    complete = false;
                    break;
                }
            }
            if (complete) return true;
            Thread.sleep(POLL_MS);
        }
        return false;
    }

    private static LinkedHashMap<String, Row> rowsForItems(ContentResolver cr, Object[] items, int match) {
        LinkedHashMap<String, Row> out = new LinkedHashMap<>();
        if (items == null) return out;
        for (Object item : items) {
            String path = itemPath(item);
            if (path == null || out.containsKey(path)) continue;
            Row row = findByPath(cr, path, match);
            if (row != null) out.put(path, row);
        }
        return out;
    }

    private static String itemPath(Object item) {
        if (item == null) return null;
        try {
            Method method = item.getClass().getMethod("getPath");
            Object value = method.invoke(item);
            return value instanceof String ? (String) value : null;
        } catch (Exception e) {
            Log.w(TAG, "Cannot read selected item path", e);
            return null;
        }
    }

    private static ArrayList<Uri> selectUris(ContentResolver cr, Uri uri, String selection, String[] args, int match) {
        ArrayList<Uri> out = new ArrayList<>();
        Bundle query = new Bundle();
        query.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, match);
        if (selection != null) query.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection);
        if (args != null) query.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args);
        String[] projection = {MediaStore.MediaColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE};
        try (Cursor c = cr.query(uri, projection, query, null)) {
            if (c != null) while (c.moveToNext()) out.add(mediaUri(c.getLong(0), c.getInt(1)));
        } catch (Exception e) {
            Log.e(TAG, "Unable to resolve MediaStore request", e);
        }
        return out;
    }

    private static ArrayList<Uri> urisForIds(ContentResolver cr, List<Long> ids) {
        ArrayList<Uri> out = new ArrayList<>();
        if (ids == null) return out;
        for (Long id : ids) {
            if (id == null) continue;
            Row row = findById(cr, id, MediaStore.MATCH_INCLUDE);
            if (row != null) out.add(row.uri);
        }
        return out;
    }

    /* createTrashRequest/createDeleteRequest reject Files URIs: "All requested items must be Media items". */
    private static Uri mediaUri(long id, int mediaType) {
        Uri base;
        switch (mediaType) {
            case MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE:
                base = MediaStore.Images.Media.getContentUri("external");
                break;
            case MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO:
                base = MediaStore.Video.Media.getContentUri("external");
                break;
            case MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO:
                base = MediaStore.Audio.Media.getContentUri("external");
                break;
            default:
                base = FILES;
        }
        return Uri.withAppendedPath(base, Long.toString(id));
    }

    private static Row findByPath(ContentResolver cr, String path, int match) {
        Bundle args = new Bundle();
        args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, match);
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, MediaStore.MediaColumns.DATA + "=?");
        args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[] {path});
        return first(cr, FILES, args);
    }

    private static Row findById(ContentResolver cr, long id, int match) {
        Bundle args = new Bundle();
        args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, match);
        return first(cr, Uri.withAppendedPath(FILES, Long.toString(id)), args);
    }

    private static Row first(ContentResolver cr, Uri uri, Bundle args) {
        String[] projection = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.IS_TRASHED, MediaStore.Files.FileColumns.MEDIA_TYPE};
        try (Cursor c = cr.query(uri, projection, args, null)) {
            if (c != null && c.moveToFirst()) {
                long id = c.getLong(0);
                return new Row(id, mediaUri(id, c.getInt(3)), c.getString(1), c.getInt(2) != 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "MediaStore lookup failed for " + uri, e);
        }
        return null;
    }

    private static Context appContext() {
        try {
            Class<?> cls = Class.forName("com.samsung.android.gallery.support.utils.AppResources");
            Object value = cls.getMethod("getAppContext").invoke(null);
            return value instanceof Context ? (Context) value : null;
        } catch (Exception e) {
            Log.e(TAG, "Gallery application context unavailable", e);
            return null;
        }
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private enum Operation { TRASH, RESTORE, DELETE }

    private static final class Row {
        final long id;
        final Uri uri;
        final String path;
        final boolean trashed;

        Row(long id, Uri uri, String path, boolean trashed) {
            this.id = id;
            this.uri = uri;
            this.path = path;
            this.trashed = trashed;
        }
    }
}
