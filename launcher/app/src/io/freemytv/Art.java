package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Art mode (MaTV): public-domain paintings for the screensaver, from two museums' open APIs that need no key:
 * the Art Institute of Chicago (IIIF images, 1686 px wide) and the Cleveland Museum of Art (CC0, print size).
 * Only landscape paintings are kept (the screensaver fills a 16:9 screen; a portrait would lose most of itself).
 * Up to MAX paintings live in the app's cache (art/), with their caption in art.json. Every 12 hours, when the
 * screensaver starts, up to BATCH new ones come in one at a time and the oldest beyond MAX go. Nothing is fetched
 * unless the screensaver is set to Art mode.
 */
final class Art {
    private Art() {}

    static final int MAX = 60, BATCH = 12;
    private static final long EVERY = 12 * 3600_000L;
    private static final String UA = "MaTV/1.0 (TV screensaver; github.com/simbasjungles/free-my-tv fork)";
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile boolean busy;

    static boolean on() {
        try { return "art".equals(Prefs.saverMode()); } catch (RuntimeException e) { return false; }
    }

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), "art");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static File index(Context c) { return new File(dir(c), "art.json"); }

    /** The paintings on disk, newest first, as screensaver slides (a photo with a caption). */
    static synchronized List<Recs.Pick> picks(Context c) {
        List<Recs.Pick> out = new ArrayList<>();
        JSONArray items = read(c).optJSONArray("items");
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            File f = new File(dir(c), o.optString("file"));
            if (!f.exists()) continue;
            Recs.Pick p = new Recs.Pick();
            p.kind = "art";
            p.id = o.optString("key").hashCode();
            p.photo = f;
            p.art = true;
            p.title = o.optString("title");
            p.meta = join(o.optString("artist"), o.optString("date"));
            p.app = o.optString("museum");
            out.add(p);
        }
        // A different order each time the screensaver starts.
        java.util.Collections.shuffle(out);
        return out;
    }

    private static String join(String a, String b) {
        a = a == null ? "" : a.trim();
        b = b == null ? "" : b.trim();
        return a.isEmpty() ? b : b.isEmpty() ? a : a + " · " + b;
    }

    /** Fetches new paintings in the background when the cache is old or thin; `done` runs on the main thread after each one. */
    static void refreshIfStale(Context c, Runnable done) {
        if (!on() || busy) return;
        final Context app = c.getApplicationContext();
        AppLog.w("art: refresh");
        JSONObject idx = read(app);
        int have = idx.optJSONArray("items") == null ? 0 : idx.optJSONArray("items").length();
        boolean seeded = idx.optBoolean("seeded");
        if (seeded && have >= BATCH && System.currentTimeMillis() - idx.optLong("at") < EVERY) return;
        busy = true;
        worker.execute(() -> {
            try {
                if (!seeded) {
                    seed(app);
                    AppLog.w("art: seeded " + picks(app).size() + " paintings");
                    if (done != null) main.post(done);
                }
                if (read(app).optJSONArray("items") != null && System.currentTimeMillis() - read(app).optLong("at") < EVERY
                        && read(app).optJSONArray("items").length() >= BATCH) return;
                fetch(app, done);
            } catch (Throwable e) {   // never crash the screensaver's process over a museum's answer
                AppLog.w("art: " + e.getMessage());
            } finally {
                busy = false;
            }
        });
    }

    private static void fetch(Context c, Runnable done) throws Exception {
        Random rnd = new Random();
        List<JSONObject> found = new ArrayList<>();
        try { found.addAll(chicago(rnd)); } catch (Exception e) { AppLog.w("art: Chicago: " + e.getMessage()); }
        try { found.addAll(cleveland(rnd)); } catch (Exception e) { AppLog.w("art: Cleveland: " + e.getMessage()); }
        java.util.Collections.shuffle(found, rnd);
        int added = 0;
        for (JSONObject o : found) {
            if (added >= BATCH || !on()) break;
            if (known(c, o.optString("key"))) continue;
            File f = new File(dir(c), "a" + Integer.toHexString(o.optString("key").hashCode()) + ".jpg");
            try {
                download(o.optString("url"), f);
            } catch (Exception e) {
                AppLog.w("art: image: " + e.getMessage());
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                continue;
            }
            o.put("file", f.getName());
            o.remove("url");
            add(c, o);
            added++;
            if (done != null) main.post(done);
        }
        synchronized (Art.class) {
            JSONObject idx = read(c);
            idx.put("at", System.currentTimeMillis());
            write(c, idx);
        }
    }

    /**
     * The paintings that ship with the app (assets/art: public domain, from The Met's Open Access), copied once into
     * the cache so Art mode shows paintings from its very first start, before anything is downloaded. They go after
     * the downloaded ones, and leave like them once MAX is reached.
     */
    private static void seed(Context c) throws Exception {
        android.content.res.AssetManager am = c.getAssets();
        JSONObject bundled;
        try (InputStream in = am.open("art/art.json")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            bundled = new JSONObject(b.toString("UTF-8"));
        }
        JSONArray items = bundled.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject o = items.getJSONObject(i);
            File to = new File(dir(c), o.getString("file"));
            if (!to.exists()) {
                try (InputStream in = am.open("art/" + o.getString("file")); OutputStream out = new FileOutputStream(to)) {
                    byte[] buf = new byte[65536];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                }
            }
        }
        synchronized (Art.class) {
            JSONObject idx = read(c);
            JSONArray now = idx.optJSONArray("items");
            if (now == null) now = new JSONArray();
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject o = items.getJSONObject(i);
                boolean dup = false;
                for (int j = 0; j < now.length() && !dup; j++) dup = o.getString("key").equals(now.getJSONObject(j).optString("key"));
                if (!dup && now.length() < MAX) now.put(o);
            }
            idx.put("items", now);
            idx.put("seeded", true);
            write(c, idx);
        }
    }

    /** Art Institute of Chicago: public-domain paintings, a random page of the results. */
    private static List<JSONObject> chicago(Random rnd) throws Exception {
        String url = "https://api.artic.edu/api/v1/artworks/search?"
                + "query[bool][must][0][term][is_public_domain]=true"
                + "&query[bool][must][1][term][artwork_type_title.keyword]=Painting"
                + "&fields=id,title,artist_title,date_display,image_id,thumbnail"
                + "&limit=50&page=" + (1 + rnd.nextInt(40));
        JSONArray data = new JSONObject(text(url)).optJSONArray("data");
        List<JSONObject> out = new ArrayList<>();
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            JSONObject a = data.optJSONObject(i);
            if (a == null || a.optString("image_id").isEmpty() || a.isNull("image_id")) continue;
            JSONObject t = a.optJSONObject("thumbnail");
            if (t == null || !landscape(t.optDouble("width"), t.optDouble("height"))) continue;
            JSONObject o = new JSONObject();
            o.put("key", "aic:" + a.optInt("id"));
            o.put("title", a.optString("title"));
            o.put("artist", a.isNull("artist_title") ? "" : a.optString("artist_title"));
            o.put("date", a.optString("date_display"));
            o.put("museum", "Art Institute of Chicago");
            o.put("url", "https://www.artic.edu/iiif/2/" + a.optString("image_id") + "/full/1686,/0/default.jpg");
            out.add(o);
        }
        return out;
    }

    /** Cleveland Museum of Art: CC0 paintings with an image, a random slice. */
    private static List<JSONObject> cleveland(Random rnd) throws Exception {
        String url = "https://openaccess-api.clevelandart.org/api/artworks/?type=Painting&cc0=1&has_image=1"
                + "&fields=id,title,creators,creation_date,images&limit=50&skip=" + rnd.nextInt(2500);
        JSONArray data = new JSONObject(text(url)).optJSONArray("data");
        List<JSONObject> out = new ArrayList<>();
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            JSONObject a = data.optJSONObject(i);
            JSONObject images = a == null ? null : a.optJSONObject("images");
            JSONObject img = images == null ? null : images.optJSONObject("print");
            if (img == null) img = images == null ? null : images.optJSONObject("web");
            if (img == null || img.optString("url").isEmpty()) continue;
            if (!landscape(img.optDouble("width"), img.optDouble("height"))) continue;
            String artist = "";
            JSONArray cr = a.optJSONArray("creators");
            if (cr != null && cr.length() > 0) artist = cr.optJSONObject(0).optString("description").replaceAll("\\s*\\(.*$", "");
            JSONObject o = new JSONObject();
            o.put("key", "cma:" + a.optInt("id"));
            o.put("title", a.optString("title"));
            o.put("artist", artist);
            o.put("date", a.optString("creation_date"));
            o.put("museum", "Cleveland Museum of Art");
            o.put("url", img.optString("url"));
            out.add(o);
        }
        return out;
    }

    private static boolean landscape(double w, double h) {
        return w > 0 && h > 0 && w / h >= 1.25 && w / h <= 2.4;
    }

    private static HttpURLConnection open(String url) throws Exception {
        if (!url.startsWith("https://")) throw new java.io.IOException("not https");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("AIC-User-Agent", UA);
        if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode() + " for " + new URL(url).getHost());
        return c;
    }

    private static String text(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) {
                b.write(buf, 0, n);
                if (b.size() > 4_000_000) throw new java.io.IOException("answer too large");
            }
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File to) throws Exception {
        HttpURLConnection c = open(url);
        File part = new File(to.getPath() + ".part");
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[65536];
            long total = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                total += n;
                if (total > 25_000_000) throw new java.io.IOException("image too large");
            }
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(to)) throw new java.io.IOException("couldn't save");
    }

    // MARK: - The index (art.json)

    private static synchronized JSONObject read(Context c) {
        File f = index(c);
        if (!f.exists()) return new JSONObject();
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return new JSONObject(b.toString("UTF-8"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static synchronized void write(Context c, JSONObject o) {
        File f = index(c), tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes("UTF-8"));
        } catch (Exception e) {
            AppLog.w("art: index: " + e.getMessage());
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }

    private static synchronized boolean known(Context c, String key) {
        JSONArray items = read(c).optJSONArray("items");
        if (items == null) return false;
        for (int i = 0; i < items.length(); i++) if (key.equals(items.optJSONObject(i).optString("key"))) return true;
        return false;
    }

    /** Newest first; beyond MAX the oldest paintings and their files go. */
    private static synchronized void add(Context c, JSONObject item) throws Exception {
        JSONObject idx = read(c);
        JSONArray old = idx.optJSONArray("items");
        JSONArray items = new JSONArray();
        items.put(item);
        for (int i = 0; old != null && i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (items.length() < MAX) items.put(o);
            else //noinspection ResultOfMethodCallIgnored
                new File(dir(c), o.optString("file")).delete();
        }
        idx.put("items", items);
        write(c, idx);
    }
}
