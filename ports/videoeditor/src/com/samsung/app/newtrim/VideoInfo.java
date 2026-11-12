package com.samsung.app.newtrim;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What the editor needs to know about the source clip. Load off the main thread. */
public final class VideoInfo {
    public long durationMs;
    public int width, height, rotation;
    public boolean hasAudio;
    public long bitrate;             // container bitrate (bits/s), 0 if unknown
    public String displayName = "Video";
    public String relativePath;      // MediaStore RELATIVE_PATH of the source, null if not a MediaStore item
    public long dateTakenMs;         // 0 if unknown
    public float latitude = Float.NaN, longitude = Float.NaN;

    /** Width/height as shown on screen (rotation metadata applied). */
    public int displayWidth() { return rotation % 180 == 0 ? width : height; }
    public int displayHeight() { return rotation % 180 == 0 ? height : width; }

    public static VideoInfo load(Context ctx, Uri uri) throws IOException {
        VideoInfo info = new VideoInfo();
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, uri);
            info.durationMs = parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            info.width = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            info.height = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            info.rotation = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            info.bitrate = parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
            info.hasAudio = "yes".equals(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
            if (!"yes".equals(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))) {
                throw new IOException("not a video: " + uri);
            }
            parseLocation(info, r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION));
        } catch (RuntimeException e) {
            throw new IOException("can't read " + uri, e);
        } finally {
            try { r.release(); } catch (IOException ignored) { }
        }
        if (info.durationMs <= 0 || info.width <= 0 || info.height <= 0) throw new IOException("bad metadata " + uri);
        queryStore(ctx.getContentResolver(), uri, info);
        return info;
    }

    private static void queryStore(ContentResolver cr, Uri uri, VideoInfo info) {
        boolean media = MediaStore.AUTHORITY.equals(uri.getAuthority());
        String[] cols = media
                ? new String[]{MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DATE_TAKEN}
                : new String[]{OpenableColumns.DISPLAY_NAME};
        try (Cursor c = cr.query(uri, cols, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null && !name.isEmpty()) info.displayName = name;
                if (media) {
                    info.relativePath = c.getString(1);
                    info.dateTakenMs = c.isNull(2) ? 0 : c.getLong(2);
                }
            }
        } catch (RuntimeException ignored) {
            // Picker / third-party URIs may not support every column; defaults are fine.
        }
    }

    // ISO-6709, e.g. "+37.5090-127.0283/" or "+37.5090-127.0283+012.3/"
    private static final Pattern ISO6709 = Pattern.compile("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)");

    private static void parseLocation(VideoInfo info, String s) {
        if (s == null) return;
        Matcher m = ISO6709.matcher(s);
        if (!m.find()) return;
        try {
            float lat = Float.parseFloat(m.group(1)), lon = Float.parseFloat(m.group(2));
            if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180) { info.latitude = lat; info.longitude = lon; }
        } catch (NumberFormatException ignored) { }
    }

    private static long parseLong(String s) {
        if (s == null) return 0;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
