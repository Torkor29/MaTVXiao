package io.matv.installer;

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

/** Draws MaTV Installer's screens at phone size, to look at them without a phone. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w393dp-h852dp-port-xxhdpi")
public class ShotsTest {
    private MainActivity a;

    private void settle() throws Exception {
        for (int i = 0; i < 10; i++) { ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS); Thread.sleep(5); }
    }

    private void shot(String name) throws Exception {
        settle();
        View d = a.getWindow().getDecorView();
        int w = 1179, h = 2556;
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
        shot("i0-nowifi");
        Scanner.testAddress = (Inet4Address) InetAddress.getByName("192.168.1.23");
        Map<String, Scanner.Tv> tvs = (Map<String, Scanner.Tv>) field("tvs");
        set("scanning", true);
        tvs.put("192.168.1.189", tv("192.168.1.189", "TV du salon", "MiTV-MOOR2", true));
        call("showList");
        shot("i1-scanning");
        set("scanning", false);
        tvs.put("192.168.1.57", tv("192.168.1.57", "Chambre", "Xiaomi TV A Pro 55", false));
        tvs.put("192.168.1.80", tv("192.168.1.80", "", "", true));
        call("showList");
        shot("i2-list");
        tvs.clear();
        call("showList");
        shot("i3-empty");
        set("tv", tv("192.168.1.57", "Chambre", "Xiaomi TV A Pro 55", false));
        set("screen", "guide");
        call("showGuide");
        shot("i4-guide");
        set("tv", tv("192.168.1.189", "TV du salon", "MiTV-MOOR2", true));
        set("screen", "install");
        set("phase", "approve");
        call("showInstall");
        shot("i5-approve");
        Net.Item home = (Net.Item) field("home"), live = (Net.Item) field("live"), tt = (Net.Item) field("tizen");
        set("tvInfo", "Xiaomi MiTV-MOOR2, Android 11");
        home.installed = "2.7.1"; home.version = "2.9.0"; home.url = "x"; home.wanted = true;
        live.installed = "2.9.0"; live.version = "2.9.0"; live.url = "x"; live.wanted = false;
        tt.installed = ""; tt.version = "v1.6.2"; tt.url = "x"; tt.wanted = true;
        set("phase", "choose");
        call("showInstall");
        shot("i6-choose");
        set("phase", "installing");
        home.status = "Envoi à la TV 62 %";
        set("playProtectHint", true);
        call("showInstall");
        Method s = MainActivity.class.getDeclaredMethod("set", Net.Item.class, String.class, float.class);
        s.setAccessible(true);
        s.invoke(a, home, "Envoi à la TV 62 %", 0.76f);
        s.invoke(a, tt, "Téléchargement 18 %", 0.08f);
        shot("i7-installing");
        home.status = "Installé"; home.done = true; home.installed = "2.9.0"; home.wanted = false;
        tt.status = "Installé"; tt.done = true; tt.installed = "v1.6.2"; tt.wanted = false;
        set("playProtectHint", false);
        set("phase", "done");
        call("showInstall");
        shot("i8-done");
        set("phase", "error");
        set("error", "La TV n'a pas autorisé ce téléphone.");
        call("showInstall");
        shot("i9-error");
    }
}
