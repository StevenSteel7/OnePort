package com.samsung.android.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.TimedText;
import android.net.Uri;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.widget.VideoView;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Compatibility implementation of Samsung's SemMediaPlayer backed by the
 * public Android MediaPlayer. Samsung-only effects are deliberately no-ops;
 * ordinary playback, seeking, audio, surfaces and callbacks are preserved.
 */
public final class SemMediaPlayer {
    public interface OnBufferingUpdateListener { void onBufferingUpdate(SemMediaPlayer player, int percent); }
    public interface OnErrorListener { boolean onError(SemMediaPlayer player, int what, int extra); }
    public interface OnInfoListener { boolean onInfo(SemMediaPlayer player, int what, int extra); }
    public interface OnInitCompleteListener { void onInitComplete(SemMediaPlayer player, TrackInfo[] tracks); }
    public interface OnPlaybackCompleteListener { void onPlaybackComplete(SemMediaPlayer player); }
    public interface OnSeekCompleteListener { void onSeekComplete(SemMediaPlayer player); }
    public interface OnTimedTextListener { void onTimedText(SemMediaPlayer player, TimedText text); }
    public interface OnVideoSizeChangedListener { void onVideoSizeChanged(SemMediaPlayer player, int width, int height); }

    public static final class DynamicViewingConfiguration {
        public DynamicViewingConfiguration(int startMs, int endMs, float speed) {}
    }

    public static final class TrackInfo {
        private final MediaPlayer.TrackInfo delegate;
        private final int videoWidth;
        private final int videoHeight;

        private TrackInfo(MediaPlayer.TrackInfo delegate, int videoWidth, int videoHeight) {
            this.delegate = delegate;
            this.videoWidth = videoWidth;
            this.videoHeight = videoHeight;
        }

        public int getTrackType() { return delegate == null ? 0 : delegate.getTrackType(); }
        public String getLanguage() {
            String value = delegate == null ? null : delegate.getLanguage();
            return value == null ? "und" : value;
        }
        public String getName() {
            MediaFormat format = delegate == null ? null : delegate.getFormat();
            if (format != null && format.containsKey(MediaFormat.KEY_LANGUAGE)) {
                String value = format.getString(MediaFormat.KEY_LANGUAGE);
                if (value != null) return value;
            }
            return "";
        }
        public int getVideoWidth() { return videoWidth; }
        public int getVideoHeight() { return videoHeight; }
        public int getFrameRate() {
            MediaFormat format = delegate == null ? null : delegate.getFormat();
            if (format != null && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                try { return format.getInteger(MediaFormat.KEY_FRAME_RATE); }
                catch (RuntimeException ignored) {}
            }
            return 0;
        }
    }

    private MediaPlayer player = new MediaPlayer();
    private OnInitCompleteListener initListener;

    public SemMediaPlayer() { attachBridges(); }

    private void attachBridges() {
        player.setOnPreparedListener(mp -> {
            OnInitCompleteListener listener = initListener;
            if (listener != null) listener.onInitComplete(this, getTrackInfo());
        });
    }

    public void init(Context context, Uri uri) throws IOException {
        player.setDataSource(context, uri);
        player.prepareAsync();
    }

    public void init(FileDescriptor descriptor) throws IOException {
        player.setDataSource(descriptor);
        player.prepareAsync();
    }

    public void setOnInitCompleteListener(OnInitCompleteListener listener) { initListener = listener; }
    public void setOnBufferingUpdateListener(OnBufferingUpdateListener listener) {
        player.setOnBufferingUpdateListener(listener == null ? null : (mp, percent) -> listener.onBufferingUpdate(this, percent));
    }
    public void setOnErrorListener(OnErrorListener listener) {
        player.setOnErrorListener(listener == null ? null : (mp, what, extra) -> listener.onError(this, what, extra));
    }
    public void setOnInfoListener(OnInfoListener listener) {
        player.setOnInfoListener(listener == null ? null : (mp, what, extra) -> listener.onInfo(this, what, extra));
    }
    public void setOnPlaybackCompleteListener(OnPlaybackCompleteListener listener) {
        player.setOnCompletionListener(listener == null ? null : mp -> listener.onPlaybackComplete(this));
    }
    public void setOnSeekCompleteListener(OnSeekCompleteListener listener) {
        player.setOnSeekCompleteListener(listener == null ? null : mp -> listener.onSeekComplete(this));
    }
    public void setOnTimedTextListener(OnTimedTextListener listener) {
        player.setOnTimedTextListener(listener == null ? null : (mp, text) -> listener.onTimedText(this, text));
    }
    public void setOnVideoSizeChangedListener(OnVideoSizeChangedListener listener) {
        player.setOnVideoSizeChangedListener(listener == null ? null : (mp, width, height) -> listener.onVideoSizeChanged(this, width, height));
    }

