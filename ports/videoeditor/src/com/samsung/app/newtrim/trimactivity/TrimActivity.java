package com.samsung.app.newtrim.trimactivity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;
import com.samsung.app.newtrim.Icon;
import com.samsung.app.newtrim.TrimRangeView;
import com.samsung.app.newtrim.VideoExporter;
import com.samsung.app.newtrim.VideoInfo;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Video editor: preview, trim, rotate, mute, save as a copy.
 *
 * Gallery contract (StartVideoTrimmerCmd / VideoEditorReceiveHandler in the Samsung Gallery port):
 *  in:  explicit component, action MAIN, data + extra "uri" = content:// video, read/write grant, request code 794.
 *  out: setResult(RESULT_OK, data=saved uri, extra "saved_uri"), and the broadcast
 *       com.samsung.app.newtrim.action.saved / .canceled with extra "saved_uri", which Gallery's viewer uses to
 *       jump to the new item.
 */
public class TrimActivity extends Activity implements TextureView.SurfaceTextureListener, TrimRangeView.Listener {
    private static final String TAG = "SamVideoEditor";
    private static final String GALLERY = "com.sec.android.gallery3d";
    private static final String ACTION_SAVED = "com.samsung.app.newtrim.action.saved";
    private static final String ACTION_CANCELED = "com.samsung.app.newtrim.action.canceled";
    private static final int REQ_PICK = 1, REQ_WRITE = 2;
    private static final int ACCENT = 0xFF3E91FF, TEXT = 0xFFFFFFFF, TEXT2 = 0xB3FFFFFF, BG = 0xFF000000;

    private final Handler mUi = new Handler(Looper.getMainLooper());
    private final ExecutorService mWork = Executors.newSingleThreadExecutor();
    private final ExecutorService mThumbs = Executors.newSingleThreadExecutor();

    private Uri mSource;
    private VideoInfo mInfo;
    private MediaPlayer mPlayer;
    private Surface mSurface;
    private boolean mPrepared, mResumePlayAfterTouch;

    private int mRotationTurns;          // cumulative quarter turns (for smooth animation); export uses mod 4
    private boolean mMuted;
    private VideoExporter mExporter;

    private FrameLayout mPreviewBox, mOverlay;
    private TextureView mTexture;
    private ImageView mPlayButton;
    private Icon mPlayIcon;
    private TextView mSave, mStartText, mEndText, mLengthText, mProgressText;
    private ProgressBar mProgress;
    private TrimRangeView mTrim;
    private ToolButton mRotateTool, mMuteTool;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setNavigationBarColor(BG);
        getWindow().setStatusBarColor(BG);
        setContentView(buildUi());
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        Uri uri = sourceFrom(getIntent());
        if (uri != null) open(uri);
        else if (saved == null) pickVideo();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() { handleBack(); }   // API 30-32 only; 33+ uses the OnBackInvokedCallback

