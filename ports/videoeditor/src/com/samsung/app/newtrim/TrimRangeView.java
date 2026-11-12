package com.samsung.app.newtrim;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Filmstrip of thumbnails with a start and an end handle (One UI style: rounded frame, dimmed outside the
 * selection) and a playhead. Drag a handle to trim, drag inside the strip to scrub.
 */
public final class TrimRangeView extends View {
    public interface Listener {
        void onTrimTouchStart();
        void onRangeChanged(long startMs, long endMs, boolean startHandle);
        void onScrub(long ms);
        void onTrimTouchEnd();
    }

    private static final int NONE = 0, START = 1, END = 2, SCRUB = 3;
    private static final int FRAME = 0xFFFFFFFF, ACTIVE = 0xFFFFD54F, DIM = 0xB4000000, EMPTY = 0xFF2A2A2A;

    private final float mDp;
    private final float mHandleW, mFrame, mRadius, mSlop;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path mClip = new Path();
    private final RectF mR = new RectF();
    private final Rect mSrc = new Rect();

    private Listener mListener;
    private long mDuration = 1, mStart, mEnd = 1, mPlayhead, mMinRange = 1000;
    private Bitmap[] mThumbs = new Bitmap[0];
    private int mActive = NONE;
    private float mGrabOffset;

    public TrimRangeView(Context ctx) {
        super(ctx);
        mDp = ctx.getResources().getDisplayMetrics().density;
        mHandleW = 14 * mDp;
        mFrame = 3 * mDp;
        mRadius = 8 * mDp;
        mSlop = 20 * mDp;
        setHapticFeedbackEnabled(true);
    }

    public void setListener(Listener l) { mListener = l; }

    public void setDuration(long durationMs) {
        mDuration = Math.max(1, durationMs);
        mMinRange = Math.min(1000, mDuration);
        mStart = 0;
        mEnd = mDuration;
        mPlayhead = 0;
        invalidate();
    }

    public long getStartMs() { return mStart; }
    public long getEndMs() { return mEnd; }

    public void setPlayhead(long ms) {
        mPlayhead = ms;
        invalidate();
    }

    /** Number of thumbnails that fill the strip for frames of the given aspect ratio (w/h). */
    public int thumbCountFor(float aspect) {
        float stripW = right() - left(), stripH = stripBottom() - stripTop();
        if (stripW <= 0 || stripH <= 0) return 0;
        float cell = stripH * Math.max(0.56f, Math.min(1.78f, aspect));
        return Math.max(1, (int) Math.ceil(stripW / cell));
    }

    public void setThumbCount(int n) { mThumbs = new Bitmap[n]; invalidate(); }

    public void setThumb(int i, Bitmap b) {
        if (i >= 0 && i < mThumbs.length) { mThumbs[i] = b; invalidate(); }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // The handles sit near the screen edges, where the system back gesture would steal the drag.
        if (changed) setSystemGestureExclusionRects(java.util.Collections.singletonList(new Rect(0, 0, r - l, b - t)));
    }

    private float left() { return getPaddingLeft() + mHandleW; }
    private float right() { return getWidth() - getPaddingRight() - mHandleW; }
    private float stripTop() { return getPaddingTop() + mFrame; }
    private float stripBottom() { return getHeight() - getPaddingBottom() - mFrame; }
    private float xOf(long ms) { return left() + (right() - left()) * ms / (float) mDuration; }

    private long msOf(float x) {
        float f = (x - left()) / Math.max(1f, right() - left());
        return Math.round(Math.max(0f, Math.min(1f, f)) * mDuration);
    }

