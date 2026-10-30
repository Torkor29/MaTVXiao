package io.matv.iptv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Xtream Codes API (player_api.php): sign-in, categories and streams for live TV, films and series, the short EPG,
 * and the URLs the player opens. Lists are cached on disk for 6 hours so the app opens instantly.
 * The account (server, user, password) stays in the app's private preferences, never in a log.
 */
final class Xtream {
    static final String LIVE = "live", VOD = "vod", SERIES = "series";
    private static final long CACHE_MS = 6 * 3600_000L;

    static final class Item {
        String id, name, icon, ext = "", plot = "", rating = "", kind;
        String category;
        int num;
    }

    static final class Category {
        String id, name;
        Category(String id, String name) { this.id = id; this.name = name; }
    }

    private final Context ctx;
    private final SharedPreferences sp;
    String server = "", user = "", pass = "";

    Xtream(Context c) {
        ctx = c.getApplicationContext();
        sp = ctx.getSharedPreferences("account", Context.MODE_PRIVATE);
        server = sp.getString("server", "");
        user = sp.getString("user", "");
        pass = sp.getString("pass", "");
    }

    boolean signedIn() { return !server.isEmpty() && !user.isEmpty(); }

    /** "host:port" or a full URL; http:// is added when no scheme is given, and the trailing slash or path goes. */
    static String normalize(String s) {
        s = s.trim();
        if (s.isEmpty()) return s;
        if (!s.matches("(?i)^https?://.*")) s = "http://" + s;
        s = s.replaceAll("/+$", "");
        s = s.replaceAll("(?i)/(player_api|get)\\.php.*$", "");
        return s;
    }

    /** Checks the account with the server; saves it when the server says it's active. Returns an error or null. */
    String signIn(String server, String user, String pass) {
        String srv = normalize(server);
        try {
            JSONObject o = new JSONObject(get(srv + "/player_api.php?username=" + enc(user) + "&password=" + enc(pass)));
            JSONObject info = o.optJSONObject("user_info");
            if (info == null || info.optInt("auth", 0) != 1) return "Identifiants refusés par le serveur.";
            String status = info.optString("status", "Active");
            if (!status.equalsIgnoreCase("Active")) return "Compte " + status.toLowerCase() + ".";
            this.server = srv;
            this.user = user;
            this.pass = pass;
            sp.edit().putString("server", srv).putString("user", user).putString("pass", pass)
                    .putString("expires", info.optString("exp_date", "")).apply();
            clearCache();
            return null;
        } catch (Exception e) {
            return "Serveur injoignable (" + e.getClass().getSimpleName() + ").";
        }
    }

    void signOut() {
        sp.edit().clear().apply();
        server = user = pass = "";
        clearCache();
    }

