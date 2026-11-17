package io.freemytv;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Pairs the MaTV phone app without the QR code: the app, already allowed on the TV's ADB ("Allow USB debugging"),
 * runs `am broadcast -n io.freemytv/.PairReceiver` and reads MaTV Connect's secret and port from the result. Only the
 * shell can call it (android.permission.DUMP, which no installed app gets), so it gives nothing to the TV's other apps.
 * Runs in :home, where the Connect server lives; turns the server on if it was off (whoever holds ADB holds the TV).
 */
public final class PairReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        if (!Prefs.connect()) Prefs.setConnect(true);
        Companion.start(c);
        setResultCode(Activity.RESULT_OK);
        setResultData("matv-connect " + Companion.secret(c) + " " + Companion.PORT);
    }
}
