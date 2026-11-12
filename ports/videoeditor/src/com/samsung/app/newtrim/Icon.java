package com.samsung.app.newtrim;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Small vector icons drawn on a 24-unit grid, so the app needs no image resources. */
public final class Icon extends Drawable {
    public enum Kind { PLAY, PAUSE, ROTATE, VOLUME, MUTE, CLOSE }

    private Kind mKind;
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();

    public Icon(Kind kind, int color) {
        mKind = kind;
        mFill.setStyle(Paint.Style.FILL);
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeCap(Paint.Cap.ROUND);
        mStroke.setStrokeJoin(Paint.Join.ROUND);
        setColor(color);
    }

    public void setKind(Kind kind) { mKind = kind; invalidateSelf(); }

    public void setColor(int color) { mFill.setColor(color); mStroke.setColor(color); invalidateSelf(); }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height()) / 24f;
        c.save();
        c.translate(b.left + (b.width() - 24 * s) / 2, b.top + (b.height() - 24 * s) / 2);
        c.scale(s, s);
        mStroke.setStrokeWidth(2f);
        mPath.reset();
        switch (mKind) {
            case PLAY:
                mPath.moveTo(8, 5); mPath.lineTo(19, 12); mPath.lineTo(8, 19); mPath.close();
                c.drawPath(mPath, mFill);
                break;
            case PAUSE:
                c.drawRoundRect(new RectF(6.5f, 5, 10, 19), 1, 1, mFill);
                c.drawRoundRect(new RectF(14, 5, 17.5f, 19), 1, 1, mFill);
                break;
            case ROTATE:
                // Rectangle being turned plus a curved arrow.
                c.drawRoundRect(new RectF(4, 10, 14, 20), 1.5f, 1.5f, mStroke);
                mPath.moveTo(9, 5.5f);
                mPath.cubicTo(14, 3.5f, 19, 6, 19.5f, 12);
                c.drawPath(mPath, mStroke);
                mPath.reset();
                mPath.moveTo(17, 10); mPath.lineTo(19.5f, 12.8f); mPath.lineTo(22, 10);
                c.drawPath(mPath, mStroke);
                break;
            case VOLUME:
            case MUTE:
                mPath.moveTo(3, 9.5f); mPath.lineTo(7, 9.5f); mPath.lineTo(12, 5); mPath.lineTo(12, 19);
                mPath.lineTo(7, 14.5f); mPath.lineTo(3, 14.5f); mPath.close();
                c.drawPath(mPath, mFill);
                if (mKind == Kind.VOLUME) {
                    c.drawArc(new RectF(9, 8, 17, 16), -50, 100, false, mStroke);
                    c.drawArc(new RectF(9, 4.5f, 21, 19.5f), -50, 100, false, mStroke);
                } else {
                    c.drawLine(15.5f, 9, 21, 15, mStroke);
                    c.drawLine(21, 9, 15.5f, 15, mStroke);
                }
                break;
            case CLOSE:
                c.drawLine(6, 6, 18, 18, mStroke);
                c.drawLine(18, 6, 6, 18, mStroke);
                break;
        }
        c.restore();
    }

    @Override public void setAlpha(int alpha) { mFill.setAlpha(alpha); mStroke.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { mFill.setColorFilter(cf); mStroke.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
