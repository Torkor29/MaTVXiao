package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * MaTV's Home Settings, laid out like the Apple TV's: two levels. First the categories, then one page per category.
 * The left half shows the category's large symbol and, under it, what the focused row does; the right half is the
 * list. Rows are plain text; the focused row becomes a white rounded plate that rises a little. Switches for yes/no,
 * the value in gray for choices (◀ ▶ change it), a chevron for rows that open another screen (SettingsActivity's).
 * It runs in :home like the home screen, so a change bumps Prefs.version there and the home screen redraws itself.
 */
public class MaTVSettingsActivity extends Activity {

    /** One row: a choice (◀ ▶ through `values`), a switch (values off/on), or an action (OK opens something). */
    private static final class Opt {
        final String title;
        String desc;
        String key;
        String[] values, labels;
        Getter get;
        Setter set;
        Runnable action;
        boolean isSwitch;
        /** The value shown on the right, read again every second (a status, a count, a position). */
        java.util.function.Supplier<String> live;
        /** ◀ ▶ on an action row (the dock's order). */
        java.util.function.IntConsumer step;
        /** The explanation on the left, when it changes (a status). */
        java.util.function.Supplier<String> about;
        /** An action row that opens another page shows a chevron; one that acts in place doesn't. */
        boolean chevron = true;
        /** Grayed: shown but OK does nothing. */
        java.util.function.BooleanSupplier dimmed;

        Opt(String title, String desc) { this.title = title; this.desc = desc; }
    }

    private interface Getter { String get(); }
    private interface Setter { void set(String v); }
    private interface BoolGet { boolean get(); }
    private interface BoolSet { void set(boolean v); }

    private static final class Category {
        final String symbol, name;
        String about;
        final List<Opt> opts = new ArrayList<>();
        /** A page built when it opens (and again after a change): apps, services, search results. */
        java.util.function.Consumer<Category> fill;
        /** Shown on the left instead of the symbol (the TMDB key's QR code). */
        android.graphics.Bitmap image;
        /** Called when the page closes. */
        Runnable onClose;
        int focus;
        Category(String symbol, String name, String about) { this.symbol = symbol; this.name = name; this.about = about; }
    }

    private final List<Category> categories = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private float k;
    private boolean light;
    private int bg0, bg1, ink, dim, faint, plate, plateInk, plateDim, line;

    private TextView pageTitle, sideName, sideText;
    private SymbolView sideSymbol;
    private android.widget.ImageView sideImage;
    private LinearLayout list;
    private ScrollView scroll;
    private FrameLayout root;
    /** -1: the categories; else the open category. */
    private int open = -1, lastCategory;
    /** The open pages, the category first; empty on the categories. */
    private final List<Category> stack = new ArrayList<>();
    private DreamView preview;
    private boolean resumed;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        palette();
        build();

        root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {bg0, bg1}));
        setContentView(root);

        pageTitle = text(40, ink, Ui.displayMedium);
        pageTitle.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        tp.topMargin = px(64);
        root.addView(pageTitle, tp);

        // Left: the large symbol, the category's name, then the focused row's explanation.
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(px(760), -2, Gravity.START | Gravity.CENTER_VERTICAL);
        sp.leftMargin = px(80);
        root.addView(side, sp);
        sideSymbol = new SymbolView(this);
        side.addView(sideSymbol, new LinearLayout.LayoutParams(px(260), px(260)));
        sideImage = new android.widget.ImageView(this);
        sideImage.setVisibility(View.GONE);
        sideImage.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        GradientDrawable qrBg = new GradientDrawable();
        qrBg.setColor(0xFFFFFFFF);
        qrBg.setCornerRadius(px(28));
        sideImage.setBackground(qrBg);
        sideImage.setPadding(px(18), px(18), px(18), px(18));
        sideImage.setClipToOutline(true);
        side.addView(sideImage, new LinearLayout.LayoutParams(px(360), px(360)));
        sideName = text(34, ink, Ui.medium);
        sideName.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.topMargin = px(28);
        side.addView(sideName, np);
        sideText = text(24, dim, Ui.regular);
        sideText.setGravity(Gravity.CENTER);
        sideText.setSingleLine(false);
        sideText.setMaxLines(7);
        sideText.setLineSpacing(px(6), 1);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(px(560), -2);
        dp.topMargin = px(16);
        side.addView(sideText, dp);

        // Right: the list.
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, px(170), 0, px(120));
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(px(80));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        list.setPadding(px(28), 0, px(28), 0);   // room for the focused row's growth
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(900), -1, Gravity.END);
        lp.rightMargin = px(100);
        root.addView(scroll, lp);

        showCategories(0);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (list != null) refreshValues();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 1000);
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(tick);
        closePreview();
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopKeyPage();
        super.onDestroy();
    }

    /** Once a second: statuses (updates, the switch, the picks, the sleep timer) follow what happens. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            refreshValues();
            View f = list == null ? null : list.findFocus();
            while (f != null && !(f instanceof Row) && f.getParent() instanceof View) f = (View) f.getParent();
            if (f instanceof Row && ((Row) f).opt != null && ((Row) f).opt.about != null) sideText.setText(((Row) f).opt.about.get());
            handler.postDelayed(this, 1000);
        }
    };

    /** Light or dark like the home screen's theme ("auto": light by day). */
    private void palette() {
        String t = Prefs.theme();
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        light = t.equals("light") || (t.equals("auto") && hour >= 7 && hour < 19);
        if (light) {
            bg0 = 0xFFF5F5F7; bg1 = 0xFFE8E8ED;
            ink = 0xFF1D1D1F; dim = 0xFF6E6E73; faint = 0xFFA1A1A6;
            plate = 0xFFFFFFFF; plateInk = 0xFF1D1D1F; plateDim = 0xFF6E6E73; line = 0x14000000;
        } else {
            bg0 = 0xFF1C1C1E; bg1 = 0xFF0E0E10;
            ink = 0xFFF5F5F7; dim = 0xFF98989D; faint = 0xFF5E5E63;
            plate = 0xFFF5F5F7; plateInk = 0xFF1D1D1F; plateDim = 0xFF6E6E73; line = 0x14FFFFFF;
        }
    }

    // MARK: - The options

    private void build() {
        Context c = this;
        Category look = add("cat_look", "Apparence", "Thème, verre, couleurs et dock.");
        choice(look, "Thème", "Automatique suit le lever et le coucher du soleil.",
                new String[] {"auto", "light", "dark"}, new String[] {"Automatique", "Clair", "Sombre"},
                Prefs::theme, v -> { Prefs.setTheme(v); SleepOverlay.update(c); });
        choice(look, "Verre", "Liquide : reflets et réfraction. Givré : plus doux. Opaque : panneaux pleins.",
                Prefs.GLASS_STYLES, new String[] {"Liquide", "Givré", "Opaque"}, Prefs::glassStyle, Prefs::setGlassStyle);
        choice(look, "Teinte", "Plus forte, les panneaux sont plus opaques et le texte plus lisible.",
                Prefs.GLASS_TINTS, new String[] {"Légère", "Normale", "Forte"}, Prefs::glassTint, Prefs::setGlassTint);
        choice(look, "Couleur", "Une touche de couleur dans le verre.",
                Prefs.ACCENTS, new String[] {"Neutre", "Bleu", "Violet", "Rose", "Or", "Vert"}, Prefs::accent, Prefs::setAccent);
        choice(look, "Taille des applis", "La taille des logos en bas de l'accueil.",
                Prefs.DOCK_SIZES, new String[] {"Compacte", "Normale", "Grande"}, Prefs::dockSize, Prefs::setDockSize);
        toggle(look, "Cadre du dock", "Sans cadre, les applis sont plus grandes et plus proches.", Prefs::dockFrame, Prefs::setDockFrame);
        toggle(look, "Contour de sélection", "Un fin liseré autour de l'appli choisie, et un reflet quand on change.",
                Prefs::focusRing, Prefs::setFocusRing);
        toggle(look, "Sons", "Un léger clic à chaque déplacement.", Prefs::sounds, Prefs::setSounds);

        Category home = add("cat_home", "Accueil", "Ce qui s'affiche sur l'accueil.");
        choice(home, "En haut", "Les suggestions, ce que tu regardais sur toutes tes applis, ou une grande horloge.",
                Prefs.TOP_CONTENT, new String[] {"Suggestions", "Vous regardiez", "Horloge"}, Prefs::topContent,
                v -> { Prefs.setTopContent(v); askWatchGrant(); });
        page(home, "Applis du dock", "Choisir les applis et leur ordre.", "cat_home", this::fillDock).live =
                () -> HomeApps.shelfPackages(c).size() + " applis";
        page(home, "Boutons du haut", "Les boutons ronds en haut à droite de l'accueil.", "cat_home", this::fillTopButtons);
        toggle(home, "Entrées", "TV en direct et HDMI, dans le tiroir du bas.", Prefs::drawerInputs, Prefs::setDrawerInputs);
        toggle(home, "Raccourcis", "Toutes les apps et Réglages de la TV, dans le tiroir du bas.",
                Prefs::drawerShortcuts, Prefs::setDrawerShortcuts);
        toggle(home, "Icônes d'état", "Wi‑Fi, Bluetooth et USB en haut à gauche.", Prefs::statusIcons, v -> {
            Prefs.setStatusIcons(v);
            if (v && android.os.Build.VERSION.SDK_INT >= 31
                    && checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {"android.permission.BLUETOOTH_CONNECT"}, 2);
            }
        });
        toggle(home, "Applis pour téléphone", "Les montrer aussi dans Toutes les apps.", Prefs::androidApps, Prefs::setAndroidApps);
        String[] recents = RemoteKeys.XIAOMI ? new String[] {"off", "drawer"} : new String[] {"off", "drawer", "home"};
        String[] recentLabels = RemoteKeys.XIAOMI ? new String[] {"Désactivées", "Dans le tiroir"}
                : new String[] {"Désactivées", "Dans le tiroir", "Double Accueil"};
        choice(home, "Applis récentes", "Revenir aux applis ouvertes ou les fermer, depuis le tiroir du bas.",
                recents, recentLabels, Prefs::recents, Prefs::setRecents);
        toggle(home, "Mes photos", "Tes photos en fond quand le haut de l'écran montre l'horloge.", Prefs::photos, Prefs::setPhotos);

        Category picks = add("cat_picks", "Suggestions", "Tendances et nouveautés de tes services.");
        Opt state = info(picks, "État", this::picksState);
        state.about = this::picksStateLong;
        choice(picks, "Présentation", "Affiche : une grande image, comme Netflix. Cartes : comme l'Apple TV.",
                Prefs.TOP_STYLES, new String[] {"Affiche", "Cartes"}, Prefs::topStyle, Prefs::setTopStyle);
        choice(picks, "Nombre de titres", "Combien de suggestions à la fois.",
                new String[] {"3", "4", "5", "6"}, new String[] {"3", "4", "5", "6"},
                () -> String.valueOf(Prefs.cards()), v -> Prefs.setCards(Integer.parseInt(v)));
        page(picks, "Services", "Les services dont viennent les suggestions. Coupe ceux auxquels tu n'es pas abonné.",
                "cat_picks", this::fillServices).live = this::servicesCount;
        page(picks, "Vous regardiez", "Les applis qui remontent ce que tu as commencé.", "cat_picks", this::fillWatch);
        Opt key = page(picks, "Clé TMDB", "Pour les suggestions. Un QR code s'affiche : scanne-le et colle ta clé sur ton téléphone.",
                "cat_picks", this::fillKey);
        key.live = () -> Recs.hasKey(c) ? "•••• " + last4(Recs.key()) : "Aucune";

        Category saver = add("cat_saver", "Écran de veille", "Quand la TV reste inactive.");
        choice(saver, "Contenu", "Suggestions : les films et séries de tes services. Fonds d'écran : tes images, en plein écran.",
                Prefs.SAVER_MODES, new String[] {"Suggestions", "Fonds d'écran"}, Prefs::saverMode, Prefs::setSaverMode);
        choice(saver, "Fonds d'écran", "Tes fonds : art, paysages, ou tout mélangé.",
                Prefs.SAVER_CATEGORIES, new String[] {"Tout", "Art", "Paysages"}, Prefs::saverCategory, Prefs::setSaverCategory);
        String[] secs = new String[Prefs.SAVER_SECONDS.length];
        for (int i = 0; i < secs.length; i++) secs[i] = String.valueOf(Prefs.SAVER_SECONDS[i]);
        choice(saver, "Durée", "Le temps de chaque image.",
                secs, new String[] {"15 s", "30 s", "1 min", "3 min", "10 min"},
                () -> String.valueOf(Prefs.saverSeconds()), v -> Prefs.setSaverSeconds(Integer.parseInt(v)));
        toggle(saver, "Horloge", "L'heure, la date et la météo. Réglée à part pour chaque contenu.", Prefs::saverClock, Prefs::setSaverClock);
        toggle(saver, "Légende", "Le titre et l'auteur. Réglée à part pour chaque contenu.", Prefs::saverCaption, Prefs::setSaverCaption);
        toggle(saver, "Ouvrir avec OK", "OK lance le film ou la série à l'écran.", Prefs::saverWatch, Prefs::setSaverWatch);
        act(saver, "Aperçu", "Voir l'écran de veille maintenant. N'importe quelle touche le ferme.", this::showPreview);

        Category widgets = add("cat_widgets", "Widgets", "Les infos de l'accueil et du tiroir du bas.");
        toggle(widgets, "Météo", "Le temps qu'il fait et les heures à venir.", Prefs::weather, Prefs::setWeather);
        page(widgets, "Ville", "Pour la météo et le lever du soleil.", "cat_widgets", this::fillCity).live = Prefs::city;
        choice(widgets, "Prévisions", "Les prochaines heures, ou les prochains jours.",
                new String[] {"hours", "days"}, new String[] {"Par heure", "Par jour"}, Prefs::forecast, Prefs::setForecast);
        toggle(widgets, "Lecture en cours", "La musique ou le podcast en cours, sur l'accueil. MaTV demande à la TV le droit de le voir.",
                Prefs::nowPlaying, v -> { Prefs.setNowPlaying(v); HomeMode.nowPlayingAccess(c); });
        toggle(widgets, "Cryptos", "Les cours, en haut de l'accueil.", Prefs::quotes, Prefs::setQuotes);
        String[] coins = {"Bitcoin", "Ethereum", "Solana"};
        for (int i = 0; i < coins.length; i++) {
            final int at = i;
            Opt o = toggle(widgets, coins[i], "Afficher " + coins[i] + " avec les cryptos.", () -> Prefs.coin(at), v -> Prefs.setCoin(at, v));
            o.dimmed = () -> !Prefs.quotes();
        }

        Category sys = add("cat_system", "Système", "Accueil, langue, télécommande et mises à jour.");
        page(sys, "Écran d'accueil", "MaTV, ou l'accueil d'origine de la TV.", "cat_system", this::fillHomeMode).live = this::modeLabel;
        String[] langs = languageChoices();
        String[] langNames = new String[langs.length];
        for (int i = 0; i < langs.length; i++) langNames[i] = langs[i].equals("auto") ? "Automatique" : Ui.languageName(langs[i]);
        choice(sys, "Langue", "La langue de l'accueil. Automatique suit celle de la TV.", langs, langNames, Prefs::language,
                v -> { Prefs.setLanguage(v); SleepOverlay.update(c); });
        choice(sys, "Température", "", new String[] {"auto", "C", "F"}, new String[] {"Auto", "°C", "°F"},
                Prefs::temperature, Prefs::setTemperature);
        choice(sys, "Format de l'heure", "", new String[] {"auto", "24", "12"}, new String[] {"Auto", "24 h", "12 h"},
                Prefs::clockFormat, Prefs::setClockFormat);
        page(sys, "Télécommande", "Ce qu'ouvrent les boutons de la télécommande.", "cat_system", this::fillRemote);
        page(sys, "Minuterie de veille", "Éteindre la TV après un délai.", "cat_system", this::fillSleep).live = this::sleepLabel;
        Opt up = act(sys, "Mises à jour", "OK cherche une nouvelle version, puis l'installe.", this::updateNow);
        up.live = this::updateLabel;
        up.about = () -> "Version installée : " + Updates.installedVersion(c) + ". OK cherche une nouvelle version, puis l'installe.";
        act(sys, "Réglages de la TV", "Image, son, réseau, comptes.", () -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); } catch (RuntimeException ignored) {
                startActivity(new Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.MainSettings"));
            }
        });
    }

    private Category add(String symbol, String name, String about) {
        Category c = new Category(symbol, name, about);
        categories.add(c);
        return c;
    }

    private Opt choice(Category cat, String title, String desc, String[] values, String[] labels, Getter get, Setter set) {
        Opt o = new Opt(title, desc);
        o.values = values;
        o.labels = labels;
        o.get = get;
        o.set = set;
        cat.opts.add(o);
        return o;
    }

    private Opt toggle(Category cat, String title, String desc, BoolGet get, BoolSet set) {
        Opt o = choice(cat, title, desc, new String[] {"off", "on"}, new String[] {"Non", "Oui"},
                () -> get.get() ? "on" : "off", v -> set.set(v.equals("on")));
        o.isSwitch = true;
        return o;
    }

    /** OK does something in place (no chevron). */
    private Opt act(Category cat, String title, String desc, Runnable r) {
        Opt o = new Opt(title, desc);
        o.action = r;
        o.chevron = false;
        cat.opts.add(o);
        return o;
    }

    /** OK opens a page of its own, built by `fill`. */
    private Opt page(Category cat, String title, String desc, String symbol, java.util.function.Consumer<Category> fill) {
        Opt o = new Opt(title, desc);
        Category sub = new Category(symbol, title, desc);
        sub.fill = fill;
        o.action = () -> openPage(sub);
        cat.opts.add(o);
        return o;
    }

    /** A line that only says something (a status). */
    private Opt info(Category cat, String title, java.util.function.Supplier<String> value) {
        Opt o = new Opt(title, "");
        o.live = value;
        o.action = () -> { };
        o.chevron = false;
        cat.opts.add(o);
        return o;
    }

    private static String last4(String s) {
        return s.length() <= 4 ? s : s.substring(s.length() - 4);
    }

    private static String[] languageChoices() {
        String[] all = Ui.languages();
        String[] v = new String[all.length + 1];
        v[0] = "auto";
        System.arraycopy(all, 0, v, 1, all.length);
        return v;
    }

    // MARK: - Suggestions: why there are none

    private String picksState() {
        if (!Recs.hasKey(this)) return "Pas de clé";
        org.json.JSONObject s = Recs.status(this);
        String error = s.optString("error");
        int count = s.optInt("count");
        if (!error.isEmpty()) return count > 0 ? count + " titres" : "Problème";
        if (!s.has("at")) return "En attente";
        return count + " titres";
    }

    private String picksStateLong() {
        if (!Recs.hasKey(this)) return "Les suggestions ont besoin d'une clé TMDB gratuite. Va dans Clé TMDB, plus bas.";
        if (!Prefs.topContent().equals(Prefs.TOP_PICKS)) return "Le haut de l'accueil montre autre chose : Accueil → En haut → Suggestions.";
        org.json.JSONObject s = Recs.status(this);
        String error = s.optString("error");
        if (error.isEmpty()) {
            return !s.has("at") ? "L'accueil va chercher les suggestions. Reviens sur l'accueil quelques secondes."
                    : s.optInt("count") + " titres prêts, mis à jour " + ago(s.optLong("at")) + ".";
        }
        if (error.contains("rejected")) return "TMDB refuse la clé. Va dans Clé TMDB et donne-la à nouveau.";
        if (error.equals(Recs.NO_KNOWN_APPS)) return "Aucune appli de streaming connue sur la TV, ou tous les services sont coupés dans Services.";
        if (error.equals(Recs.NO_APPS)) return "TMDB ne connaît aucun de tes services dans ton pays. Vérifie Services.";
        return "Dernier essai " + ago(s.optLong("at")) + " : " + error + ". L'accueil réessaie tout seul.";
    }

    private static String ago(long at) {
        long m = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
        return m < 1 ? "à l'instant" : m < 60 ? "il y a " + m + " min" : "il y a " + (m / 60) + " h";
    }

    // MARK: - Dock and top buttons

    private void fillDock(Category p) {
        p.about = "OK ajoute ou retire une appli. ◀ ▶ change sa place dans le dock.";
        List<String> order = new ArrayList<>();
        android.content.pm.PackageManager pm = getPackageManager();
        for (String pkg : HomeApps.shelfPackages(this)) if (HomeApps.launchIntent(pm, pkg) != null) order.add(pkg);
        fillOrdered(p, order, HomeApps.shelfCandidates(this), HomeApps.maxShelf(this), Prefs::setShelf);
    }

    private void fillTopButtons(Category p) {
        p.about = "OK ajoute ou retire un bouton. ◀ ▶ change sa place.";
        fillOrdered(p, new ArrayList<>(HomeApps.topButtonKeys()), HomeApps.topCandidates(), Integer.MAX_VALUE, Prefs::setTopButtons);
    }

    /** The chosen ones first, in order, then the others: OK puts in or takes out, ◀ ▶ moves. */
    private void fillOrdered(Category p, List<String> order, List<String[]> candidates, int max,
                             java.util.function.Consumer<List<String>> save) {
        List<String[]> rows = new ArrayList<>();
        for (String key : order) for (String[] cand : candidates) if (cand[0].equals(key)) { rows.add(cand); break; }
        for (String[] cand : candidates) if (!order.contains(cand[0])) rows.add(cand);
        for (String[] cand : rows) {
            final String key = cand[0];
            Opt o = new Opt(cand[1], "");
            o.isSwitch = true;
            o.values = new String[] {"off", "on"};
            o.labels = new String[] {"", ""};
            o.get = () -> order.contains(key) ? "on" : "off";
            o.key = key;
            o.live = () -> order.contains(key) ? String.valueOf(order.indexOf(key) + 1) : "";
            o.chevron = false;
            o.action = () -> {
                if (order.contains(key)) order.remove(key);
                else if (order.size() < max) order.add(key);
                else { toast("Le dock est plein : retire d'abord une appli."); return; }
                save.accept(new ArrayList<>(order));
                rebuildKeeping(key);
            };
            o.step = dir -> {
                int at = order.indexOf(key);
                if (at < 0) return;
                int to = Math.max(0, Math.min(order.size() - 1, at + dir));
                if (to == at) return;
                order.remove(at);
                order.add(to, key);
                save.accept(new ArrayList<>(order));
                rebuildKeeping(key);
            };
            o.desc = order.contains(key) ? "Dans le dock. ◀ ▶ change sa place, OK le retire." : "OK l'ajoute au dock.";
            p.opts.add(o);
        }
    }

    // MARK: - Suggestions' services, Continue Watching's apps

    private String servicesCount() {
        List<String[]> all = Recs.installedServices(this);
        int on = 0;
        for (String[] s : all) if (!Prefs.serviceOff(s[0])) on++;
        return on + " sur " + all.size();
    }

    private void fillServices(Category p) {
        List<String[]> all = Recs.installedServices(this);
        if (all.isEmpty()) {
            info(p, "Aucun service de streaming trouvé sur la TV", () -> "");
            return;
        }
        for (String[] s : all) {
            final String key = s[0];
            toggle(p, s[1], "Des suggestions de " + s[1] + ". Coupe-le si tu n'y es pas abonné.",
                    () -> !Prefs.serviceOff(key), v -> Prefs.setServiceOff(key, !v));
        }
    }

    private List<String[]> watchApps;

    private void fillWatch(Category p) {
        if (!Prefs.topContent().equals(Prefs.TOP_CONTINUE)) {
            info(p, "Utilisé quand le haut montre Vous regardiez", () -> "");
        }
        if (watchApps == null) {
            info(p, "Recherche des applis…", () -> "");
            final Context app = getApplicationContext();
            new Thread(() -> {
                List<String[]> found = WatchNext.sources(app);
                handler.post(() -> { watchApps = found; if (!stack.isEmpty() && stack.get(stack.size() - 1) == p) rebuildKeeping(null); });
            }, "WatchApps").start();
            return;
        }
        if (watchApps.isEmpty()) info(p, "Aucune appli ne partage ce que tu regardes", () -> "");
        for (String[] a : watchApps) {
            final String pkg = a[0];
            toggle(p, a[1], "Ce que tu as commencé dans " + a[1] + ".", () -> !Prefs.watchOff().contains(pkg), v -> {
                java.util.Set<String> off = Prefs.watchOff();
                if (v) off.remove(pkg); else off.add(pkg);
                Prefs.setWatchOff(off);
            });
        }
        int hidden = Prefs.watchHidden().size();
        if (hidden > 0) act(p, "Réafficher les titres masqués (" + hidden + ")", "Ceux que tu as retirés de Vous regardiez.", () -> {
            Prefs.setWatchHidden(new java.util.HashSet<>());
            rebuildKeeping(null);
        });
    }

    // MARK: - TMDB key (a QR code for the phone, or typed)

    private KeyPage keyPage;
    private String keyStatus = "";
    private boolean keyChecking;

    private void fillKey(Category p) {
        p.onClose = this::stopKeyPage;
        p.image = keyPage == null ? null : qrBitmap(Qr.encode(keyPage.url));
        java.util.function.Supplier<String> explain = () -> {
            String base = keyPage != null ? "Scanne le code avec ton téléphone, puis colle ta clé. Ou ouvre " + keyPage.url
                    : "Clé gratuite : themoviedb.org → Paramètres → API.";
            return keyStatus.isEmpty() ? base : keyStatus + "\n" + base;
        };
        p.about = explain.get();
        if (keyPage == null && !keyChecking) {
            Opt o = act(p, "Envoyer depuis mon téléphone", "", this::startKeyPage);
            o.about = explain;
        }
        Opt t = act(p, "Taper la clé", "", this::askKey);
        t.about = explain;
        if (!Prefs.tmdbKey().isEmpty()) {
            Opt r = act(p, "Supprimer la clé", "", () -> new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Supprimer la clé TMDB ?")
                    .setPositiveButton("Supprimer", (d, w) -> { Prefs.setTmdbKey(""); keyStatus = ""; rebuildKeeping(null); })
                    .setNegativeButton("Annuler", null).show());
            r.about = explain;
        }
    }

    private void startKeyPage() {
        stopKeyPage();
        java.net.InetAddress lan = KeyPage.lanAddress(this);
        if (lan == null) {
            keyStatus = "La TV n'est pas sur le réseau : tape la clé.";
        } else {
            try {
                keyPage = new KeyPage(lan, keyListener);
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                keyStatus = "En attente de ton téléphone (5 minutes).";
            } catch (java.io.IOException | RuntimeException e) {
                stopKeyPage();
                keyStatus = "Erreur : " + e.getMessage();
            }
        }
        rebuildKeeping(null);
    }

    private void stopKeyPage() {
        if (keyPage != null) keyPage.close();
        keyPage = null;
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private final KeyPage.Listener keyListener = new KeyPage.Listener() {
        @Override public void checking() {
            handler.post(() -> { keyChecking = true; keyStatus = "Vérification de la clé auprès de TMDB…"; rebuildKeeping(null); });
        }

        @Override public String accept(String key) { return saveKey(key); }

        @Override public void closed(KeyPage page, boolean expired) {
            handler.post(() -> {
                if (page != keyPage) return;
                stopKeyPage();
                if (expired) keyStatus = "Ce code a expiré.";
                rebuildKeeping(null);
            });
        }
    };

    /** Off the UI thread: checks the key with TMDB, saves it if it works. Null when saved, else why not. */
    private String saveKey(String pasted) {
        String key = Recs.cleanKey(pasted);
        String why = key.isEmpty() ? "Ce n'est pas une clé TMDB." : Recs.checkKey(key);
        if (why == null) Prefs.setTmdbKey(key);
        handler.post(() -> {
            keyChecking = false;
            if (why == null) {
                stopKeyPage();
                keyStatus = "Clé enregistrée. Les suggestions arrivent dans un instant.";
                Recs.get(this).refreshIfStale();
            } else {
                keyStatus = why;
            }
            rebuildKeeping(null);
        });
        return why;
    }

    private void askKey() {
        if (keyChecking) return;
        android.widget.EditText field = new android.widget.EditText(this);
        field.setSingleLine(true);
        field.setHint("Clé API ou jeton d'accès");
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Clé TMDB").setView(field)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    String text = field.getText().toString();
                    if (text.trim().isEmpty()) return;
                    keyChecking = true;
                    keyStatus = "Vérification de la clé auprès de TMDB…";
                    rebuildKeeping(null);
                    new Thread(() -> saveKey(text), "TmdbKey").start();
                })
                .setNegativeButton("Annuler", null).show();
    }

    private static android.graphics.Bitmap qrBitmap(boolean[][] modules) {
        int quiet = 2, n = modules.length + 2 * quiet, scale = 8;
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(n * scale, n * scale, android.graphics.Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFFFFFFFF);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        p.setColor(0xFF000000);
        for (int y = 0; y < modules.length; y++) {
            for (int x = 0; x < modules.length; x++) {
                if (modules[y][x]) c.drawRect((x + quiet) * scale, (y + quiet) * scale, (x + quiet + 1) * scale, (y + quiet + 1) * scale, p);
            }
        }
        return b;
    }

    // MARK: - City

    private List<String[]> cityResults = new ArrayList<>();
    private String cityStatus = "";

    private void fillCity(Category p) {
        p.about = cityStatus.isEmpty() ? "Ville actuelle : " + Prefs.city() + "." : cityStatus;
        act(p, "Chercher une ville…", "", this::askCity).about = () -> cityStatus.isEmpty() ? "Ville actuelle : " + Prefs.city() + "." : cityStatus;
        for (String[] city : cityResults) {
            act(p, city[0], "OK choisit cette ville.", () -> {
                Prefs.setCity(city[1], city[2], city[3]);
                HomeData.get(this).settingsChanged();
                cityResults = new ArrayList<>();
                cityStatus = "";
                toast("Ville : " + city[1]);
                closePage();
            });
        }
    }

    private void askCity() {
        android.widget.EditText field = new android.widget.EditText(this);
        field.setSingleLine(true);
        field.setHint("Nom de la ville");
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Ville").setView(field)
                .setPositiveButton("Chercher", (d, w) -> searchCity(field.getText().toString()))
                .setNegativeButton("Annuler", null).show();
    }

    private void searchCity(String name) {
        if (name.trim().isEmpty()) return;
        cityStatus = "Recherche…";
        rebuildKeeping(null);
        new Thread(() -> {
            List<String[]> found;
            String message;
            try {
                found = HomeData.findCity(name);
                message = found.isEmpty() ? "Aucune ville de ce nom." : "Choisis ta ville dans la liste.";
            } catch (Exception e) {
                found = new ArrayList<>();
                message = "Recherche impossible pour l'instant : " + e.getMessage();
            }
            List<String[]> result = found;
            String m = message;
            handler.post(() -> { cityResults = result; cityStatus = m; rebuildKeeping(null); });
        }, "CitySearch").start();
    }

    // MARK: - Home screen mode (MaTV or the TV's own)

    private boolean freeActive() {
        org.json.JSONObject s = HomeMode.state(this);
        return HomeMode.FREE.equals(s.optString("mode")) && !s.optBoolean("homeReverted");
    }

    private String modeLabel() {
        String busy = modeBusy();
        if (!busy.isEmpty()) return "En cours…";
        return freeActive() ? "MaTV" : "Origine";
    }

    private String modeBusy() {
        org.json.JSONObject s = HomeMode.state(this);
        long at = s.optLong("busyAt"), now = android.os.SystemClock.elapsedRealtime();
        return now < at || now - at > 10 * 60_000L ? "" : s.optString("busy");
    }

    private String modeAbout() {
        if (HomeMode.phone(this)) return HomeMode.phoneText();
        if (HomeMode.BRICKS) return HomeMode.bricksText();
        String busy = modeBusy();
        if (!busy.isEmpty()) return busy;
        String error = HomeMode.state(this).optString("error");
        if (!error.isEmpty()) return error;
        return freeActive() ? "MaTV est ton écran d'accueil. L'accueil d'origine remet la TV comme à la sortie d'usine."
                : "L'accueil d'origine de la TV est actif. Choisis MaTV pour l'accueil sans pub.";
    }

    private void fillHomeMode(Category p) {
        p.about = modeAbout();
        Opt free = act(p, "MaTV", "", () -> requestMode(HomeMode.FREE));
        free.live = () -> freeActive() && modeBusy().isEmpty() ? "✓" : "";
        free.about = this::modeAbout;
        Opt orig = act(p, "Accueil d'origine", "", () -> requestMode(HomeMode.ORIGINAL));
        orig.live = () -> !freeActive() && modeBusy().isEmpty() ? "✓" : "";
        orig.about = this::modeAbout;
    }

    private long modeAskedAt;

    private void requestMode(String mode) {
        if (HomeMode.phone(this) || (HomeMode.BRICKS && mode.equals(HomeMode.FREE))) return;
        boolean already = mode.equals(HomeMode.FREE) ? freeActive()
                : HomeMode.ORIGINAL.equals(HomeMode.state(this).optString("mode", HomeMode.ORIGINAL));
        long now = android.os.SystemClock.uptimeMillis();
        if (already || !modeBusy().isEmpty() || now - modeAskedAt < 10_000) return;
        modeAskedAt = now;
        HomeMode.request(this, mode);
        toast(mode.equals(HomeMode.FREE) ? "Passage à MaTV… Accepte le débogage sur la TV si elle le demande."
                : "Retour à l'accueil d'origine…");
    }

    // MARK: - Remote buttons

    private void fillRemote(Category p) {
        String[] labels = remoteLabels();
        List<String> pkgs = new ArrayList<>(), names = new ArrayList<>();
        loadRemoteApps(pkgs, names);
        String[] values = pkgs.toArray(new String[0]), shown = names.toArray(new String[0]);
        String note = RemoteKeys.XIAOMI ? "« Xiaomi par défaut » : le bouton fait ce qu'il faisait d'origine. ◀ ▶ change l'appli."
                : "◀ ▶ change l'appli ouverte par le bouton.";
        if (!RemoteKeys.FILTER_SAFE) note += " Sur cette TV, les boutons marchent seulement sur l'accueil MaTV.";
        p.about = note;
        for (int i = 0; i < Prefs.KEYS.length && i < labels.length; i++) {
            final int code = Prefs.KEYS[i];
            Opt o = choice(p, labels[i], note, values, shown, () -> Prefs.keyApp(code), v -> {
                Prefs.setKeyApp(code, v);
                HomeMode.ensureRemoteService(this, 3_000);
            });
            o.live = () -> { int at = pkgs.indexOf(Prefs.keyApp(code)); return at >= 0 ? names.get(at) : "Non installée"; };
        }
    }

    private static String[] remoteLabels() {
        if (RemoteKeys.GOOGLE_STREAMER) return new String[] {"Bouton YouTube", "Bouton Netflix", "Bouton ★"};
        if (RemoteKeys.JVC_SKYWORTH) return new String[] {"Entrées", "Bouton Réglages"};
        if (RemoteKeys.HISENSE) return new String[] {"Bouton Prime Video", "Bouton YouTube", "Bouton Disney+",
                "Bouton Toutes les apps", "Bouton Réglages", "Bouton Profil"};
        if (RemoteKeys.STEM_NETFLIX) return new String[] {"Bouton Netflix"};
        if (RemoteKeys.KTC) return new String[] {"Bouton Tabii"};
        if (RemoteKeys.XIAOMI_PATCHWALL_ONLY) return new String[] {"Bouton PatchWall"};
        if (RemoteKeys.XIAOMI) return new String[] {"Bouton Mi", "Bouton YouTube", "Bouton PatchWall",
                "Bouton Okko", "Bouton VK", "Bouton Rutube", "Bouton KION", "Bouton Wink", "Bouton IVI"};
        if (RemoteKeys.SONY) return new String[] {"Bouton BRAVIA CORE", "Bouton Netflix", "Bouton Disney+",
                "Bouton Prime Video", "Bouton YouTube", "Bouton Crunchyroll", "Bouton Google Play", "Bouton SonyLIV"};
        return new String[] {"Bouton Netflix", "Bouton YouTube", "Bouton Prime Video", "Bouton TCL Channel", "Bouton Réglages"};
    }

    private void loadRemoteApps(List<String> pkgs, List<String> names) {
        List<String[]> found = new ArrayList<>();
        for (String[] app : HomeApps.launchable(this)) if (!app[0].equals(getPackageName())) found.add(app);
        java.util.Collections.sort(found, (a, b) -> a[1].toLowerCase(java.util.Locale.ROOT).compareTo(b[1].toLowerCase(java.util.Locale.ROOT)));
        pkgs.add("");
        names.add(RemoteKeys.GOOGLE_STREAMER ? "Google par défaut" : RemoteKeys.SONY ? "Sony par défaut"
                : RemoteKeys.XIAOMI ? "Xiaomi par défaut" : "Rien");
        if (RemoteKeys.JVC_SKYWORTH) {
            pkgs.add(Prefs.TV_SHORTCUTS); names.add("Entrées");
            pkgs.add(Prefs.QUICK_SETTINGS); names.add("Réglages rapides");
        }
        if (RemoteKeys.TCL) {
            pkgs.add(Prefs.QUICK_PANEL); names.add("Réglages rapides");
            boolean listed = false;
            for (String[] f : found) listed |= f[0].equals("com.android.tv.settings");
            if (!listed) { pkgs.add("com.android.tv.settings"); names.add("Réglages de la TV"); }
        }
        if (RemoteKeys.XIAOMI) { pkgs.add(HomeMode.PKG); names.add("Accueil MaTV"); }
        if (RemoteKeys.HISENSE || RemoteKeys.GOOGLE_STREAMER) { pkgs.add(Prefs.ALL_APPS); names.add("Toutes les apps"); }
        for (String[] f : found) { pkgs.add(f[0]); names.add(f[1]); }
    }

    // MARK: - Sleep timer

    private String sleepLabel() {
        int left = SleepTimer.minutesLeft(this);
        return left == 0 ? "Désactivée" : "Encore " + SleepTimer.duration(left);
    }

    private void fillSleep(Category p) {
        int[] mins = {0, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240};
        String[] values = new String[mins.length], labels = new String[mins.length];
        for (int i = 0; i < mins.length; i++) {
            values[i] = String.valueOf(mins[i]);
            labels[i] = mins[i] == 0 ? "Désactivée" : SleepTimer.duration(mins[i]);
        }
        Opt t = choice(p, "Éteindre dans", "◀ ▶ choisit le délai. La TV s'éteint toute seule à la fin.", values, labels,
                () -> {
                    int left = SleepTimer.minutesLeft(this);
                    for (int m : mins) if (m >= left) return String.valueOf(m);
                    return "240";
                }, v -> SleepTimer.set(this, Integer.parseInt(v)));
        t.live = this::sleepLabel;
        toggle(p, "Afficher le temps restant", "Une petite pastille avec les minutes restantes, par-dessus les applis.",
                Prefs::sleepShown, v -> { Prefs.setSleepShown(v); SleepOverlay.update(this); });
        String[] places = new String[SleepOverlay.PLACES], placeNames = new String[SleepOverlay.PLACES];
        for (int i = 0; i < places.length; i++) { places[i] = String.valueOf(i); placeNames[i] = SleepOverlay.placeName(i); }
        Opt where = choice(p, "Position", "Où se place la pastille.", places, placeNames,
                () -> String.valueOf(Prefs.sleepPlace()), v -> { Prefs.setSleepPlace(Integer.parseInt(v)); SleepOverlay.update(this); });
        where.dimmed = () -> !Prefs.sleepShown();
    }

    // MARK: - Screensaver preview

    private void showPreview() {
        closePreview();
        preview = new DreamView(this);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        root.removeView(preview);
        preview = null;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (preview != null) {
            if (e.getAction() == KeyEvent.ACTION_UP) closePreview();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void toast(String text) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show();
    }

    // MARK: - Updates

    private long checkAskedAt, installAskedAt;

    private String updateLabel() {
        org.json.JSONObject o = Updates.state(this);
        if (System.currentTimeMillis() - checkAskedAt < 10_000 && o.optLong("at") < checkAskedAt) return Ui.tr("Checking…");
        return Updates.summary(o, Updates.installedVersion(this));
    }

    private void updateNow() {
        org.json.JSONObject o = Updates.state(this);
        long now = System.currentTimeMillis();
        if (Updates.ready(o, Updates.installedVersion(this))) {
            if (now - installAskedAt < 5000) return;
            installAskedAt = now;
            ModeJob.schedule(this, ModeJob.INSTALL, "", 0);
        } else {
            if (Updates.busy(o) || now - checkAskedAt < 10_000) return;
            checkAskedAt = now;
            ModeJob.schedule(this, ModeJob.UPDATE, "", 0);
        }
        refreshValues();
    }

    /** Continue Watching needs READ_TV_LISTINGS: through the TV's own ADB first, else Android asks. */
    private void askWatchGrant() {
        if (!Prefs.topContent().equals(Prefs.TOP_CONTINUE) || WatchNext.allowed(this)) return;
        HomeMode.requestWatchGrant(this);
        handler.postDelayed(() -> {
            if (!isFinishing() && Prefs.topContent().equals(Prefs.TOP_CONTINUE) && !WatchNext.allowed(this)) {
                requestPermissions(new String[] {WatchNext.PERMISSION}, 1);
            }
        }, 4000);
    }

    // MARK: - Pages

    private void showCategories(int focus) {
        for (Category p : stack) if (p.onClose != null) p.onClose.run();
        stack.clear();
        open = -1;
        pageTitle.setText("Réglages");
        sideImage.setVisibility(View.GONE);
        sideSymbol.setVisibility(View.VISIBLE);
        list.removeAllViews();
        for (int i = 0; i < categories.size(); i++) {
            final int at = i;
            Category cat = categories.get(i);
            Row r = new Row(cat.name, null, true);
            r.symbol = cat.symbol;
            r.setOnFocusChangeListener((v, has) -> {
                r.focus(has);
                if (has) side(cat.symbol, cat.name, cat.about, null);
            });
            r.setOnClickListener(v -> showCategory(at, 0));
            r.setOnKeyListener((v, code, e) -> {
                if (e.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT) { showCategory(at, 0); return true; }
                return false;
            });
            list.addView(r, rowParams());
        }
        scroll.scrollTo(0, 0);
        list.getChildAt(focus).requestFocus();
    }

    private void showCategory(int at, int focus) {
        open = at;
        lastCategory = at;
        stack.clear();
        stack.add(categories.get(at));
        render(focus);
    }

    private void openPage(Category sub) {
        if (stack.isEmpty()) return;
        stack.get(stack.size() - 1).focus = focusedRow();
        stack.add(sub);
        render(0);
    }

    private void closePage() {
        if (stack.size() <= 1) { showCategories(lastCategory); return; }
        Category gone = stack.remove(stack.size() - 1);
        if (gone.onClose != null) gone.onClose.run();
        render(stack.get(stack.size() - 1).focus);
    }

    /** The open page again (after a change that adds or moves rows), with the focus on `key`'s row if given. */
    private void rebuildKeeping(String key) {
        if (stack.isEmpty()) return;
        int focus = focusedRow();
        Category p = stack.get(stack.size() - 1);
        render(focus);
        if (key == null) return;
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (v instanceof Row && ((Row) v).key != null && ((Row) v).key.equals(key)) { v.requestFocus(); break; }
        }
    }

    /** Draws the top page: its rows, built again when it has a builder. */
    private void render(int focus) {
        Category page = stack.get(stack.size() - 1);
        if (page.fill != null) {
            page.opts.clear();
            page.image = null;
            page.fill.accept(page);
        }
        Category cat = stack.get(0);
        String symbol = page.symbol != null ? page.symbol : cat.symbol;
        pageTitle.setText(stack.size() > 1 ? cat.name + "  ›  " + page.name : page.name);
        int scrollWas = scroll.getScrollY();
        list.removeAllViews();
        for (Opt o : page.opts) {
            Row r = new Row(o.title, o, o.action != null && o.chevron);
            r.key = o.key;
            r.setOnFocusChangeListener((v, has) -> {
                r.focus(has);
                if (has) {
                    String about = o.about != null ? o.about.get() : !o.desc.isEmpty() ? o.desc : page.about;
                    side(symbol, page == cat ? o.title : page.name, about, page.image);
                }
            });
            r.setOnClickListener(v -> {
                if (o.dimmed != null && o.dimmed.getAsBoolean()) return;
                if (o.action != null) o.action.run();
                else change(o, 1);
            });
            r.setOnKeyListener((v, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int dir = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                if (o.dimmed != null && o.dimmed.getAsBoolean()) return true;
                if (o.step != null) { o.step.accept(dir); return true; }
                if (o.action != null) {
                    if (dir > 0 && o.chevron) o.action.run();
                    return true;
                }
                change(o, dir);
                return true;
            });
            list.addView(r, rowParams());
        }
        if (list.getChildCount() == 0) return;
        list.getChildAt(Math.max(0, Math.min(focus, list.getChildCount() - 1))).requestFocus();
        if (focus == 0) scroll.scrollTo(0, 0); else scroll.scrollTo(0, scrollWas);
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, px(84));
        p.bottomMargin = px(6);
        return p;
    }

    private void side(String symbol, String name, String about, android.graphics.Bitmap image) {
        if (image != null) {
            sideImage.setImageBitmap(image);
            sideImage.setVisibility(View.VISIBLE);
            sideSymbol.setVisibility(View.GONE);
        } else {
            sideImage.setVisibility(View.GONE);
            sideSymbol.setVisibility(View.VISIBLE);
            sideSymbol.set(symbol);
        }
        sideName.setText(name);
        sideText.setText(about);
    }

    private void change(Opt o, int dir) {
        if (o.values == null) return;
        int at = java.util.Arrays.asList(o.values).indexOf(o.get.get());
        int n = o.values.length;
        o.set.set(o.values[((at < 0 ? 0 : at) + dir + n) % n]);
        HomeData.get(this).settingsChanged();
        if (o.title.equals("Thème")) {   // the page itself follows the theme
            recreateKeeping(focusedRow());
            return;
        }
        refreshValues();   // other rows can follow (the clock and caption are set per screensaver content)
    }

    private int focusedRow() {
        for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).hasFocus()) return i;
        return 0;
    }

    private void recreateKeeping(int focus) {
        palette();
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {bg0, bg1}));
        pageTitle.setTextColor(ink);
        sideName.setTextColor(ink);
        sideText.setTextColor(dim);
        sideSymbol.invalidate();
        render(focus);
    }

    private void refreshValues() {
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (v instanceof Row) ((Row) v).update();
        }
    }

    @Override public void onBackPressed() {
        if (!stack.isEmpty()) {
            closePage();
            return;
        }
        super.onBackPressed();
    }

    // MARK: - Views

    private int px(float v) { return Math.round(v * k); }

    private TextView text(float size, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
        t.setTextColor(color);
        t.setTypeface(face != null ? face : Typeface.DEFAULT);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** The large symbol on the left, drawn from the app's Material Symbols set at its size. */
    private final class SymbolView extends View {
        private String name;

        SymbolView(Context c) { super(c); }

        void set(String n) {
            if (n.equals(name)) return;
            name = n;
            setAlpha(0);
            animate().alpha(1).setDuration(220).start();
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            if (name == null) return;
            float pt = Symbols.pointSizeForHeight(getContext(), name, getHeight() * 0.62f);
            Symbols.draw(getContext(), c, name, getWidth() / 2f, getHeight() / 2f, pt, light ? 0xFF3A3A3C : 0xFFE5E5EA);
        }
    }

    /**
     * A row: title on the left, then the value (gray text), a switch, or a chevron. At rest it's text on the page; with
     * the focus it's a white plate with a soft shadow, a touch larger.
     */
    private final class Row extends FrameLayout {
        final Opt opt;
        final boolean opens;
        String key;
        String symbol;
        private final TextView title, value;
        private final SwitchView sw;
        private final ChevronView chevron;
        private boolean focused;
        private final GradientDrawable plateBg = new GradientDrawable();

        Row(String text, Opt o, boolean opens) {
            super(MaTVSettingsActivity.this);
            this.opt = o;
            this.opens = opens;
            setFocusable(true);
            setClipToPadding(false);
            setPadding(px(34), 0, px(30), 0);
            plateBg.setCornerRadius(px(16));
            plateBg.setColor(0x00000000);
            setBackground(plateBg);
            title = text(30, ink, Ui.regular);
            addView(title, new LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
            LinearLayout end = new LinearLayout(getContext());
            end.setGravity(Gravity.CENTER_VERTICAL);
            value = text(28, dim, Ui.regular);
            end.addView(value);
            sw = new SwitchView(getContext());
            LinearLayout.LayoutParams swp = new LinearLayout.LayoutParams(px(76), px(44));
            end.addView(sw, swp);
            chevron = new ChevronView(getContext());
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(px(30), px(40));
            cp.leftMargin = px(14);
            end.addView(chevron, cp);
            addView(end, new LayoutParams(-2, -1, Gravity.END | Gravity.CENTER_VERTICAL));
            title.setText(text);
            ((LayoutParams) title.getLayoutParams()).rightMargin = px(320);
            setElevation(0);
            update();
        }

        void update() {
            boolean isSwitch = opt != null && opt.isSwitch;
            sw.setVisibility(isSwitch ? VISIBLE : GONE);
            chevron.setVisibility(opens ? VISIBLE : GONE);
            boolean off = opt != null && opt.dimmed != null && opt.dimmed.getAsBoolean();
            setAlpha(off ? 0.4f : 1f);
            if (opt != null && opt.live != null) {
                String v = opt.live.get();
                boolean arrows = focused && opt.values != null && !isSwitch;
                value.setText(arrows ? "‹   " + v + "   ›" : v);
                value.setVisibility(v.isEmpty() ? GONE : VISIBLE);
            } else if (opt != null && opt.values != null && !isSwitch) {
                int at = java.util.Arrays.asList(opt.values).indexOf(opt.get.get());
                String label = opt.labels[Math.max(0, at)];
                value.setText(focused ? "‹   " + label + "   ›" : label);
                value.setVisibility(VISIBLE);
            } else if (opt != null && opt.live != null) {
                value.setText(opt.live.get());
                value.setVisibility(VISIBLE);
            } else {
                value.setVisibility(GONE);
            }
            if (isSwitch) sw.set("on".equals(opt.get.get()), focused);
            title.setTextColor(focused ? plateInk : ink);
            title.setTypeface(focused ? Ui.medium : Ui.regular);
            value.setTextColor(focused ? plateDim : dim);
            chevron.invalidate();
        }

        void focus(boolean has) {
            focused = has;
            plateBg.setColor(has ? plate : 0x00000000);
            setElevation(has ? px(14) : 0);
            setTranslationZ(0);
            animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(160).start();
            update();
            if (has) {   // keep the focused row in view, with room around it
                int top = getTop() - px(170), bottom = getBottom() + px(120);
                if (top < scroll.getScrollY()) scroll.smoothScrollTo(0, Math.max(0, top));
                else if (bottom > scroll.getScrollY() + scroll.getHeight()) scroll.smoothScrollTo(0, bottom - scroll.getHeight());
            }
        }

        private final class ChevronView extends View {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            ChevronView(Context c) {
                super(c);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
            }

            @Override protected void onDraw(Canvas c) {
                p.setStrokeWidth(px(3));
                p.setColor(focused ? plateDim : faint);
                float cx = getWidth() / 2f, cy = getHeight() / 2f, s = px(8);
                c.drawLine(cx - s / 2, cy - s, cx + s / 2, cy, p);
                c.drawLine(cx + s / 2, cy, cx - s / 2, cy + s, p);
            }
        }
    }

    /** An iOS-like switch: a green track when on, a white knob that slides. */
    private final class SwitchView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float pos = -1;
        private boolean on, onPlate;

        SwitchView(Context c) { super(c); }

        void set(boolean on, boolean onPlate) {
            boolean animate = pos >= 0 && this.on != on;
            this.on = on;
            this.onPlate = onPlate;
            if (!animate) {
                pos = on ? 1 : 0;
                invalidate();
                return;
            }
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(pos, on ? 1 : 0);
            a.setDuration(180);
            a.addUpdateListener(x -> { pos = (float) x.getAnimatedValue(); invalidate(); });
            a.start();
        }

        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth(), pad = px(3);
            r.set(0, 0, w, h);
            int off = onPlate || light ? 0xFFE3E3E8 : 0xFF39393D;
            p.setColor(blend(off, 0xFF34C759, pos));
            c.drawRoundRect(r, h / 2, h / 2, p);
            float d = h - 2 * pad, x = pad + (w - 2 * pad - d) * pos;
            p.setColor(0x26000000);
            c.drawCircle(x + d / 2, h / 2 + px(1.5f), d / 2, p);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(x + d / 2, h / 2, d / 2, p);
        }
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }
}
