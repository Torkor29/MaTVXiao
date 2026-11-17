package io.matv.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Map;

/** Draws the app's screens at phone size (shots/), for a look without a phone and for the store listing. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w411dp-h731dp-port-xxhdpi")
public class ShotsTest {
    private MainActivity a;

    private void settle() throws Exception {
        for (int i = 0; i < 10; i++) { ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS); Thread.sleep(5); }
    }

    private void shot(String name) throws Exception {
        settle();
        View d = a.getWindow().getDecorView();
        int w = 1080, h = 1920;
        d.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        d.layout(0, 0, w, h);
        settle();
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.draw(new Canvas(b));
        File dir = new File(System.getProperty("shots"));
        dir.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(dir, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
    }

    private Object field(String n) throws Exception { Field f = MainActivity.class.getDeclaredField(n); f.setAccessible(true); return f.get(a); }
    private void set(String n, Object v) throws Exception { Field f = MainActivity.class.getDeclaredField(n); f.setAccessible(true); f.set(a, v); }
    private void call(String n) throws Exception { Method m = MainActivity.class.getDeclaredMethod(n); m.setAccessible(true); m.invoke(a); }

    private Scanner.Tv tv(String ip, String name, String model, boolean ready) {
        Scanner.Tv t = new Scanner.Tv(ip); t.name = name; t.model = model; t.ready = ready; return t;
    }

    @SuppressWarnings("unchecked")
    @Test public void screens() throws Exception {
        Method scale = android.animation.ValueAnimator.class.getDeclaredMethod("setDurationScale", float.class);
        scale.setAccessible(true);
        scale.invoke(null, 0f);
        a = Robolectric.buildActivity(MainActivity.class).setup().get();
        Scanner.testAddress = (Inet4Address) InetAddress.getByName("192.168.1.23");
        set("scanning", false);
        ((java.util.concurrent.atomic.AtomicBoolean) field("scanCancel")).set(true);
        call("showList");
        shot("a1-empty");
        a.save("S1", "TV du salon", "192.168.1.189", 8765, "token_abcdefghijklmnopqrstu");
        Map<String, Scanner.Tv> tvs = (Map<String, Scanner.Tv>) field("tvs");
        tvs.put("192.168.1.57", tv("192.168.1.57", "Chambre", "Xiaomi TV A Pro 55", false));
        tvs.put("192.168.1.80", tv("192.168.1.80", "Bureau", "", true));
        call("showList");
        shot("a2-list");
        set("tv", tv("192.168.1.57", "Chambre", "Xiaomi TV A Pro 55", false));
        call("showGuide");
        shot("a3-guide");
        set("tv", tv("192.168.1.80", "Bureau", "", true));
        set("phase", "approve");
        call("showInstall");
        shot("a4-approve");
        Net.Item home = (Net.Item) field("home"), live = (Net.Item) field("live");
        set("tvInfo", "Xiaomi MiTV-MOOR2, Android 11");
        home.installed = "2.9.0"; home.version = "2.10.0"; home.url = "x"; home.wanted = true;
        live.installed = ""; live.version = "2.10.0"; live.url = "x"; live.wanted = true;
        set("phase", "choose");
        call("showInstall");
        shot("a5-choose");
        home.status = "Installé"; home.done = true; home.installed = "2.10.0"; home.wanted = false;
        live.status = "Installé"; live.done = true; live.installed = "2.10.0"; live.wanted = false;
        set("phase", "done");
        call("showInstall");
        shot("a6-done");
    }
}
