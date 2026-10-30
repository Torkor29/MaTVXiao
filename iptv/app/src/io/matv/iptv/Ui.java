package io.matv.iptv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared look (dark, glass-like panels, focus that lifts) and a small image loader for logos and posters. */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF07090D, TEXT = 0xFFF2F4F8, DIM = 0x99F2F4F8, ACCENT = 0xFF3D8BFF;

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable glass(Context c, int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radius));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }

    /** A row or button: faint glass at rest, bright glass with an accent rim when focused or selected. */
    static StateListDrawable focusable(Context c, float radius) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, glass(c, 0x40FFFFFF, 0xCCFFFFFF, radius));
        s.addState(new int[] {android.R.attr.state_selected}, glass(c, 0x26FFFFFF, 0x553D8BFF, radius));
        s.addState(new int[] {android.R.attr.state_activated}, glass(c, 0x26FFFFFF, 0x553D8BFF, radius));
        s.addState(new int[] {}, glass(c, 0x00000000, 0, radius));
        return s;
    }

    /** A list row: ListView marks the row under the focus as selected; the chosen category is activated. */
    static StateListDrawable row(Context c, float radius) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_selected}, glass(c, 0x40FFFFFF, 0xCCFFFFFF, radius));
        s.addState(new int[] {android.R.attr.state_activated}, glass(c, 0x22FFFFFF, 0x663D8BFF, radius));
        s.addState(new int[] {}, glass(c, 0x00000000, 0, radius));
        return s;
    }

    static TextView text(Context c, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif" + (bold ? "-medium" : ""), Typeface.NORMAL));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    /** The focused view grows a little, like on the home screen. */
    static void lift(View v) {
        v.setOnFocusChangeListener((view, has) -> view.animate().scaleX(has ? 1.03f : 1f).scaleY(has ? 1.03f : 1f)
                .setDuration(140).start());
    }

    // MARK: - Images

    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService pool = Executors.newFixedThreadPool(4);
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** Loads `url` into `view` (scaled down to about `maxPx`); a recycled row that moved on keeps its own image. */
    static void image(ImageView view, String url, int maxPx) {
        view.setTag(url);
        if (url == null || url.isEmpty() || !url.startsWith("http")) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap hit = cache.get(url);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setImageDrawable(null);
        pool.execute(() -> {
            Bitmap b = null;
            try {
                b = fetch(url, maxPx);
            } catch (Exception | OutOfMemoryError ignored) {
                // a missing logo is fine: the name shows
            }
            final Bitmap got = b;
            if (got != null) cache.put(url, got);
            main.post(() -> { if (url.equals(view.getTag()) && got != null) view.setImageBitmap(got); });
        });
    }

    private static Bitmap fetch(String url, int maxPx) throws Exception {
        byte[] data;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[32768];
            for (int n; (n = in.read(buf)) > 0; ) {
                b.write(buf, 0, n);
                if (b.size() > 8_000_000) return null;
            }
            data = b.toByteArray();
        } finally {
            c.disconnect();
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= maxPx) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    static int alpha(int color, float a) {
        return Color.argb(Math.round(255 * a), Color.red(color), Color.green(color), Color.blue(color));
    }
}
