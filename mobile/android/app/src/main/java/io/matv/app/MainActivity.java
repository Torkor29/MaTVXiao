package io.matv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MaTV for the phone. On the TV's Wi-Fi it finds the TVs, installs or updates MaTV on them (over the TV's ADB, once
 * its USB debugging is on), pairs with them, and then pilots them: the control screen is MaTV Connect, the web app
 * each TV serves (remote, wallpapers, suggestions). "Essayer sans TV" runs the same web app with a demo TV.
 */
public class MainActivity extends Activity {
    // The palette of MaTV Connect (the TV's web app), so both feel like one product.
    static final int NIGHT = 0xFF0B1020, NIGHT_HI = 0xFF18213D, INK = 0xFFF5F3EE, DIM = 0x99F5F3EE, FAINT = 0x52F5F3EE,
            GLASS = 0x13FFFFFF, EDGE = 0x1FFFFFFF, GOLD = 0xFFF3D7A3, ON_GOLD = 0xFF1C1608, OK = 0xFF7BE0A8, WARN = 0xFFFFB38A,
            BAD = 0xFFFF8A8A;
    static final String DEMO_URL = "file:///android_asset/connect/index.html#demo";
    private static final int PICK_FILES = 7;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newCachedThreadPool();
    private FrameLayout root;
    private LinearLayout page, bottom;
    private String screen = "list";
    private boolean resumed;

    // The TVs: the ones this phone pilots (saved), and the ones on the Wi-Fi now
    private final Map<String, Scanner.Tv> tvs = new LinkedHashMap<>();
    private final Map<String, Boolean> online = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean scanning;
    private AtomicBoolean scanCancel = new AtomicBoolean();
    private long scannedAt;
    private Inet4Address self;

    // The TV being set up
    private Scanner.Tv tv;
    private AtomicBoolean session = new AtomicBoolean();
    private AdbClient adb;
    private String phase = "", error = "", tvInfo = "", serial = "";
    private final Net.Item home = new Net.Item("home", "Écran d'accueil MaTV", "Accueil sans pub, suggestions, fonds d'écran", "io.freemytv", Net.HOME_PIN);
    private final Net.Item live = new Net.Item("live", "MaTV Live", "Lecteur vidéo pour tes abonnements", "io.matv.iptv", Net.LIVE_PIN);
    private final List<Net.Item> items = Arrays.asList(home, live);
    private boolean playProtectHint, pairing;

