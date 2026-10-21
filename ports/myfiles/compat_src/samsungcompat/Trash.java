package samsungcompat;

import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stand-in for Samsung's system "sectrash" provider, which does not exist on non-Samsung phones.
 *
 * My Files moves a trashed item to
 *   <volume>/Android/.Trash/com.sec.android.app.myfiles/<uuid>/<deleteTimeMs>/<original parent>/.!%#@$/<name>
 * and does the actual file moves/deletes itself; the provider only indexes them. So the index is rebuilt
 * from disk on every query (into an in-memory SQLite table, so the app's own SQL selections work unchanged),
 * and row deletes/updates are no-ops.
 *
 * Samsung's system service also empties expired items after 30 days; that is done here, on each listing:
 * a delete-time folder whose items have expired is removed as a whole. Items that were already older than
 * 30 days when this was first installed count from that day instead (recorded in EXPIRY_START_FILE),
 * so the backlog is not wiped the first time it runs.
 */
final class Trash {
    static final String AUTHORITY = "sectrash";
    static final Uri FILES = Uri.parse("content://sectrash/files");
    private static final String TRASH_DIR = "Android/.Trash/com.sec.android.app.myfiles";
    private static final String MARKER = ".!%#@$";
    private static final long RETENTION_MS = 30L * 24 * 60 * 60 * 1000;
    private static final String EXPIRY_START_FILE = ".compat_expiry_start";
    private static long expiryStart;
    private static final Pattern MISSING_COLUMN = Pattern.compile("no such column: ([\\w.]+)");
    // the system refuses observers on an authority with no provider, so sectrash observers live here
    private static final List<ContentObserver> observers = new CopyOnWriteArrayList<>();

    private Trash() {}

    static boolean handles(Uri uri) {
        return uri != null && AUTHORITY.equals(uri.getAuthority());
    }

    static synchronized Cursor query(ContentResolver cr, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        SQLiteDatabase db = SQLiteDatabase.create(null);
        try {
            db.execSQL("CREATE TABLE files (_id INTEGER PRIMARY KEY, _data TEXT, original_path TEXT, _display_name TEXT,"
                    + " _size INTEGER, mime_type TEXT, date_expires INTEGER, date_deleted INTEGER, date_modified INTEGER,"
                    + " media_id INTEGER, is_cloud INTEGER, parent INTEGER, is_dir INTEGER, user_id INTEGER, volume_name TEXT)");
            long now = System.currentTimeMillis();
            long start = expiryStart();
            db.beginTransaction();
            try {
                for (File volume : volumes()) {
                    String volumeName = volume.equals(Environment.getExternalStorageDirectory())
                            ? "external_primary" : volume.getName().toLowerCase();
                    File[] owners = new File(volume, TRASH_DIR).listFiles();
                    if (owners == null) continue;
                    for (File owner : owners) {
                        File[] times = owner.listFiles();
                        if (times == null) continue;
                        for (File time : times) {
                            long deleted;
                            try {
                                deleted = Long.parseLong(time.getName());
                            } catch (NumberFormatException e) {
                                continue;
                            }
                            long expires = Math.max(deleted, start) + RETENTION_MS;
                            if (expires <= now) {
                                deleteRecursively(time);
                                continue;
                            }
                            findMarkers(db, time, time, deleted, expires, volumeName);
                        }
                    }
                }
                MediaTrash.addTrashedMedia(cr, db);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            Cursor c = null;
            for (int attempt = 0; c == null; attempt++) {
                try {
                    c = db.query("files", projection, selection, selectionArgs, null, null, sortOrder);
                } catch (SQLiteException e) {
                    // Samsung's table has more columns than we fill in; add any the app asks for as NULL
                    Matcher m = MISSING_COLUMN.matcher(String.valueOf(e.getMessage()));
                    if (attempt > 20 || !m.find()) throw e;
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

    static Bundle call(ContentResolver cr, String method) {
        notifyChanged(cr);   // "scan" after a move to trash: listing is live, just tell observers to reload
        return new Bundle();
    }

    static ContentProviderResult[] applyBatch(ContentResolver cr, int count) {
        ContentProviderResult[] results = new ContentProviderResult[count];
        for (int i = 0; i < count; i++) results[i] = new ContentProviderResult(1);
        notifyChanged(cr);
        return results;
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

    private static List<File> volumes() {
        List<File> out = new ArrayList<>();
        out.add(Environment.getExternalStorageDirectory());
        File[] mounted = new File("/storage").listFiles();
        if (mounted != null) {
            for (File f : mounted) {
                if (!f.getName().equals("emulated") && !f.getName().equals("self")) out.add(f);
            }
        }
        return out;
    }

    /** Walk the mirrored original-path folders under one delete-time dir, indexing the contents of each marker dir. */
    private static void findMarkers(SQLiteDatabase db, File timeDir, File dir, long deleted, long expires, String volumeName) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            if (child.getName().equals(MARKER)) {
                String originalParent = dir.getAbsolutePath().substring(timeDir.getAbsolutePath().length());
                File[] items = child.listFiles();
                if (items == null) continue;
                for (File item : items) add(db, item, originalParent + "/" + item.getName(), deleted, expires, 0, volumeName);
            } else {
                findMarkers(db, timeDir, child, deleted, expires, volumeName);
            }
        }
    }

    /** Index a trashed file or folder (recursively); returns its total size. */
    private static long add(SQLiteDatabase db, File f, String originalPath, long deleted, long expires, long parent, String volumeName) {
        long id = id(f);
        boolean dir = f.isDirectory();
        long size = 0;
        if (dir) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) size += add(db, c, originalPath + "/" + c.getName(), deleted, expires, id, volumeName);
            }
        } else {
            size = f.length();
        }
        db.execSQL("INSERT OR REPLACE INTO files VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", new Object[] {
                id, f.getAbsolutePath(), originalPath, f.getName(), size, dir ? null : mimeType(f.getName()),
                expires, deleted, f.lastModified() / 1000, 0, 1, parent, dir ? 1 : 0,
                Process.myUid() / 100000, volumeName});
        return size;
    }

    /** When auto-expiry started on this phone; created on first use. */
    private static long expiryStart() {
        if (expiryStart != 0) return expiryStart;
        File f = new File(new File(Environment.getExternalStorageDirectory(), TRASH_DIR), EXPIRY_START_FILE);
        try {
            expiryStart = Long.parseLong(new String(Files.readAllBytes(f.toPath())).trim());
        } catch (Exception e) {
            expiryStart = System.currentTimeMillis();
            try {
                f.getParentFile().mkdirs();
                Files.write(f.toPath(), Long.toString(expiryStart).getBytes());
            } catch (Exception ignored) {
                // can't persist: expire relative to this session only, still never earlier than now + 30 days
            }
        }
        return expiryStart;
    }

    private static void deleteRecursively(File f) {
        File[] children = f.isDirectory() && !Files.isSymbolicLink(f.toPath()) ? f.listFiles() : null;
        if (children != null) for (File c : children) deleteRecursively(c);
        f.delete();
    }

    private static long id(File f) {
        long h = 1125899906842597L;
        String s = f.getAbsolutePath();
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        h &= Long.MAX_VALUE;
        return h == 0 ? 1 : h;
    }

    private static String mimeType(String name) {
        int dot = name.lastIndexOf('.');
        String type = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
        return type != null ? type : "application/octet-stream";
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
