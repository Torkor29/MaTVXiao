package io.matv.installer;

import android.content.pm.PackageInfo;
import android.content.pm.Signature;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowApplicationPackageManager;

import java.io.File;
import java.nio.file.Files;

/** Robolectric can't run Android's APK verifier: this returns the APK's real certificate (extracted by apksigner). */
@Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
public class ArchiveShadow extends ShadowApplicationPackageManager {
    @Implementation
    public PackageInfo getPackageArchiveInfo(String path, int flags) {
        try {
            String name = new File(path).getName();
            String which = name.startsWith("home") ? "Home" : name.startsWith("live") ? "Live" : null;
            if (which == null) return null;
            PackageInfo p = new PackageInfo();
            p.packageName = which.equals("Home") ? "io.freemytv" : "io.matv.iptv";
            p.signatures = new Signature[] {new Signature(Files.readAllBytes(new File(System.getProperty("certs"), which + ".der").toPath()))};
            return p;
        } catch (Exception e) {
            return null;
        }
    }
}
