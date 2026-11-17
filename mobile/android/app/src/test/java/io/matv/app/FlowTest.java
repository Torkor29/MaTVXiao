package io.matv.app;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The real flow against a fake TV on 127.0.0.1:5555 (tools/installer/fake_tv.py): connect, the TV's prompt, read the
 * versions, GitHub's latest release, download, certificate check, streamed install, pairing, control screen.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w393dp-h852dp-port-xxhdpi", shadows = {ArchiveShadow.class})
public class FlowTest {
    private MainActivity a;
    private Object get(String n) throws Exception { Field f = MainActivity.class.getDeclaredField(n); f.setAccessible(true); return f.get(a); }
    private void set(String n, Object v) throws Exception { Field f = MainActivity.class.getDeclaredField(n); f.setAccessible(true); f.set(a, v); }
    private void call(String n) throws Exception { Method m = MainActivity.class.getDeclaredMethod(n); m.setAccessible(true); m.invoke(a); }

    private String waitFor(String field, String... want) throws Exception {
        String last = "";
        for (int i = 0; i < 2400; i++) {
            ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS);
            Thread.sleep(50);
            String p = (String) get(field);
            if (!p.equals(last)) { System.out.println(field.toUpperCase() + " " + p + (p.equals("error") ? " : " + get("error") : "")); last = p; }
            for (String w : want) if (p.equals(w)) return p;
        }
        return last;
    }

    @Test public void installPairControl() throws Exception {
        a = Robolectric.buildActivity(MainActivity.class).setup().get();
        Scanner.Tv t = new Scanner.Tv("127.0.0.1"); t.name = "Fake"; t.ready = true;
        set("tv", t);
        call("startInstall");
        assertEquals("choose", waitFor("phase", "choose", "error"));
        Net.Item home = (Net.Item) get("home"), live = (Net.Item) get("live");
        System.out.println("home " + home.installed + " -> " + home.version + ", live " + live.installed + " -> " + live.version);
        assertTrue(home.wanted && live.wanted);
        call("runInstall");
        assertEquals("done", waitFor("phase", "done", "choose", "error"));
        assertTrue(home.done && live.done);
        call("pairAndControl");
        assertEquals("control", waitFor("screen", "control"));
        String saved = a.getSharedPreferences("tvs", 0).getString("saved", "");
        System.out.println("SAVED " + saved);
        assertTrue(saved.contains("faketoken_0123456789abcdef") && saved.contains("\"port\":8765"));
        assertEquals("http://127.0.0.1:8765/#k=faketoken_0123456789abcdef", MainActivity.connectUrl(a.saved().get(0)));
    }
}
