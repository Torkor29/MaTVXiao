package io.freemytv;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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

import static org.robolectric.Shadows.shadowOf;

/** Draws MaTV's home screen as the TV would (1920 × 1080, Android 11 like the Xiaomi), with real TMDB picks. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "fr-rFR-w960dp-h540dp-land-television-xhdpi", shadows = BannerShadow.class)
public class HomeRenderTest {
    static final String[][] APPS = {
        {"com.netflix.ninja", "Netflix", "E50914"}, {"com.google.android.youtube.tv", "YouTube", "FF0033"},
        {"com.amazon.amazonvideo.livingroom", "Prime Video", "1A98FF"}, {"com.disney.disneyplus", "Disney+", "1838C8"},
        {"com.apple.atve.androidtv.appletv", "Apple TV", "1C1C1E"}, {"com.canal.android.canal", "myCANAL", "111111"},
        {"io.matv.iptv", "MaTV Live", "5E5CE6"}, {"com.spotify.tv.android", "Spotify", "1DB954"},
    };

    static void settle(int ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS);
            Thread.sleep(20);
        }
    }

    static void shot(android.app.Activity a, String name) throws Exception {
        View d = a.getWindow().getDecorView();
        d.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
        d.layout(0, 0, 1920, 1080);
        settle(400);
        Bitmap b = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
        d.draw(new Canvas(b));
        File dir = new File(System.getProperty("shots"));
        dir.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(dir, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
    }

    /** A stand-in for the app's TV banner: its colour and name (the real apps bring their own art). */
    static android.graphics.drawable.Drawable banner(android.content.Context c, String name, String hex) {
        Bitmap b = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        int col = 0xFF000000 | Integer.parseInt(hex, 16);
        android.graphics.Paint bg = new android.graphics.Paint();
        bg.setShader(new android.graphics.LinearGradient(0, 0, 640, 360, col, android.graphics.Color.rgb(
                Math.max(0, android.graphics.Color.red(col) - 40), Math.max(0, android.graphics.Color.green(col) - 40),
                Math.max(0, android.graphics.Color.blue(col) - 40)), android.graphics.Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, 640, 360, bg);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setTextSize(78);
        p.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD));
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        cv.drawText(name, 320, 205, p);
        return new android.graphics.drawable.BitmapDrawable(c.getResources(), b);
    }

    static void installApps() {
        android.content.Context c = RuntimeEnvironment.getApplication();
        org.robolectric.shadows.ShadowPackageManager spm = shadowOf(c.getPackageManager());
        for (String[] app : APPS) {
            PackageInfo pi = new PackageInfo();
            pi.packageName = app[0];
            pi.applicationInfo = new ApplicationInfo();
            pi.applicationInfo.packageName = app[0];
            pi.applicationInfo.nonLocalizedLabel = app[1];
            pi.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED;
            spm.installPackage(pi);
            BannerShadow.BANNERS.put(app[0], banner(c, app[1], app[2]));
            ComponentName cn = new ComponentName(app[0], app[0] + ".Main");
            spm.addActivityIfNotPresent(cn);
            for (String cat : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
                spm.addIntentFilterForActivity(cn, new android.content.IntentFilter(Intent.ACTION_MAIN) {{ addCategory(cat); }});
                ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = new ActivityInfo();
                ri.activityInfo.packageName = app[0];
                ri.activityInfo.name = cn.getClassName();
                ri.activityInfo.applicationInfo = pi.applicationInfo;
                ri.nonLocalizedLabel = app[1];
                spm.addResolveInfoForIntent(new Intent(i).setPackage(app[0]), ri);
                spm.addResolveInfoForIntent(i, ri);
            }
        }
    }

    static Object field(Object o, Class<?> c, String name) throws Exception {
        java.lang.reflect.Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    void run(String style, String theme, String name) throws Exception { run(style, theme, name, "all"); }

    void run(String style, String theme, String name, String mood) throws Exception {
        org.robolectric.shadows.ShadowLog.stream = System.out;
        installApps();
        String key = System.getProperty("tmdbKey", "");
        Prefs.setTmdbKey(key);
        Prefs.setTheme(theme);
        Prefs.setTopContent(Prefs.TOP_PICKS);
        Prefs.setTopStyle(style);
        Prefs.setPicksMood(mood);
        ActivityController<HomeActivity> ctl = Robolectric.buildActivity(HomeActivity.class).setup();
        settle(Integer.getInteger("waitMs", 25000));
        System.out.println("RECS " + Recs.status(RuntimeEnvironment.getApplication()));
        Object hv = field(ctl.get(), HomeActivity.class, "home");
        Recs r = (Recs) field(hv, HomeView.class, "recs");
        for (Recs.Pick pk : r.now()) System.out.println("PICK " + pk.title + " logo=" + pk.logo + " detailed=" + pk.detailed + " bmp=" + r.logo(pk));
        System.out.println("BB image=" + field(hv, HomeView.class, "bbImage") + " key=" + field(hv, HomeView.class, "bbKey")
                + " billboard=" + field(hv, HomeView.class, "billboard") + " w=" + field(hv, HomeView.class, "w"));
        shot(ctl.get(), name + "-first");
        settle(8000);
        shot(ctl.get(), name);
        ctl.pause().stop().destroy();
    }

    @Test public void billboardDark() throws Exception { run("billboard", "dark", "home-billboard-dark"); }
    @Test public void moodLaugh() throws Exception { run("billboard", "dark", "home-mood-laugh", "laugh"); }
    @Test public void cardsDark() throws Exception { run("cards", "dark", "home-cards-dark"); }
}