    @Override
    protected void onPause() {
        super.onPause();
        pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mExporter != null) mExporter.cancel();
        mUi.removeCallbacksAndMessages(null);
        releasePlayer();
        mWork.shutdownNow();
        mThumbs.shutdownNow();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_WRITE) {
            if (result == RESULT_OK) startExport(true);
            else Toast.makeText(this, "Permission denied; the original was not changed", Toast.LENGTH_LONG).show();
            return;
        }
        if (request != REQ_PICK) return;
        if (result == RESULT_OK && data != null && data.getData() != null) open(data.getData());
        else finish();
    }

    private static Uri sourceFrom(Intent in) {
        if (in == null) return null;
        if (in.getData() != null) return in.getData();
        Parcelable p = in.getParcelableExtra("uri");
        if (p instanceof Uri) return (Uri) p;
        p = in.getParcelableExtra(Intent.EXTRA_STREAM);
        if (p instanceof Uri) return (Uri) p;
        ArrayList<Parcelable> list = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (list != null && !list.isEmpty() && list.get(0) instanceof Uri) return (Uri) list.get(0);
        return null;
    }

    private void pickVideo() {
        Intent pick;
        if (Build.VERSION.SDK_INT >= 33) {
            pick = new Intent(MediaStore.ACTION_PICK_IMAGES).setType("video/*");
        } else {
            pick = new Intent(Intent.ACTION_GET_CONTENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE);
        }
        try {
            startActivityForResult(pick, REQ_PICK);
        } catch (RuntimeException e) {
            Toast.makeText(this, "No app to pick a video", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void open(Uri uri) {
        mSource = uri;
        Log.i(TAG, "open " + uri + " from " + getCallingPackage());
        mWork.execute(() -> {
            try {
                VideoInfo info = VideoInfo.load(this, uri);
                mUi.post(() -> onInfoLoaded(info));
            } catch (Exception e) {
                Log.e(TAG, "can't open " + uri, e);
                mUi.post(() -> {
                    Toast.makeText(this, "Can't open this video", Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        });
    }

    private void onInfoLoaded(VideoInfo info) {
        if (isFinishing() || isDestroyed()) return;
        mInfo = info;
        mTrim.setDuration(info.durationMs);
        mMuteTool.setEnabled(info.hasAudio);
        updateTexts();
        layoutTexture(false);
        ensurePlayer();
        mTrim.post(this::loadThumbnails);
    }

    // ---------------------------------------------------------------- preview

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        mSurface = new Surface(st);
        ensurePlayer();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        releasePlayer();
        if (mSurface != null) { mSurface.release(); mSurface = null; }
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }

    private void ensurePlayer() {
        if (mPlayer != null || mSurface == null || mInfo == null) return;
        MediaPlayer p = new MediaPlayer();
        mPlayer = p;
        mPrepared = false;
        try {
            p.setDataSource(this, mSource);
            p.setSurface(mSurface);
            p.setOnPreparedListener(mp -> {
                mPrepared = true;
                applyMute();
                mp.seekTo(mTrim.getStartMs(), MediaPlayer.SEEK_CLOSEST);
            });
            p.setOnCompletionListener(mp -> onReachedEnd());
            p.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "player error " + what + "/" + extra);
                releasePlayer();
                return true;
            });
            p.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "player setup failed", e);
            releasePlayer();
        }
    }

    private void releasePlayer() {
        mUi.removeCallbacks(mTick);
        if (mPlayer != null) {
            try { mPlayer.release(); } catch (RuntimeException ignored) { }
            mPlayer = null;
        }
        mPrepared = false;
        setPlayIcon(false);
    }

    private boolean isPlaying() {
        try { return mPrepared && mPlayer != null && mPlayer.isPlaying(); } catch (IllegalStateException e) { return false; }
    }

    private void togglePlay() {
        if (isPlaying()) pause(); else play();
    }

    private void play() {
        if (!mPrepared || mPlayer == null) return;
        long pos = mPlayer.getCurrentPosition();
        if (pos < mTrim.getStartMs() || pos >= mTrim.getEndMs() - 50) mPlayer.seekTo(mTrim.getStartMs(), MediaPlayer.SEEK_CLOSEST);
        mPlayer.start();
        setPlayIcon(true);
        mUi.removeCallbacks(mTick);
        mUi.post(mTick);
    }

    private void pause() {
        mUi.removeCallbacks(mTick);
        if (isPlaying()) mPlayer.pause();
        setPlayIcon(false);
    }

    private void onReachedEnd() {
        pause();
        mTrim.setPlayhead(mTrim.getEndMs());
    }

    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            if (!isPlaying()) { setPlayIcon(false); return; }
            long pos = mPlayer.getCurrentPosition();
            mTrim.setPlayhead(pos);
            if (pos >= mTrim.getEndMs()) { onReachedEnd(); return; }
            mUi.postDelayed(this, 30);
        }
    };

    private void seekPreview(long ms) {
        if (mPrepared && mPlayer != null) mPlayer.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
    }

    private void applyMute() {
        if (mPlayer != null) {
            float v = mMuted ? 0f : 1f;
            mPlayer.setVolume(v, v);
        }
    }

    private void setPlayIcon(boolean playing) {
        if (mPlayIcon == null) return;
        mPlayIcon.setKind(playing ? Icon.Kind.PAUSE : Icon.Kind.PLAY);
        mPlayButton.setContentDescription(playing ? "Pause" : "Play");
        mPlayButton.animate().alpha(playing ? 0f : 1f).setDuration(150).start();
    }

    /** Fits the video into the preview box, then applies the user rotation (scaled so the turned frame still fits). */
    private void layoutTexture(boolean animate) {
        if (mInfo == null || mPreviewBox.getWidth() == 0) return;
        float bw = mPreviewBox.getWidth(), bh = mPreviewBox.getHeight();
        float dw = mInfo.displayWidth(), dh = mInfo.displayHeight();
        float fit = Math.min(bw / dw, bh / dh);
        int w = Math.round(dw * fit), h = Math.round(dh * fit);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mTexture.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            lp.gravity = Gravity.CENTER;
            mTexture.setLayoutParams(lp);
        }
        float scale = (mRotationTurns & 1) == 1 ? Math.min(bw / h, bh / w) : 1f;
        float deg = mRotationTurns * 90f;
        if (animate) {
            mTexture.animate().rotation(deg).scaleX(scale).scaleY(scale).setDuration(220).start();
        } else {
            mTexture.setRotation(deg);
            mTexture.setScaleX(scale);
            mTexture.setScaleY(scale);
        }
    }

    // ---------------------------------------------------------------- thumbnails

    private void loadThumbnails() {
        if (mInfo == null) return;
        int n = mTrim.thumbCountFor(mInfo.displayWidth() / (float) mInfo.displayHeight());
        if (n <= 0) return;
        mTrim.setThumbCount(n);
        Uri uri = mSource;
        long duration = mInfo.durationMs;
        int th = Math.max(64, mTrim.getHeight());
        int tw = Math.round(th * mInfo.displayWidth() / (float) mInfo.displayHeight());
        mThumbs.execute(() -> {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(this, uri);
                for (int i = 0; i < n && !Thread.currentThread().isInterrupted(); i++) {
                    long us = (long) ((i + 0.5) * duration * 1000 / n);
                    Bitmap b = r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th);
                    int idx = i;
                    if (b != null) mUi.post(() -> mTrim.setThumb(idx, b));
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "thumbnails failed", e);
            } finally {
                try { r.release(); } catch (Exception ignored) { }
            }
        });
    }

    // ---------------------------------------------------------------- trim callbacks

    @Override
    public void onTrimTouchStart() {
        mResumePlayAfterTouch = isPlaying();
        pause();
    }

    @Override
    public void onRangeChanged(long startMs, long endMs, boolean startHandle) {
        seekPreview(startHandle ? startMs : endMs);
        updateTexts();
    }

    @Override
    public void onScrub(long ms) { seekPreview(ms); }

    @Override
    public void onTrimTouchEnd() {
        if (mResumePlayAfterTouch) play();
        mResumePlayAfterTouch = false;
    }

    private boolean isEdited() {
        if (mInfo == null) return false;
        return mTrim.getStartMs() > 0 || mTrim.getEndMs() < mInfo.durationMs || (mRotationTurns & 3) != 0 || mMuted;
    }

    private void updateTexts() {
        long s = mTrim.getStartMs(), e = mTrim.getEndMs();
        mStartText.setText(fmt(s, true));
        mEndText.setText(fmt(e, true));
        mLengthText.setText(fmt(e - s, false));
        boolean edited = isEdited();
        mSave.setEnabled(edited);
        mSave.setAlpha(edited ? 1f : 0.4f);
    }

    private static String fmt(long ms, boolean tenths) {
        long totalS = ms / 1000;
        String base = totalS >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", totalS / 3600, totalS / 60 % 60, totalS % 60)
                : String.format(Locale.US, "%d:%02d", totalS / 60, totalS % 60);
        return tenths ? base + "." + (ms % 1000) / 100 : base;
    }

    // ---------------------------------------------------------------- tools

    private void rotate() {
        if (mInfo == null) return;
        mRotationTurns++;
        layoutTexture(true);
        mRotateTool.setActive((mRotationTurns & 3) != 0);
        updateTexts();
    }

    private void toggleMute() {
        if (mInfo == null || !mInfo.hasAudio) return;
        mMuted = !mMuted;
        applyMute();
        mMuteTool.setIcon(mMuted ? Icon.Kind.MUTE : Icon.Kind.VOLUME);
        mMuteTool.setLabel(mMuted ? "Muted" : "Sound");
        mMuteTool.setActive(mMuted);
        updateTexts();
    }

    // ---------------------------------------------------------------- save / cancel

    /** Save: ask "Save as copy" or "Replace original" (the latter only for MediaStore items, which can be granted). */
    private void save() {
        if (mInfo == null || mExporter != null || !isEdited()) return;
        pause();
        if (!MediaStore.AUTHORITY.equals(mSource.getAuthority())) { startExport(false); return; }

        AlertDialog[] dialog = new AlertDialog[1];
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        box.addView(choiceRow("Save as copy", "Keep the original and save the edit as a new video", v -> {
            dialog[0].dismiss();
            startExport(false);
        }));
        box.addView(choiceRow("Replace original", "Overwrite the original video with this edit", v -> {
            dialog[0].dismiss();
            confirmReplace();
        }));
        dialog[0] = new AlertDialog.Builder(this).setTitle("Save video").setView(box).setNegativeButton("Cancel", null).create();
        dialog[0].show();
    }

    private void confirmReplace() {
        new AlertDialog.Builder(this)
                .setMessage("The original video will be replaced. This can't be undone.")
                .setPositiveButton("Replace", (d, w) -> requestWriteThenExport())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Replacing needs write access to the original. Gallery only grants read (it isn't privileged off Samsung), so ask
     * MediaStore: the system shows "Allow Video Editor to modify this video?" (skipped if Media management is allowed).
     */
    private void requestWriteThenExport() {
        try {
            android.app.PendingIntent pi = MediaStore.createWriteRequest(getContentResolver(), java.util.Collections.singletonList(mSource));
            startIntentSenderForResult(pi.getIntentSender(), REQ_WRITE, null, 0, 0, 0);
        } catch (Exception e) {
            Log.e(TAG, "write request failed for " + mSource, e);
            Toast.makeText(this, "Can't replace this video; saving a copy instead", Toast.LENGTH_LONG).show();
            startExport(false);
        }
    }

    private View choiceRow(String title, String subtitle, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackground(ripple());
        row.setOnClickListener(click);
        TextView t = text(title, 17, TEXT, true);
        TextView s = text(subtitle, 13, TEXT2, false);
        s.setSingleLine(false);
        s.setPadding(0, dp(2), 0, 0);
        row.addView(t);
        row.addView(s);
        return row;
    }

    private void startExport(boolean overwrite) {
        VideoExporter.Request req = new VideoExporter.Request();
        req.overwrite = overwrite;
        req.source = mSource;
        req.info = mInfo;
        req.startMs = mTrim.getStartMs();
        req.endMs = mTrim.getEndMs();
        req.extraRotation = (mRotationTurns & 3) * 90;
        req.mute = mMuted;
        VideoExporter ex = new VideoExporter();
        mExporter = ex;
        showProgress(true);
        mWork.execute(() -> {
            try {
                Uri out = ex.export(this, req, f -> mUi.post(() -> setProgress(f)));
                mUi.post(() -> onSaved(out, overwrite));
            } catch (CancellationException e) {
                mUi.post(() -> { mExporter = null; showProgress(false); });
            } catch (Exception e) {
                Log.e(TAG, "save failed", e);
                mUi.post(() -> {
                    mExporter = null;
                    showProgress(false);
                    String msg = e.getMessage() != null && e.getMessage().contains("not supported")
                            ? "Can't save: " + e.getMessage() : "Couldn't save the video";
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void onSaved(Uri saved, boolean replaced) {
        mExporter = null;
        showProgress(false);
        Toast.makeText(this, replaced ? "Original replaced" : "Saved as copy", Toast.LENGTH_SHORT).show();
        // Gallery's viewer loads the result by "content://media/external/video/media/<id>" and compares the id with
        // the current item: same id = reload the replaced original, new id = move to the copy.
        Uri out = saved;
        try {
            out = android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    android.content.ContentUris.parseId(saved));
        } catch (RuntimeException ignored) { }
        Intent result = new Intent().setData(out).putExtra("saved_uri", out)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        setResult(RESULT_OK, result);
        notifyGallery(ACTION_SAVED, out);
        finish();
    }

    private void cancelAndFinish() {
        setResult(RESULT_CANCELED);
        notifyGallery(ACTION_CANCELED, null);
        finish();
    }

    private void handleBack() {
        if (mExporter != null) { mExporter.cancel(); return; }
        if (!isEdited()) { cancelAndFinish(); return; }
        pause();
        new AlertDialog.Builder(this)
                .setMessage("Discard changes?")
                .setPositiveButton("Discard", (d, w) -> cancelAndFinish())
                .setNegativeButton("Keep editing", null)
                .show();
    }

    /** Gallery's viewer listens for these (RECEIVER_EXPORTED) to jump to the saved copy. Only tell it if it called us. */
    private void notifyGallery(String action, Uri saved) {
        // Gallery doesn't always start us for result, and its referrer isn't reliable either, so always send it.
        // It is explicit to Gallery, and Gallery only listens while its own Edit flow is waiting
        // (VideoEditorReceiveHandler is registered in prepareEditor), so other callers are unaffected.
        Log.i(TAG, "notify gallery " + action + " " + saved + " caller=" + getCallingPackage() + " ref=" + getReferrer());
        Intent i = new Intent(action).setPackage(GALLERY);
        if (saved != null) i.putExtra("saved_uri", saved);
        if (mSource != null) i.putExtra("uri", mSource);
        try {
            sendBroadcast(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "notify gallery failed", e);
        }
    }

    private void showProgress(boolean show) {
        mOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) setProgress(0);
    }

    private void setProgress(float f) {
        int pct = Math.round(f * 100);
        mProgress.setProgress(pct);
        mProgressText.setText("Saving… " + pct + "%");
    }

    // ---------------------------------------------------------------- UI construction

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // Top bar: close | title | Save
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), 0, dp(12), 0);
        ImageView close = new ImageView(this);
        close.setImageDrawable(new Icon(Icon.Kind.CLOSE, TEXT));
        close.setPadding(dp(12), dp(12), dp(12), dp(12));
        close.setContentDescription("Cancel");
        close.setBackground(ripple());
        close.setOnClickListener(v -> handleBack());
        top.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = text("Edit video", 18, TEXT, true);
        title.setPadding(dp(8), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        mSave = text("Save", 16, TEXT, true);
        mSave.setGravity(Gravity.CENTER);
        mSave.setPadding(dp(20), 0, dp(20), 0);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(ACCENT);
        pill.setCornerRadius(dp(20));
        mSave.setBackground(pill);
        mSave.setOnClickListener(v -> save());
        top.addView(mSave, new LinearLayout.LayoutParams(-2, dp(40)));
        col.addView(top, new LinearLayout.LayoutParams(-1, dp(56)));

        // Preview
        mPreviewBox = new FrameLayout(this);
        mPreviewBox.setClipChildren(true);
        mTexture = new TextureView(this);
        mTexture.setSurfaceTextureListener(this);
        mPreviewBox.addView(mTexture, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        mPlayButton = new ImageView(this);
        mPlayIcon = new Icon(Icon.Kind.PLAY, TEXT);
        mPlayButton.setImageDrawable(mPlayIcon);
        mPlayButton.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x66000000);
        mPlayButton.setBackground(circle);
        mPlayButton.setContentDescription("Play");
        mPreviewBox.addView(mPlayButton, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));
        mPreviewBox.setOnClickListener(v -> togglePlay());
        mPreviewBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) v.post(() -> layoutTexture(false));
        });
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(-1, 0, 1);
        previewLp.setMargins(dp(16), dp(8), dp(16), dp(8));
        col.addView(mPreviewBox, previewLp);

        // Times
        LinearLayout times = new LinearLayout(this);
        times.setGravity(Gravity.CENTER_VERTICAL);
        times.setPadding(dp(24), 0, dp(24), 0);
        mStartText = text("0:00.0", 13, TEXT2, false);
        mLengthText = text("0:00", 15, TEXT, true);
        mLengthText.setGravity(Gravity.CENTER);
        mEndText = text("0:00.0", 13, TEXT2, false);
        mEndText.setGravity(Gravity.END);
        times.addView(mStartText, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(mLengthText, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(mEndText, new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(times, new LinearLayout.LayoutParams(-1, dp(28)));

        // Trim strip
        mTrim = new TrimRangeView(this);
        mTrim.setListener(this);
        mTrim.setPadding(dp(12), dp(6), dp(12), dp(6));
        mTrim.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol && mInfo != null) v.post(this::loadThumbnails);
        });
        col.addView(mTrim, new LinearLayout.LayoutParams(-1, dp(76)));

        // Tools
        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER);
        mRotateTool = new ToolButton(Icon.Kind.ROTATE, "Rotate");
        mRotateTool.setOnClickListener(v -> rotate());
        mMuteTool = new ToolButton(Icon.Kind.VOLUME, "Sound");
        mMuteTool.setOnClickListener(v -> toggleMute());
        tools.addView(mRotateTool, new LinearLayout.LayoutParams(dp(96), -1));
        tools.addView(mMuteTool, new LinearLayout.LayoutParams(dp(96), -1));
        LinearLayout.LayoutParams toolsLp = new LinearLayout.LayoutParams(-1, dp(80));
        toolsLp.setMargins(0, dp(8), 0, dp(8));
        col.addView(tools, toolsLp);

        // Saving overlay
        mOverlay = new FrameLayout(this);
        mOverlay.setBackgroundColor(0xCC000000);
        mOverlay.setClickable(true);
        mOverlay.setVisibility(View.GONE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        mProgressText = text("Saving…", 16, TEXT, true);
        box.addView(mProgressText);
        mProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mProgress.setMax(100);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(dp(240), -2);
        pbLp.setMargins(0, dp(16), 0, dp(16));
        box.addView(mProgress, pbLp);
        TextView stop = text("Cancel", 15, ACCENT, true);
        stop.setPadding(dp(16), dp(10), dp(16), dp(10));
        stop.setBackground(ripple());
        stop.setOnClickListener(v -> { if (mExporter != null) mExporter.cancel(); });
        box.addView(stop);
        mOverlay.addView(box, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(mOverlay, new FrameLayout.LayoutParams(-1, -1));

        // Edge-to-edge (enforced for targetSdk 35+): keep content clear of system bars and cutouts.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });
        return root;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private android.graphics.drawable.Drawable ripple() {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        return getDrawable(tv.resourceId);
    }

    /** Icon above a label; accent-coloured while its effect is on. */
    private final class ToolButton extends LinearLayout {
        private final Icon mIcon;
        private final TextView mLabel;

        ToolButton(Icon.Kind kind, String label) {
            super(TrimActivity.this);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setBackground(ripple());
            mIcon = new Icon(kind, TEXT);
            ImageView iv = new ImageView(getContext());
            iv.setImageDrawable(mIcon);
            addView(iv, new LayoutParams(dp(28), dp(28)));
            mLabel = text(label, 12, TEXT2, false);
            mLabel.setPadding(0, dp(6), 0, 0);
            addView(mLabel, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            setContentDescription(label);
        }

        void setIcon(Icon.Kind k) { mIcon.setKind(k); }

        void setLabel(String s) { mLabel.setText(s); setContentDescription(s); }

        void setActive(boolean on) {
            mIcon.setColor(on ? ACCENT : TEXT);
            mLabel.setTextColor(on ? ACCENT : TEXT2);
        }

        @Override public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setAlpha(enabled ? 1f : 0.35f);
        }
    }
}
