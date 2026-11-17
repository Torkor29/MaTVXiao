package io.matv.installer;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/** What to install, fetched from GitHub: MaTV's latest release (two signed APKs) and TizenTube's. */
final class Net {
    static final String REPO = "Torkor29/MaTVXiao";
    static final String TIZENTUBE = "reisxd/TizenTubeCobalt";

    /** One thing to put on the TV. */
    static final class Item {
        final String key, title, about, pkg;
        String version = "", url = "", installed = "";
        long size;
        /** SHA-256 of the signing certificate the APK must carry (null: not checked, a third-party app). */
        final String pin;
        boolean wanted, done, failed;
        String status = "";
        File file;
        Item(String key, String title, String about, String pkg, String pin) {
            this.key = key; this.title = title; this.about = about; this.pkg = pkg; this.pin = pin;
        }
    }

    static final String HOME_PIN = "517bb5aee53452b043aa056467fc9d6e11c631c363b9452f5199535eff61f57e";
    static final String LIVE_PIN = "a8af55f531d524d1f9ec214dde7ac8097f9b56f0b19a38b87dbb1eb1f0be756f";

    static JSONObject latest(String repo) throws IOException {
        try {
            return new JSONObject(new String(get("https://api.github.com/repos/" + repo + "/releases/latest"), "UTF-8"));
        } catch (org.json.JSONException e) {
            throw new IOException("Réponse GitHub illisible.");
        }
    }

    /** Fills in the version and download address of MaTV's two apps from the latest release. */
    static void fillMaTV(JSONObject release, Item home, Item live) throws IOException {
        String v = release.optString("tag_name").replaceFirst("^v", "");
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) throw new IOException("Version MaTV introuvable.");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String name = a.optString("name");
            Item it = name.equals("MaTV-Home.apk") ? home : name.equals("MaTV-Live.apk") ? live : null;
            if (it == null) continue;
            it.version = v;
            it.url = a.optString("browser_download_url");
            it.size = a.optLong("size");
        }
        if (home.url.isEmpty() || live.url.isEmpty()) throw new IOException("La dernière version de MaTV est incomplète.");
    }

    /** TizenTube: the APK made for the TV's processor (from its ABI list), else a universal one. */
    static void fillTizenTube(JSONObject release, String abis, Item tt) {
        tt.version = release.optString("tag_name");
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return;
        JSONObject pick = null;
        for (String abi : abis.split(",")) {
            String want = abi.contains("arm64") ? "arm64" : abi.startsWith("armeabi") ? "arm" : abi.contains("x86_64") ? "x86_64" : abi.trim();
            if (want.isEmpty()) continue;
            for (int i = 0; i < assets.length() && pick == null; i++) {
                JSONObject a = assets.optJSONObject(i);
                String n = a == null ? "" : a.optString("name").toLowerCase();
                if (n.endsWith(".apk") && n.contains(want)) pick = a;
            }
            if (pick != null) break;
        }
        for (int i = 0; i < assets.length() && pick == null; i++) {
            JSONObject a = assets.optJSONObject(i);
            String n = a == null ? "" : a.optString("name").toLowerCase();
            if (n.endsWith(".apk") && (n.contains("universal") || n.contains("release"))) pick = a;
        }
        for (int i = 0; i < assets.length() && pick == null; i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a != null && a.optString("name").toLowerCase().endsWith(".apk")) pick = a;
        }
        if (pick != null) {
            tt.url = pick.optString("browser_download_url");
            tt.size = pick.optLong("size");
        }
    }

    interface Progress { void at(long done, long total); }

    static void download(String url, File to, long expected, Progress progress, AtomicBoolean cancel) throws IOException {
        HttpURLConnection c = connection(url);
        try {
            if (c.getResponseCode() != 200) throw new IOException("Téléchargement refusé (" + c.getResponseCode() + ").");
            long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : expected;
            File tmp = new File(to.getPath() + ".part");
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] b = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(b)) > 0) {
                    if (cancel.get()) throw new IOException("Annulé.");
                    out.write(b, 0, n);
                    done += n;
                    progress.at(done, total);
                }
                if (total > 0 && done != total) throw new IOException("Téléchargement incomplet.");
            }
            if (!tmp.renameTo(to)) throw new IOException("Impossible d'enregistrer le fichier.");
        } finally {
            c.disconnect();
        }
    }

    /** Throws unless the APK is the expected app, signed with the expected key (a changed file never reaches the TV). */
    static void check(Context c, File apk, Item it) throws IOException {
        PackageManager pm = c.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), flags);
        if (info == null) throw new IOException("Fichier " + it.title + " illisible.");
        if (it.pkg != null && !it.pkg.equals(info.packageName)) throw new IOException("Ce n'est pas " + it.title + ".");
        if (it.pin == null) return;
        Signature[] sigs;
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            sigs = info.signingInfo.hasMultipleSigners() ? info.signingInfo.getApkContentsSigners() : info.signingInfo.getSigningCertificateHistory();
        } else {
            sigs = info.signatures;
        }
        if (sigs != null) {
            for (Signature s : sigs) if (it.pin.equals(sha256(s.toByteArray()))) return;
        }
        throw new IOException(it.title + " n'est pas signé avec la clé de MaTV : installation annulée.");
    }

    static String sha256(byte[] b) {
        try {
            StringBuilder s = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) s.append(String.format("%02x", x));
            return s.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Compares "2.8.0" and "2.10.1" number by number. */
    static int compare(String a, String b) {
        String[] x = a.replaceAll("[^0-9.]", "").split("\\."), y = b.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection c = connection(url);
        try {
            int code = c.getResponseCode();
            if (code == 403) throw new IOException("GitHub limite les requêtes : réessaie dans quelques minutes.");
            if (code != 200) throw new IOException("GitHub ne répond pas (" + code + ").");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[16 * 1024];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                return out.toByteArray();
            }
        } catch (java.net.UnknownHostException e) {
            throw new IOException("Pas d'accès à Internet depuis le téléphone.");
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection connection(String url) throws IOException {
        // GitHub's download links redirect to another https host; HttpURLConnection follows https -> https.
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "MaTV-Installer");
        c.setRequestProperty("Accept", url.contains("api.github.com") ? "application/vnd.github+json" : "*/*");
        return c;
    }
}
