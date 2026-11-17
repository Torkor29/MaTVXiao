package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.KeyEvent;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;

/** Draws MaTV's settings screen as the TV would (1920 × 1080), to look at it without a TV. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-television-xhdpi")
public class RenderTest {
    private MaTVSettingsActivity a;

    private void settle(int ms) throws Exception {
        for (int i = 0; i < ms / 50; i++) {
            ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS);
            Thread.sleep(10);
        }
    }

    private void shot(String name) throws Exception {
        settle(1200);
        View d = a.getWindow().getDecorView();
        int w = 1920, h = 1080;
        d.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        d.layout(0, 0, w, h);
        settle(300);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.draw(new Canvas(b));
        File dir = new File(System.getProperty("shots"));
        dir.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(dir, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
    }

    private Object call(String name, Class<?>[] types, Object... args) throws Exception {
        java.lang.reflect.Method m = MaTVSettingsActivity.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(a, args);
    }

    /** Puts the focus on row `i` of the list, as the remote would. */
    private void focus(int i) throws Exception {
        java.lang.reflect.Field f = MaTVSettingsActivity.class.getDeclaredField("list");
        f.setAccessible(true);
        android.widget.LinearLayout list = (android.widget.LinearLayout) f.get(a);
        for (int j = 0; j < list.getChildCount(); j++) {
            View r = list.getChildAt(j);
            if (r.getOnFocusChangeListener() != null) r.getOnFocusChangeListener().onFocusChange(r, j == i);
        }
        settle(400);
    }

    private void run(String theme, String prefix) throws Exception {
        android.content.Context c = RuntimeEnvironment.getApplication();
        java.lang.reflect.Method scale = android.animation.ValueAnimator.class.getDeclaredMethod("setDurationScale", float.class);
        scale.setAccessible(true);
        scale.invoke(null, 0f);
        Prefs.setTheme(theme);
        Art.ensureSeeded(c);
        ActivityController<MaTVSettingsActivity> ctl = Robolectric.buildActivity(MaTVSettingsActivity.class).setup();
        a = ctl.get();
        Thread.sleep(1500);   // the blurred backdrop is made on a thread
        focus(3);
        shot(prefix + "1-categories");
        call("showCategory", new Class<?>[] {int.class, int.class}, 3, 0);
        focus(1);
        shot(prefix + "2-saver");
        call("showCategory", new Class<?>[] {int.class, int.class}, 0, 0);
        focus(4);
        shot(prefix + "3-look");
        call("showCategory", new Class<?>[] {int.class, int.class}, 5, 0);
        focus(2);
        shot(prefix + "4-phone");
        ctl.pause().stop().destroy();
    }

    @Test public void dark() throws Exception { run("dark", "dark-"); }
    @Test public void light() throws Exception { run("light", "light-"); }
}
