package io.freemytv;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;

/** The update's install through the TV's own ADB, against the fake TV (mobile/fake_tv.py on 127.0.0.1:5555). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AdbInstallTest {
    @Test public void streamsTheApk() throws Exception {
        android.content.Context c = RuntimeEnvironment.getApplication();
        Ui.init(c);
        File apk = new File(c.getCacheDir(), "x.apk");
        byte[] b = new byte[1_234_567];
        new java.util.Random(7).nextBytes(b);
        try (FileOutputStream o = new FileOutputStream(apk)) { o.write(b); }
        try (Adb adb = Adb.connect(c, 10_000, null); FileInputStream in = new FileInputStream(apk)) {
            assertEquals("Success", adb.install(in, apk.length()).trim());
        }
    }
}
