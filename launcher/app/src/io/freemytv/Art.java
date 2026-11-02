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
 * The Met (originals) and the Cleveland Museum of Art (CC0, print size); only wide works, cut to 16:9 on the TV.
 * Only landscape paintings are kept (the screensaver fills a 16:9 screen; a portrait would lose most of itself).
 * Up to MAX paintings live in the app's cache (art/), with their caption in art.json. Every 12 hours, when the
 * screensaver starts, up to BATCH new ones come in one at a time and the oldest beyond MAX go. Nothing is fetched
 * unless the screensaver is set to Art mode.
 */
final class Art {
    private Art() {}

    static final int MAX = 100, BATCH = 12;
    /** The bundled set's version: a new one replaces what's in the cache (MaTV 2: 50 wide works cut for 16:9). */
    private static final int SEED_VERSION = 2;
    /** Every painting is stored cut to the screen's shape at this size (what the screensaver draws). */
    private static final int OUT_W = 2112, OUT_H = 1188;
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
        boolean seeded = idx.optInt("seedVersion") == SEED_VERSION;
        if (seeded && have >= BATCH && System.currentTimeMillis() - idx.optLong("at") < EVERY) return;
        busy = true;
        worker.execute(() -> {
            try {
                if (!seeded) {
                    synchronized (Art.class) {   // an older set: start over with the new one
                        File[] old = dir(app).listFiles();
                        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                            f.delete();
                    }
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
        try { found.addAll(met(rnd)); } catch (Exception e) { AppLog.w("art: Met: " + e.getMessage()); }
        try { found.addAll(cleveland(rnd)); } catch (Exception e) { AppLog.w("art: Cleveland: " + e.getMessage()); }
        java.util.Collections.shuffle(found, rnd);
        int added = 0;
        for (JSONObject o : found) {
            if (added >= BATCH || !on()) break;
            if (known(c, o.optString("key"))) continue;
            File f = new File(dir(c), "a" + Integer.toHexString(o.optString("key").hashCode()) + ".jpg");
            File raw = new File(dir(c), "download.tmp");
            try {
                download(o.optString("url"), raw);
                if (!cut(raw, f)) continue;   // too narrow or too small once measured
            } catch (Exception e) {
                AppLog.w("art: image: " + e.getMessage());
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                continue;
            } finally {
                //noinspection ResultOfMethodCallIgnored
                raw.delete();
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
            idx.put("seedVersion", SEED_VERSION);
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

    /** Wide enough that cutting it to 16:9 keeps most of the work, and sharp enough for the TV. */
    private static boolean landscape(double w, double h) {
        return w >= 2000 && h > 0 && w / h >= 1.45 && w / h <= 2.3;
    }

    private static final String[] MET_TERMS = {"landscape", "sea", "river", "mountains", "sunset", "lake", "coast",
            "harbor", "valley", "view", "bay", "winter", "forest", "Venice", "clouds"};

    /** The Met's Open Access: public-domain paintings of European and American painters (originals, very large). */
    private static List<JSONObject> met(Random rnd) throws Exception {
        String term = MET_TERMS[rnd.nextInt(MET_TERMS.length)];
        int dep = rnd.nextBoolean() ? 11 : 1;
        JSONArray ids = new JSONObject(text("https://collectionapi.metmuseum.org/public/collection/v1.1/search?departmentId="
                + dep + "&hasImages=true&q=" + term + "&limit=80")).optJSONArray("objectIDs");
        List<JSONObject> out = new ArrayList<>();
        if (ids == null) return out;
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < ids.length(); i++) all.add(ids.optInt(i));
        java.util.Collections.shuffle(all, rnd);
        for (int i = 0; i < all.size() && out.size() < 24; i++) {
            JSONObject a;
            try {
                a = new JSONObject(text("https://collectionapi.metmuseum.org/public/collection/v1/objects/" + all.get(i)));
            } catch (Exception e) {
                continue;
            }
            if (!a.optBoolean("isPublicDomain") || a.optString("primaryImage").isEmpty()) continue;
            if (!"Paintings".equals(a.optString("classification"))) continue;
            JSONObject o = new JSONObject();
            o.put("key", "met:" + a.optInt("objectID"));
            o.put("title", a.optString("title"));
            o.put("artist", a.optString("artistDisplayName"));
            o.put("date", a.optString("objectDate"));
            o.put("museum", "The Metropolitan Museum of Art");
            o.put("url", a.optString("primaryImage"));
            out.add(o);
        }
        return out;
    }

    /**
     * The downloaded original, checked (wide and large enough) and cut to the screen's shape at OUT_W × OUT_H, saved
     * as `to`. The original goes: a museum's file can weigh 20 MB, the cut about 500 KB.
     */
    private static boolean cut(File original, File to) {
        android.graphics.BitmapFactory.Options b = new android.graphics.BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(original.getPath(), b);
        if (!landscape(b.outWidth, b.outHeight)) return false;
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        while (b.outWidth / (o.inSampleSize * 2) >= OUT_W && b.outHeight / (o.inSampleSize * 2) >= OUT_H) o.inSampleSize *= 2;
        android.graphics.Bitmap src = android.graphics.BitmapFactory.decodeFile(original.getPath(), o);
        if (src == null) return false;
        try {
            float r = (float) OUT_W / OUT_H;
            int iw = Math.round(src.getWidth() * 0.97f), ih = Math.round(src.getHeight() * 0.97f);   // past the edges' scan marks
            int cw = Math.min(iw, Math.round(ih * r)), ch = Math.round(cw / r);
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(OUT_W, OUT_H, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(out);
            int left = (src.getWidth() - cw) / 2, top = (src.getHeight() - ch) / 2;
            c.drawBitmap(src, new android.graphics.Rect(left, top, left + cw, top + ch), new android.graphics.Rect(0, 0, OUT_W, OUT_H),
                    new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.DITHER_FLAG));
            try (OutputStream os = new FileOutputStream(to)) {
                out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, os);
            }
            out.recycle();
            return true;
        } catch (Exception | OutOfMemoryError e) {
            AppLog.w("art: cut: " + e.getMessage());
            return false;
        } finally {
            src.recycle();
        }
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
                if (total > 60_000_000) throw new java.io.IOException("image too large");
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
