package io.matv.iptv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MaTV Live: an Xtream Codes player for the TV. Sign in once (server, user, password); then Chaînes, Films, Séries
 * and Favoris, each a list of categories on the left and their streams on the right. OK plays; a long OK (or the
 * menu key) adds a favourite. Everything works with the remote's arrows.
 */
public class MainActivity extends Activity {
    private static final String[] TABS = {"Chaînes", "Films", "Séries", "Favoris", "Compte"};
    private static final String[] KINDS = {Xtream.LIVE, Xtream.VOD, Xtream.SERIES, "fav", "account"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private Xtream x;
    private SharedPreferences prefs;

    private FrameLayout root;
    private final List<TextView> tabViews = new ArrayList<>();
    private ListView categoryList, itemList;
    private TextView status, detail;
    private EditText search;
    private LinearLayout body, accountPane;

    private int tab = 0;
    private final List<Xtream.Category> categories = new ArrayList<>();
    private final List<Xtream.Item> all = new ArrayList<>(), shown = new ArrayList<>();
    private String seriesName;          // non-null: the episodes of this series are shown
    private int seriesReturn;           // where the series was in its list
    private int loadGen;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        x = new Xtream(this);
        prefs = getSharedPreferences("player", MODE_PRIVATE);
        root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFF0E1420, 0xFF07090D, 0xFF120B1C});
        root.setBackground(bg);
        setContentView(root);
        if (x.signedIn()) showBrowser(); else showLogin(null);
        Updater.check(this, false);   // once a day: a newer MaTV Live from GitHub is offered
    }

    // MARK: - Sign-in

    private void showLogin(String message) {
        root.removeAllViews();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 36);
        box.setPadding(p, p, p, p);
        box.setBackground(Ui.glass(this, 0x1FFFFFFF, 0x33FFFFFF, 28));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(this, 560), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(box, lp);

        TextView title = Ui.text(this, 30, Ui.TEXT, true);
        title.setText("MaTV Live");
        box.addView(title);
        TextView sub = Ui.text(this, 15, Ui.DIM, false);
        sub.setSingleLine(false);
        sub.setText("Entre les codes Xtream fournis par ton abonnement IPTV.");
        box.addView(sub);

        EditText server = field("Serveur (ex. http://exemple.com:8080)", InputType.TYPE_TEXT_VARIATION_URI);
        EditText user = field("Nom d'utilisateur", InputType.TYPE_CLASS_TEXT);
        EditText pass = field("Mot de passe", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        server.setText(x.server);
        user.setText(x.user);
        box.addView(server);
        box.addView(user);
        box.addView(pass);

        TextView msg = Ui.text(this, 15, 0xFFFF8A8A, false);
        msg.setSingleLine(false);
        if (message != null) msg.setText(message);
        Button go = button("Se connecter");
        pass.setImeOptions(EditorInfo.IME_ACTION_GO);
        Runnable submit = () -> {
            String s = server.getText().toString(), u = user.getText().toString().trim(), pw = pass.getText().toString();
            if (s.trim().isEmpty() || u.isEmpty()) { msg.setText("Il manque le serveur ou l'utilisateur."); return; }
            msg.setTextColor(Ui.DIM);
            msg.setText("Connexion…");
            go.setEnabled(false);
            work.execute(() -> {
                String err = x.signIn(s, u, pw);
                main.post(() -> {
                    go.setEnabled(true);
                    if (err == null) showBrowser();
                    else { msg.setTextColor(0xFFFF8A8A); msg.setText(err); }
                });
            });
        };
        pass.setOnEditorActionListener((v, id, e) -> { submit.run(); return true; });
        go.setOnClickListener(v -> submit.run());
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56));
        gp.topMargin = Ui.dp(this, 18);
        box.addView(go, gp);
        box.addView(msg);
        (x.server.isEmpty() ? server : pass).requestFocus();
    }

    private EditText field(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(type == InputType.TYPE_TEXT_VARIATION_URI ? InputType.TYPE_CLASS_TEXT | type : type);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(0x66FFFFFF);
        e.setTextSize(18);
        int p = Ui.dp(this, 14);
        e.setPadding(p, p, p, p);
        e.setBackground(Ui.focusable(this, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 12);
        e.setLayoutParams(lp);
        return e;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Ui.TEXT);
        b.setTextSize(17);
        b.setBackground(Ui.focusable(this, 16));
        b.setStateListAnimator(null);
        Ui.lift(b);
        return b;
    }

    // MARK: - Browser

    private void showBrowser() {
        root.removeAllViews();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, 40), Ui.dp(this, 22), Ui.dp(this, 40), Ui.dp(this, 18));
        root.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Top bar: name, tabs, search
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = Ui.text(this, 22, Ui.TEXT, true);
        name.setText("MaTV Live");
        name.setPadding(0, 0, Ui.dp(this, 28), 0);
        bar.addView(name);
        tabViews.clear();
        for (int i = 0; i < TABS.length; i++) {
            TextView t = Ui.text(this, 17, Ui.TEXT, true);
            t.setText(TABS[i]);
            t.setFocusable(true);
            t.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 8));
            t.setBackground(Ui.focusable(this, 20));
            final int at = i;
            t.setOnClickListener(v -> selectTab(at));
            t.setOnFocusChangeListener((v, has) -> { if (has && at != tab) selectTab(at); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 6);
            bar.addView(t, lp);
            tabViews.add(t);
        }
        View gap = new View(this);
        bar.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
        search = new EditText(this);
        search.setHint("Rechercher");
        search.setSingleLine(true);
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(0x66FFFFFF);
        search.setTextSize(16);
        search.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        search.setBackground(Ui.focusable(this, 20));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { filter(); }
        });
        bar.addView(search, new LinearLayout.LayoutParams(Ui.dp(this, 260), ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(bar);

        // Body: categories | streams
        body = new LinearLayout(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        bp.topMargin = Ui.dp(this, 18);
        page.addView(body, bp);

        categoryList = list();
        categoryList.setAdapter(new CategoryAdapter());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.3f);
        cp.rightMargin = Ui.dp(this, 16);
        body.addView(wrap(categoryList), cp);
        categoryList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) { pickCategory(pos); }
            @Override public void onNothingSelected(AdapterView<?> a) {}
        });
        categoryList.setOnItemClickListener((a, v, pos, id) -> { pickCategory(pos); itemList.requestFocus(); });

        itemList = list();
        itemList.setAdapter(new ItemAdapter());
        body.addView(wrap(itemList), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.7f));
        itemList.setOnItemClickListener((a, v, pos, id) -> open(pos));
        itemList.setOnItemLongClickListener((a, v, pos, id) -> { favourite(pos); return true; });
        itemList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) { describe(pos); }
            @Override public void onNothingSelected(AdapterView<?> a) { detail.setText(""); }
        });

        accountPane = new LinearLayout(this);
        accountPane.setOrientation(LinearLayout.VERTICAL);
        accountPane.setVisibility(View.GONE);
        page.addView(accountPane, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout foot = new LinearLayout(this);
        detail = Ui.text(this, 15, Ui.DIM, false);
        detail.setMaxLines(2);
        detail.setSingleLine(false);
        status = Ui.text(this, 14, 0x66FFFFFF, false);
        status.setGravity(Gravity.END);
        foot.addView(detail, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        foot.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48));
        fp.topMargin = Ui.dp(this, 10);
        page.addView(foot, fp);

        selectTab(0);
        tabViews.get(0).requestFocus();
    }

    private ListView list() {
        ListView l = new ListView(this);
        l.setDivider(null);
        l.setSelector(new android.graphics.drawable.ColorDrawable(0));
        l.setItemsCanFocus(false);
        l.setVerticalScrollBarEnabled(false);
        l.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        l.setClipToPadding(false);
        l.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        return l;
    }

    private View wrap(View v) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(Ui.glass(this, 0x14FFFFFF, 0x1FFFFFFF, 24));
        f.addView(v);
        return f;
    }

    private void selectTab(int i) {
        tab = i;
        seriesName = null;
        for (int t = 0; t < tabViews.size(); t++) tabViews.get(t).setSelected(t == i);
        boolean account = KINDS[i].equals("account");
        body.setVisibility(account ? View.GONE : View.VISIBLE);
        search.setVisibility(account ? View.INVISIBLE : View.VISIBLE);
        accountPane.setVisibility(account ? View.VISIBLE : View.GONE);
        if (account) { showAccount(); return; }
        search.setText("");
        categories.clear();
        all.clear();
        shown.clear();
        notifyLists();
        final int gen = ++loadGen;
        final String kind = KINDS[i];
        status.setText("Chargement…");
        work.execute(() -> {
            try {
                final List<Xtream.Category> cats = new ArrayList<>();
                if (kind.equals("fav")) {
                    cats.add(new Xtream.Category("*", "Tous les favoris"));
                    cats.add(new Xtream.Category(Xtream.LIVE, "Chaînes"));
                    cats.add(new Xtream.Category(Xtream.VOD, "Films"));
                    cats.add(new Xtream.Category(Xtream.SERIES, "Séries"));
                } else {
                    cats.addAll(x.categories(kind));
                }
                main.post(() -> {
                    if (gen != loadGen) return;
                    categories.addAll(cats);
                    notifyLists();
                    categoryList.setItemChecked(0, true);
                    pickCategory(0);
                });
            } catch (Exception e) {
                main.post(() -> { if (gen == loadGen) status.setText("Erreur : " + e.getMessage()); });
            }
        });
    }

    private int categoryAt = -1;

    private void pickCategory(int pos) {
        if (pos < 0 || pos >= categories.size()) return;
        categoryAt = pos;
        seriesName = null;
        categoryList.setItemChecked(pos, true);
        final int gen = ++loadGen;
        final String kind = KINDS[tab], cat = categories.get(pos).id;
        status.setText("Chargement…");
        work.execute(() -> {
            try {
                List<Xtream.Item> got = new ArrayList<>();
                if (kind.equals("fav")) {
                    Set<String> fav = x.favourites();
                    for (String k : new String[] {Xtream.LIVE, Xtream.VOD, Xtream.SERIES}) {
                        if (!cat.equals("*") && !cat.equals(k)) continue;
                        for (Xtream.Item it : x.items(k, "*")) if (fav.contains(it.kind + ":" + it.id)) got.add(it);
                    }
                } else {
                    got = x.items(kind, cat);
                }
                final List<Xtream.Item> ready = got;
                main.post(() -> {
                    if (gen != loadGen) return;
                    all.clear();
                    all.addAll(ready);
                    filter();
                });
            } catch (Exception e) {
                main.post(() -> { if (gen == loadGen) status.setText("Erreur : " + e.getMessage()); });
            }
        });
    }

    private void filter() {
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Xtream.Item it : all) if (q.isEmpty() || it.name.toLowerCase(Locale.ROOT).contains(q)) shown.add(it);
        notifyLists();
        status.setText(shown.size() + (shown.size() > 1 ? " éléments" : " élément")
                + (seriesName != null ? " · " + seriesName : ""));
    }

    private void notifyLists() {
        ((BaseAdapter) categoryList.getAdapter()).notifyDataSetChanged();
        ((BaseAdapter) itemList.getAdapter()).notifyDataSetChanged();
    }

    private void describe(int pos) {
        if (pos < 0 || pos >= shown.size()) return;
        Xtream.Item it = shown.get(pos);
        StringBuilder b = new StringBuilder();
        if (!it.rating.isEmpty() && !it.rating.equals("0")) b.append("★ ").append(it.rating).append("   ");
        b.append(it.plot);
        if (b.length() == 0 && Xtream.LIVE.equals(it.kind)) b.append("OK : regarder · OK long : favori");
        detail.setText(b.toString());
        if (Xtream.LIVE.equals(it.kind)) {
            final String id = it.id;
            final int gen = loadGen;
            work.execute(() -> {
                String epg = x.nowNext(id);
                main.post(() -> {
                    if (gen != loadGen || epg.isEmpty()) return;
                    int now = itemList.getSelectedItemPosition();
                    if (now >= 0 && now < shown.size() && shown.get(now).id.equals(id)) detail.setText(epg.replace("\n", "   ·   "));
                });
            });
        }
    }

    private void open(int pos) {
        if (pos < 0 || pos >= shown.size()) return;
        Xtream.Item it = shown.get(pos);
        if (Xtream.SERIES.equals(it.kind)) {
            final int gen = ++loadGen;
            status.setText("Épisodes…");
            work.execute(() -> {
                try {
                    List<Xtream.Item> eps = x.episodes(it.id);
                    main.post(() -> {
                        if (gen != loadGen) return;
                        seriesName = it.name;
                        seriesReturn = pos;
                        all.clear();
                        all.addAll(eps);
                        search.setText("");
                        filter();
                        itemList.setSelection(0);
                        itemList.requestFocus();
                    });
                } catch (Exception e) {
                    main.post(() -> status.setText("Erreur : " + e.getMessage()));
                }
            });
            return;
        }
        if (prefs.getBoolean("external", false)) {
            playExternal(this, x.url(it), it.name);
            return;
        }
        Player.queue = new ArrayList<>(shown);
        Player.index = pos;
        Player.x = x;
        startActivity(new Intent(this, PlayerActivity.class));
    }

    static void playExternal(Context c, String url, String title) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(android.net.Uri.parse(url), "video/*");
        i.putExtra("title", title);
        try {
            c.startActivity(Intent.createChooser(i, "Lire avec"));
        } catch (Exception e) {
            Toast.makeText(c, "Aucun lecteur vidéo externe installé (VLC, MX Player…).", Toast.LENGTH_LONG).show();
        }
    }

    private void favourite(int pos) {
        if (pos < 0 || pos >= shown.size()) return;
        Xtream.Item it = shown.get(pos);
        if ("episode".equals(it.kind)) return;
        x.toggleFavourite(it);
        Toast.makeText(this, x.isFavourite(it) ? "Ajouté aux favoris" : "Retiré des favoris", Toast.LENGTH_SHORT).show();
        if (KINDS[tab].equals("fav")) pickCategory(Math.max(0, categoryAt));
        else notifyLists();
    }

    // MARK: - Account

    private void showAccount() {
        accountPane.removeAllViews();
        accountPane.setPadding(Ui.dp(this, 8), Ui.dp(this, 24), 0, 0);
        TextView t = Ui.text(this, 20, Ui.TEXT, true);
        t.setText("Compte");
        accountPane.addView(t);
        TextView info = Ui.text(this, 16, Ui.DIM, false);
        info.setSingleLine(false);
        String exp = x.expires();
        info.setText("Serveur : " + x.server + "\nUtilisateur : " + x.user + (exp.isEmpty() ? "" : "\nExpire le " + exp));
        accountPane.addView(info);

        Button refresh = button("Actualiser les listes");
        refresh.setOnClickListener(v -> { x.refresh(); Toast.makeText(this, "Les listes seront rechargées.", Toast.LENGTH_SHORT).show(); });
        final Button player = button(playerLabel());
        player.setOnClickListener(v -> {
            prefs.edit().putBoolean("external", !prefs.getBoolean("external", false)).apply();
            player.setText(playerLabel());
        });
        Button out = button("Se déconnecter");
        out.setOnClickListener(v -> { x.signOut(); showLogin(null); });
        Button upd = button("Rechercher une mise à jour");
        upd.setOnClickListener(v -> Updater.check(this, true));
        for (Button b : new Button[] {refresh, player, upd, out}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 420), Ui.dp(this, 54));
            lp.topMargin = Ui.dp(this, 14);
            accountPane.addView(b, lp);
        }
        TextView note = Ui.text(this, 14, 0x66FFFFFF, false);
        note.setSingleLine(false);
        note.setText("\nDans le lecteur : ▲ ▼ ou CH+/CH− pour zapper, OK pour les infos, ◀ ▶ pour avancer dans un film.\n"
                + "À utiliser avec un abonnement légal.");
        accountPane.addView(note);
    }

    private String playerLabel() {
        return "Lecteur : " + (prefs.getBoolean("external", false) ? "externe (VLC, MX Player…)" : "intégré");
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU && itemList != null && itemList.hasFocus()) {
            favourite(itemList.getSelectedItemPosition());
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override public void onBackPressed() {
        if (seriesName != null) {   // back from the episodes to the series
            final int back = seriesReturn;
            pickCategory(Math.max(0, categoryAt));
            main.postDelayed(() -> { if (back < itemList.getCount()) itemList.setSelection(back); }, 300);
            return;
        }
        if (itemList != null && itemList.hasFocus()) { categoryList.requestFocus(); return; }
        if (categoryList != null && categoryList.hasFocus()) { tabViews.get(tab).requestFocus(); return; }
        super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        if (itemList != null) notifyLists();
    }

    // MARK: - Adapters

    private final class CategoryAdapter extends BaseAdapter {
        @Override public int getCount() { return categories.size(); }
        @Override public Object getItem(int p) { return categories.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int p, View v, ViewGroup parent) {
            TextView t = v instanceof TextView ? (TextView) v : Ui.text(MainActivity.this, 17, Ui.TEXT, false);
            if (v == null) {
                t.setPadding(Ui.dp(MainActivity.this, 16), Ui.dp(MainActivity.this, 12), Ui.dp(MainActivity.this, 16), Ui.dp(MainActivity.this, 12));
                t.setBackground(Ui.row(MainActivity.this, 14));
            }
            t.setText(categories.get(p).name);
            t.setActivated(p == categoryAt);
            return t;
        }
    }

    private final class ItemAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int p, View v, ViewGroup parent) {
            Row r;
            if (v == null) {
                r = new Row(MainActivity.this);
                v = r.view;
                v.setTag(r);
            } else {
                r = (Row) v.getTag();
            }
            Xtream.Item it = shown.get(p);
            boolean live = Xtream.LIVE.equals(it.kind);
            r.num.setText(live ? String.valueOf(it.num) : "");
            r.num.setVisibility(live ? View.VISIBLE : View.GONE);
            r.name.setText((x.isFavourite(it) ? "★  " : "") + it.name);
            Ui.image(r.icon, it.icon, Ui.dp(MainActivity.this, 120));
            return v;
        }
    }

    /** A stream's row: number (live), logo or poster, name. The focus highlight comes from the list's selection. */
    private static final class Row {
        final LinearLayout view;
        final TextView num, name;
        final ImageView icon;

        Row(Context c) {
            view = new LinearLayout(c);
            view.setGravity(Gravity.CENTER_VERTICAL);
            int p = Ui.dp(c, 10);
            view.setPadding(p, p / 2, p, p / 2);
            view.setBackground(Ui.row(c, 14));
            num = Ui.text(c, 15, Ui.DIM, false);
            num.setMinWidth(Ui.dp(c, 44));
            view.addView(num);
            icon = new ImageView(c);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(c, 80), Ui.dp(c, 46));
            ip.rightMargin = Ui.dp(c, 14);
            view.addView(icon, ip);
            name = Ui.text(c, 18, Ui.TEXT, false);
            view.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
    }
}
