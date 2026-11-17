package io.freemytv;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PairTest {
    @Test public void answersWithTheSecret() {
        Context c = RuntimeEnvironment.getApplication();
        final String[] got = {null};
        final int[] code = {0};
        Intent i = new Intent().setComponent(new ComponentName(c, PairReceiver.class));
        c.sendOrderedBroadcast(i, null, new BroadcastReceiver() {
            @Override public void onReceive(Context x, Intent y) { got[0] = getResultData(); code[0] = getResultCode(); }
        }, null, 0, null, null);
        ShadowLooper.idleMainLooper();
        System.out.println("PAIR " + code[0] + " " + got[0]);
        assertTrue(code[0] == Activity.RESULT_OK && got[0] != null && got[0].matches("matv-connect [A-Za-z0-9_-]{20,} 8765"));
    }
}
