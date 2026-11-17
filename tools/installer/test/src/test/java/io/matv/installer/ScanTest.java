package io.matv.installer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ScanTest {
    @Test public void scan() throws Exception {
        long t0 = System.currentTimeMillis();
        Scanner.scan(RuntimeEnvironment.getApplication(), (Inet4Address) InetAddress.getByName("127.0.0.2"),
                tv -> System.out.println("FOUND " + tv.ip + " ready=" + tv.ready + " name=" + tv.title()), new AtomicBoolean());
        System.out.println("SCAN took " + (System.currentTimeMillis() - t0) + " ms");
    }
}
