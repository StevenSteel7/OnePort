package com.samsung.app.newtrim;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;
import android.media.MediaScannerConnection;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

/**
 * Trim / rotate / mute into a new MediaStore item next to the original (the original is never modified).
 *
 * Two paths:
 *  - Lossless: compressed samples are copied with MediaExtractor -> MediaMuxer. Used when the start is on a key frame
 *    (or the clip is untrimmed at the start), so quality and HDR metadata are kept exactly and saving is near instant.
 *  - Exact: when the start falls between key frames, the video track is re-encoded (decoder renders straight into the
 *    encoder's input Surface, as AOSP's media transcoder does) from the exact start frame; audio is still copied
 *    losslessly. HDR / 10-bit sources and any encoder failure fall back to the lossless path, which snaps the start
 *    back to the previous key frame.
 * Rotation is always lossless: it only changes the container's orientation matrix.
 */
public final class VideoExporter {
    private static final String TAG = "SamVideoEditor";
    private static final long EXACT_THRESHOLD_US = 50_000;   // re-encode only if snapping would move the start > 50ms
    private static final long TIMEOUT_US = 10_000;

    public interface Progress { void onProgress(float fraction); }

    public static final class Request {
        public Uri source;
        public VideoInfo info;
        public long startMs, endMs;
        public int extraRotation;     // 0/90/180/270, added to the source's own rotation
        public boolean mute;
        /** Replace the source in place (caller must hold a MediaStore write grant) instead of saving a copy. */
        public boolean overwrite;
    }

    private volatile boolean mCancelled;

    public void cancel() { mCancelled = true; }

    public Uri export(Context ctx, Request req, Progress progress) throws IOException {
        mCancelled = false;
        try {
            return exportOnce(ctx, req, progress, true);
        } catch (ExactUnavailable e) {
            Log.w(TAG, "exact trim unavailable, saving from the previous key frame: " + e.getMessage(), e.getCause());
            return exportOnce(ctx, req, progress, false);
        }
    }

    /** Thrown by the re-encode path before anything final happened; the caller retries losslessly. */
    private static final class ExactUnavailable extends IOException {
        ExactUnavailable(String msg, Throwable cause) { super(msg, cause); }
    }