    @Override
    protected void onDraw(Canvas c) {
        float l = left(), r = right(), t = stripTop(), b = stripBottom();
        float xs = xOf(mStart), xe = xOf(mEnd);

        // Filmstrip, rounded at the ends.
        c.save();
        mClip.reset();
        mR.set(l, t, r, b);
        mClip.addRoundRect(mR, mRadius * 0.6f, mRadius * 0.6f, Path.Direction.CW);
        c.clipPath(mClip);
        int n = mThumbs.length;
        float cell = n > 0 ? (r - l) / n : r - l;
        for (int i = 0; i < Math.max(1, n); i++) {
            mR.set(l + i * cell, t, l + (i + 1) * cell + 1, b);
            Bitmap bmp = n > 0 ? mThumbs[i] : null;
            if (bmp != null && !bmp.isRecycled()) {
                centerCrop(bmp, mR.width() / mR.height());
                c.drawBitmap(bmp, mSrc, mR, mPaint);
            } else {
                mPaint.setColor(EMPTY);
                c.drawRect(mR, mPaint);
            }
        }
        mPaint.setColor(DIM);
        if (xs > l) c.drawRect(l, t, xs, b, mPaint);
        if (xe < r) c.drawRect(xe, t, r, b, mPaint);
        c.restore();

        // Selection frame: two handles joined by top and bottom bars.
        int frameColor = mActive == START || mActive == END ? ACTIVE : FRAME;
        mPaint.setColor(frameColor);
        c.drawRect(xs, t - mFrame, xe, t, mPaint);
        c.drawRect(xs, b, xe, b + mFrame, mPaint);
        drawHandle(c, xs - mHandleW, xs, t - mFrame, b + mFrame, true, mActive == START ? ACTIVE : frameColor);
        drawHandle(c, xe, xe + mHandleW, t - mFrame, b + mFrame, false, mActive == END ? ACTIVE : frameColor);

        // Playhead.
        if (mPlayhead >= mStart && mPlayhead <= mEnd) {
            float x = xOf(mPlayhead);
            mPaint.setColor(0xFFFFFFFF);
            mR.set(x - 1.5f * mDp, t - 4 * mDp, x + 1.5f * mDp, b + 4 * mDp);
            c.drawRoundRect(mR, 1.5f * mDp, 1.5f * mDp, mPaint);
        }
    }

    private void drawHandle(Canvas c, float x0, float x1, float t, float b, boolean leftSide, int color) {
        mPaint.setColor(color);
        mClip.reset();
        mR.set(x0, t, x1, b);
        float[] radii = leftSide
                ? new float[]{mRadius, mRadius, 0, 0, 0, 0, mRadius, mRadius}
                : new float[]{0, 0, mRadius, mRadius, mRadius, mRadius, 0, 0};
        mClip.addRoundRect(mR, radii, Path.Direction.CW);
        c.drawPath(mClip, mPaint);
        // Grip.
        mPaint.setColor(0xFF000000);
        float cx = (x0 + x1) / 2, cy = (t + b) / 2;
        mR.set(cx - 1f * mDp, cy - 7 * mDp, cx + 1f * mDp, cy + 7 * mDp);
        c.drawRoundRect(mR, mDp, mDp, mPaint);
    }

    private void centerCrop(Bitmap bmp, float dstAspect) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w / (float) h > dstAspect) {
            int cw = Math.round(h * dstAspect);
            mSrc.set((w - cw) / 2, 0, (w + cw) / 2, h);
        } else {
            int ch = Math.round(w / dstAspect);
            mSrc.set(0, (h - ch) / 2, w, (h + ch) / 2);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float hs = xOf(mStart) - mHandleW / 2, he = xOf(mEnd) + mHandleW / 2;
                float ds = Math.abs(x - hs), de = Math.abs(x - he);
                float reach = mHandleW / 2 + mSlop;
                if (Math.min(ds, de) <= reach) {
                    // When the handles overlap, pick by direction of the touch relative to their midpoint.
                    mActive = ds < de || (ds == de && x < (hs + he) / 2) ? START : END;
                    mGrabOffset = x - (mActive == START ? xOf(mStart) : xOf(mEnd));
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                } else {
                    mActive = SCRUB;
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                if (mListener != null) mListener.onTrimTouchStart();
                handleMove(x);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                handleMove(x);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mActive = NONE;
                invalidate();
                if (mListener != null) mListener.onTrimTouchEnd();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void handleMove(float x) {
        if (mActive == START) {
            long v = Math.max(0, Math.min(msOf(x - mGrabOffset), mEnd - mMinRange));
            if (v != mStart) { mStart = v; mPlayhead = v; changed(true); }
        } else if (mActive == END) {
            long v = Math.min(mDuration, Math.max(msOf(x - mGrabOffset), mStart + mMinRange));
            if (v != mEnd) { mEnd = v; mPlayhead = v; changed(false); }
        } else if (mActive == SCRUB) {
            mPlayhead = Math.max(mStart, Math.min(mEnd, msOf(x)));
            invalidate();
            if (mListener != null) mListener.onScrub(mPlayhead);
        }
    }

    private void changed(boolean startHandle) {
        invalidate();
        if (mListener != null) mListener.onRangeChanged(mStart, mEnd, startHandle);
    }
}
