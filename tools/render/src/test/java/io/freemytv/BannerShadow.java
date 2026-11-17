package io.freemytv;

import android.graphics.drawable.Drawable;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowApplicationPackageManager;

import java.util.HashMap;
import java.util.Map;

/** Gives the stand-in apps a TV banner, as real apps have (Robolectric has none). */
@Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false, looseSignatures = true)
public class BannerShadow extends ShadowApplicationPackageManager {
    static final Map<String, Drawable> BANNERS = new HashMap<>();

    @Implementation
    protected Drawable getApplicationBanner(String packageName) {
        return BANNERS.get(packageName);
    }
}
