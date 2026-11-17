package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MaTV's wallpaper library: the person's own wallpapers for the screensaver, in collections (Art, Paysages, and any
 * they make from their phone). They live in the app's files (wallpapers/), never the cache, which the system may
 * clear: each one cut to the screen's shape at OUT_W × OUT_H, with a small copy for the phone's grid (thumbs/), and
 * listed in wallpapers.json. The set bundled with the app (assets/art) comes in once. The phone adds, moves and
 * removes them through Companion; the screensaver (another process on Android 11 to 12L) only reads the index, which
 * is always replaced whole (written aside, then renamed).
 */
final class Art {
    private Art() {}

    static final int MAX = 200;
    /** Every wallpaper is stored cut to 16:9 at this size: sharp on a 4K panel's 1080p or 1440p UI. */
    static final int OUT_W = 2560, OUT_H = 1440, TH_W = 640, TH_H = 360;
    /** The bundled set's version: a newer one adds what's new, without touching what the person changed. */
    private static final int SEED_VERSION = 6;
    private static final String UA = "MaTV/2.5 (TV wallpapers; https://github.com/Torkor29/MaTVXiao)";
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile boolean seeding;
    private static final Object CUT_LOCK = new Object();
    /** Shown first the next time the screensaver starts (the phone's "Show on the TV"). */
    static volatile String showFirst;
    /** Bumped on every change: the screensaver reads the list again. */
    static volatile int version;

