package io.matv.installer;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.LinearInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MaTV Installer: from the phone, on the TV's Wi-Fi, finds the TVs and installs MaTV on them (no computer).
 * Three screens: the TVs of the network, the one-time guide to turn on the TV's debugging, and the install itself.
 */
public class MainActivity extends Activity {
    // The palette of MaTV Connect (the phone web app), so both feel like one product.
    static final int NIGHT = 0xFF0B1020, NIGHT_HI = 0xFF18213D, INK = 0xFFF5F3EE, DIM = 0x99F5F3EE, FAINT = 0x52F5F3EE,
            GLASS = 0x13FFFFFF, EDGE = 0x1FFFFFFF, GOLD = 0xFFF3D7A3, ON_GOLD = 0xFF1C1608, OK = 0xFF7BE0A8, WARN = 0xFFFFB38A,
            BAD = 0xFFFF8A8A;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newCachedThreadPool();
    private FrameLayout root;
    private LinearLayout page, bottom;
    private String screen = "list";

    // The TVs of the network
    private final Map<String, Scanner.Tv> tvs = new LinkedHashMap<>();
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
    private final Net.Item live = new Net.Item("live", "MaTV Live", "Chaînes, films et séries avec tes codes Xtream", "io.matv.iptv", Net.LIVE_PIN);
    private final Net.Item tizen = new Net.Item("tt", "TizenTube", "YouTube sans pub, avec SponsorBlock", null, null);
    private final List<Net.Item> items = Arrays.asList(home, live, tizen);
    private boolean playProtectHint;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(NIGHT);
        w.setNavigationBarColor(NIGHT);
        root = new FrameLayout(this);
        root.setBackground(backdrop());
        setContentView(root);
        showList();
    }

    @Override protected void onResume() {
        super.onResume();
        if (screen.equals("list") && !scanning && System.currentTimeMillis() - scannedAt > 20_000) scan();
    }

    @Override public void onBackPressed() {
        if (screen.equals("list")) { super.onBackPressed(); return; }
        if (screen.equals("install") && phase.equals("installing")) return;   // not halfway through an install
        endSession();
        showList();
    }

    @Override protected void onDestroy() {
        endSession();
        scanCancel.set(true);
        work.shutdownNow();
        super.onDestroy();
    }

    // MARK: - Screen 1: the TVs of the network

    private void showList() {
        screen = "list";
        frame();
        TextView kicker = text("MaTV Installer", 14, DIM, Typeface.DEFAULT_BOLD);
        page.addView(kicker);
        TextView title = serif("Installe MaTV\nsur ta TV", 36);
        page.addView(title, margins(-1, -2, 0, 6, 0, 0));
        page.addView(text("Le téléphone et la TV sur le même Wi‑Fi, c'est tout.", 16, DIM, Typeface.DEFAULT), margins(-1, -2, 0, 10, 0, 22));

        self = Scanner.localAddress(this);
        if (self == null) {
            LinearLayout card = card();
            card.addView(text("Pas de Wi‑Fi", 19, INK, Typeface.DEFAULT_BOLD));
            card.addView(text("Connecte le téléphone au Wi‑Fi de la TV pour la trouver.", 15, DIM, Typeface.DEFAULT), margins(-1, -2, 0, 6, 0, 0));
            page.addView(card, margins(-1, -2, 0, 4, 0, 0));
            primary("Ouvrir les réglages Wi‑Fi", () -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
            secondary("Réessayer", this::showList);
            return;
        }

        LinearLayout status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        if (scanning) {
            status.addView(new Pulse(this, GOLD), new LinearLayout.LayoutParams(dp(26), dp(26)));
        }
        String net = self.getHostAddress().replaceFirst("\\.\\d+$", ".x");
        int n = tvs.size();
        String line = scanning ? "Recherche des TV sur le réseau " + net + "…"
                : n == 0 ? "Aucune TV trouvée sur le réseau " + net + "." : (n == 1 ? "1 TV" : n + " TV") + " sur le réseau " + net + ".";
        TextView st = text(line, 14, DIM, Typeface.DEFAULT);
        status.addView(st, margins(-2, -2, scanning ? 10 : 0, 0, 0, 0));
        page.addView(status, margins(-1, -2, 0, 0, 0, 12));

        for (Scanner.Tv t : tvs.values()) page.addView(tvRow(t), margins(-1, -2, 0, 0, 0, 10));

        if (!scanning && n == 0) {
            LinearLayout card = card();
            card.addView(text("Vérifie que :", 16, INK, Typeface.DEFAULT_BOLD));
            card.addView(text("• la TV est allumée (pas en veille) ;\n• le téléphone est sur le même Wi‑Fi qu'elle ;\n• le Wi‑Fi n'isole pas les appareils (Wi‑Fi invité).", 15, DIM, Typeface.DEFAULT), margins(-1, -2, 0, 6, 0, 0));
            card.addView(text("Si tu connais l'adresse IP de la TV (Paramètres → Réseau), entre-la ci-dessous.", 15, DIM, Typeface.DEFAULT), margins(-1, -2, 0, 10, 0, 0));
            page.addView(card);
        }
        if (!scanning) primary(n == 0 ? "Rechercher à nouveau" : "Actualiser", this::scan);
        secondary("Entrer l'adresse IP de la TV", this::askAddress);
    }

    private View tvRow(Scanner.Tv t) {
        LinearLayout row = card();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(new TvGlyph(this, t.ready), new LinearLayout.LayoutParams(dp(54), dp(42)));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(t.title(), 18, INK, Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(name);
        String sub = (t.model.isEmpty() || t.model.equals(t.title()) ? "" : t.model + ", ") + t.ip;
        mid.addView(text(sub, 13, DIM, Typeface.DEFAULT));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
        mp.leftMargin = dp(14);
        mp.rightMargin = dp(8);
        row.addView(mid, mp);
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
        back();
        page.addView(serif("Prépare « " + tv.title() + " »", 30), margins(-1, -2, 0, 14, 0, 0));
        page.addView(text("Une seule fois pour cette TV, avec sa télécommande. C'est ce qui permet au téléphone d'y installer des applis.", 15, DIM, Typeface.DEFAULT), margins(-1, -2, 0, 8, 0, 20));
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
        wait.addView(new Pulse(this, GOLD), new LinearLayout.LayoutParams(dp(26), dp(26)));
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

    // MARK: - Screen 3: install

    private void startInstall() {
        screen = "install";
        AtomicBoolean mine = newSession();
        phase = "connecting";
        error = "";
        tvInfo = "";
        playProtectHint = false;
        for (Net.Item it : items) { it.installed = ""; it.status = ""; it.done = it.failed = false; it.wanted = false; it.file = null; }
        showInstall();
        Scanner.Tv target = tv;
        work.execute(() -> {
            try {
                AdbClient a = AdbClient.connect(target.ip, Scanner.ADB_PORT, getFilesDir(), "matv-installer@" + Build.MODEL.replace(' ', '-'),
                        120_000, () -> ui.post(() -> { if (!mine.get()) { phase = "approve"; showInstall(); } }));
                if (mine.get()) { a.close(); return; }
                adb = a;
                ui.post(() -> { if (!mine.get()) { phase = "reading"; showInstall(); } });
                String brand = a.shell("getprop ro.product.brand").trim(), model = a.shell("getprop ro.product.model").trim();
                String android = a.shell("getprop ro.build.version.release").trim(), abis = a.shell("getprop ro.product.cpu.abilist").trim();
                serial = a.shell("getprop ro.serialno").trim();
                if (serial.isEmpty()) serial = target.ip;
                String info = (brand + " " + model).trim() + ", Android " + android;
                home.installed = versionOf(a, home.pkg);
                live.installed = versionOf(a, live.pkg);
                String ttPkg = prefs().getString("ttpkg_" + serial, "");
                if (ttPkg.isEmpty()) ttPkg = findTizenTube(a);
                tizen.installed = ttPkg.isEmpty() ? "" : prefs().getString("tttag_" + serial, "?");

                JSONObject matv = Net.latest(Net.REPO);
                Net.fillMaTV(matv, home, live);
                try {
                    Net.fillTizenTube(Net.latest(Net.TIZENTUBE), abis, tizen);
                } catch (IOException e) {
                    tizen.url = "";
                }
                home.wanted = home.installed.isEmpty() || Net.compare(home.installed, home.version) < 0;
                live.wanted = live.installed.isEmpty() || Net.compare(live.installed, live.version) < 0;
                tizen.wanted = !tizen.url.isEmpty() && !tizen.installed.equals(tizen.version);
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
                    Set<String> before = it == tizen ? packages(a) : null;
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
                    if (it == tizen) {
                        String pkg = prefs().getString("ttpkg_" + serial, "");
                        if (pkg.isEmpty()) {
                            Set<String> after = packages(a);
                            after.removeAll(before);
                            pkg = after.isEmpty() ? findTizenTube(a) : after.iterator().next();
                        }
                        prefs().edit().putString("ttpkg_" + serial, pkg).putString("tttag_" + serial, it.version).apply();
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
        frame();
        back();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(new TvGlyph(this, true), new LinearLayout.LayoutParams(dp(62), dp(48)));
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
                c.addView(new Pulse(this, GOLD), new LinearLayout.LayoutParams(dp(26), dp(26)));
                c.addView(text(phase.equals("connecting") ? "Connexion à la TV…" : "On regarde ce qui est déjà installé…", 16, INK, Typeface.DEFAULT), margins(0, -2, 12, 0, 0, 0, 1));
                page.addView(c);
                break;
            }
            case "approve": {
                page.addView(serif("Regarde la TV", 24), margins(-1, -2, 0, 0, 0, 6));
                page.addView(text("Elle demande si ce téléphone peut installer des applis. Coche « Toujours autoriser », puis choisis OK.", 16, DIM, Typeface.DEFAULT), margins(-1, -2, 0, 0, 0, 16));
                page.addView(new PromptMock(this), new LinearLayout.LayoutParams(-1, dp(190)));
                LinearLayout c = new LinearLayout(this);
                c.setGravity(Gravity.CENTER_VERTICAL);
                c.addView(new Pulse(this, GOLD), new LinearLayout.LayoutParams(dp(26), dp(26)));
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
        if (done) {
            LinearLayout c = card();
            c.setBackground(round(0x1A7BE0A8, dp(22), 0x337BE0A8));
            c.addView(text("C'est installé", 19, OK, Typeface.DEFAULT_BOLD));
            c.addView(text("Dernière étape, sur la TV : choisis MaTV comme écran d'accueil. La TV demande encore « Autoriser le débogage » : cette fois c'est MaTV qui le demande, accepte en cochant « Toujours autoriser ».", 15, INK, Typeface.DEFAULT), margins(-1, -2, 0, 6, 0, 0));
            c.addView(text("Ensuite, la TV se met à jour toute seule. Pour gérer tes fonds depuis le téléphone : MaTV → Téléphone, scanne le QR code.", 14, DIM, Typeface.DEFAULT), margins(-1, -2, 0, 10, 0, 0));
            page.addView(c, margins(-1, -2, 0, 0, 0, 16));
        }
        page.addView(text(done ? "Sur cette TV" : "À installer", 14, DIM, Typeface.DEFAULT_BOLD), margins(-1, -2, 4, 0, 0, 8));
        for (Net.Item it : items) page.addView(itemRow(it, busy || done), margins(-1, -2, 0, 0, 0, 10));

        hint = text("Si la TV affiche Play Protect : « Plus d'infos » → « Installer quand même ». MaTV n'est pas sur le Play Store, c'est normal.", 14, WARN, Typeface.DEFAULT);
        hint.setBackground(round(0x14FFB38A, dp(16), 0x33FFB38A));
        hint.setPadding(dp(16), dp(12), dp(16), dp(12));
        page.addView(hint, margins(-1, -2, 0, 4, 0, 0));
        refreshHint();

        int count = 0;
        for (Net.Item it : items) if (it.wanted && !it.url.isEmpty()) count++;
        if (busy) {
            primary("Installation…", null);
        } else if (done) {
            primary("Ouvrir MaTV sur la TV", this::openOnTv);
            secondary("Installer sur une autre TV", () -> { endSession(); showList(); scan(); });
        } else if (count == 0) {
            primary("Ouvrir MaTV sur la TV", home.installed.isEmpty() ? null : this::openOnTv);
            secondary("Tout est à jour", null);
        } else {
            boolean update = false;
            for (Net.Item it : items) update |= it.wanted && !it.installed.isEmpty();
            primary(update ? "Mettre à jour (" + count + ")" : count == 1 ? "Installer 1 appli" : "Installer " + count + " applis", this::runInstall);
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
        Check check = new Check(this, it.wanted && !it.url.isEmpty(), it.done);
        top.addView(check, new LinearLayout.LayoutParams(dp(26), dp(26)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(it.title, 17, INK, Typeface.DEFAULT_BOLD));
        col.addView(text(it.about, 13, DIM, Typeface.DEFAULT));
        top.addView(col, margins(0, -2, 14, 0, 8, 0, 1));
        String state;
        int color;
        if (it.url.isEmpty()) { state = "Indisponible"; color = FAINT; }
        else if (it.installed.isEmpty()) { state = it.version.isEmpty() ? "Nouveau" : it.version; color = GOLD; }
        else if (it.installed.equals(it.version) || (it.pin != null && Net.compare(it.installed, it.version) >= 0)) { state = "À jour"; color = OK; }
        else { state = it.installed.equals("?") ? "Mise à jour" : it.installed + " → " + it.version; color = GOLD; }
        top.addView(pill(state, color));
        row.addView(top);
        Bar bar = new Bar(this);
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
            Bar bar = (Bar) v[0];
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

    private static String findTizenTube(AdbClient a) throws IOException {
        for (String line : a.shell("pm list packages").split("\n")) {
            String p = line.replace("package:", "").trim();
            if (p.toLowerCase().contains("tizentube")) return p;
        }
        return "";
    }

    private static Set<String> packages(AdbClient a) throws IOException {
        Set<String> s = new HashSet<>();
        for (String line : a.shell("pm list packages -3").split("\n")) {
            String p = line.replace("package:", "").trim();
            if (!p.isEmpty()) s.add(p);
        }
        return s;
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

    private SharedPreferences prefs() { return getSharedPreferences("tvs", Context.MODE_PRIVATE); }

    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show(); }

    // MARK: - Layout

    /** A fresh screen: scrolling content, and a bar at the bottom for the buttons. */
    private void frame() {
        root.removeAllViews();
        rowViews.clear();
        hint = null;
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(28), dp(22), dp(24));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(22), dp(12), dp(22), dp(18));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {NIGHT, NIGHT, 0x000B1020}));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        column.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));
    }

    private void back() {
        TextView b = text("‹  Toutes les TV", 15, DIM, Typeface.DEFAULT_BOLD);
        b.setPadding(0, dp(6), dp(16), dp(6));
        b.setOnClickListener(v -> onBackPressed());
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
        t.setText(s.replace("« ", "«\u00a0").replace(" »", "\u00a0»").replace(" :", "\u00a0:"));
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

    // MARK: - Small drawn pieces

    /** A TV: a screen on a thin stand, lit gold when it's ready. */
    static final class TvGlyph extends View {
        private final boolean lit;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        TvGlyph(Context c, boolean lit) { super(c); this.lit = lit; }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
            RectF screen = new RectF(d, d, w - d, h * 0.8f);
            p.setStyle(Paint.Style.FILL);
            if (lit) {
                p.setShader(new LinearGradient(0, 0, w, h, 0xFF3A3F5C, 0xFF1A2038, Shader.TileMode.CLAMP));
            } else {
                p.setShader(null);
                p.setColor(0xFF161C30);
            }
            c.drawRoundRect(screen, 6 * d, 6 * d, p);
            p.setShader(null);
            if (lit) {
                p.setColor(GOLD);
                p.setAlpha(230);
                c.drawRoundRect(new RectF(w * 0.18f, h * 0.52f, w * 0.62f, h * 0.6f), 2 * d, 2 * d, p);
                p.setAlpha(110);
                c.drawRoundRect(new RectF(w * 0.18f, h * 0.24f, w * 0.44f, h * 0.42f), 3 * d, 3 * d, p);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.2f * d);
            p.setColor(lit ? 0x66F3D7A3 : 0x33F5F3EE);
            c.drawRoundRect(screen, 6 * d, 6 * d, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x55F5F3EE);
            c.drawRoundRect(new RectF(w * 0.36f, h * 0.86f, w * 0.64f, h * 0.86f + 2.4f * d), d, d, p);
        }
    }

    /** A soft pulsing dot: something is under way. */
    static final class Pulse extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
        private float t;
        Pulse(Context c, int color) {
            super(c);
            p.setColor(color);
            anim.setDuration(1400);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
        }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (ValueAnimator.areAnimatorsEnabled()) anim.start(); }
        @Override protected void onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow(); }
        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy);
            int a = p.getAlpha();
            p.setAlpha((int) (120 * (1 - t)));
            c.drawCircle(cx, cy, r * (0.35f + 0.65f * t), p);
            p.setAlpha(255);
            c.drawCircle(cx, cy, r * 0.3f, p);
            p.setAlpha(a);
        }
    }

    /** A thin progress line. */
    static final class Bar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float f;
        Bar(Context c) { super(c); }
        void set(float v) { f = Math.max(0, Math.min(1, v)); invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth();
            p.setColor(0x1FF5F3EE);
            c.drawRoundRect(new RectF(0, 0, w, h), h / 2, h / 2, p);
            p.setColor(GOLD);
            if (f > 0) c.drawRoundRect(new RectF(0, 0, Math.max(h, w * f), h), h / 2, h / 2, p);
        }
    }

    /** A round tick: chosen (gold), installed (green), or not. */
    static final class Check extends View {
        private final boolean on, done;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Check(Context c, boolean on, boolean done) { super(c); this.on = on; this.done = done; }
        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()), d = getResources().getDisplayMetrics().density, r = s / 2 - d;
            if (on || done) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(done ? OK : GOLD);
                c.drawCircle(s / 2, s / 2, r, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.2f * d);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setColor(ON_GOLD);
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(s * 0.3f, s * 0.52f);
                path.lineTo(s * 0.44f, s * 0.66f);
                path.lineTo(s * 0.71f, s * 0.36f);
                c.drawPath(path, p);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.5f * d);
                p.setColor(0x52F5F3EE);
                c.drawCircle(s / 2, s / 2, r, p);
            }
        }
    }

    /** What the TV shows: "Allow USB debugging?" with "Always allow" ticked and OK focused. */
    static final class PromptMock extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        PromptMock(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
            // The TV
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF060912);
            RectF tvr = new RectF(0, 0, w, h - 10 * d);
            c.drawRoundRect(tvr, 14 * d, 14 * d, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(d);
            p.setColor(EDGE);
            c.drawRoundRect(tvr, 14 * d, 14 * d, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x55F5F3EE);
            c.drawRoundRect(new RectF(w * 0.4f, h - 6 * d, w * 0.6f, h - 3 * d), d, d, p);
            // The dialog
            float dw = Math.min(w * 0.78f, 320 * d), dx = (w - dw) / 2, dy = 18 * d, dh = h - 46 * d;
            p.setColor(0xFF2A2F3A);
            c.drawRoundRect(new RectF(dx, dy, dx + dw, dy + dh), 10 * d, 10 * d, p);
            p.setColor(INK);
            p.setTextSize(14 * d);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText("Autoriser le débogage USB ?", dx + 16 * d, dy + 28 * d, p);
            p.setTypeface(Typeface.DEFAULT);
            p.setTextSize(11 * d);
            p.setColor(DIM);
            c.drawText("Empreinte de la clé RSA : 3F:A2:…", dx + 16 * d, dy + 48 * d, p);
            // "Always allow" ticked
            float by = dy + 74 * d;
            p.setColor(GOLD);
            c.drawRoundRect(new RectF(dx + 16 * d, by - 11 * d, dx + 30 * d, by + 3 * d), 3 * d, 3 * d, p);
            p.setColor(ON_GOLD);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            c.drawLine(dx + 19 * d, by - 4 * d, dx + 22 * d, by - d, p);
            c.drawLine(dx + 22 * d, by - d, dx + 27 * d, by - 8 * d, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(INK);
            p.setTextSize(12 * d);
            c.drawText("Toujours autoriser", dx + 38 * d, by, p);
            // Buttons, OK focused
            float bty = dy + dh - 36 * d;
            p.setColor(DIM);
            c.drawText("ANNULER", dx + dw - 150 * d, bty + 19 * d, p);
            p.setColor(GOLD);
            c.drawRoundRect(new RectF(dx + dw - 70 * d, bty, dx + dw - 14 * d, bty + 28 * d), 14 * d, 14 * d, p);
            p.setColor(ON_GOLD);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText("OK", dx + dw - 50 * d, bty + 19 * d, p);
            p.setTypeface(Typeface.DEFAULT);
        }
    }
}
