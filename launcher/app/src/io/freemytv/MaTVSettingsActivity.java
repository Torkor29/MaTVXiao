package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
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
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * MaTV's Home Settings: categories on the left (Apparence, Accueil, Suggestions, Écran de veille, Widgets, Système),
 * their options on the right, each with a one-line explanation and its value. ◀ ▶ change a value, OK opens the
 * options that have their own screen (the city, the TMDB key, the remote's buttons…), which still live in
 * SettingsActivity and come back here with Back.
 * It runs in :home like the home screen, so a change bumps Prefs.version there and the home screen redraws itself.
 */
public class MaTVSettingsActivity extends Activity {

    /** One option: a choice (◀ ▶ through `values`), a switch, or an action (OK opens something). */
    private static final class Opt {
        final String title, desc;
        String[] values, labels;
        Getter get;
        Setter set;
        Runnable action;

        Opt(String title, String desc) { this.title = title; this.desc = desc; }
    }

    private interface Getter { String get(); }
    private interface Setter { void set(String v); }

    private static final class Category {
        final String icon, name, about;
        final List<Opt> opts = new ArrayList<>();
        Category(String icon, String name, String about) { this.icon = icon; this.name = name; this.about = about; }
    }

    private final List<Category> categories = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout catColumn, optColumn;
    private ScrollView optScroll;
    private TextView heading, about;
    private int current;
    private float k;
    private Typeface bold, medium, regular;

    private static final int TEXT = 0xFFF5F5F7, DIM = 0x99F5F5F7, FAINT = 0x66F5F5F7;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        bold = Ui.semibold != null ? Ui.semibold : Typeface.DEFAULT_BOLD;
        medium = Ui.medium != null ? Ui.medium : Typeface.DEFAULT;
        regular = Ui.regular != null ? Ui.regular : Typeface.DEFAULT;
        build();

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFF0B0C14, 0xFF07080C, accentWash()}));
        setContentView(root);

        LinearLayout page = new LinearLayout(this);
        page.setPadding(px(80), px(64), px(80), px(48));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // Left: the title and the categories, on a glass column.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(px(28), px(36), px(28), px(28));
        left.setBackground(glass(0x14FFFFFF, 0x24FFFFFF, 40));
        page.addView(left, new LinearLayout.LayoutParams(px(520), -1));
        TextView title = text(56, TEXT, bold);
        title.setText("Réglages");
        title.setPadding(px(16), 0, 0, px(6));
        left.addView(title);
        TextView sub = text(22, DIM, regular);
        sub.setText("Tout ce que tu peux changer sur l'accueil");
        sub.setPadding(px(16), 0, 0, px(28));
        left.addView(sub);
        catColumn = new LinearLayout(this);
        catColumn.setOrientation(LinearLayout.VERTICAL);
        left.addView(catColumn);
        for (int i = 0; i < categories.size(); i++) catColumn.addView(categoryRow(i));

        // Right: the category's name, what it's for, then its options.
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(px(56), px(28), 0, 0);
        page.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
        heading = text(44, TEXT, bold);
        right.addView(heading);
        about = text(24, DIM, regular);
        about.setSingleLine(false);
        about.setPadding(0, px(6), 0, px(26));
        right.addView(about);
        optScroll = new ScrollView(this);
        optScroll.setVerticalScrollBarEnabled(false);
        optScroll.setClipToPadding(false);
        optScroll.setPadding(px(12), px(12), px(12), px(40));
        optColumn = new LinearLayout(this);
        optColumn.setOrientation(LinearLayout.VERTICAL);
        optScroll.addView(optColumn, new ScrollView.LayoutParams(-1, -2));
        right.addView(optScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView hint = text(20, FAINT, regular);
        hint.setText("◀ ▶ pour changer · OK pour ouvrir · Retour pour revenir");
        right.addView(hint);

        showCategory(0);
        catColumn.getChildAt(0).requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        if (optColumn != null) refreshValues();   // back from one of SettingsActivity's screens
    }

    private int accentWash() {
        int a = Prefs.accentColor();
        return a == 0 ? 0xFF161226 : Color.rgb(Color.red(a) / 6, Color.green(a) / 6, Color.blue(a) / 6);
    }

    // MARK: - The options

    private void build() {
        Context c = this;
        Category look = add("🎨", "Apparence", "Le style de l'accueil : thème, verre, couleurs et taille des applis.");
        choice(look, "Thème", "Automatique suit le lever et le coucher du soleil.",
                new String[] {"auto", "light", "dark"}, new String[] {"Automatique", "Clair", "Sombre"},
                Prefs::theme, v -> { Prefs.setTheme(v); SleepOverlay.update(c); });
        choice(look, "Style du verre", "Verre liquide : reflets et réfraction. Givré : plus doux. Opaque : panneaux pleins.",
                Prefs.GLASS_STYLES, new String[] {"Verre liquide", "Givré", "Opaque"}, Prefs::glassStyle, Prefs::setGlassStyle);
        choice(look, "Teinte du verre", "Plus la teinte est forte, plus les panneaux sont opaques et le texte lisible.",
                Prefs.GLASS_TINTS, new String[] {"Légère", "Normale", "Forte"}, Prefs::glassTint, Prefs::setGlassTint);
        choice(look, "Couleur d'accent", "Une touche de couleur mélangée au verre.",
                Prefs.ACCENTS, new String[] {"Neutre", "Bleu", "Violet", "Rose", "Or", "Vert"}, Prefs::accent, Prefs::setAccent);
        choice(look, "Taille des applis", "La taille des logos dans le dock, en bas de l'accueil.",
                Prefs.DOCK_SIZES, new String[] {"Compacte", "Normale", "Grande"}, Prefs::dockSize, Prefs::setDockSize);
        toggle(look, "Sons de navigation", "Un petit clic à chaque déplacement.", Prefs::sounds, Prefs::setSounds);

        Category home = add("🏠", "Accueil", "Ce qui s'affiche, et dans quel ordre.");
        choice(home, "Haut de l'écran", "Suggestions de films et séries, ce que tu regardais (toutes plateformes), ou une grande horloge.",
                Prefs.TOP_CONTENT, new String[] {"Suggestions", "Vous regardiez", "Horloge"}, Prefs::topContent,
                v -> { Prefs.setTopContent(v); askWatchGrant(); });
        action(home, "Applis du dock", "Choisis les applis du bas et leur ordre.", () -> openOld("main:3"));
        action(home, "Boutons du haut", "Les petits boutons ronds en haut à droite.", () -> openOld("main:4"));
        toggle(home, "Entrées dans le tiroir", "TV en direct et les ports HDMI, en bas de l'accueil (flèche ▼).",
                Prefs::drawerInputs, Prefs::setDrawerInputs);
        toggle(home, "Raccourcis dans le tiroir", "Toutes les apps et Réglages de la TV (Réglages reste toujours là).",
                Prefs::drawerShortcuts, Prefs::setDrawerShortcuts);
        toggle(home, "Icônes d'état", "Wi‑Fi, casque Bluetooth et clé USB en haut à gauche.", Prefs::statusIcons, Prefs::setStatusIcons);
        toggle(home, "Applis Android", "Montre aussi les applis pour téléphone dans Toutes les apps.", Prefs::androidApps, Prefs::setAndroidApps);
        action(home, "Applis récentes", "Fermer les applis ouvertes depuis l'accueil.", () -> openOld("row:recents"));

        Category picks = add("🍿", "Suggestions", "Les tendances et nouveautés de tes services, en France.");
        choice(picks, "Présentation", "Affiche : l'image en plein écran et une rangée de vignettes, comme Netflix. Cartes : le style Apple TV.",
                Prefs.TOP_STYLES, new String[] {"Affiche (Netflix)", "Cartes"}, Prefs::topStyle, Prefs::setTopStyle);
        choice(picks, "Titres par onglet", "Combien de suggestions sont affichées à la fois.",
                new String[] {"3", "4", "5", "6"}, new String[] {"3", "4", "5", "6"},
                () -> String.valueOf(Prefs.cards()), v -> Prefs.setCards(Integer.parseInt(v)));
        action(picks, "Services", "Netflix, Prime Video, Disney+… : d'où viennent les suggestions.", () -> openOld("row:picks"));
        action(picks, "Applis de « Vous regardiez »", "Quelles applis remontent ce que tu as commencé.", () -> openOld("row:watch"));
        action(picks, "Clé TMDB", "La clé qui donne accès aux infos des films. Déjà intégrée.", () -> openOld("row:tmdb"));

        Category saver = add("🖼️", "Écran de veille", "Ce qui s'affiche quand la TV reste inactive.");
        choice(saver, "Contenu", "Mode art : uniquement des œuvres de musées, en plein écran. Sinon les suggestions.",
                Prefs.SAVER_MODES, new String[] {"Suggestions", "Mode art (tableaux)"}, Prefs::saverMode, Prefs::setSaverMode);
        String[] secs = new String[Prefs.SAVER_SECONDS.length];
        for (int i = 0; i < secs.length; i++) secs[i] = String.valueOf(Prefs.SAVER_SECONDS[i]);
        choice(saver, "Durée par image", "Combien de temps chaque image reste à l'écran.",
                secs, new String[] {"15 s", "30 s", "1 min", "3 min", "10 min"},
                () -> String.valueOf(Prefs.saverSeconds()), v -> Prefs.setSaverSeconds(Integer.parseInt(v)));
        toggle(saver, "Horloge", "L'heure, la date et la météo en haut à droite (réglé à part pour chaque contenu).", Prefs::saverClock, Prefs::setSaverClock);
        toggle(saver, "Légende", "Le titre et l'auteur de chaque image, en bas à gauche (réglé à part pour chaque contenu).", Prefs::saverCaption, Prefs::setSaverCaption);
        toggle(saver, "Regarder depuis l'écran de veille", "OK ouvre le film ou la série affichés.", Prefs::saverWatch, Prefs::setSaverWatch);
        action(saver, "Aperçu", "Voir l'écran de veille maintenant.", () -> openOld("main:1"));

        Category widgets = add("⛅", "Widgets", "Les infos du tiroir en bas de l'accueil.");
        toggle(widgets, "Météo", "Le temps qu'il fait et les prochaines heures.", Prefs::weather, Prefs::setWeather);
        action(widgets, "Ville", "Pour la météo et le lever du soleil.", () -> openOld("row:city"));
        choice(widgets, "Prévisions", "Heure par heure, ou les jours suivants.",
                new String[] {"hours", "days"}, new String[] {"Prochaines heures", "Prochains jours"}, Prefs::forecast, Prefs::setForecast);
        action(widgets, "Lecture en cours", "La musique ou le podcast qui joue, affiché sur l'accueil.", () -> openOld("row:playing"));
        toggle(widgets, "Cours des cryptos", "Bitcoin, Ethereum et Solana dans le tiroir.", Prefs::quotes, Prefs::setQuotes);

        Category sys = add("⚙️", "Système", "Langue, télécommande, mises à jour et retour à l'ancienne TV.");
        action(sys, "Écran d'accueil", "Passer de MaTV à l'accueil d'origine (Original), et inversement.", () -> openOld("main:0"));
        action(sys, "Langue", "La langue de l'accueil.", () -> openOld("row:language"));
        choice(sys, "Température", "", new String[] {"auto", "C", "F"}, new String[] {"Automatique", "°C", "°F"},
                Prefs::temperature, Prefs::setTemperature);
        choice(sys, "Horloge", "", new String[] {"auto", "24", "12"}, new String[] {"Automatique", "24 heures", "12 heures"},
                Prefs::clockFormat, Prefs::setClockFormat);
        action(sys, "Boutons de la télécommande", "Ce qu'ouvrent les boutons YouTube, Netflix, PatchWall…", () -> openOld("main:5"));
        action(sys, "Minuterie de veille", "Éteindre la TV après un délai.", () -> openOld("main:6"));
        action(sys, "Mises à jour", "Chercher une nouvelle version.", () -> openOld("row:updates"));
        action(sys, "Réglages de la TV", "Image, son, réseau, comptes…", () -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); } catch (RuntimeException ignored) {
                startActivity(new Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.MainSettings"));
            }
        });
        action(sys, "Tous les réglages (liste complète)", "L'ancienne liste, avec chaque option.", () -> openOld("row:all"));
    }

    private Category add(String icon, String name, String about) {
        Category c = new Category(icon, Ui.tr(name), Ui.tr(about));
        categories.add(c);
        return c;
    }

    private void choice(Category cat, String title, String desc, String[] values, String[] labels, Getter get, Setter set) {
        Opt o = new Opt(Ui.tr(title), Ui.tr(desc));
        o.values = values;
        o.labels = new String[labels.length];
        for (int i = 0; i < labels.length; i++) o.labels[i] = Ui.tr(labels[i]);
        o.get = get;
        o.set = set;
        cat.opts.add(o);
    }

    private interface BoolGet { boolean get(); }
    private interface BoolSet { void set(boolean v); }

    private void toggle(Category cat, String title, String desc, BoolGet get, BoolSet set) {
        choice(cat, title, desc, new String[] {"off", "on"}, new String[] {"Non", "Oui"},
                () -> get.get() ? "on" : "off", v -> set.set(v.equals("on")));
    }

    private void action(Category cat, String title, String desc, Runnable r) {
        Opt o = new Opt(Ui.tr(title), Ui.tr(desc));
        o.action = r;
        cat.opts.add(o);
    }

    private void openOld(String where) {
        startActivity(new Intent(this, SettingsActivity.class).putExtra("open", where)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
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

    // MARK: - Views

    private View categoryRow(int i) {
        Category cat = categories.get(i);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(18), px(16), px(18), px(16));
        row.setFocusable(true);
        row.setBackground(states(20));
        TextView icon = text(34, TEXT, regular);
        icon.setText(cat.icon);
        icon.setPadding(0, 0, px(18), 0);
        row.addView(icon);
        TextView name = text(30, TEXT, medium);
        name.setText(cat.name);
        row.addView(name);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = px(6);
        row.setLayoutParams(lp);
        row.setOnFocusChangeListener((v, has) -> {
            if (has && current != i) showCategory(i);
            for (int j = 0; j < catColumn.getChildCount(); j++) catColumn.getChildAt(j).setActivated(j == current);
        });
        row.setOnClickListener(v -> focusFirstOption());
        row.setOnKeyListener((v, code, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT) { focusFirstOption(); return true; }
            return false;
        });
        return row;
    }

    private void focusFirstOption() {
        if (optColumn.getChildCount() > 0) optColumn.getChildAt(0).requestFocus();
    }

    private void showCategory(int i) {
        current = i;
        Category cat = categories.get(i);
        heading.setText(cat.icon + "  " + cat.name);
        about.setText(cat.about);
        optColumn.removeAllViews();
        for (Opt o : cat.opts) optColumn.addView(optionRow(o));
        optScroll.scrollTo(0, 0);
    }

    private View optionRow(Opt o) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(30), px(20), px(30), px(20));
        row.setFocusable(true);
        row.setBackground(states(26));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(29, TEXT, medium);
        t.setText(o.title);
        texts.addView(t);
        if (!o.desc.isEmpty()) {
            TextView d = text(21, DIM, regular);
            d.setText(o.desc);
            d.setSingleLine(false);
            d.setMaxLines(2);
            d.setPadding(0, px(4), 0, 0);
            texts.addView(d);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = text(26, TEXT, medium);
        value.setGravity(Gravity.CENTER);
        value.setPadding(px(22), px(10), px(22), px(10));
        value.setBackground(glass(0x1FFFFFFF, 0x33FFFFFF, 30));
        row.addView(value, new LinearLayout.LayoutParams(-2, -2));
        row.setTag(new Object[] {o, value});
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = px(10);
        row.setLayoutParams(lp);
        fill(o, value, false);
        row.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.015f : 1f).scaleY(has ? 1.015f : 1f).setDuration(140).start();
            fill(o, value, has);
        });
        row.setOnClickListener(v -> {
            if (o.action != null) o.action.run();
            else change(o, value, 1);
        });
        row.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (o.action != null || o.values == null) {
                    if (code == KeyEvent.KEYCODE_DPAD_LEFT) { catColumn.getChildAt(current).requestFocus(); return true; }
                    return true;
                }
                change(o, value, code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            }
            return false;
        });
        return row;
    }

    private void change(Opt o, TextView value, int dir) {
        if (o.values == null) return;
        int at = java.util.Arrays.asList(o.values).indexOf(o.get.get());
        int n = o.values.length;
        o.set.set(o.values[((at < 0 ? 0 : at) + dir + n) % n]);
        HomeData.get(this).settingsChanged();
        refreshValues();   // other rows can follow (the clock and caption are set per screensaver content)
        fill(o, value, true);
    }

    /** The value pill: "‹ Value ›" on a choice that has focus, "›" on an action. */
    private void fill(Opt o, TextView value, boolean focused) {
        if (o.action != null) {
            value.setText("›");
            value.setBackground(null);
            return;
        }
        int at = java.util.Arrays.asList(o.values).indexOf(o.get.get());
        String label = o.labels[Math.max(0, at)];
        value.setText(focused ? "‹  " + label + "  ›" : label);
        boolean on = o.values.length == 2 && o.values[1].equals("on");
        int fillColor = on && "on".equals(o.get.get()) ? 0x5530D158 : focused ? 0x33FFFFFF : 0x1FFFFFFF;
        value.setBackground(glass(fillColor, 0x33FFFFFF, 30));
    }

    private void refreshValues() {
        for (int i = 0; i < optColumn.getChildCount(); i++) {
            View v = optColumn.getChildAt(i);
            Object[] tag = (Object[]) v.getTag();
            if (tag != null) fill((Opt) tag[0], (TextView) tag[1], v.hasFocus());
        }
    }

    @Override public void onBackPressed() {
        View f = getCurrentFocus();
        if (f != null && f.getParent() == optColumn) {
            catColumn.getChildAt(current).requestFocus();
            return;
        }
        super.onBackPressed();
    }

    // MARK: - Helpers

    private int px(float v) { return Math.round(v * k); }

    private TextView text(float size, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    private GradientDrawable glass(int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(px(radius));
        if (stroke != 0) g.setStroke(Math.max(1, px(1.5f)), stroke);
        return g;
    }

    /** Focused: bright glass with a white rim; activated (the open category): faint glass; else nothing. */
    private android.graphics.drawable.StateListDrawable states(float radius) {
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        int accent = Prefs.accentColor();
        int rim = accent == 0 ? 0xE6FFFFFF : accent;
        s.addState(new int[] {android.R.attr.state_focused}, glass(0x3DFFFFFF, rim, radius));
        s.addState(new int[] {android.R.attr.state_activated}, glass(0x1AFFFFFF, 0x26FFFFFF, radius));
        s.addState(new int[] {}, glass(0x00000000, 0, radius));
        return s;
    }
}