    static boolean on() {
        try { return "art".equals(Prefs.saverMode()); } catch (RuntimeException e) { return false; }
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "wallpapers");
        //noinspection ResultOfMethodCallIgnored
        new File(d, "thumbs").mkdirs();
        return d;
    }

    private static File index(Context c) { return new File(dir(c), "wallpapers.json"); }

    // MARK: - Reading

    /** The library as it is on disk: {collections: [{id, name}], items: [...]}, newest first. */
    static synchronized JSONObject library(Context c) {
        JSONObject idx = read(c);
        try {
            if (idx.optJSONArray("collections") == null) idx.put("collections", defaultCollections());
            if (idx.optJSONArray("items") == null) idx.put("items", new JSONArray());
        } catch (JSONException ignored) {
        }
        return idx;
    }

    static List<String[]> collections(Context c) {
        List<String[]> out = new ArrayList<>();
        JSONArray cols = library(c).optJSONArray("collections");
        for (int i = 0; cols != null && i < cols.length(); i++) {
            JSONObject o = cols.optJSONObject(i);
            if (o != null) out.add(new String[] {o.optString("id"), o.optString("name")});
        }
        return out;
    }

    /** The collection the screensaver shows: "all", or one that still exists. */
    static String activeCollection(Context c) {
        String want = Prefs.saverCategory();
        if (want.equals("all")) return want;
        for (String[] col : collections(c)) if (col[0].equals(want)) return want;
        return "all";
    }

    /** The wallpapers in the screensaver's rotation, as slides (a photo with a caption), in a new order each time. */
    static List<Recs.Pick> picks(Context c) {
        ensureSeeded(c);
        List<Recs.Pick> out = new ArrayList<>();
        JSONArray items = library(c).optJSONArray("items");
        String want = activeCollection(c), first = showFirst;
        Recs.Pick firstPick = null;
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id");
            boolean chosen = id.equals(first);
            if (!chosen && (!o.optBoolean("enabled", true) || !want.equals("all") && !want.equals(o.optString("collection")))) continue;
            File f = new File(dir(c), o.optString("file"));
            if (!f.exists()) continue;
            Recs.Pick p = new Recs.Pick();
            p.kind = "art";
            p.id = id.hashCode();
            p.photo = f;
            p.art = true;
            p.title = o.optString("title");
            p.meta = o.optString("credit");
            p.app = o.optString("place");
            if (chosen) firstPick = p; else out.add(p);
        }
        Collections.shuffle(out);
        if (firstPick != null) {
            out.add(0, firstPick);
            showFirst = null;
        }
        return out;
    }

    static File image(Context c, String id, boolean thumb) {
        JSONObject o = find(library(c), id);
        if (o == null) return null;
        File full = new File(dir(c), o.optString("file"));
        if (!thumb) return full.exists() ? full : null;
        File th = new File(new File(dir(c), "thumbs"), o.optString("file"));
        if (!th.exists() && full.exists()) thumb(full, th);
        return th.exists() ? th : full.exists() ? full : null;
    }

    /** A small copy of one wallpaper the screensaver shows (for a blurred backdrop), or null. */
    static File randomThumb(Context c) {
        JSONArray items = library(c).optJSONArray("items");
        String want = activeCollection(c);
        List<String> ids = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o != null && o.optBoolean("enabled", true) && (want.equals("all") || want.equals(o.optString("collection")))) ids.add(o.optString("id"));
        }
        if (ids.isEmpty()) return null;
        return image(c, ids.get(new java.util.Random().nextInt(ids.size())), true);
    }

    // MARK: - The bundled set

    /** Brings the bundled wallpapers in once (and new ones from a newer version), in the background. */
    static void refreshIfStale(Context c, Runnable done) {
        if (seeding || library(c).optInt("seedVersion") == SEED_VERSION) return;
        seeding = true;
        final Context app = c.getApplicationContext();
        worker.execute(() -> {
            try {
                seed(app);
                if (done != null) main.post(done);
            } catch (Throwable e) {   // never crash the screensaver's process over a file
                AppLog.w("wallpapers: seed: " + e);
            } finally {
                seeding = false;
            }
        });
    }

    /** On the caller's thread: the phone's first look must already show the bundled set. */
    static void ensureSeeded(Context c) {
        if (library(c).optInt("seedVersion") == SEED_VERSION) return;
        synchronized (Art.class) {
            if (library(c).optInt("seedVersion") == SEED_VERSION) return;
            try {
                seed(c);
            } catch (Exception e) {
                AppLog.w("wallpapers: seed: " + e);
            }
        }
    }

    private static synchronized void seed(Context c) throws Exception {
        if (library(c).optInt("seedVersion") == SEED_VERSION) return;
        deleteTree(new File(c.getCacheDir(), "art"));   // MaTV 2.0 to 2.4 kept them in the cache
        android.content.res.AssetManager am = c.getAssets();
        JSONObject bundled;
        try (InputStream in = am.open("art/art.json")) {
            bundled = new JSONObject(readAll(in, 1_000_000));
        }
        JSONObject idx = library(c);
        JSONArray items = idx.getJSONArray("items");
        JSONArray seeds = bundled.optJSONArray("items");
        JSONArray known = idx.optJSONArray("seeded");
        if (known == null) known = new JSONArray();
        for (int i = 0; seeds != null && i < seeds.length(); i++) {
            JSONObject s = seeds.getJSONObject(i);
            String key = s.getString("key");
            boolean done = false;
            for (int j = 0; j < known.length() && !done; j++) done = key.equals(known.optString(j));
            if (done) continue;   // brought in before (and maybe removed since: it stays removed)
            known.put(key);
            String id = "s" + Integer.toHexString(key.hashCode());
            File to = new File(dir(c), id + ".jpg");
            try (InputStream in = am.open("art/" + s.getString("file")); OutputStream out = new FileOutputStream(to)) {
                copy(in, out);
            }
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("file", to.getName());
            o.put("title", s.optString("title"));
            o.put("credit", s.optString("artist"));
            o.put("collection", s.optString("category", "art"));
            o.put("source", "bundled");
            o.put("enabled", true);
            o.put("added", System.currentTimeMillis() - i);
            items.put(o);
        }
        idx.put("items", items);
        idx.put("seeded", known);
        idx.put("seedVersion", SEED_VERSION);
        write(c, idx);
        version++;
    }

    private static JSONArray defaultCollections() {
        JSONArray a = new JSONArray();
        try {
            a.put(new JSONObject().put("id", "art").put("name", "Art"));
            a.put(new JSONObject().put("id", "landscape").put("name", "Paysages"));
        } catch (JSONException ignored) {
        }
        return a;
    }

    // MARK: - Changes (from the phone)

    /** A new wallpaper from an image file (the phone's upload, or a download): cut to 16:9, then listed. */
    static JSONObject add(Context c, File raw, JSONObject meta) throws IOException {
        ensureSeeded(c);
        if (count(c) >= MAX) throw new IOException("La bibliothèque est pleine (" + MAX + " fonds) : supprimes-en d'abord.");
        String id = newId();
        File to = new File(dir(c), id + ".jpg");
        float focusX = (float) meta.optDouble("fx", 0.5), focusY = (float) meta.optDouble("fy", 0.5);
        synchronized (CUT_LOCK) {   // one large image in memory at a time: TVs have little RAM
            if (!cut(raw, to, focusX, focusY)) {
                //noinspection ResultOfMethodCallIgnored
                to.delete();
                throw new IOException("Cette image n'a pas pu être lue (format, ou trop petite : au moins 1280 × 720).");
            }
            thumb(to, new File(new File(dir(c), "thumbs"), to.getName()));
        }
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("file", to.getName());
            o.put("title", trim(meta.optString("title"), 90));
            o.put("credit", trim(meta.optString("credit"), 120));
            o.put("license", trim(meta.optString("license"), 40));
            o.put("link", trim(meta.optString("link"), 300));
            o.put("source", trim(meta.optString("source", "phone"), 20));
            o.put("remote", trim(meta.optString("remote"), 40));
            o.put("collection", collectionOr(c, meta.optString("collection"), "landscape"));
            o.put("enabled", true);
            o.put("added", System.currentTimeMillis());
            synchronized (Art.class) {
                JSONObject idx = library(c);
                JSONArray items = idx.getJSONArray("items"), now = new JSONArray();
                now.put(o);
                for (int i = 0; i < items.length(); i++) now.put(items.get(i));
                idx.put("items", now);
                write(c, idx);
            }
            version++;
            return o;
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    /** Downloads an image (https only, at most 40 MB) and adds it. */
    static JSONObject addFromUrl(Context c, String url, JSONObject meta) throws IOException {
        String remote = meta.optString("remote");
        if (!remote.isEmpty() && hasRemote(c, remote)) throw new IOException("Ce fond est déjà sur la TV.");
        File raw = new File(c.getCacheDir(), "wallpaper-" + newId() + ".tmp");
        try {
            download(url, raw);
            return add(c, raw, meta);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            raw.delete();
        }
    }

    static synchronized boolean hasRemote(Context c, String remote) {
        JSONArray items = library(c).optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            if (remote.equals(items.optJSONObject(i).optString("remote"))) return true;
        }
        return false;
    }

    /** Changes the given wallpapers: any of collection, enabled, title. */
    static synchronized int update(Context c, JSONArray ids, JSONObject change) throws JSONException {
        JSONObject idx = library(c);
        JSONArray items = idx.getJSONArray("items");
        int n = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.getJSONObject(i);
            if (!contains(ids, o.optString("id"))) continue;
            if (change.has("collection")) o.put("collection", collectionOr(c, change.optString("collection"), o.optString("collection")));
            if (change.has("enabled")) o.put("enabled", change.optBoolean("enabled"));
            if (change.has("title")) o.put("title", trim(change.optString("title"), 90));
            n++;
        }
        write(c, idx);
        version++;
        return n;
    }

    static synchronized int delete(Context c, JSONArray ids) throws JSONException {
        JSONObject idx = library(c);
        JSONArray items = idx.getJSONArray("items"), keep = new JSONArray();
        int n = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.getJSONObject(i);
            if (contains(ids, o.optString("id"))) {
                //noinspection ResultOfMethodCallIgnored
                new File(dir(c), o.optString("file")).delete();
                //noinspection ResultOfMethodCallIgnored
                new File(new File(dir(c), "thumbs"), o.optString("file")).delete();
                n++;
            } else {
                keep.put(o);
            }
        }
        idx.put("items", keep);
        write(c, idx);
        version++;
        return n;
    }

    /** Creates a collection; returns its id. */
    static synchronized String createCollection(Context c, String name) throws JSONException, IOException {
        name = trim(name, 30);
        if (name.isEmpty()) throw new IOException("Donne un nom à la collection.");
        JSONObject idx = library(c);
        JSONArray cols = idx.getJSONArray("collections");
        if (cols.length() >= 20) throw new IOException("20 collections au plus.");
        String id = "c" + newId().substring(0, 6);
        cols.put(new JSONObject().put("id", id).put("name", name));
        write(c, idx);
        version++;
        return id;
    }

    static synchronized void renameCollection(Context c, String id, String name) throws JSONException, IOException {
        name = trim(name, 30);
        if (name.isEmpty()) throw new IOException("Donne un nom à la collection.");
        JSONObject idx = library(c);
        JSONArray cols = idx.getJSONArray("collections");
        for (int i = 0; i < cols.length(); i++) if (cols.getJSONObject(i).optString("id").equals(id)) cols.getJSONObject(i).put("name", name);
        write(c, idx);
        version++;
    }

    /** Removes a collection: its wallpapers go too when `withItems`, else they move to the first other collection. */
    static synchronized void deleteCollection(Context c, String id, boolean withItems) throws JSONException, IOException {
        JSONObject idx = library(c);
        JSONArray cols = idx.getJSONArray("collections"), keep = new JSONArray();
        for (int i = 0; i < cols.length(); i++) if (!cols.getJSONObject(i).optString("id").equals(id)) keep.put(cols.get(i));
        if (keep.length() == 0) throw new IOException("Il faut garder au moins une collection.");
        if (keep.length() == cols.length()) return;
        String to = keep.getJSONObject(0).optString("id");
        JSONArray items = idx.getJSONArray("items"), left = new JSONArray(), gone = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.getJSONObject(i);
            if (!o.optString("collection").equals(id)) left.put(o);
            else if (withItems) gone.put(o.optString("id"));
            else { o.put("collection", to); left.put(o); }
        }
        idx.put("collections", keep);
        idx.put("items", left);
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.getJSONObject(i);
            if (contains(gone, o.optString("id"))) {
                //noinspection ResultOfMethodCallIgnored
                new File(dir(c), o.optString("file")).delete();
                //noinspection ResultOfMethodCallIgnored
                new File(new File(dir(c), "thumbs"), o.optString("file")).delete();
            }
        }
        write(c, idx);
        if (Prefs.saverCategory().equals(id)) Prefs.setSaverCategory("all");
        version++;
    }

    private static String collectionOr(Context c, String want, String fallback) {
        for (String[] col : collections(c)) if (col[0].equals(want)) return want;
        for (String[] col : collections(c)) if (col[0].equals(fallback)) return fallback;
        List<String[]> all = collections(c);
        return all.isEmpty() ? "landscape" : all.get(0)[0];
    }

    static int count(Context c) {
        JSONArray items = library(c).optJSONArray("items");
        return items == null ? 0 : items.length();
    }

    private static JSONObject find(JSONObject idx, String id) {
        JSONArray items = idx.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o != null && o.optString("id").equals(id)) return o;
        }
        return null;
    }

    private static boolean contains(JSONArray a, String s) {
        for (int i = 0; a != null && i < a.length(); i++) if (s.equals(a.optString(i))) return true;
        return false;
    }

    private static String newId() {
        byte[] b = new byte[6];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder("w");
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }

    private static String trim(String s, int max) {
        s = s == null ? "" : s.replaceAll("[\\p{Cntrl}]", " ").trim();
        return s.length() <= max ? s : s.substring(0, max);
    }

    // MARK: - Images

    /** Cuts `original` to 16:9 around the focus point (0..1) and saves it at OUT_W × OUT_H (or smaller, never upscaled past 2×). */
    private static boolean cut(File original, File to, float fx, float fy) {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(original.getPath(), b);
        if (b.outWidth < 1280 && b.outHeight < 720 || b.outWidth < 640 || b.outHeight < 360) return false;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 1;
        while (b.outWidth / (o.inSampleSize * 2) >= OUT_W && b.outHeight / (o.inSampleSize * 2) >= OUT_H) o.inSampleSize *= 2;
        while ((long) (b.outWidth / o.inSampleSize) * (b.outHeight / o.inSampleSize) > 24_000_000L) o.inSampleSize *= 2;
        Bitmap src;
        try {
            src = BitmapFactory.decodeFile(original.getPath(), o);
        } catch (OutOfMemoryError e) {
            return false;
        }
        if (src == null) return false;
        Bitmap out = null;
        try {
            float r = (float) OUT_W / OUT_H;
            int cw = Math.min(src.getWidth(), Math.round(src.getHeight() * r)), ch = Math.round(cw / r);
            int left = Math.round(Math.max(0, Math.min(src.getWidth() - cw, fx * src.getWidth() - cw / 2f)));
            int top = Math.round(Math.max(0, Math.min(src.getHeight() - ch, fy * src.getHeight() - ch / 2f)));
            int ow = Math.min(OUT_W, cw * 2), oh = Math.round(ow / r);
            out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
            new Canvas(out).drawBitmap(src, new Rect(left, top, left + cw, top + ch), new Rect(0, 0, ow, oh),
                    new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
            File part = new File(to.getPath() + ".part");
            try (OutputStream os = new FileOutputStream(part)) {
                out.compress(Bitmap.CompressFormat.JPEG, 90, os);
            }
            return part.renameTo(to);
        } catch (Exception | OutOfMemoryError e) {
            AppLog.w("wallpapers: cut: " + e.getMessage());
            return false;
        } finally {
            src.recycle();
            if (out != null) out.recycle();
        }
    }

    private static void thumb(File full, File th) {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(full.getPath(), b);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 1;
        while (b.outWidth / (o.inSampleSize * 2) >= TH_W) o.inSampleSize *= 2;
        Bitmap src = null, out = null;
        try {
            src = BitmapFactory.decodeFile(full.getPath(), o);
            if (src == null) return;
            out = Bitmap.createScaledBitmap(src, TH_W, Math.round(TH_W * src.getHeight() / (float) src.getWidth()), true);
            try (OutputStream os = new FileOutputStream(th)) {
                out.compress(Bitmap.CompressFormat.JPEG, 82, os);
            }
        } catch (Exception | OutOfMemoryError e) {
            AppLog.w("wallpapers: thumb: " + e.getMessage());
        } finally {
            if (src != null) src.recycle();
            if (out != null && out != src) out.recycle();
        }
    }

    private static void download(String url, File to) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("Adresse refusée (https seulement).");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(40_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Le site de l'image a répondu " + code + ".");
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[65536];
                long total = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    total += n;
                    if (total > 40_000_000) throw new IOException("Image trop lourde (40 Mo au plus).");
                }
            }
        } finally {
            c.disconnect();
        }
    }

    // MARK: - The index (wallpapers.json)

    private static synchronized JSONObject read(Context c) {
        File f = index(c);
        if (!f.exists()) return new JSONObject();
        try (InputStream in = new FileInputStream(f)) {
            return new JSONObject(readAll(in, 4_000_000));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static synchronized void write(Context c, JSONObject o) {
        File f = index(c), tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes("UTF-8"));
        } catch (IOException e) {
            AppLog.w("wallpapers: index: " + e.getMessage());
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }

    static String readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) {
            b.write(buf, 0, n);
            if (b.size() > max) throw new IOException("too large");
        }
        return b.toString("UTF-8");
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
