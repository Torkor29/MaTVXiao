package io.matv.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.LinearInterpolator;

import static io.matv.app.MainActivity.*;

/** The small pieces drawn by hand: a TV, a pulse, a progress line, a tick, and the TV's permission prompt. */
final class Drawn {
    private Drawn() {}


    /** A TV: a screen on a thin stand, lit gold when it's ready. */
    static final class TvGlyph extends View {
        private final boolean lit;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        TvGlyph(Context c, boolean lit) { super(c); this.lit = lit; }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
            RectF screen = new RectF(d, d, w - d, h * 0.8f);
            p.setStyle(Paint.Style.FILL);
            if (lit) {
                p.setShader(new LinearGradient(0, 0, w, h, 0xFF3A3F5C, 0xFF1A2038, Shader.TileMode.CLAMP));
            } else {
                p.setShader(null);
                p.setColor(0xFF161C30);
            }
            c.drawRoundRect(screen, 6 * d, 6 * d, p);
            p.setShader(null);
            if (lit) {
                p.setColor(GOLD);
                p.setAlpha(230);
                c.drawRoundRect(new RectF(w * 0.18f, h * 0.52f, w * 0.62f, h * 0.6f), 2 * d, 2 * d, p);
                p.setAlpha(110);
                c.drawRoundRect(new RectF(w * 0.18f, h * 0.24f, w * 0.44f, h * 0.42f), 3 * d, 3 * d, p);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.2f * d);
            p.setColor(lit ? 0x66F3D7A3 : 0x33F5F3EE);
            c.drawRoundRect(screen, 6 * d, 6 * d, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x55F5F3EE);
            c.drawRoundRect(new RectF(w * 0.36f, h * 0.86f, w * 0.64f, h * 0.86f + 2.4f * d), d, d, p);
        }
    }

    /** A soft pulsing dot: something is under way. */
    static final class Pulse extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
        private float t;
        Pulse(Context c, int color) {
            super(c);
            p.setColor(color);
            anim.setDuration(1400);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
        }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (ValueAnimator.areAnimatorsEnabled()) anim.start(); }
        @Override protected void onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow(); }
        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy);
            int a = p.getAlpha();
            p.setAlpha((int) (120 * (1 - t)));
            c.drawCircle(cx, cy, r * (0.35f + 0.65f * t), p);
            p.setAlpha(255);
            c.drawCircle(cx, cy, r * 0.3f, p);
            p.setAlpha(a);
        }
    }

    /** A thin progress line. */
    static final class Bar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float f;
        Bar(Context c) { super(c); }
        void set(float v) { f = Math.max(0, Math.min(1, v)); invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth();
            p.setColor(0x1FF5F3EE);
            c.drawRoundRect(new RectF(0, 0, w, h), h / 2, h / 2, p);
            p.setColor(GOLD);
            if (f > 0) c.drawRoundRect(new RectF(0, 0, Math.max(h, w * f), h), h / 2, h / 2, p);
        }
    }

    /** A round tick: chosen (gold), installed (green), or not. */
    static final class Check extends View {
        private final boolean on, done;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Check(Context c, boolean on, boolean done) { super(c); this.on = on; this.done = done; }
        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()), d = getResources().getDisplayMetrics().density, r = s / 2 - d;
            if (on || done) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(done ? OK : GOLD);
                c.drawCircle(s / 2, s / 2, r, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.2f * d);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setColor(ON_GOLD);
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(s * 0.3f, s * 0.52f);
                path.lineTo(s * 0.44f, s * 0.66f);
                path.lineTo(s * 0.71f, s * 0.36f);
                c.drawPath(path, p);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.5f * d);
                p.setColor(0x52F5F3EE);
                c.drawCircle(s / 2, s / 2, r, p);
            }
        }
    }

    /** What the TV shows: "Allow USB debugging?" with "Always allow" ticked and OK focused. */
    static final class PromptMock extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        PromptMock(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
            // The TV
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF060912);
            RectF tvr = new RectF(0, 0, w, h - 10 * d);
            c.drawRoundRect(tvr, 14 * d, 14 * d, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(d);
            p.setColor(EDGE);
            c.drawRoundRect(tvr, 14 * d, 14 * d, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x55F5F3EE);
            c.drawRoundRect(new RectF(w * 0.4f, h - 6 * d, w * 0.6f, h - 3 * d), d, d, p);
            // The dialog
            float dw = Math.min(w * 0.78f, 320 * d), dx = (w - dw) / 2, dy = 18 * d, dh = h - 46 * d;
            p.setColor(0xFF2A2F3A);
            c.drawRoundRect(new RectF(dx, dy, dx + dw, dy + dh), 10 * d, 10 * d, p);
            p.setColor(INK);
            p.setTextSize(14 * d);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText("Autoriser le débogage USB ?", dx + 16 * d, dy + 28 * d, p);
            p.setTypeface(Typeface.DEFAULT);
            p.setTextSize(11 * d);
            p.setColor(DIM);
            c.drawText("Empreinte de la clé RSA : 3F:A2:…", dx + 16 * d, dy + 48 * d, p);
            // "Always allow" ticked
            float by = dy + 74 * d;
            p.setColor(GOLD);
            c.drawRoundRect(new RectF(dx + 16 * d, by - 11 * d, dx + 30 * d, by + 3 * d), 3 * d, 3 * d, p);
            p.setColor(ON_GOLD);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            c.drawLine(dx + 19 * d, by - 4 * d, dx + 22 * d, by - d, p);
            c.drawLine(dx + 22 * d, by - d, dx + 27 * d, by - 8 * d, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(INK);
            p.setTextSize(12 * d);
            c.drawText("Toujours autoriser", dx + 38 * d, by, p);
            // Buttons, OK focused
            float bty = dy + dh - 36 * d;
            p.setColor(DIM);
            c.drawText("ANNULER", dx + dw - 150 * d, bty + 19 * d, p);
            p.setColor(GOLD);
            c.drawRoundRect(new RectF(dx + dw - 70 * d, bty, dx + dw - 14 * d, bty + 28 * d), 14 * d, 14 * d, p);
            p.setColor(ON_GOLD);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText("OK", dx + dw - 50 * d, bty + 19 * d, p);
            p.setTypeface(Typeface.DEFAULT);
        }
    }
}
