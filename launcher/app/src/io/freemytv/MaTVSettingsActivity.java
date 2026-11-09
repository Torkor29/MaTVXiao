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
        final String title, desc;
        String[] values, labels;
        Getter get;
        Setter set;
        Runnable action;
        boolean isSwitch;

        Opt(String title, String desc) { this.title = title; this.desc = desc; }
    }

    private interface Getter { String get(); }
    private interface Setter { void set(String v); }
    private interface BoolGet { boolean get(); }
    private interface BoolSet { void set(boolean v); }

    private static final class Category {
        final String symbol, name, about;
        final List<Opt> opts = new ArrayList<>();
        Category(String symbol, String name, String about) { this.symbol = symbol; this.name = name; this.about = about; }
    }

    private final List<Category> categories = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private float k;
    private boolean light;
    private int bg0, bg1, ink, dim, faint, plate, plateInk, plateDim, line;

    private TextView pageTitle, sideName, sideText;
    private SymbolView sideSymbol;
    private LinearLayout list;
    private ScrollView scroll;
    /** -1: the categories; else the open category. */
    private int open = -1, lastCategory;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        palette();
        build();

        FrameLayout root = new FrameLayout(this);
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
        sideName = text(34, ink, Ui.medium);
        sideName.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.topMargin = px(28);
        side.addView(sideName, np);
        sideText = text(24, dim, Ui.regular);
        sideText.setGravity(Gravity.CENTER);
        sideText.setSingleLine(false);
        sideText.setMaxLines(4);
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
        if (list != null) refreshValues();   // back from one of SettingsActivity's screens
    }

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
        action(home, "Applis du dock", "Choisir les applis et leur ordre.", () -> openOld("main:3"));
        action(home, "Boutons du haut", "Les boutons ronds en haut à droite.", () -> openOld("main:4"));
        toggle(home, "Entrées", "TV en direct et HDMI, dans le tiroir du bas.", Prefs::drawerInputs, Prefs::setDrawerInputs);
        toggle(home, "Raccourcis", "Toutes les apps et Réglages de la TV, dans le tiroir du bas.",
                Prefs::drawerShortcuts, Prefs::setDrawerShortcuts);
        toggle(home, "Icônes d'état", "Wi‑Fi, Bluetooth et USB en haut à gauche.", Prefs::statusIcons, Prefs::setStatusIcons);
        toggle(home, "Applis pour téléphone", "Les montrer aussi dans Toutes les apps.", Prefs::androidApps, Prefs::setAndroidApps);
        action(home, "Applis récentes", "Revenir aux applis ouvertes ou les fermer.", () -> openOld("row:recents"));

        Category picks = add("cat_picks", "Suggestions", "Tendances et nouveautés de tes services.");
        choice(picks, "Présentation", "Affiche : une grande image, comme Netflix. Cartes : comme l'Apple TV.",
                Prefs.TOP_STYLES, new String[] {"Affiche", "Cartes"}, Prefs::topStyle, Prefs::setTopStyle);
        choice(picks, "Nombre de titres", "Combien de suggestions à la fois.",
                new String[] {"3", "4", "5", "6"}, new String[] {"3", "4", "5", "6"},
                () -> String.valueOf(Prefs.cards()), v -> Prefs.setCards(Integer.parseInt(v)));
        action(picks, "Services", "Netflix, Prime Video, Disney+…", () -> openOld("row:picks"));
        action(picks, "Vous regardiez", "Les applis qui remontent ce que tu as commencé.", () -> openOld("row:watch"));
        action(picks, "Clé TMDB", "Pour les suggestions. Un QR code s'affiche : scanne-le et colle ta clé sur ton téléphone.", () -> openOld("row:tmdb"));

        Category saver = add("cat_saver", "Écran de veille", "Quand la TV reste inactive.");
        choice(saver, "Contenu", "Fonds d'écran : tes images, seules, en plein écran.",
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
        action(saver, "Aperçu", "Voir l'écran de veille maintenant.", () -> openOld("main:1"));

        Category widgets = add("cat_widgets", "Widgets", "Les infos du tiroir du bas.");
        toggle(widgets, "Météo", "Le temps qu'il fait et les heures à venir.", Prefs::weather, Prefs::setWeather);
        action(widgets, "Ville", "Pour la météo et le lever du soleil.", () -> openOld("row:city"));
        choice(widgets, "Prévisions", "",
                new String[] {"hours", "days"}, new String[] {"Par heure", "Par jour"}, Prefs::forecast, Prefs::setForecast);
        action(widgets, "Lecture en cours", "La musique ou le podcast en cours, sur l'accueil.", () -> openOld("row:playing"));
        toggle(widgets, "Cryptos", "Bitcoin, Ethereum et Solana.", Prefs::quotes, Prefs::setQuotes);

        Category sys = add("cat_system", "Système", "Langue, télécommande et mises à jour.");
        action(sys, "Écran d'accueil", "MaTV ou l'accueil d'origine de la TV.", () -> openOld("main:0"));
        action(sys, "Langue", "", () -> openOld("row:language"));
        choice(sys, "Température", "", new String[] {"auto", "C", "F"}, new String[] {"Auto", "°C", "°F"},
                Prefs::temperature, Prefs::setTemperature);
        choice(sys, "Format de l'heure", "", new String[] {"auto", "24", "12"}, new String[] {"Auto", "24 h", "12 h"},
                Prefs::clockFormat, Prefs::setClockFormat);
        action(sys, "Télécommande", "Ce qu'ouvrent les boutons YouTube, Netflix…", () -> openOld("main:5"));
        action(sys, "Minuterie de veille", "Éteindre la TV après un délai.", () -> openOld("main:6"));
        action(sys, "Mises à jour", "", () -> openOld("row:updates"));
        action(sys, "Réglages de la TV", "Image, son, réseau, comptes.", () -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); } catch (RuntimeException ignored) {
                startActivity(new Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.MainSettings"));
            }
        });
        action(sys, "Tous les réglages", "La liste complète, option par option.", () -> openOld("row:all"));
    }

    private Category add(String symbol, String name, String about) {
        Category c = new Category(symbol, name, about);
        categories.add(c);
        return c;
    }

    private void choice(Category cat, String title, String desc, String[] values, String[] labels, Getter get, Setter set) {
        Opt o = new Opt(title, desc);
        o.values = values;
        o.labels = labels;
        o.get = get;
        o.set = set;
        cat.opts.add(o);
    }

    private void toggle(Category cat, String title, String desc, BoolGet get, BoolSet set) {
        choice(cat, title, desc, new String[] {"off", "on"}, new String[] {"Non", "Oui"},
                () -> get.get() ? "on" : "off", v -> set.set(v.equals("on")));
        cat.opts.get(cat.opts.size() - 1).isSwitch = true;
    }

    private void action(Category cat, String title, String desc, Runnable r) {
        Opt o = new Opt(title, desc);
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

    // MARK: - Pages

    private void showCategories(int focus) {
        open = -1;
        pageTitle.setText("Réglages");
        list.removeAllViews();
        for (int i = 0; i < categories.size(); i++) {
            final int at = i;
            Category cat = categories.get(i);
            Row r = new Row(cat.name, null, true);
            r.symbol = cat.symbol;
            r.setOnFocusChangeListener((v, has) -> {
                r.focus(has);
                if (has) side(cat.symbol, cat.name, cat.about);
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
        Category cat = categories.get(at);
        pageTitle.setText(cat.name);
        list.removeAllViews();
        for (Opt o : cat.opts) {
            Row r = new Row(o.title, o, o.action != null);
            r.setOnFocusChangeListener((v, has) -> {
                r.focus(has);
                if (has) side(cat.symbol, o.title, o.desc.isEmpty() ? cat.about : o.desc);
            });
            r.setOnClickListener(v -> {
                if (o.action != null) o.action.run();
                else change(o, 1);
            });
            r.setOnKeyListener((v, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (o.action != null) {
                    if (code == KeyEvent.KEYCODE_DPAD_RIGHT) o.action.run();
                    return true;
                }
                change(o, code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            });
            list.addView(r, rowParams());
        }
        scroll.scrollTo(0, 0);
        list.getChildAt(Math.min(focus, list.getChildCount() - 1)).requestFocus();
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, px(84));
        p.bottomMargin = px(6);
        return p;
    }

    private void side(String symbol, String name, String about) {
        sideSymbol.set(symbol);
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
        FrameLayout root = (FrameLayout) pageTitle.getParent();
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {bg0, bg1}));
        pageTitle.setTextColor(ink);
        sideName.setTextColor(ink);
        sideText.setTextColor(dim);
        sideSymbol.invalidate();
        showCategory(open, focus);
    }

    private void refreshValues() {
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (v instanceof Row) ((Row) v).update();
        }
    }

    @Override public void onBackPressed() {
        if (open >= 0) {
            showCategories(lastCategory);
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
            if (opt != null && opt.values != null && !isSwitch) {
                int at = java.util.Arrays.asList(opt.values).indexOf(opt.get.get());
                String label = opt.labels[Math.max(0, at)];
                value.setText(focused ? "‹   " + label + "   ›" : label);
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