    // The control screen
    private WebView web;
    private ValueCallback<Uri[]> filePick;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = new FrameLayout(this);
        root.setBackground(backdrop());
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            // Edge to edge (Android 15+ always): keep the content clear of the status bar, the gesture bar and the keyboard.
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }
        showList();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (screen.equals("list") && !scanning && System.currentTimeMillis() - scannedAt > 20_000) scan();
    }

    @Override protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        back();
    }

    private void back() {
        switch (screen) {
            case "list": finish(); return;
            case "install": if (phase.equals("installing")) return; break;
            case "control": closeWeb(); break;
        }
        endSession();
        showList();
    }

    @Override protected void onDestroy() {
        endSession();
        scanCancel.set(true);
        closeWeb();
        work.shutdownNow();
        super.onDestroy();
    }

    // MARK: - Screen 1: your TVs

    private void showList() {
        screen = "list";
        frame();
        page.addView(text("MaTV", 14, DIM, Typeface.DEFAULT_BOLD));
        page.addView(serif("Tes TV", 38), margins(-1, -2, 0, 4, 0, 0));
        page.addView(text("Le téléphone et la TV sur le même Wi‑Fi, c'est tout.", 16, DIM, Typeface.DEFAULT), margins(-1, -2, 0, 8, 0, 20));

        List<JSONObject> saved = saved();
        if (!saved.isEmpty()) {
            page.addView(section("Pilotées par ce téléphone"));
            for (JSONObject s : saved) page.addView(savedRow(s), margins(-1, -2, 0, 0, 0, 10));
            page.addView(new View(this), new LinearLayout.LayoutParams(1, dp(12)));
        }

        self = Scanner.localAddress(this);
        if (self == null) {
            LinearLayout card = card();
            card.addView(text("Pas de Wi‑Fi", 19, INK, Typeface.DEFAULT_BOLD));
            card.addView(text("Connecte le téléphone au Wi‑Fi de la TV pour la trouver.", 15, DIM, Typeface.DEFAULT), margins(-1, -2, 0, 6, 0, 0));
            page.addView(card, margins(-1, -2, 0, 4, 0, 0));
            demoLink();
            primary("Ouvrir les réglages Wi‑Fi", () -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
            secondary("Réessayer", this::showList);
            return;
        }

        List<Scanner.Tv> fresh = new ArrayList<>();
        for (Scanner.Tv t : tvs.values()) if (savedFor(t.ip) == null) fresh.add(t);
        LinearLayout status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        if (scanning) status.addView(new Drawn.Pulse(this, GOLD), new LinearLayout.LayoutParams(dp(26), dp(26)));
        String net = self.getHostAddress().replaceFirst("\\.\\d+$", ".x");
        String line = scanning ? "Recherche des TV sur le réseau " + net + "…"
                : fresh.isEmpty() ? (saved.isEmpty() ? "Aucune TV trouvée sur le réseau " + net + "." : "Pas d'autre TV sur le réseau " + net + ".")
                : (fresh.size() == 1 ? "1 TV" : fresh.size() + " TV") + " sur le réseau " + net + ".";
        status.addView(text(line, 14, DIM, Typeface.DEFAULT), margins(-2, -2, scanning ? 10 : 0, 0, 0, 0));
        page.addView(section("Sur le Wi‑Fi"));
        page.addView(status, margins(-1, -2, 0, 0, 0, 12));
        for (Scanner.Tv t : fresh) page.addView(tvRow(t), margins(-1, -2, 0, 0, 0, 10));

        if (!scanning && fresh.isEmpty() && saved.isEmpty()) {
            LinearLayout card = card();
            card.addView(text("Vérifie que :", 16, INK, Typeface.DEFAULT_BOLD));
            card.addView(text("• la TV est allumée (pas en veille) ;\n• le téléphone est sur le même Wi‑Fi qu'elle ;\n• le Wi‑Fi n'isole pas les appareils (Wi‑Fi invité).", 15, DIM, Typeface.DEFAULT), margins(-1, -2, 0, 6, 0, 0));
            card.addView(text("Tu connais l'adresse IP de la TV (Paramètres → Réseau) ? Entre-la ci-dessous.", 15, DIM, Typeface.DEFAULT), margins(-1, -2, 0, 10, 0, 0));
            page.addView(card);
        }
        demoLink();
        if (!scanning) primary(tvs.isEmpty() ? "Rechercher les TV" : "Actualiser", this::scan);
        secondary("Entrer l'adresse IP de la TV", this::askAddress);
    }

    private TextView section(String s) {
        TextView t = text(s, 14, DIM, Typeface.DEFAULT_BOLD);
        t.setPadding(dp(4), 0, 0, dp(8));
        return t;
    }

    private void demoLink() {
        TextView d = text("Pas de TV sous la main ? Essayer sans TV", 15, GOLD, Typeface.DEFAULT_BOLD);
        d.setPadding(dp(4), dp(14), dp(4), dp(14));
        d.setOnClickListener(v -> showControl("Démonstration", DEMO_URL));
        page.addView(d, new LinearLayout.LayoutParams(-2, -2));
    }

    private View savedRow(JSONObject s) {
        LinearLayout row = card();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        String ip = s.optString("ip");
        row.addView(new Drawn.TvGlyph(this, true), new LinearLayout.LayoutParams(dp(54), dp(42)));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(s.optString("name", "TV"), 18, INK, Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(name);
        mid.addView(text(ip, 13, DIM, Typeface.DEFAULT));
        row.addView(mid, margins(0, -2, 14, 0, 8, 0, 1));
        Boolean on = online.get(ip);
        row.addView(on == null ? pill("Piloter", GOLD) : on ? pill("Piloter", GOLD) : pill("Éteinte ?", FAINT));
        row.setOnClickListener(v -> showControl(s.optString("name", "TV"), connectUrl(s)));
        row.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(s.optString("name", "TV"))
                    .setItems(new String[] {"Installer ou mettre à jour MaTV", "Oublier cette TV"}, (d, which) -> {
                        if (which == 0) { Scanner.Tv t = new Scanner.Tv(ip); t.name = s.optString("name"); t.ready = true; openTv(t); }
                        else { forget(s.optString("key")); showList(); }
                    }).show();
            return true;
        });
        row.setForeground(getDrawable(android.R.drawable.list_selector_background));
        return row;
    }

    private View tvRow(Scanner.Tv t) {
        LinearLayout row = card();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(new Drawn.TvGlyph(this, t.ready), new LinearLayout.LayoutParams(dp(54), dp(42)));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(t.title(), 18, INK, Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(name);
        String sub = (t.model.isEmpty() || t.model.equals(t.title()) ? "" : t.model + ", ") + t.ip;
        mid.addView(text(sub, 13, DIM, Typeface.DEFAULT));
        row.addView(mid, margins(0, -2, 14, 0, 8, 0, 1));
        row.addView(pill(t.ready ? "Prête" : "À préparer", t.ready ? OK : WARN));
        row.setOnClickListener(v -> openTv(t));
        row.setForeground(getDrawable(android.R.drawable.list_selector_background));
        return row;
    }

    private void scan() {
        if (scanning) return;
        self = Scanner.localAddress(this);
        if (self == null) { showList(); return; }
        scanning = true;
        tvs.clear();
        scanCancel = new AtomicBoolean();
        AtomicBoolean cancel = scanCancel;
        showList();
        for (JSONObject s : saved()) {
            String ip = s.optString("ip");
            int port = s.optInt("port", 8765);
            work.execute(() -> {
                boolean on = Scanner.open(ip, port, 1200);
                online.put(ip, on);
                ui.post(() -> { if (!cancel.get() && screen.equals("list")) showList(); });
            });
        }
        work.execute(() -> {
            Scanner.scan(this, self, t -> ui.post(() -> {
                if (cancel.get()) return;
                tvs.put(t.ip, t);
                if (screen.equals("list")) showList();
            }), cancel);
            ui.post(() -> {
                if (cancel.get()) return;
                scanning = false;
                scannedAt = System.currentTimeMillis();
                if (screen.equals("list")) showList();
            });
        });
    }

    private void askAddress() {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_PHONE);
        field.setHint("192.168.1.42");
        String net = self == null ? "" : self.getHostAddress().replaceFirst("\\d+$", "");
        field.setText(net);
        field.setSelection(field.getText().length());
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        box.addView(field);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Adresse IP de la TV")
                .setMessage("Sur la TV : Paramètres → Réseau et Internet → ton Wi‑Fi.")
                .setView(box)
                .setPositiveButton("Continuer", (d, x) -> {
                    String ip = field.getText().toString().trim();
                    if (!ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return;
                    Scanner.Tv t = tvs.get(ip);
                    if (t == null) { t = new Scanner.Tv(ip); tvs.put(ip, t); }
                    Scanner.Tv chosen = t;
                    work.execute(() -> {
                        chosen.ready = Scanner.open(ip, Scanner.ADB_PORT, 1500);
                        ui.post(() -> openTv(chosen));
                    });
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void openTv(Scanner.Tv t) {
        scanCancel.set(true);
        scanning = false;
        tv = t;
        if (t.ready) startInstall(); else showGuide();
    }

    // MARK: - Screen 2: once per TV, turn on its debugging

    private void showGuide() {
        screen = "guide";
        frame();
        backLink();
        page.addView(serif("Prépare « " + tv.title() + " »", 30), margins(-1, -2, 0, 14, 0, 0));
        page.addView(text("Une seule fois pour cette TV, avec sa télécommande. C'est ce qui permet au téléphone d'y installer MaTV et de la piloter.", 15, DIM, Typeface.DEFAULT), margins(-1, -2, 0, 8, 0, 20));
        String[][] steps = {
                {"Ouvre les paramètres", "Paramètres → Système → À propos."},
                {"Deviens développeur", "Descends sur « Build du système Android TV » et appuie 7 fois sur OK, jusqu'à « Vous êtes développeur »."},
                {"Ouvre les options pour les développeurs", "Reviens dans Paramètres → Système → Options pour les développeurs."},
                {"Active le débogage USB", "Mets « Débogage USB » sur activé, puis confirme."},
        };
        for (int i = 0; i < steps.length; i++) page.addView(step(i + 1, steps[i][0], steps[i][1]), margins(-1, -2, 0, 0, 0, 10));

        LinearLayout wait = card();
        wait.setOrientation(LinearLayout.HORIZONTAL);
        wait.setGravity(Gravity.CENTER_VERTICAL);
        wait.addView(new Drawn.Pulse(this, GOLD), new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView wt = text("Le téléphone attend la TV… il continue tout seul dès que c'est fait.", 15, INK, Typeface.DEFAULT);
        wait.addView(wt, margins(0, -2, 12, 0, 0, 0, 1));
        page.addView(wait, margins(-1, -2, 0, 8, 0, 0));

        AtomicBoolean mine = newSession();
        Scanner.Tv waitingFor = tv;
        work.execute(() -> {
            while (!mine.get()) {
                if (Scanner.open(waitingFor.ip, Scanner.ADB_PORT, 1200)) {
                    waitingFor.ready = true;
                    ui.post(() -> {
                        if (mine.get()) return;
                        wt.setText("La TV est prête.");
                        wt.setTextColor(OK);
                        ui.postDelayed(() -> { if (!mine.get()) startInstall(); }, 700);
                    });
                    return;
                }
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
        });
    }

    private View step(int n, String title, String body) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView num = text(String.valueOf(n), 15, ON_GOLD, Typeface.DEFAULT_BOLD);
        num.setGravity(Gravity.CENTER);
        num.setBackground(round(GOLD, dp(14), 0));
        row.addView(num, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(title, 16, INK, Typeface.DEFAULT_BOLD));
        col.addView(text(body, 15, DIM, Typeface.DEFAULT), margins(-1, -2, 0, 3, 0, 0));
        row.addView(col, margins(0, -2, 14, 3, 0, 6, 1));
        return row;
    }

    // MARK: - Screen 3: install, update, pair

    private void startInstall() {
        screen = "install";
        AtomicBoolean mine = newSession();
        phase = "connecting";
        error = "";
        tvInfo = "";
        playProtectHint = false;
        pairing = false;
        for (Net.Item it : items) { it.installed = ""; it.status = ""; it.done = it.failed = false; it.wanted = false; it.file = null; }
        showInstall();
        Scanner.Tv target = tv;
        work.execute(() -> {
            try {
                AdbClient a = AdbClient.connect(target.ip, Scanner.ADB_PORT, getFilesDir(), "matv@" + Build.MODEL.replace(' ', '-'),
                        120_000, () -> ui.post(() -> { if (!mine.get()) { phase = "approve"; showInstall(); } }));
                if (mine.get()) { a.close(); return; }
                adb = a;
                ui.post(() -> { if (!mine.get()) { phase = "reading"; showInstall(); } });
                String brand = a.shell("getprop ro.product.brand").trim(), model = a.shell("getprop ro.product.model").trim();
                String android = a.shell("getprop ro.build.version.release").trim();
                serial = a.shell("getprop ro.serialno").trim();
                if (serial.isEmpty()) serial = target.ip;
                String info = (brand + " " + model).trim() + ", Android " + android;
                home.installed = versionOf(a, home.pkg);
                live.installed = versionOf(a, live.pkg);
                Net.fillMaTV(Net.latest(Net.REPO), home, live);
                home.wanted = home.installed.isEmpty() || Net.compare(home.installed, home.version) < 0;
                live.wanted = live.installed.isEmpty() || Net.compare(live.installed, live.version) < 0;
                ui.post(() -> {
                    if (mine.get()) return;
                    tvInfo = info;
                    phase = "choose";
                    showInstall();
                });
            } catch (IOException | RuntimeException e) {
                ui.post(() -> {
                    if (mine.get()) return;
                    error = e.getMessage() == null ? "Erreur inattendue." : e.getMessage();
                    phase = "error";
                    showInstall();
                });
            }
        });
    }

    private void runInstall() {
        AtomicBoolean mine = session;
        AdbClient a = adb;
        if (a == null) return;
        phase = "installing";
        showInstall();
        work.execute(() -> {
            int ok = 0;
            for (Net.Item it : items) {
                if (!it.wanted || mine.get()) continue;
                try {
                    File f = new File(getCacheDir(), it.key + ".apk");
                    set(it, "Téléchargement…", 0);
                    Net.download(it.url, f, it.size, (d, t) -> set(it, "Téléchargement " + pct(d, t) + " %", t > 0 ? d * 0.45f / t : 0), mine);
                    Net.check(this, f, it);
                    set(it, "Envoi à la TV…", 0.45f);
                    String answer;
                    try (InputStream in = new FileInputStream(f)) {
                        answer = a.install(in, f.length(), (d, t) -> {
                            if (d < t) set(it, "Envoi à la TV " + pct(d, t) + " %", 0.45f + d * 0.5f / t);
                            else { set(it, "Installation sur la TV…", 0.97f); ui.post(() -> { playProtectHint = true; refreshHint(); }); }
                        });
                    }
                    f.delete();
                    if (!answer.contains("Success")) throw new IOException(explain(answer));
                    if (it == home && tvInfo.toLowerCase().startsWith("tcl")) {
                        a.shell("appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow");
                    }
                    it.installed = it.version;
                    it.done = true;
                    ok++;
                    set(it, "Installé", 1);
                } catch (Throwable e) {   // whatever happens, the screen must not stay on "Installation…"
                    it.failed = true;
                    set(it, e instanceof IOException && e.getMessage() != null ? e.getMessage() : "Échec : " + e, -1);
                }
            }
            int installed = ok;
            ui.post(() -> {
                if (mine.get()) return;
                playProtectHint = false;
                boolean anyFailed = false;
                for (Net.Item it : items) anyFailed |= it.wanted && it.failed;
                phase = anyFailed && installed == 0 ? "choose" : "done";
                for (Net.Item it : items) if (it.done) it.wanted = false;
                showInstall();
            });
        });
    }

    private static final Pattern PAIRED = Pattern.compile("matv-connect ([A-Za-z0-9_-]{20,}) (\\d+)");

    /** Asks MaTV on the TV for MaTV Connect's secret (over ADB: no QR code), saves the TV, opens its control. */
    private void pairAndControl() {
        AdbClient a = adb;
        if (a == null || pairing) return;
        pairing = true;
        showInstall();
        String ip = tv.ip, name = tv.title();
        work.execute(() -> {
            String answer;
            try {
                answer = a.shell("am broadcast -n io.freemytv/.PairReceiver");
            } catch (IOException e) {
                answer = "";
            }
            Matcher m = PAIRED.matcher(answer);
            boolean ok = m.find();
            String token = ok ? m.group(1) : "", port = ok ? m.group(2) : "";
            ui.post(() -> {
                pairing = false;
                if (!ok) {
                    toast("MaTV sur la TV est trop ancienne pour être pilotée : mets-la à jour ci-dessus.");
                    showInstall();
                    return;
                }
                JSONObject s = save(serial.isEmpty() ? ip : serial, name, ip, Integer.parseInt(port), token);
                endSession();
                showControl(name, connectUrl(s));
            });
        });
    }

    private void openOnTv() {
        AdbClient a = adb;
        if (a == null) return;
        work.execute(() -> {
            try {
                a.shell("input keyevent KEYCODE_WAKEUP");
                a.shell("am start -n io.freemytv/.MaTVSettingsActivity --es open home_mode");
                ui.post(() -> toast("C'est ouvert sur la TV."));
            } catch (IOException e) {
                ui.post(() -> toast("La TV ne répond plus : ouvre l'appli MaTV avec la télécommande."));
            }
        });
    }

    private void showInstall() {
        screen = "install";
        frame();
        backLink();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(new Drawn.TvGlyph(this, true), new LinearLayout.LayoutParams(dp(62), dp(48)));
        LinearLayout hc = new LinearLayout(this);
        hc.setOrientation(LinearLayout.VERTICAL);
        hc.addView(serif(tv.title(), 28));
        hc.addView(text(tvInfo.isEmpty() ? tv.ip : tvInfo + ", " + tv.ip, 13, DIM, Typeface.DEFAULT));
        head.addView(hc, margins(0, -2, 14, 0, 0, 0, 1));
        page.addView(head, margins(-1, -2, 0, 14, 0, 20));

        switch (phase) {
            case "connecting":
            case "reading": {
                LinearLayout c = card();
                c.setOrientation(LinearLayout.HORIZONTAL);
                c.setGravity(Gravity.CENTER_VERTICAL);
                c.addView(new Drawn.Pulse(this, GOLD), new LinearLayout.LayoutParams(dp(26), dp(26)));
                c.addView(text(phase.equals("connecting") ? "Connexion à la TV…" : "On regarde ce qui est déjà installé…", 16, INK, Typeface.DEFAULT), margins(0, -2, 12, 0, 0, 0, 1));
                page.addView(c);
                break;
            }
            case "approve": {
                page.addView(serif("Regarde la TV", 24), margins(-1, -2, 0, 0, 0, 6));
                page.addView(text("Elle demande si ce téléphone peut s'y connecter. Coche « Toujours autoriser », puis choisis OK.", 16, DIM, Typeface.DEFAULT), margins(-1, -2, 0, 0, 0, 16));
                page.addView(new Drawn.PromptMock(this), new LinearLayout.LayoutParams(-1, dp(190)));
                LinearLayout c = new LinearLayout(this);
                c.setGravity(Gravity.CENTER_VERTICAL);
                c.addView(new Drawn.Pulse(this, GOLD), new LinearLayout.LayoutParams(dp(26), dp(26)));
                c.addView(text("En attente de ta réponse sur la TV…", 15, DIM, Typeface.DEFAULT), margins(-2, -2, 10, 0, 0, 0));
                page.addView(c, margins(-1, -2, 0, 16, 0, 0));
                break;
            }
            case "error": {
                LinearLayout c = card();
                c.addView(text("Ça n'a pas marché", 18, BAD, Typeface.DEFAULT_BOLD));
                c.addView(text(error, 15, INK, Typeface.DEFAULT), margins(-1, -2, 0, 6, 0, 0));
                c.addView(text("Vérifie que la TV est allumée et que le débogage USB est toujours activé.", 14, DIM, Typeface.DEFAULT), margins(-1, -2, 0, 10, 0, 0));
                page.addView(c);
                primary("Réessayer", this::startInstall);
                secondary("Choisir une autre TV", () -> { endSession(); showList(); });
                break;
            }
            default:
                showItems();
        }
    }

    private TextView hint;

    private void showItems() {
        boolean busy = phase.equals("installing"), done = phase.equals("done");
        boolean homeReady = !home.installed.isEmpty() && Net.compare(home.installed, home.version) >= 0;
        if (done) {
            LinearLayout c = card();
            c.setBackground(round(0x1A7BE0A8, dp(22), 0x337BE0A8));
            c.addView(text("C'est installé", 19, OK, Typeface.DEFAULT_BOLD));
            c.addView(text("Sur la TV, choisis MaTV comme écran d'accueil (« Ouvrir MaTV sur la TV » y mène). La TV demande encore « Autoriser le débogage » : cette fois c'est MaTV, accepte en cochant « Toujours autoriser ».", 15, INK, Typeface.DEFAULT), margins(-1, -2, 0, 6, 0, 0));
            c.addView(text("Ensuite, la TV se met à jour toute seule, et ce téléphone la pilote.", 14, DIM, Typeface.DEFAULT), margins(-1, -2, 0, 10, 0, 0));
            page.addView(c, margins(-1, -2, 0, 0, 0, 16));
        }
        page.addView(text(done ? "Sur cette TV" : "MaTV sur cette TV", 14, DIM, Typeface.DEFAULT_BOLD), margins(-1, -2, 4, 0, 0, 8));
        for (Net.Item it : items) page.addView(itemRow(it, busy || done), margins(-1, -2, 0, 0, 0, 10));

        hint = text("Si la TV affiche Play Protect : « Plus d'infos » → « Installer quand même ». MaTV n'est pas sur le Play Store de la TV, c'est normal.", 14, WARN, Typeface.DEFAULT);
        hint.setBackground(round(0x14FFB38A, dp(16), 0x33FFB38A));
        hint.setPadding(dp(16), dp(12), dp(16), dp(12));
        page.addView(hint, margins(-1, -2, 0, 4, 0, 0));
        refreshHint();

        int count = 0;
        for (Net.Item it : items) if (it.wanted && !it.url.isEmpty()) count++;
        if (busy) {
            primary("Installation…", null);
        } else if (pairing) {
            primary("Connexion…", null);
        } else if (done || (count == 0 && homeReady)) {
            primary("Piloter la TV", this::pairAndControl);
            secondary("Ouvrir MaTV sur la TV", this::openOnTv);
        } else if (count == 0) {
            primary("Choisis ce qu'il faut installer", null);
        } else {
            boolean update = false;
            for (Net.Item it : items) update |= it.wanted && !it.installed.isEmpty();
            primary(update ? "Mettre à jour (" + count + ")" : count == 1 ? "Installer 1 appli" : "Installer " + count + " applis", this::runInstall);
            if (homeReady) secondary("Piloter la TV sans mettre à jour", this::pairAndControl);
        }
    }

    private void refreshHint() {
        if (hint != null) hint.setVisibility(playProtectHint ? View.VISIBLE : View.GONE);
    }

    private final Map<Net.Item, Object[]> rowViews = new java.util.HashMap<>();

    private View itemRow(Net.Item it, boolean locked) {
        LinearLayout row = card();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(new Drawn.Check(this, it.wanted && !it.url.isEmpty(), it.done), new LinearLayout.LayoutParams(dp(26), dp(26)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(it.title, 17, INK, Typeface.DEFAULT_BOLD));
        col.addView(text(it.about, 13, DIM, Typeface.DEFAULT));
        top.addView(col, margins(0, -2, 14, 0, 8, 0, 1));
        String state;
        int color;
        if (it.url.isEmpty()) { state = "Indisponible"; color = FAINT; }
        else if (it.installed.isEmpty()) { state = it.version.isEmpty() ? "Nouveau" : it.version; color = GOLD; }
        else if (Net.compare(it.installed, it.version) >= 0) { state = "À jour"; color = OK; }
        else { state = it.installed + " → " + it.version; color = GOLD; }
        top.addView(pill(state, color));
        row.addView(top);
        Drawn.Bar bar = new Drawn.Bar(this);
        TextView status = text(it.status, 13, it.failed ? BAD : DIM, Typeface.DEFAULT);
        boolean active = !it.status.isEmpty();
        bar.setVisibility(active && !it.failed && !it.done ? View.VISIBLE : View.GONE);
        status.setVisibility(active ? View.VISIBLE : View.GONE);
        row.addView(bar, margins(-1, dp(4), 40, 12, 0, 0));
        row.addView(status, margins(-1, -2, 40, 6, 0, 0));
        rowViews.put(it, new Object[] {bar, status});
        if (!locked && !it.url.isEmpty()) {
            row.setOnClickListener(v -> {
                it.wanted = !it.wanted;
                showInstall();
            });
        }
        return row;
    }

    /** Progress of one item, from the worker thread (fraction < 0: failed). */
    private void set(Net.Item it, String status, float fraction) {
        ui.post(() -> {
            it.status = status;
            Object[] v = rowViews.get(it);
            if (v == null) return;
            Drawn.Bar bar = (Drawn.Bar) v[0];
            TextView t = (TextView) v[1];
            t.setText(status);
            t.setVisibility(View.VISIBLE);
            t.setTextColor(fraction < 0 ? BAD : fraction >= 1 ? OK : DIM);
            bar.setVisibility(fraction < 0 || fraction >= 1 ? View.GONE : View.VISIBLE);
            if (fraction >= 0) bar.set(fraction);
        });
    }

    private static int pct(long d, long t) { return t > 0 ? (int) Math.min(100, d * 100 / t) : 0; }

    private static String versionOf(AdbClient a, String pkg) throws IOException {
        String out = a.shell("dumpsys package " + pkg + " | grep -m1 versionName");
        int i = out.indexOf("versionName=");
        return i < 0 ? "" : out.substring(i + 12).trim().split("\\s")[0];
    }

    /** The TV's refusal in words (pm's codes are not for people). */
    private static String explain(String answer) {
        if (answer.contains("UPDATE_INCOMPATIBLE")) return "Une autre version de cette appli, signée autrement, est déjà sur la TV : désinstalle-la, puis réessaie.";
        if (answer.contains("INSUFFICIENT_STORAGE")) return "Plus assez de place sur la TV.";
        if (answer.contains("VERIFICATION") || answer.contains("USER_RESTRICTED") || answer.contains("ABORTED")) return "Installation refusée sur la TV (Play Protect). Réessaie et choisis « Installer quand même ».";
        if (answer.contains("OLDER_SDK")) return "Cette TV est trop ancienne pour cette appli.";
        if (answer.contains("NO_MATCHING_ABIS")) return "Cette version n'est pas faite pour le processeur de la TV.";
        String a = answer.replaceAll("\\s+", " ").trim();
        return a.isEmpty() ? "La TV n'a pas répondu." : "La TV a refusé : " + (a.length() > 160 ? a.substring(0, 160) + "…" : a);
    }

    private AtomicBoolean newSession() {
        endSession();
        session = new AtomicBoolean();
        return session;
    }

    private void endSession() {
        session.set(true);
        AdbClient a = adb;
        adb = null;
        if (a != null) work.execute(a::close);
    }

    // MARK: - Screen 4: control (MaTV Connect, the TV's own web app)

    private void showControl(String name, String url) {
        scanCancel.set(true);
        scanning = false;
        screen = "control";
        root.removeAllViews();
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(4), dp(16), dp(4));
        TextView back = text("‹  Mes TV", 16, INK, Typeface.DEFAULT_BOLD);
        back.setPadding(dp(10), dp(10), dp(14), dp(10));
        back.setOnClickListener(v -> back());
        bar.addView(back);
        TextView title = text(name, 15, DIM, Typeface.DEFAULT);
        title.setGravity(Gravity.END);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        column.addView(bar, new LinearLayout.LayoutParams(-1, dp(52)));

        web = new WebView(this);
        web.setBackgroundColor(NIGHT);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(url.startsWith("file:"));   // only the demo, which is in the app itself
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSupportMultipleWindows(false);
        String home = Uri.parse(url).getHost();
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                boolean same = home == null ? "file".equals(u.getScheme()) : home.equals(u.getHost());
                if (same) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (ActivityNotFoundException ignored) {}
                return true;   // links to Wikimedia and the like open in the browser
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePick != null) filePick.onReceiveValue(null);
                filePick = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, "Photos"), PICK_FILES);
                } catch (ActivityNotFoundException e) {
                    filePick = null;
                    return false;
                }
                return true;
            }
        });
        column.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
        web.loadUrl(url);
    }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FILES || filePick == null) return;
        Uri[] picked = null;
        if (result == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                picked = new Uri[data.getClipData().getItemCount()];
                for (int i = 0; i < picked.length; i++) picked[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                picked = new Uri[] {data.getData()};
            }
        }
        filePick.onReceiveValue(picked);
        filePick = null;
    }

    private void closeWeb() {
        if (web == null) return;
        web.stopLoading();
        web.destroy();
        web = null;
    }

    // MARK: - The TVs this phone pilots (MaTV Connect's address and secret, on this phone only)

    private SharedPreferences prefs() { return getSharedPreferences("tvs", Context.MODE_PRIVATE); }

    List<JSONObject> saved() {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs().getString("saved", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    private JSONObject savedFor(String ip) {
        for (JSONObject s : saved()) if (ip.equals(s.optString("ip"))) return s;
        return null;
    }

    JSONObject save(String key, String name, String ip, int port, String token) {
        JSONArray a = new JSONArray();
        JSONObject me = new JSONObject();
        try {
            me.put("key", key).put("name", name).put("ip", ip).put("port", port).put("token", token);
            a.put(me);
            for (JSONObject s : saved()) if (!key.equals(s.optString("key")) && !ip.equals(s.optString("ip"))) a.put(s);
        } catch (Exception ignored) {
        }
        prefs().edit().putString("saved", a.toString()).apply();
        return me;
    }

    private void forget(String key) {
        JSONArray a = new JSONArray();
        for (JSONObject s : saved()) if (!key.equals(s.optString("key"))) a.put(s);
        prefs().edit().putString("saved", a.toString()).apply();
    }

    static String connectUrl(JSONObject s) {
        return "http://" + s.optString("ip") + ":" + s.optInt("port", 8765) + "/#k=" + s.optString("token");
    }

    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show(); }

    // MARK: - Layout

    /** A fresh screen: scrolling content, and a bar at the bottom for the buttons. */
    private void frame() {
        closeWeb();
        root.removeAllViews();
        rowViews.clear();
        hint = null;
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(20), dp(22), dp(24));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(22), dp(12), dp(22), dp(14));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {NIGHT, NIGHT, 0x000B1020}));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        column.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
    }

    private void backLink() {
        TextView b = text("‹  Mes TV", 15, DIM, Typeface.DEFAULT_BOLD);
        b.setPadding(0, dp(6), dp(16), dp(6));
        b.setOnClickListener(v -> back());
        page.addView(b, new LinearLayout.LayoutParams(-2, -2));
    }

    private void primary(String label, Runnable r) {
        TextView b = text(label, 17, ON_GOLD, Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(r == null ? 0x66F3D7A3 : GOLD, dp(28), 0));
        if (r != null) b.setOnClickListener(v -> r.run());
        b.setEnabled(r != null);
        bottom.addView(b, new LinearLayout.LayoutParams(-1, dp(56)));
    }

    private void secondary(String label, Runnable r) {
        TextView b = text(label, 15, r == null ? FAINT : INK, Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        if (r != null) b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48));
        p.topMargin = dp(6);
        bottom.addView(b, p);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(GLASS, dp(22), EDGE));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        return c;
    }

    private TextView pill(String s, int color) {
        TextView p = text(s, 12, color, Typeface.DEFAULT_BOLD);
        p.setSingleLine(true);
        p.setBackground(round((color & 0x00FFFFFF) | 0x1F000000, dp(12), (color & 0x00FFFFFF) | 0x40000000));
        p.setPadding(dp(10), dp(4), dp(10), dp(4));
        return p;
    }

    private GradientDrawable round(int fill, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private GradientDrawable backdrop() {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[] {NIGHT_HI, NIGHT});
        g.setGradientCenter(0.85f, 0.05f);
        g.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 1.1f);
        return g;
    }

    private TextView text(String s, int sp, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setText(s.replace("« ", "« ").replace(" »", " »").replace(" :", " :"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    private TextView serif(String s, int sp) {
        TextView t = text(s, sp, INK, Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        t.setLineSpacing(0, 1.02f);
        t.setLetterSpacing(-0.01f);
        return t;
    }

    private LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b, float weight) {
        LinearLayout.LayoutParams p = margins(w, h, l, t, r, b);
        p.weight = weight;
        return p;
    }

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
