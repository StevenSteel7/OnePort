package samsungcompat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Android keeps trashed media under /storage/emulated/0/.trash-storage, which apps can't open by path (EACCES).
 * gallery_patch.py redirects Gallery's path-based file opens here; trashed paths are opened through their
 * MediaStore content URI, everything else goes to the original framework call unchanged.
 */
public final class TrashFiles {
    private TrashFiles() {}

    static ParcelFileDescriptor openTrashed(String path, String mode) throws FileNotFoundException {
        if (path == null || !MediaStoreTrash.isTrashedPath(path)) return null;
        Context context = MediaStoreTrash.context();
        if (context == null) return null;
        return context.getContentResolver().openFileDescriptor(MediaStoreTrash.uriForPath(path), mode);
    }

    public static FileInputStream fis(String path) throws FileNotFoundException {
        ParcelFileDescriptor pfd = openTrashed(path, "r");
        return pfd != null ? new ParcelFileDescriptor.AutoCloseInputStream(pfd) : new FileInputStream(path);
    }

    public static FileInputStream fis(File file) throws FileNotFoundException {
        ParcelFileDescriptor pfd = file != null ? openTrashed(file.getPath(), "r") : null;
        return pfd != null ? new ParcelFileDescriptor.AutoCloseInputStream(pfd) : new FileInputStream(file);
    }

    public static ParcelFileDescriptor open(File file, int mode) throws FileNotFoundException {
        ParcelFileDescriptor pfd = mode == ParcelFileDescriptor.MODE_READ_ONLY && file != null
                ? openTrashed(file.getPath(), "r") : null;
        return pfd != null ? pfd : ParcelFileDescriptor.open(file, mode);
    }

    public static void setDataSource(MediaPlayer player, String path) throws IOException {
        try (ParcelFileDescriptor pfd = openTrashed(path, "r")) {
            if (pfd != null) player.setDataSource(pfd.getFileDescriptor());
            else player.setDataSource(path);
        }
    }

    public static void setDataSource(MediaMetadataRetriever retriever, String path) {
        ParcelFileDescriptor pfd = null;
        try {
            pfd = openTrashed(path, "r");
        } catch (FileNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
        if (pfd == null) {
            retriever.setDataSource(path);
            return;
        }
        try (ParcelFileDescriptor p = pfd) {
            retriever.setDataSource(p.getFileDescriptor());
        } catch (IOException ignored) {
        }
    }

    public static Bitmap decodeFile(String path) {
        return decodeFile(path, null);
    }

    public static Bitmap decodeFile(String path, BitmapFactory.Options options) {
        try (ParcelFileDescriptor pfd = openTrashed(path, "r")) {
            if (pfd != null) return BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, options);
        } catch (IOException e) {
            return null;
        }
        return BitmapFactory.decodeFile(path, options);
    }

    public static ExifInterface exif(String path) throws IOException {
        try (ParcelFileDescriptor pfd = openTrashed(path, "r")) {
            if (pfd != null) return new ExifInterface(pfd.getFileDescriptor());
        }
        return new ExifInterface(path);
    }
}