    /** The subscription's end, as a date, or "" when the server gives none. */
    String expires() {
        String e = sp.getString("expires", "");
        try {
            long s = Long.parseLong(e);
            return java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG, java.util.Locale.FRANCE).format(new java.util.Date(s * 1000));
        } catch (NumberFormatException ex) {
            return "";
        }
    }

    // MARK: - Lists

    List<Category> categories(String kind) throws Exception {
        String action = kind.equals(LIVE) ? "get_live_categories" : kind.equals(VOD) ? "get_vod_categories" : "get_series_categories";
        JSONArray a = new JSONArray(cached(action, api(action)));
        List<Category> out = new ArrayList<>();
        out.add(new Category("*", "Tout"));
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(new Category(o.optString("category_id"), o.optString("category_name")));
        }
        return out;
    }

    /** Every stream of a kind (one request, cached), filtered by category on the device. */
    List<Item> items(String kind, String category) throws Exception {
        String action = kind.equals(LIVE) ? "get_live_streams" : kind.equals(VOD) ? "get_vod_streams" : "get_series";
        JSONArray a = new JSONArray(cached(action, api(action)));
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Item it = new Item();
            it.kind = kind;
            it.category = o.optString("category_id");
            if (!"*".equals(category) && !category.equals(it.category)) continue;
            it.name = o.optString("name");
            it.num = o.optInt("num", i + 1);
            if (kind.equals(SERIES)) {
                it.id = o.optString("series_id");
                it.icon = o.optString("cover");
                it.plot = o.optString("plot");
                it.rating = o.optString("rating");
            } else {
                it.id = o.optString("stream_id");
                it.icon = o.optString("stream_icon");
                it.ext = o.optString("container_extension", kind.equals(LIVE) ? "ts" : "mp4");
                it.rating = o.optString("rating");
            }
            out.add(it);
        }
        return out;
    }

    /** A series' episodes, season by season, as playable items ("S1 E3 · Title"). */
    List<Item> episodes(String seriesId) throws Exception {
        JSONObject o = new JSONObject(get(api("get_series_info") + "&series_id=" + enc(seriesId)));
        List<Item> out = new ArrayList<>();
        JSONObject eps = o.optJSONObject("episodes");
        if (eps == null) return out;
        List<String> seasons = new ArrayList<>();
        for (java.util.Iterator<String> it = eps.keys(); it.hasNext(); ) seasons.add(it.next());
        java.util.Collections.sort(seasons, (a, b) -> {
            try { return Integer.parseInt(a) - Integer.parseInt(b); } catch (NumberFormatException e) { return a.compareTo(b); }
        });
        for (String s : seasons) {
            JSONArray list = eps.optJSONArray(s);
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject e = list.optJSONObject(i);
                Item it = new Item();
                it.kind = "episode";
                it.id = e.optString("id");
                it.ext = e.optString("container_extension", "mp4");
                String title = e.optString("title");
                it.name = "S" + s + " E" + e.optString("episode_num") + (title.isEmpty() ? "" : " · " + title);
                JSONObject info = e.optJSONObject("info");
                if (info != null) {
                    it.icon = info.optString("movie_image");
                    it.plot = info.optString("plot");
                }
                out.add(it);
            }
        }
        return out;
    }

    /** What's on now and next on a channel, or "" (the EPG titles come base64-encoded). */
    String nowNext(String streamId) {
        try {
            JSONObject o = new JSONObject(get(api("get_short_epg") + "&stream_id=" + enc(streamId) + "&limit=2"));
            JSONArray a = o.optJSONArray("epg_listings");
            if (a == null || a.length() == 0) return "";
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < a.length() && i < 2; i++) {
                JSONObject e = a.optJSONObject(i);
                String t = decode(e.optString("title"));
                String start = e.optString("start");
                String at = start.length() >= 16 ? start.substring(11, 16) : "";
                if (b.length() > 0) b.append("\n");
                b.append(i == 0 ? "Maintenant  " : "Ensuite  ").append(at.isEmpty() ? "" : at + "  ").append(t);
            }
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String decode(String s) {
        try { return new String(Base64.decode(s, Base64.DEFAULT), "UTF-8"); } catch (Exception e) { return s; }
    }

    // MARK: - Stream URLs

    /** Live: HLS first (what the TV's player handles best), MPEG-TS as the second try. */
    String liveUrl(Item it, boolean hls) {
        return server + "/live/" + enc(user) + "/" + enc(pass) + "/" + it.id + (hls ? ".m3u8" : ".ts");
    }

    String url(Item it) {
        if (LIVE.equals(it.kind)) return liveUrl(it, true);
        String path = "episode".equals(it.kind) ? "/series/" : "/movie/";
        return server + path + enc(user) + "/" + enc(pass) + "/" + it.id + "." + (it.ext.isEmpty() ? "mp4" : it.ext);
    }

    // MARK: - Favourites and last channel

    Set<String> favourites() { return new HashSet<>(sp.getStringSet("fav", new HashSet<>())); }

    boolean isFavourite(Item it) { return favourites().contains(it.kind + ":" + it.id); }

    void toggleFavourite(Item it) {
        Set<String> f = favourites();
        String k = it.kind + ":" + it.id;
        if (!f.remove(k)) f.add(k);
        sp.edit().putStringSet("fav", f).apply();
    }

    void setLast(String kind, String id) { sp.edit().putString("last", kind + ":" + id).apply(); }
    String last() { return sp.getString("last", ""); }

    // MARK: - HTTP and cache

    private String api(String action) {
        return server + "/player_api.php?username=" + enc(user) + "&password=" + enc(pass) + "&action=" + action;
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private File cacheFile(String action) { return new File(ctx.getCacheDir(), "x-" + action + ".json"); }

    private void clearCache() {
        File[] all = ctx.getCacheDir().listFiles();
        if (all != null) for (File f : all) if (f.getName().startsWith("x-")) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    void refresh() { clearCache(); }

    private String cached(String action, String url) throws Exception {
        File f = cacheFile(action);
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < CACHE_MS) {
            try (InputStream in = new FileInputStream(f)) { return read(in); }
        }
        String body = get(url);
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(body.getBytes("UTF-8")); }
        return body;
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12_000);
        c.setReadTimeout(60_000);
        c.setRequestProperty("User-Agent", "MaTV Live/1.0 (Android TV)");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) { return read(in); }
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }
}
