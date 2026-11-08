package io.matv.iptv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Updates from the MaTV repository's GitHub releases, once a day when the app opens. A newer release's MaTV-Live.apk
 * is downloaded and checked (GitHub's SHA-256, this package, a higher version, signed with MaTV Live's key) before
 * anything is offered; Android's installer then asks on the TV. Nothing else is ever installed.
 */
final class Updater {
    private Updater() {}

    private static final String LATEST = "https://api.github.com/repos/Torkor29/MaTVXiao/releases/latest";
    private static final String FROM = "https://github.com/Torkor29/MaTVXiao/releases/download/";
    private static final String ASSET = "MaTV-Live.apk";
    /** MaTV Live's signing certificate (SHA-256, as apksigner prints it): only APKs signed with it come in. */
    private static final String CERT = "a8af55f531d524d1f9ec214dde7ac8097f9b56f0b19a38b87dbb1eb1f0be756f";
    private static final long DAY = 24 * 3600_000L;
    static final String ACTION = "io.matv.iptv.INSTALL_STATUS";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile boolean busy;

    /** Looks for an update in the background (at most once a day) and offers it on `a` when it's ready. */
    static void check(Activity a, boolean force) {
        SharedPreferences sp = a.getSharedPreferences("update", Context.MODE_PRIVATE);
        if (busy || (!force && System.currentTimeMillis() - sp.getLong("at", 0) < DAY)) return;
        busy = true;
        Context app = a.getApplicationContext();
        new Thread(() -> {
            File apk = null;
            String version = null;
            try {
                JSONObject rel = new JSONObject(text(LATEST));
                version = rel.optString("tag_name").replaceFirst("^[vV]", "");
                sp.edit().putLong("at", System.currentTimeMillis()).apply();
                if (version.isEmpty() || compare(version, installed(app)) <= 0) {
                    if (force) toast(a, "MaTV Live est à jour.");
                    return;
                }
                JSONArray assets = rel.optJSONArray("assets");
                JSONObject asset = null;
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    if (ASSET.equals(assets.getJSONObject(i).optString("name"))) asset = assets.getJSONObject(i);
                }
                if (asset == null) return;
                String url = asset.optString("browser_download_url"), digest = asset.optString("digest");
                if (!url.startsWith(FROM)) return;
                apk = new File(app.getCacheDir(), "update.apk");
                download(url, apk);
                if (digest.startsWith("sha256:") && !digest.substring(7).equalsIgnoreCase(sha256(apk))) throw new Exception("SHA-256 differs");
                String wrong = misfit(app, apk);
                if (wrong != null) throw new Exception(wrong);
                final File ready = apk;
                final String v = version;
                main.post(() -> offer(a, ready, v));
            } catch (Exception e) {
                android.util.Log.w("MaTVLive", "update: " + e);
                if (apk != null) //noinspection ResultOfMethodCallIgnored
                    apk.delete();
            } finally {
                busy = false;
            }
        }).start();
    }

    private static void toast(Activity a, String s) {
        main.post(() -> Toast.makeText(a, s, Toast.LENGTH_SHORT).show());
    }

    private static void offer(Activity a, File apk, String version) {
        if (a.isFinishing()) return;
        new AlertDialog.Builder(a)
                .setTitle("MaTV Live " + version)
                .setMessage("Une nouvelle version est prête. L'installer maintenant ? Tes codes et favoris sont conservés.")
                .setPositiveButton("Installer", (d, w) -> install(a, apk))
                .setNegativeButton("Plus tard", null)
                .show();
    }

    private static void install(Activity a, File apk) {
        if (!a.getPackageManager().canRequestPackageInstalls()) {   // once: allow MaTV Live to install its updates
            Toast.makeText(a, "Autorise MaTV Live à installer ses mises à jour, puis reviens.", Toast.LENGTH_LONG).show();
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            a.getSharedPreferences("update", Context.MODE_PRIVATE).edit().putLong("at", 0).apply();   // offer again on return
            return;
        }
        try {
            PackageInstaller pi = a.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(a.getPackageName());
            int id = pi.createSession(params);
            try (PackageInstaller.Session s = pi.openSession(id);
                 OutputStream out = s.openWrite("base.apk", 0, apk.length());
                 InputStream in = new FileInputStream(apk)) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                s.fsync(out);
                out.close();
                Intent status = new Intent(a, StatusReceiver.class).setAction(ACTION);
                PendingIntent pending = PendingIntent.getBroadcast(a, id, status,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                s.commit(pending.getIntentSender());
            }
        } catch (Exception e) {
            Toast.makeText(a, "Mise à jour impossible : " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Android's answer: it may need the person's OK (its own screen), or report the result. */
    public static final class StatusReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                Toast.makeText(c, "Mise à jour non installée : " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // MARK: - Checks

    private static String installed(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "0"; }
    }

    /** Null when the APK is MaTV Live, newer, and signed with its key; else why not. */
    @SuppressWarnings("deprecation")
    private static String misfit(Context c, File apk) throws Exception {
        PackageManager pm = c.getPackageManager();
        PackageInfo theirs = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        PackageInfo ours = pm.getPackageInfo(c.getPackageName(), 0);
        if (theirs == null) return "unreadable APK";
        if (!c.getPackageName().equals(theirs.packageName)) return "another app";
        if (theirs.versionCode <= ours.versionCode) return "not newer";
        Signature[] sigs = theirs.signatures;
        if (sigs == null || sigs.length != 1) return "not signed once";
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        if (!CERT.equals(hex(md.digest(sigs[0].toByteArray())))) return "another key";
        return null;
    }

    static int compare(String a, String b) {
        String[] x = a.split("[.\\-]"), y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D.*", "")); } catch (NumberFormatException e) { return 0; }
    }

    // MARK: - HTTP

    private static String text(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", "MaTV-Live");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** github.com sends the download on to its own host: followed by hand, https only. */
    private static void download(String url, File to) throws Exception {
        for (int hops = 0; hops < 5; hops++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(60_000);
            c.setRequestProperty("User-Agent", "MaTV-Live");
            int code = c.getResponseCode();
            if (code / 100 == 3) {
                url = c.getHeaderField("Location");
                c.disconnect();
                if (url == null || !url.startsWith("https://")) throw new Exception("bad redirect");
                continue;
            }
            if (code != 200) throw new Exception("HTTP " + code);
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[65536];
                long total = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    if ((total += n) > 30L << 20) throw new Exception("too large");
                }
            } finally {
                c.disconnect();
            }
            return;
        }
        throw new Exception("too many redirects");
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