    public void start() { player.start(); }
    public void pause() { player.pause(); }
    public void reset() { player.reset(); attachBridges(); }
    public void release() { player.release(); }
    public boolean isPlaying() { return player.isPlaying(); }
    public int getCurrentPosition() { return player.getCurrentPosition(); }
    public int getLastRenderedVideoPosition() { return player.getCurrentPosition(); }
    public int getDuration() { return player.getDuration(); }
    public void seekTo(int positionMs) { player.seekTo(positionMs); }
    public void seekTo(int positionMs, int mode) {
        // Samsung also uses mode 4; public MediaPlayer accepts only 0..3.
        int publicMode = mode >= MediaPlayer.SEEK_PREVIOUS_SYNC && mode <= MediaPlayer.SEEK_CLOSEST
                ? mode : MediaPlayer.SEEK_CLOSEST;
        player.seekTo(positionMs, publicMode);
    }
    public void setDisplay(SurfaceHolder holder) { player.setDisplay(holder); }
    public void setSurface(Surface surface) { player.setSurface(surface); }
    public void setScreenOnWhilePlaying(boolean enabled) { player.setScreenOnWhilePlaying(enabled); }
    public void setLooping(boolean looping) { player.setLooping(looping); }
    public void setVolume(float left, float right) { player.setVolume(left, right); }
    public void setAudioAttributes(AudioAttributes attributes) { player.setAudioAttributes(attributes); }
    public PlaybackParams getPlaybackParams() { return player.getPlaybackParams(); }
    public void setPlaybackParams(PlaybackParams params) { player.setPlaybackParams(params); }
    public void selectTrack(int index) { player.selectTrack(index); }
    public void deselectTrack(int index) { player.deselectTrack(index); }
    // The old stream-based subtitle method is no longer in the public API 37
    // SDK surface. Keep the Samsung signature so callers link; external text
    // tracks can still use addTimedTextSource below.
    public void addSubtitleSource(InputStream stream, MediaFormat format) {}
    public void addTimedTextSource(String path, String mimeType) throws IOException { player.addTimedTextSource(path, mimeType); }

    public TrackInfo[] getTrackInfo() {
        MediaPlayer.TrackInfo[] source;
        try { source = player.getTrackInfo(); }
        catch (RuntimeException ignored) { return new TrackInfo[0]; }
        List<TrackInfo> result = new ArrayList<>(source.length);
        int width = player.getVideoWidth();
        int height = player.getVideoHeight();
        for (MediaPlayer.TrackInfo track : source) result.add(new TrackInfo(track, width, height));
        return result.toArray(new TrackInfo[0]);
    }

    public Bitmap getCurrentFrame() { return null; }
    public Bitmap getCurrentFrame(int option) { return null; }
    public Bitmap getCurrentFrame(int width, int height) { return null; }
    public boolean setParameter(int key, int value) { return false; }
    public boolean setParameter(int key, String value) { return false; }
    public void setAudioVolumeFade(int from, int to, int duration, int curve, int flags) {}
    public void setBackgroundMusic(SemBackgroundMusic music) {}
    public void setDynamicViewingConfigurations(List<DynamicViewingConfiguration> configurations) {}
    public void setPlaybackEffect(int effect, int value) {}
    public void setPlaybackRange(int startMs, int endMs) { if (startMs > 0) seekTo(startMs); }
    public void setSubtitleControllerAndAnchor(Context context, VideoView anchor) {}
    public void setVideoFrc(int mode, float rate, boolean enabled) {}
    public void removeOutbandSubtitleSources() {}
    public void removeOutbandTimedTextSources() {}
}
