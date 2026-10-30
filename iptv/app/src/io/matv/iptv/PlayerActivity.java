package io.matv.iptv;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen playback with the TV's own player (VideoView: HLS, MPEG-TS, MP4 and MKV on Android TV).
 * Live: ▲ ▼ (or CH+ / CH−) zap through the list it was opened from, OK shows the channel and what's on, the number
 * keys jump to a channel. Films and episodes: ◀ ▶ seek 30 s, OK pauses. If the stream won't play, a live channel tries
 * MPEG-TS instead of HLS, then the app offers an external player.
 */
public class PlayerActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private VideoView video;
    private LinearLayout info;
    private TextView title, sub, typed;
    private ProgressBar spinner;
    private boolean triedTs, prepared;
    private String digits = "";
    private int gen;

    private final Runnable hideInfo = () -> info.animate().alpha(0).setDuration(300).start();
    private final Runnable jump = this::jumpToTyped;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Player.queue == null || Player.x == null || Player.queue.isEmpty()) { finish(); return; }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        spinner = new ProgressBar(this);
        root.addView(spinner, new FrameLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56), Gravity.CENTER));

        info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 26);
        info.setPadding(p, p * 3 / 4, p, p * 3 / 4);
        GradientDrawable g = Ui.glass(this, 0xB3101318, 0x33FFFFFF, 26);
        info.setBackground(g);
        title = Ui.text(this, 26, Ui.TEXT, true);
        sub = Ui.text(this, 17, Ui.DIM, false);
        sub.setSingleLine(false);
        sub.setMaxLines(3);
        info.addView(title);
        info.addView(sub);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        int m = Ui.dp(this, 48);
        ip.setMargins(m, 0, m, Ui.dp(this, 36));
        root.addView(info, ip);

        typed = Ui.text(this, 48, Ui.TEXT, true);
        typed.setPadding(Ui.dp(this, 22), Ui.dp(this, 10), Ui.dp(this, 22), Ui.dp(this, 10));
        typed.setBackground(Ui.glass(this, 0xB3101318, 0x33FFFFFF, 20));
        typed.setVisibility(View.GONE);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        tp.setMargins(0, m, m, 0);
        root.addView(typed, tp);
        setContentView(root);

        video.setOnPreparedListener(mp -> {
            prepared = true;
            spinner.setVisibility(View.GONE);
            mp.setOnInfoListener((player, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) spinner.setVisibility(View.VISIBLE);
                else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) spinner.setVisibility(View.GONE);
                return false;
            });
            video.start();
        });
        video.setOnErrorListener((mp, what, extra) -> { failed(); return true; });
        video.setOnCompletionListener(mp -> {
            if (!live() && Player.index + 1 < Player.queue.size() && "episode".equals(item().kind)) move(1);   // next episode
            else if (!live()) finish();
        });
        play();
    }

    private Xtream.Item item() { return Player.queue.get(Player.index); }

    private boolean live() { return Xtream.LIVE.equals(item().kind); }

    private void play() {
        gen++;
        prepared = false;
        triedTs = false;
        spinner.setVisibility(View.VISIBLE);
        Xtream.Item it = item();
        Player.x.setLast(it.kind, it.id);
        video.setVideoURI(Uri.parse(Player.x.url(it)));
        showInfo();
    }

    private void failed() {
        Xtream.Item it = item();
        if (live() && !triedTs) {   // the TV's player may like MPEG-TS better than this server's HLS
            triedTs = true;
            video.setVideoURI(Uri.parse(Player.x.liveUrl(it, false)));
            return;
        }
        spinner.setVisibility(View.GONE);
        title.setText(it.name);
        sub.setText("Lecture impossible avec le lecteur intégré. OK : ouvrir dans un lecteur externe (VLC, MX Player).");
        info.setAlpha(1);
        main.removeCallbacks(hideInfo);
        prepared = false;
        failedUrl = live() ? Player.x.liveUrl(it, true) : Player.x.url(it);
    }

    private String failedUrl;

    private void showInfo() {
        Xtream.Item it = item();
        title.setText((live() ? it.num + "   " : "") + it.name);
        sub.setText(live() ? "" : it.plot);
        info.animate().cancel();
        info.setAlpha(1);
        main.removeCallbacks(hideInfo);
        main.postDelayed(hideInfo, 5000);
        if (live()) {
            final int g = gen;
            final String id = it.id;
            work.execute(() -> {
                String epg = Player.x.nowNext(id);
                main.post(() -> { if (g == gen && !epg.isEmpty()) sub.setText(epg); });
            });
        } else if (prepared) {
            sub.setText(time(video.getCurrentPosition()) + " / " + time(video.getDuration()) + (it.plot.isEmpty() ? "" : "\n" + it.plot));
        }
    }

    private static String time(int ms) {
        if (ms <= 0) return "--:--";
        int s = ms / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private void move(int by) {
        int n = Player.queue.size();
        Player.index = ((Player.index + by) % n + n) % n;
        failedUrl = null;
        video.stopPlayback();
        play();
    }

    private void jumpToTyped() {
        typed.setVisibility(View.GONE);
        String d = digits;
        digits = "";
        if (d.isEmpty()) return;
        int want = Integer.parseInt(d);
        for (int i = 0; i < Player.queue.size(); i++) {
            if (Player.queue.get(i).num == want) {
                Player.index = i;
                failedUrl = null;
                video.stopPlayback();
                play();
                return;
            }
        }
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (live() || code == KeyEvent.KEYCODE_CHANNEL_UP) { move(-1); return true; }
                showInfo();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (live() || code == KeyEvent.KEYCODE_CHANNEL_DOWN) { move(1); return true; }
                showInfo();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (!live() && prepared) { video.seekTo(Math.max(0, video.getCurrentPosition() - 30_000)); showInfo(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (!live() && prepared) { video.seekTo(video.getCurrentPosition() + 30_000); showInfo(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (failedUrl != null) { MainActivity.playExternal(this, failedUrl, item().name); return true; }
                if (!live() && prepared && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) {
                    if (video.isPlaying()) video.pause(); else video.start();
                } else if (!live() && prepared && info.getAlpha() > 0.5f) {
                    if (video.isPlaying()) video.pause(); else video.start();
                }
                showInfo();
                return true;
            default:
                if (live() && code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9 && digits.length() < 4) {
                    digits += (char) ('0' + code - KeyEvent.KEYCODE_0);
                    typed.setText(digits);
                    typed.setVisibility(View.VISIBLE);
                    main.removeCallbacks(jump);
                    main.postDelayed(jump, 1500);
                    return true;
                }
        }
        return super.onKeyDown(code, e);
    }

    @Override protected void onPause() {
        super.onPause();
        if (video != null) video.pause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (video != null && prepared) video.start();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        work.shutdownNow();
        if (video != null) video.stopPlayback();
        super.onDestroy();
    }
}
