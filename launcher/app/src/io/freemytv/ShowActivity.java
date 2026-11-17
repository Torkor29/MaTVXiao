package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * The wallpapers on the whole screen, now: what the phone's "Show on the TV" opens (MaTV Connect), starting with the
 * one it picked, then the screensaver's slideshow of the active collection. It keeps the screen on until a key is
 * pressed. Exported, so the TV's own ADB can open it from behind another app (Android stops background apps from
 * opening activities); it only shows the wallpapers.
 */
public class ShowActivity extends Activity {
    private DreamView view;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Watchdog.install(this);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        take(getIntent());
        view = new DreamView(this);
        view.wallpapersOnly = true;
        setContentView(view);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        take(intent);
        if (view != null) {   // another wallpaper from the phone: start again from it
            view.stop();
            view.start();
        }
    }

    private static void take(Intent i) {
        String id = i == null ? null : i.getStringExtra("id");
        if (id != null && !id.isEmpty()) Art.showFirst = id;
        Art.version++;
    }

    @Override protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (view != null) view.start();
    }

    @Override protected void onPause() {
        if (view != null) view.stop();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }
}
