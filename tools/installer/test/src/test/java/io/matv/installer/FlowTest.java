package io.matv.installer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** The real install flow against a fake TV on 127.0.0.1:5555 (fake_tv.py). */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w393dp-h852dp-port-xxhdpi", shadows = {ArchiveShadow.class})
public class FlowTest {
    private MainActivity a;
    private Object get(String n) throws Exception { Field f = MainActivity.class.getDeclaredField(n); f.setAccessible(true); return f.get(a); }
    private void set(String n, Object v) throws Exception { Field f = MainActivity.class.getDeclaredField(n); f.setAccessible(true); f.set(a, v); }
    private void call(String n) throws Exception { Method m = MainActivity.class.getDeclaredMethod(n); m.setAccessible(true); m.invoke(a); }

    private String waitPhase(String... want) throws Exception {
        String last = "";
        for (int i = 0; i < 2400; i++) {
            ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS);
            Thread.sleep(50);
            String p = (String) get("phase");
            if (!p.equals(last)) { System.out.println("PHASE " + p + (p.equals("error") ? " : " + get("error") : "")); last = p; }
            for (String w : want) if (p.equals(w)) return p;
        }
        return last;
    }

    @Test public void flow() throws Exception {
        a = Robolectric.buildActivity(MainActivity.class).setup().get();
        Scanner.Tv t = new Scanner.Tv("127.0.0.1"); t.name = "Fake"; t.ready = true;
        set("tv", t);
        call("startInstall");
        String p = waitPhase("choose", "error");
        System.out.println("tvInfo=" + get("tvInfo") + " serial=" + get("serial"));
        for (String k : new String[] {"home", "live", "tizen"}) {
            Net.Item it = (Net.Item) get(k);
            System.out.println(k + " installed=" + it.installed + " latest=" + it.version + " wanted=" + it.wanted + " url=" + it.url);
        }
        if (!p.equals("choose")) return;
        ((Net.Item) get("tizen")).wanted = false;   // third-party APK: not needed for this test
        call("runInstall");
        waitPhase("done", "choose", "error");
        for (String k : new String[] {"home", "live"}) {
            Net.Item it = (Net.Item) get(k);
            System.out.println(k + " status=" + it.status + " done=" + it.done + " failed=" + it.failed);
        }
        call("openOnTv");
        for (int i = 0; i < 40; i++) { ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS); Thread.sleep(50); }
    }
}
