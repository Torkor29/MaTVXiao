package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MaTV Connect: the TV's own small web server for the phone (or a computer) on the same Wi-Fi. It serves the web app
 * (assets/connect) and its API: the wallpaper library (Art), the screensaver's settings, showing a wallpaper now, the
 * TV's apps and a remote (through the TV's own ADB). It listens only on the TV's private address, on a fixed port
 * (PORT, so a bookmark keeps working), in :home. Everything but the page itself needs the TV's secret, which the
 * phone gets once from the QR code in Réglages > Téléphone (in the address's #fragment, which a browser never sends),
 * keeps, and sends with each request. A new secret disconnects every phone. Nothing escapes its threads: :home's
 * watchdog would count it as a home screen crash.
 */
final class Companion {
    private Companion() {}

    static final int PORT = 8765;
    private static final int MAX_HEAD = 16384, MAX_JSON = 256 * 1024, MAX_UPLOAD = 30 * 1024 * 1024, THREADS = 6;
    private static final long READ_FOR_NS = 120_000_000_000L;   // a large upload over a weak Wi-Fi

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService answering = Executors.newFixedThreadPool(THREADS);
    private static final AtomicInteger busy = new AtomicInteger();
    private static Context app;
    private static volatile ServerSocket server;
    private static volatile String address = "";
    private static volatile boolean running;
    private static String token;
    /** Whoever wants to know that the phone changed something (the settings screen, the home screen). */
    static volatile Runnable onChange;

    // MARK: - Starting

    /** Starts the server if it's on in the settings (idempotent; any thread). */
    static synchronized void start(Context c) {
        app = c.getApplicationContext();
        if (running || !Prefs.connect()) return;
        running = true;
        Thread t = new Thread(Companion::serveLoop, "Companion");
        t.setDaemon(true);
        t.start();
    }

    static synchronized void stop() {
        running = false;
        closeServer();
    }

    /** The address to open on the phone (with the secret in the fragment), or "" while the TV isn't on the network. */
    static String pairUrl(Context c) {
        String base = baseUrl();
        return base.isEmpty() ? "" : base + "/#k=" + token(c);
    }

    /** http://<ip>:<port>, or "" while not listening. */
    static String baseUrl() {
        ServerSocket s = server;
        return s == null || s.isClosed() || address.isEmpty() ? "" : "http://" + address + (s.getLocalPort() == 80 ? "" : ":" + s.getLocalPort());
    }

    /** A new secret: every phone has to scan again. */
    static synchronized void newToken(Context c) {
        token = random(24);
        writeToken(c, token);
    }

    private static synchronized String token(Context c) {
        if (token == null) {
            File f = new File(c.getFilesDir(), "connect.token");
            try (InputStream in = new FileInputStream(f)) {
                token = Art.readAll(in, 200).trim();
            } catch (IOException e) {
                token = "";
            }
            if (token.length() < 20) {
                token = random(24);
                writeToken(c, token);
            }
        }
        return token;
    }

    private static void writeToken(Context c, String t) {
        try (OutputStream out = new FileOutputStream(new File(c.getFilesDir(), "connect.token"))) {
            out.write(t.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            AppLog.w("connect: token: " + e.getMessage());
        }
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Listens on the TV's private address; binds again when it changes (a new DHCP lease) or the network comes back. */
    private static void serveLoop() {
        while (running) {
            try {
                InetAddress lan = KeyPage.lanAddress(app);
                if (lan == null) {
                    closeServer();
                    sleep(15_000);
                    continue;
                }
                String ip = lan.getHostAddress();
                if (server == null || server.isClosed() || !ip.equals(address)) {
                    closeServer();
                    server = bind(lan);
                    address = ip;
                    AppLog.w("connect: listening on port " + server.getLocalPort());
                    Runnable r = onChange;
                    if (r != null) main.post(r);
                }
                server.setSoTimeout(60_000);
                Socket socket;
                try {
                    socket = server.accept();
                } catch (SocketTimeoutException e) {
                    continue;   // look at the address again
                }
                if (busy.incrementAndGet() > THREADS * 3) {
                    busy.decrementAndGet();
                    socket.close();
                    continue;
                }
                answering.execute(() -> answer(socket));
            } catch (Throwable e) {
                if (running) AppLog.w("connect: " + e.getMessage());
                closeServer();
                sleep(10_000);
            }
        }
    }

    private static ServerSocket bind(InetAddress lan) throws IOException {
        IOException last = null;
        for (int port = PORT; port < PORT + 5; port++) {
            ServerSocket s = new ServerSocket();
            try {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(lan, port), 16);
                return s;
            } catch (IOException e) {
                s.close();
                last = e;
            }
        }
        throw last;
    }

    private static void closeServer() {
        ServerSocket s = server;
        server = null;
        address = "";
        if (s != null) try { s.close(); } catch (IOException ignored) {}
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // MARK: - One request

    private static final class Request {
        String method, path, query = "";
        final Map<String, String> headers = new HashMap<>();
        InputStream body;
        int length;
        long deadline;

        String param(String name) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(name)) {
                    try { return URLDecoder.decode(pair.substring(eq + 1), "UTF-8"); } catch (Exception e) { return ""; }
                }
            }
            return "";
        }
    }

    private static void answer(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(20_000);
            s.setTcpNoDelay(true);
            InputStream in = new java.io.BufferedInputStream(s.getInputStream(), 65536);
            OutputStream out = new java.io.BufferedOutputStream(s.getOutputStream(), 65536);
            Request r = readRequest(in);
            if (r == null) return;
            try {
                route(r, out);
            } catch (HttpError e) {
                json(out, e.status, error(e.getMessage()));
            } catch (IOException e) {
                json(out, "500 Internal Server Error", error(e.getMessage() == null ? "Erreur" : e.getMessage()));
            } catch (Throwable e) {
                AppLog.w("connect: " + e);
                json(out, "500 Internal Server Error", error("Erreur sur la TV : " + e.getClass().getSimpleName()));
            }
            out.flush();
        } catch (Throwable ignored) {
            // A phone that went away midway. Nothing may escape this thread.
        } finally {
            busy.decrementAndGet();
        }
    }

    private static Request readRequest(InputStream in) throws IOException {
        long deadline = System.nanoTime() + READ_FOR_NS;
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0, b;
        while (matched < 4 && (b = in.read()) >= 0) {
            if (head.size() >= MAX_HEAD) return null;
            head.write(b);
            matched = (b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3)) ? matched + 1
                    : b == '\r' ? 1 : 0;
        }
        if (matched < 4) return null;
        String[] lines = head.toString(StandardCharsets.ISO_8859_1.name()).split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length < 2) return null;
        Request r = new Request();
        r.method = first[0];
        String target = first[1];
        int q = target.indexOf('?');
        r.path = q >= 0 ? target.substring(0, q) : target;
        if (q >= 0) r.query = target.substring(q + 1);
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) r.headers.put(lines[i].substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        try {
            r.length = Integer.parseInt(r.headers.containsKey("content-length") ? r.headers.get("content-length") : "0");
        } catch (NumberFormatException e) {
            r.length = -1;
        }
        r.body = in;
        r.deadline = deadline;
        return r;
    }

    private static final class HttpError extends IOException {
        final String status;
        HttpError(String status, String message) { super(message); this.status = status; }
    }

    private static void route(Request r, OutputStream out) throws IOException, JSONException {
        String p = r.path;
        if (r.method.equals("GET") && (p.equals("/") || p.equals("/index.html"))) { asset(out, "connect/index.html", "text/html; charset=utf-8", false); return; }
        if (r.method.equals("GET") && p.equals("/manifest.webmanifest")) { asset(out, "connect/manifest.webmanifest", "application/manifest+json", true); return; }
        if (r.method.equals("GET") && p.equals("/icon.png")) { asset(out, "connect/icon.png", "image/png", true); return; }
        if (r.method.equals("GET") && p.equals("/library.json")) { asset(out, "connect/library.json", "application/json", false); return; }
        if (r.method.equals("GET") && p.equals("/api/hello")) {
            JSONObject o = tvInfo();
            o.put("paired", authorized(r));
            json(out, "200 OK", o);
            return;
        }
        if (!authorized(r)) throw new HttpError("401 Unauthorized", "Ce téléphone n'est pas connecté à la TV. Scanne le QR code dans Réglages › Téléphone.");
        switch (r.method + " " + (p.startsWith("/img/") ? "/img/" : p.startsWith("/icon/") ? "/icon/" : p)) {
            case "GET /api/state": json(out, "200 OK", state()); return;
            case "GET /api/wallpapers": json(out, "200 OK", wallpapers()); return;
            case "GET /img/": image(r, out); return;
            case "GET /api/apps": json(out, "200 OK", apps()); return;
            case "GET /icon/": icon(r, out); return;
            case "POST /api/upload": upload(r, out); return;
            case "POST /api/add": addUrl(r, out); return;
            case "POST /api/update": {
                JSONObject b = readJson(r);
                int n = Art.update(app, b.optJSONArray("ids"), b);
                changed();
                json(out, "200 OK", new JSONObject().put("ok", true).put("count", n));
                return;
            }
            case "POST /api/delete": {
                int n = Art.delete(app, readJson(r).optJSONArray("ids"));
                changed();
                json(out, "200 OK", new JSONObject().put("ok", true).put("count", n));
                return;
            }
            case "POST /api/collections": collections(r, out); return;
            case "POST /api/settings": settings(readJson(r)); json(out, "200 OK", state()); return;
            case "POST /api/show": show(readJson(r)); json(out, "200 OK", new JSONObject().put("ok", true)); return;
            case "POST /api/remote": remote(readJson(r)); json(out, "200 OK", new JSONObject().put("ok", true)); return;
            case "POST /api/launch": launch(readJson(r).optString("pkg")); json(out, "200 OK", new JSONObject().put("ok", true)); return;
            default: throw new HttpError("404 Not Found", "Inconnu : " + p);
        }
    }

    private static boolean authorized(Request r) {
        String given = r.headers.get("authorization");
        if (given != null && given.startsWith("Bearer ")) given = given.substring(7).trim();
        else given = r.param("t");
        String want = token(app);
        return given != null && !given.isEmpty()
                && MessageDigest.isEqual(given.getBytes(StandardCharsets.US_ASCII), want.getBytes(StandardCharsets.US_ASCII));
    }

    // MARK: - The API

    private static JSONObject tvInfo() throws JSONException {
        String model = Build.MODEL == null ? "TV" : Build.MODEL;
        String version = "";
        try { version = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName; } catch (Exception ignored) {}
        return new JSONObject().put("app", "MaTV").put("model", model).put("brand", Build.MANUFACTURER)
                .put("version", version).put("android", Build.VERSION.RELEASE);
    }

    private static JSONObject state() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("tv", tvInfo());
        o.put("saver", new JSONObject()
                .put("mode", Prefs.saverMode())
                .put("collection", Art.activeCollection(app))
                .put("seconds", Prefs.saverSeconds())
                .put("clock", Prefs.saverClock())
                .put("caption", Prefs.saverCaption()));
        o.put("library", new JSONObject().put("count", Art.count(app)).put("max", Art.MAX));
        JSONArray moods = new JSONArray();
        for (String[] m : Recs.MOODS) moods.put(new JSONObject().put("id", m[0]).put("name", m[1]));
        o.put("picks", new JSONObject().put("mood", Recs.mood()[0]).put("moods", moods).put("style", Prefs.topStyle())
                .put("on", Recs.hasKey(app)));
        return o;
    }

    private static JSONObject wallpapers() throws JSONException {
        Art.ensureSeeded(app);
        JSONObject lib = Art.library(app);
        JSONObject out = new JSONObject();
        out.put("collections", lib.optJSONArray("collections"));
        JSONArray items = lib.optJSONArray("items"), clean = new JSONArray();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject o = items.getJSONObject(i), c = new JSONObject();
            for (String k : new String[] {"id", "title", "credit", "license", "link", "source", "collection", "remote"}) c.put(k, o.optString(k));
            c.put("enabled", o.optBoolean("enabled", true));
            c.put("added", o.optLong("added"));
            clean.put(c);
        }
        out.put("items", clean);
        out.put("active", Art.activeCollection(app));
        out.put("max", Art.MAX);
        return out;
    }

    private static void image(Request r, OutputStream out) throws IOException {
        String id = r.path.substring("/img/".length());
        File f = Art.image(app, id, !"full".equals(r.param("s")));
        if (f == null) throw new HttpError("404 Not Found", "Image introuvable");
        file(out, f, "image/jpeg");
    }

    private static void upload(Request r, OutputStream out) throws IOException, JSONException {
        if (r.length <= 0 || r.length > MAX_UPLOAD) throw new HttpError("413 Payload Too Large", "Image trop lourde (30 Mo au plus).");
        File raw = new File(app.getCacheDir(), "upload-" + random(6) + ".tmp");
        try {
            try (OutputStream to = new FileOutputStream(raw)) {
                byte[] buf = new byte[65536];
                for (int left = r.length; left > 0; ) {
                    int n = r.body.read(buf, 0, Math.min(buf.length, left));
                    if (n < 0 || System.nanoTime() > r.deadline) throw new IOException("Envoi interrompu.");
                    to.write(buf, 0, n);
                    left -= n;
                }
            }
            JSONObject meta = new JSONObject();
            meta.put("title", r.param("title"));
            meta.put("collection", r.param("collection"));
            meta.put("source", "phone");
            try { meta.put("fx", Double.parseDouble(r.param("fx"))); meta.put("fy", Double.parseDouble(r.param("fy"))); } catch (NumberFormatException ignored) {}
            JSONObject item = Art.add(app, raw, meta);
            changed();
            json(out, "200 OK", new JSONObject().put("ok", true).put("item", item));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            raw.delete();
        }
    }

    private static void addUrl(Request r, OutputStream out) throws IOException, JSONException {
        JSONObject b = readJson(r);
        String url = b.optString("url");
        if (!url.startsWith("https://")) throw new HttpError("400 Bad Request", "Adresse d'image refusée.");
        JSONObject item = Art.addFromUrl(app, url, b);
        changed();
        json(out, "200 OK", new JSONObject().put("ok", true).put("item", item));
    }

    private static void collections(Request r, OutputStream out) throws IOException, JSONException {
        JSONObject b = readJson(r);
        String action = b.optString("action"), id = b.optString("id");
        JSONObject res = new JSONObject().put("ok", true);
        switch (action) {
            case "create": res.put("id", Art.createCollection(app, b.optString("name"))); break;
            case "rename": Art.renameCollection(app, id, b.optString("name")); break;
            case "delete": Art.deleteCollection(app, id, b.optBoolean("withItems")); break;
            default: throw new HttpError("400 Bad Request", "Action inconnue.");
        }
        changed();
        json(out, "200 OK", res);
    }

    private static void settings(JSONObject b) {
        if (b.has("mode")) { String v = b.optString("mode"); if (v.equals("art") || v.equals("picks")) Prefs.setSaverMode(v); }
        if (b.has("collection")) Prefs.setSaverCategory(b.optString("collection", "all"));
        if (b.has("seconds")) Prefs.setSaverSeconds(b.optInt("seconds", 60));
        if (b.has("clock")) Prefs.setSaverClock(b.optBoolean("clock"));
        if (b.has("caption")) Prefs.setSaverCaption(b.optBoolean("caption"));
        if (b.has("mood")) {
            String v = b.optString("mood");
            for (String[] m : Recs.MOODS) if (m[0].equals(v)) Prefs.setPicksMood(v);
            main.post(() -> { try { Recs.get(app).refreshIfStale(); } catch (RuntimeException ignored) {} });
        }
        if (b.has("style")) { String v = b.optString("style"); if (v.equals("billboard") || v.equals("cards")) Prefs.setTopStyle(v); }
        changed();
    }

    private static void changed() {
        Art.version++;
        main.post(() -> {
            try {
                HomeData.get(app).settingsChanged();
            } catch (RuntimeException ignored) {
            }
            Runnable r = onChange;
            if (r != null) r.run();
        });
    }

    // MARK: - Acting on the TV

    /** Shows one wallpaper now (then the others), or the slideshow from the start. */
    private static void show(JSONObject b) throws IOException {
        String id = b.optString("id");
        Art.showFirst = id.isEmpty() ? null : id;
        Art.version++;
        String cmd = "am start -n " + app.getPackageName() + "/.ShowActivity -f 0x10200000" + (id.isEmpty() ? "" : " --es id " + id.replaceAll("[^A-Za-z0-9]", ""));
        if (!adbRun(cmd)) {
            Intent i = new Intent(app, ShowActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (!id.isEmpty()) i.putExtra("id", id);
            app.startActivity(i);
        }
    }

    private static final Map<String, Integer> KEYS = new HashMap<>();
    static {
        String[] k = {"up", "19", "down", "20", "left", "21", "right", "22", "ok", "23", "back", "4", "home", "3",
                "menu", "82", "play", "85", "volup", "24", "voldown", "25", "mute", "164", "rewind", "89", "forward", "90",
                "settings", "176", "info", "165", "chup", "166", "chdown", "167", "search", "84", "del", "67", "enter", "66"};
        for (int i = 0; i < k.length; i += 2) KEYS.put(k[i], Integer.parseInt(k[i + 1]));
    }

    private static void remote(JSONObject b) throws IOException {
        if (b.has("text")) {
            String t = b.optString("text").replaceAll("[^\\p{Print}]", "");
            if (t.length() > 200) t = t.substring(0, 200);
            if (t.isEmpty()) return;
            String arg = t.replace("%", "\\%").replace(" ", "%s").replace("'", "'\\''");
            if (!adbRun("input text '" + arg + "'")) throw new HttpError("503 Service Unavailable", remoteWhy());
            return;
        }
        Integer code = KEYS.get(b.optString("key"));
        if (code == null) throw new HttpError("400 Bad Request", "Touche inconnue.");
        String cmd = "input keyevent " + (b.optBoolean("long") ? "--longpress " : "") + code;
        if (!adbRun(cmd)) throw new HttpError("503 Service Unavailable", remoteWhy());
    }

    private static String remoteWhy() {
        return "La TV n'accepte pas la télécommande : le débogage USB doit être actif (comme pour l'installation de MaTV).";
    }

    private static JSONObject apps() throws JSONException {
        JSONArray a = new JSONArray();
        List<String[]> all = HomeApps.launchable(app);
        java.util.Collections.sort(all, (x, y) -> x[1].compareToIgnoreCase(y[1]));
        for (String[] s : all) if (!s[0].equals(app.getPackageName())) a.put(new JSONObject().put("pkg", s[0]).put("name", s[1]));
        return new JSONObject().put("apps", a);
    }

    private static final Map<String, byte[]> ICONS = new HashMap<>();

    private static void icon(Request r, OutputStream out) throws IOException {
        String pkg = r.path.substring("/icon/".length());
        byte[] png;
        synchronized (ICONS) { png = ICONS.get(pkg); }
        if (png == null) {
            PackageManager pm = app.getPackageManager();
            Drawable d;
            try {
                d = pm.getApplicationBanner(pkg);
                if (d == null) d = pm.getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException e) {
                throw new HttpError("404 Not Found", "Appli introuvable");
            }
            int w = Math.max(1, Math.min(480, d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() * 2 : 320));
            int h = Math.max(1, d.getIntrinsicHeight() > 0 ? Math.round(w * d.getIntrinsicHeight() / (float) Math.max(1, d.getIntrinsicWidth())) : 180);
            Bitmap bmp = Bitmap.createBitmap(w, Math.min(h, 480), Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, bmp.getWidth(), bmp.getHeight());
            d.draw(new Canvas(bmp));
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, b);
            bmp.recycle();
            png = b.toByteArray();
            synchronized (ICONS) { ICONS.put(pkg, png); }
        }
        bytes(out, "200 OK", "image/png", png, "private, max-age=86400");
    }

    private static void launch(String pkg) throws IOException {
        Intent i = HomeApps.launchIntent(app.getPackageManager(), pkg);
        if (i == null) throw new HttpError("404 Not Found", "Appli introuvable");
        ComponentName cn = i.getComponent();
        if (cn != null && adbRun("am start -n " + cn.flattenToShortString() + " -f 0x10200000")) return;
        app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // MARK: - The TV's own ADB (kept open a minute between keys)

    private static final Object ADB_LOCK = new Object();
    private static Adb adb;
    private static long adbUsed;

    /** Runs a shell command through the TV's ADB; false when ADB isn't there or refuses us. */
    private static boolean adbRun(String cmd) {
        synchronized (ADB_LOCK) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    if (adb == null || System.currentTimeMillis() - adbUsed > 60_000) {
                        if (adb != null) adb.close();
                        adb = Adb.connect(app, 3_000, null);
                    }
                    adb.shell(cmd);
                    adbUsed = System.currentTimeMillis();
                    return true;
                } catch (IOException | RuntimeException e) {
                    if (adb != null) adb.close();
                    adb = null;
                    if (attempt == 1) AppLog.w("connect: adb: " + e.getMessage());
                }
            }
            return false;
        }
    }

    // MARK: - Answers

    private static JSONObject readJson(Request r) throws IOException {
        if (r.length < 0 || r.length > MAX_JSON) throw new HttpError("413 Payload Too Large", "Requête trop grande.");
        byte[] body = new byte[r.length];
        for (int n = 0; n < r.length; ) {
            int got = r.body.read(body, n, r.length - n);
            if (got < 0 || System.nanoTime() > r.deadline) throw new IOException("Requête interrompue.");
            n += got;
        }
        try {
            return r.length == 0 ? new JSONObject() : new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new HttpError("400 Bad Request", "Requête illisible.");
        }
    }

    private static JSONObject error(String message) {
        try { return new JSONObject().put("ok", false).put("error", message); } catch (JSONException e) { return new JSONObject(); }
    }

    private static void json(OutputStream out, String status, JSONObject o) throws IOException {
        bytes(out, status, "application/json; charset=utf-8", o.toString().getBytes(StandardCharsets.UTF_8), "no-store");
    }

    private static void asset(OutputStream out, String name, String type, boolean cache) throws IOException {
        byte[] b;
        try (InputStream in = app.getAssets().open(name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
            b = o.toByteArray();
        } catch (IOException e) {
            throw new HttpError("404 Not Found", "Introuvable");
        }
        bytes(out, "200 OK", type, b, cache ? "public, max-age=86400" : "no-cache");
    }

    private static void file(OutputStream out, File f, String type) throws IOException {
        long len = f.length();
        head(out, "200 OK", type, len, "private, max-age=31536000, immutable");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
    }

    private static void bytes(OutputStream out, String status, String type, byte[] body, String cache) throws IOException {
        head(out, status, type, body.length, cache);
        out.write(body);
    }

    private static void head(OutputStream out, String status, String type, long length, String cache) throws IOException {
        String h = "HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + length
                + "\r\nCache-Control: " + cache + "\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff"
                + "\r\nX-Frame-Options: DENY\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
    }
}