    private Uri exportOnce(Context ctx, Request req, Progress progress, boolean allowExact) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(ctx, req.source, null);
        try {
            return run(ctx, ex, req, progress, allowExact);
        } finally {
            ex.release();
        }
    }

    private Uri run(Context ctx, MediaExtractor ex, Request req, Progress progress, boolean allowExact) throws IOException {
        int videoTrack = -1, audioTrack = -1;
        String videoMime = null, audioMime = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime == null) continue;
            if (mime.startsWith("video/") && videoTrack < 0) { videoTrack = i; videoMime = mime; }
            else if (mime.startsWith("audio/") && audioTrack < 0 && !req.mute) { audioTrack = i; audioMime = mime; }
        }
        if (videoTrack < 0) throw new IOException("no video track");

        boolean webm = isWebmCodec(videoMime) || (audioMime != null && isWebmCodec(audioMime));
        int container = webm ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
        int rotation = ((req.info.rotation + req.extraRotation) % 360 + 360) % 360;
        if (webm && rotation != 0) throw new IOException("rotation is not supported for WebM videos");

        long startUs = req.startMs * 1000, endUs = req.endMs * 1000;
        ex.selectTrack(videoTrack);
        ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        long keyUs = ex.getSampleTime();
        if (keyUs < 0) throw new IOException("can't seek to " + req.startMs + "ms");
        MediaFormat videoFormat = ex.getTrackFormat(videoTrack);
        boolean exact = allowExact && !webm && startUs - keyUs > EXACT_THRESHOLD_US && canReencode(videoFormat);

        ContentResolver cr = ctx.getContentResolver();
        // Copy: write straight into a new pending MediaStore row. Replace: write a temp file first, so the original
        // is only touched once the edited video is complete.
        Uri out = null;
        File tmp = null;
        if (req.overwrite) {
            tmp = File.createTempFile("edit_", webm ? ".webm" : ".mp4", ctx.getCacheDir());
        } else {
            out = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), outputValues(req.info, webm));
            if (out == null) throw new IOException("MediaStore refused the new video");
        }
        boolean ok = false;
        try (ParcelFileDescriptor pfd = tmp != null
                ? ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_TRUNCATE)
                : cr.openFileDescriptor(out, "rw")) {
            if (pfd == null) throw new IOException("can't open output");
            MediaMuxer mux = new MediaMuxer(pfd.getFileDescriptor(), container);
            try {
                if (!webm) {
                    mux.setOrientationHint(rotation);
                    if (!Float.isNaN(req.info.latitude)) mux.setLocation(req.info.latitude, req.info.longitude);
                }
                if (exact) {
                    writeExact(ex, mux, videoTrack, audioTrack, videoFormat, req.info, startUs, endUs, progress);
                } else {
                    if (audioTrack >= 0) ex.selectTrack(audioTrack);
                    ex.seekTo(keyUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    int[] map = new int[ex.getTrackCount()];
                    Arrays.fill(map, -1);
                    int maxSize = 2 << 20;
                    for (int t : new int[]{videoTrack, audioTrack}) {
                        if (t < 0) continue;
                        MediaFormat f = ex.getTrackFormat(t);
                        if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxSize = Math.max(maxSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                        if (t == videoTrack && f.containsKey(MediaFormat.KEY_ROTATION)) f.setInteger(MediaFormat.KEY_ROTATION, rotation);
                        map[t] = addTrack(mux, f);
                    }
                    mux.start();
                    copySamples(ex, mux, map, keyUs, endUs, maxSize, progress, 0f, 1f);
                }
                mux.stop();
            } finally {
                mux.release();
            }
            if (tmp != null) {
                if (mCancelled) throw new CancellationException();
                replaceOriginal(ctx, req.source, tmp);
                out = req.source;
            } else {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(out, done, null, null);
            }
            ok = true;
            Log.i(TAG, "saved " + out + (exact ? " exact " + req.startMs : " lossless " + keyUs / 1000) + "ms-" + req.endMs
                    + "ms rot=" + rotation + " mute=" + req.mute);
            return out;
        } catch (IllegalStateException e) {
            if (exact) throw new ExactUnavailable("muxer/codec state", e);
            throw new IOException("muxer failed", e);
        } finally {
            if (tmp != null) {
                if (!tmp.delete()) Log.w(TAG, "temp not deleted " + tmp);
            } else if (!ok) {
                try { cr.delete(out, null, null); } catch (RuntimeException e) { Log.w(TAG, "cleanup failed " + out, e); }
            }
        }
    }

    /** Writes the finished temp file over the original, then has MediaStore re-read duration/size/orientation. */
    private static void replaceOriginal(Context ctx, Uri src, File tmp) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        try (InputStream in = new FileInputStream(tmp); OutputStream os = cr.openOutputStream(src, "wt")) {
            if (os == null) throw new IOException("can't open the original for writing");
            byte[] buf = new byte[1 << 20];
            for (int n; (n = in.read(buf)) > 0; ) os.write(buf, 0, n);
        } catch (SecurityException e) {
            throw new IOException("no permission to replace the original", e);
        }
        String path = null;
        try (android.database.Cursor c = cr.query(src, new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
            if (c != null && c.moveToFirst()) path = c.getString(0);
        } catch (RuntimeException ignored) { }
        if (path != null) MediaScannerConnection.scanFile(ctx, new String[]{path}, null, null);
        cr.notifyChange(src, null);
    }

    private static int addTrack(MediaMuxer mux, MediaFormat f) throws IOException {
        try {
            return mux.addTrack(f);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new IOException("format not supported: " + f.getString(MediaFormat.KEY_MIME), e);
        }
    }

    // ------------------------------------------------------------------ lossless copy

    private void copySamples(MediaExtractor ex, MediaMuxer mux, int[] map, long baseUs, long endUs, int maxSize,
                             Progress progress, float p0, float p1) throws IOException {
        ByteBuffer buf = ByteBuffer.allocateDirect(maxSize);
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        boolean[] finished = new boolean[map.length];
        int open = 0;
        for (int m : map) if (m >= 0) open++;
        int written = 0;
        float last = -1;
        while (open > 0) {
            if (mCancelled) throw new CancellationException();
            int t = ex.getSampleTrackIndex();
            if (t < 0) break;                                   // end of stream
            long pts = ex.getSampleTime();
            if (map[t] < 0 || finished[t]) { ex.advance(); continue; }
            if (pts > endUs) {
                // Samples are in decode order: once a track passes the end, drop the rest of it so no frame that
                // references a dropped frame gets written.
                finished[t] = true; open--; ex.advance(); continue;
            }
            if (pts < baseUs) { ex.advance(); continue; }      // audio pre-roll / leading pictures before the start
            int size = ex.readSampleData(buf, 0);
            if (size < 0) break;
            int flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            bi.set(0, size, pts - baseUs, flags);
            mux.writeSampleData(map[t], buf, bi);
            written++;
            float f = p0 + (p1 - p0) * Math.min(1f, (pts - baseUs) / (float) Math.max(1, endUs - baseUs));
            if (progress != null && f - last >= 0.01f) { last = f; progress.onProgress(f); }
            ex.advance();
        }
        if (written == 0 && map.length > 0 && p0 == 0f) throw new IOException("nothing to write in the selected range");
        if (progress != null) progress.onProgress(p1);
    }

    // ------------------------------------------------------------------ exact (re-encode video)

    private static boolean canReencode(MediaFormat f) {
        String mime = f.getString(MediaFormat.KEY_MIME);
        if (!MediaFormat.MIMETYPE_VIDEO_AVC.equals(mime) && !MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime)) return false;
        if (f.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
            int tr = f.getInteger(MediaFormat.KEY_COLOR_TRANSFER);
            if (tr == MediaFormat.COLOR_TRANSFER_ST2084 || tr == MediaFormat.COLOR_TRANSFER_HLG) return false;   // HDR
        }
        if (f.containsKey(MediaFormat.KEY_PROFILE)) {
            int p = f.getInteger(MediaFormat.KEY_PROFILE);
            if (MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime) && p != MediaCodecInfo.CodecProfileLevel.HEVCProfileMain) return false;
            if (MediaFormat.MIMETYPE_VIDEO_AVC.equals(mime) && p == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10) return false;
        }
        return f.containsKey(MediaFormat.KEY_WIDTH) && f.containsKey(MediaFormat.KEY_HEIGHT);
    }

    private void writeExact(MediaExtractor ex, MediaMuxer mux, int videoTrack, int audioTrack, MediaFormat inFormat,
                            VideoInfo info, long startUs, long endUs, Progress progress) throws IOException {
        String mime = inFormat.getString(MediaFormat.KEY_MIME);
        int w = inFormat.getInteger(MediaFormat.KEY_WIDTH), h = inFormat.getInteger(MediaFormat.KEY_HEIGHT);
        int fps = inFormat.containsKey(MediaFormat.KEY_FRAME_RATE) ? inFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : 30;
        if (fps <= 0 || fps > 240) fps = 30;
        int bitrate = inFormat.containsKey(MediaFormat.KEY_BIT_RATE) ? inFormat.getInteger(MediaFormat.KEY_BIT_RATE) : 0;
        if (bitrate <= 0) bitrate = info.bitrate > 0 ? (int) Math.min(Integer.MAX_VALUE, info.bitrate) : 0;
        // Keep the source's quality: at least ~0.1 bit per pixel per frame, so short/low-bitrate clips don't turn to mush.
        bitrate = Math.max(bitrate, (int) Math.min(200_000_000L, (long) w * h * fps / 10));

        MediaFormat encFormat = MediaFormat.createVideoFormat(mime, w, h);
        encFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        encFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        encFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        for (String k : new String[]{MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_COLOR_TRANSFER}) {
            if (inFormat.containsKey(k)) encFormat.setInteger(k, inFormat.getInteger(k));
        }

        MediaCodec enc = null, dec = null;
        Surface surface = null;
        int vOut = -1, aOut = -1;
        boolean muxStarted = false;
        try {
            try {
                enc = MediaCodec.createEncoderByType(mime);
                enc.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                surface = enc.createInputSurface();
                enc.start();
                dec = MediaCodec.createDecoderByType(mime);
                dec.configure(inFormat, surface, null, 0);
                dec.start();
            } catch (IOException | RuntimeException e) {
                throw new ExactUnavailable("codec setup " + mime + " " + w + "x" + h, e);
            }

            // Video phase: decode from the key frame, render only frames inside [start, end] into the encoder.
            if (audioTrack >= 0) ex.unselectTrack(audioTrack);
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            long feedUntilUs = endUs + 500_000;     // B-frames shown before `end` can come later in decode order
            boolean inDone = false, decDone = false, encDone = false;
            int frames = 0;
            float last = -1;
            while (!encDone) {
                if (mCancelled) throw new CancellationException();
                if (!inDone) {
                    int ii = dec.dequeueInputBuffer(TIMEOUT_US);
                    if (ii >= 0) {
                        ByteBuffer b = dec.getInputBuffer(ii);
                        int size = b == null ? -1 : ex.readSampleData(b, 0);
                        long t = ex.getSampleTime();
                        if (size < 0 || t > feedUntilUs) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, size, t, 0);
                            ex.advance();
                        }
                    }
                }
                if (!decDone) {
                    int oi = dec.dequeueOutputBuffer(bi, TIMEOUT_US);
                    if (oi >= 0) {
                        boolean eos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        long pts = bi.presentationTimeUs;
                        if (bi.size > 0 && pts >= startUs && pts <= endUs) {
                            dec.releaseOutputBuffer(oi, pts * 1000);       // timestamp travels to the encoder input
                            frames++;
                        } else {
                            dec.releaseOutputBuffer(oi, false);
                        }
                        if (eos) { enc.signalEndOfInputStream(); decDone = true; }
                    }
                }
                int eo = enc.dequeueOutputBuffer(bi, TIMEOUT_US);
                if (eo == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxStarted) throw new IOException("encoder format changed twice");
                    vOut = addTrack(mux, enc.getOutputFormat());
                    if (audioTrack >= 0) aOut = addTrack(mux, ex.getTrackFormat(audioTrack));
                    mux.start();
                    muxStarted = true;
                } else if (eo >= 0) {
                    ByteBuffer ob = enc.getOutputBuffer(eo);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bi.size = 0;
                    if (bi.size > 0 && muxStarted && ob != null) {
                        bi.presentationTimeUs = Math.max(0, bi.presentationTimeUs - startUs);
                        mux.writeSampleData(vOut, ob, bi);
                        float f = 0.95f * Math.min(1f, bi.presentationTimeUs / (float) Math.max(1, endUs - startUs));
                        if (progress != null && f - last >= 0.01f) { last = f; progress.onProgress(f); }
                    }
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encDone = true;
                    enc.releaseOutputBuffer(eo, false);
                }
            }
            if (!muxStarted || frames == 0) throw new ExactUnavailable("encoder produced no frames", null);

            // Audio phase: lossless copy from the exact start.
            if (audioTrack >= 0) {
                ex.unselectTrack(videoTrack);
                ex.selectTrack(audioTrack);
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                int[] map = new int[ex.getTrackCount()];
                Arrays.fill(map, -1);
                map[audioTrack] = aOut;
                MediaFormat af = ex.getTrackFormat(audioTrack);
                int maxSize = af.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? Math.max(1 << 16, af.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)) : 1 << 20;
                copySamples(ex, mux, map, startUs, endUs, maxSize, progress, 0.95f, 1f);
            }
            Log.i(TAG, "re-encoded " + frames + " frames " + mime + " " + w + "x" + h + " @" + bitrate / 1000 + "kbps");
        } catch (ExactUnavailable | CancellationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            if (!muxStarted) throw new ExactUnavailable("re-encode failed", e);
            throw e instanceof IOException ? (IOException) e : new IOException("re-encode failed", e);
        } finally {
            if (dec != null) try { dec.stop(); } catch (RuntimeException ignored) { } finally { dec.release(); }
            if (enc != null) try { enc.stop(); } catch (RuntimeException ignored) { } finally { enc.release(); }
            if (surface != null) surface.release();
        }
    }

    // ------------------------------------------------------------------ output row

    private static ContentValues outputValues(VideoInfo info, boolean webm) {
        String base = info.displayName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, base + "_edit" + (webm ? ".webm" : ".mp4"));  // MediaStore adds " (1)" on clash
        v.put(MediaStore.MediaColumns.MIME_TYPE, webm ? "video/webm" : "video/mp4");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, isWritableVideoDir(info.relativePath) ? info.relativePath : "Movies/Video Editor/");
        // Keep the original capture time so the edit sits next to the original in Gallery's timeline.
        if (info.dateTakenMs > 0) v.put(MediaStore.MediaColumns.DATE_TAKEN, info.dateTakenMs);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        return v;
    }

    /** MediaStore only lets an app create videos under DCIM/, Movies/ or Pictures/. */
    private static boolean isWritableVideoDir(String rel) {
        return rel != null && (rel.startsWith("DCIM/") || rel.startsWith("Movies/") || rel.startsWith("Pictures/"));
    }

    private static boolean isWebmCodec(String mime) {
        return "video/x-vnd.on2.vp8".equals(mime) || "video/x-vnd.on2.vp9".equals(mime)
                || "audio/vorbis".equals(mime) || "audio/opus".equals(mime);
    }
}
