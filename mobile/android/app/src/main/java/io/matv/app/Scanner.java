package io.matv.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Finds the TVs on the phone's Wi-Fi, two ways at once:
 *  - mDNS: every Android TV / Google TV announces itself (_androidtvremote2._tcp, _googlecast._tcp) with its name,
 *    even before debugging is on, so the list can say "Salon" and guide you;
 *  - a scan of port 5555 on the /24: the TVs whose debugging is on (ADB over the network).
 */
final class Scanner {
    static final int ADB_PORT = 5555;

    static final class Tv {
        final String ip;
        String name = "", model = "";
        boolean ready;          // port 5555 answers
        boolean checked;        // port 5555 tried after its announce
        boolean androidTv;      // announced the Android TV remote service (a speaker or a Chromecast doesn't)
        Tv(String ip) { this.ip = ip; }
        String title() { return name.isEmpty() ? (model.isEmpty() ? "TV Android" : model) : name; }
    }

    interface Listener {
        /** A TV appeared or changed (worker thread). */
        void update(Tv tv);
    }

    /** Set only by the screenshot tests (no Wi-Fi there). */
    static Inet4Address testAddress;

    /** The phone's IPv4 address on Wi-Fi (or Ethernet), or null when it's on mobile data only. */
    static Inet4Address localAddress(Context c) {
        if (testAddress != null) return testAddress;
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        if (cm == null) return null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null || !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) continue;
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address && !la.getAddress().isLoopbackAddress()) {
                    return (Inet4Address) la.getAddress();
                }
            }
        }
        return null;
    }

    static boolean open(String ip, int port, int ms) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), ms);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Runs both searches (about 4 seconds) and reports each TV as it shows up. Blocks; call it off the UI thread. */
    static void scan(Context c, Inet4Address self, Listener listener, AtomicBoolean cancel) {
        java.util.Map<String, Tv> found = new java.util.concurrent.ConcurrentHashMap<>();
        Thread mdns = new Thread(() -> mdns(c, found, listener, cancel), "mdns");
        mdns.start();
        byte[] a = self.getAddress();
        String prefix = (a[0] & 0xff) + "." + (a[1] & 0xff) + "." + (a[2] & 0xff) + ".";
        String me = self.getHostAddress();
        ExecutorService pool = Executors.newFixedThreadPool(48);
        for (int i = 1; i < 255; i++) {
            String ip = prefix + i;
            if (ip.equals(me)) continue;
            pool.execute(() -> {
                if (cancel.get() || !open(ip, ADB_PORT, 600)) return;
                Tv tv = found.computeIfAbsent(ip, Tv::new);
                tv.ready = true;
                listener.update(tv);
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(15, TimeUnit.SECONDS);
            mdns.join(6000);
        } catch (InterruptedException ignored) {
        }
    }

    // MARK: - mDNS

    private static final String[] SERVICES = {"_androidtvremote2._tcp.local", "_googlecast._tcp.local"};

    private static void mdns(Context c, java.util.Map<String, Tv> found, Listener listener, AtomicBoolean cancel) {
        WifiManager wifi = c.getApplicationContext().getSystemService(WifiManager.class);
        WifiManager.MulticastLock lock = wifi == null ? null : wifi.createMulticastLock("matv-installer");
        MulticastSocket unicast = null, group = null;
        try {
            if (lock != null) { lock.setReferenceCounted(false); lock.acquire(); }
            InetAddress mcast = InetAddress.getByName("224.0.0.251");
            byte[] query = query();
            unicast = new MulticastSocket(0);   // answers to "QU" questions come straight back here
            unicast.setSoTimeout(250);
            try {
                group = new MulticastSocket(5353);  // and the TVs that answer to the whole network
                group.joinGroup(mcast);
                group.setSoTimeout(250);
            } catch (Exception busy) {
                group = null;
            }
            long end = System.currentTimeMillis() + 3500;
            int sent = 0;
            byte[] buf = new byte[9000];
            while (System.currentTimeMillis() < end && !cancel.get()) {
                if (sent < 3 && System.currentTimeMillis() > end - 3500 + sent * 1000L) {
                    unicast.send(new DatagramPacket(query, query.length, mcast, 5353));
                    sent++;
                }
                for (MulticastSocket s : new MulticastSocket[] {unicast, group}) {
                    if (s == null) continue;
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(p);
                    } catch (java.net.SocketTimeoutException t) {
                        continue;
                    }
                    if (!(p.getAddress() instanceof Inet4Address)) continue;
                    Announce an = parse(p.getData(), p.getLength());
                    if (an == null) continue;
                    String ip = p.getAddress().getHostAddress();
                    Tv tv = found.computeIfAbsent(ip, Tv::new);
                    boolean changed = an.androidTv && !tv.androidTv;
                    if (an.androidTv) tv.androidTv = true;
                    if (!an.name.isEmpty() && !an.name.equals(tv.name) && (tv.name.isEmpty() || an.friendly)) { tv.name = an.name; changed = true; }
                    if (!an.model.isEmpty() && tv.model.isEmpty()) { tv.model = an.model; changed = true; }
                    if (!tv.androidTv && !tv.ready) continue;   // not a TV (yet): a Cast speaker, say
                    if (changed || !tv.checked) {
                        if (!tv.checked) { tv.checked = true; if (!tv.ready) tv.ready = open(ip, ADB_PORT, 600); }
                        listener.update(tv);
                    }
                }
            }
        } catch (Exception ignored) {
            // No multicast on this network: the port scan still finds the TVs whose debugging is on.
        } finally {
            if (unicast != null) unicast.close();
            if (group != null) group.close();
            if (lock != null && lock.isHeld()) lock.release();
        }
    }

    private static byte[] query() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0); b.write(0);           // id
        b.write(0); b.write(0);           // flags: standard query
        b.write(0); b.write(SERVICES.length);
        for (int i = 0; i < 6; i++) b.write(0);
        for (String s : SERVICES) {
            for (String label : s.split("\\.")) {
                byte[] l = label.getBytes(StandardCharsets.UTF_8);
                b.write(l.length);
                b.write(l, 0, l.length);
            }
            b.write(0);
            b.write(0); b.write(12);       // PTR
            b.write(0x80); b.write(1);     // IN, with the "unicast answer please" bit
        }
        return b.toByteArray();
    }

    static final class Announce {
        String name = "", model = "";
        boolean androidTv;  // it announced _androidtvremote2
        boolean friendly;   // the name came from the Cast "fn=" field (the one set in the TV's settings)
    }

    /** The TV's name from an mDNS answer: its androidtvremote2 instance, or the Cast fn= (and md= for the model). */
    static Announce parse(byte[] d, int len) {
        try {
            int qd = u16(d, 4), records = u16(d, 6) + u16(d, 8) + u16(d, 10);
            int[] at = {12};
            for (int i = 0; i < qd; i++) { name(d, at); at[0] += 4; }
            Announce an = new Announce();
            boolean any = false;
            for (int i = 0; i < records && at[0] < len; i++) {
                String owner = name(d, at);
                int type = u16(d, at[0]), rdlen = u16(d, at[0] + 8);
                int rd = at[0] + 10;
                at[0] = rd + rdlen;
                if (type == 12 && (owner.startsWith("_androidtvremote2.") || owner.startsWith("_googlecast."))) {
                    int[] p = {rd};
                    String instance = name(d, p);
                    String first = firstLabel(instance);
                    any = true;
                    if (owner.startsWith("_androidtvremote2.")) {
                        an.androidTv = true;
                        if (!an.friendly) an.name = first;
                    }
                } else if (type == 16 && owner.contains("._googlecast.")) {
                    any = true;
                    for (int p = rd; p < rd + rdlen; ) {
                        int l = d[p] & 0xff;
                        String kv = new String(d, p + 1, l, StandardCharsets.UTF_8);
                        p += 1 + l;
                        if (kv.startsWith("fn=") && kv.length() > 3) { an.name = kv.substring(3); an.friendly = true; }
                        if (kv.startsWith("md=") && kv.length() > 3) an.model = kv.substring(3);
                    }
                }
            }
            return any ? an : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String firstLabel(String fqdn) {
        int i = fqdn.indexOf("._");
        return i > 0 ? fqdn.substring(0, i) : fqdn;
    }

    private static int u16(byte[] d, int i) { return ((d[i] & 0xff) << 8) | (d[i + 1] & 0xff); }

    /** A DNS name at at[0] (with compression); moves at[0] past it. */
    private static String name(byte[] d, int[] at) {
        StringBuilder s = new StringBuilder();
        int p = at[0], jumps = 0;
        boolean jumped = false;
        while (true) {
            int l = d[p] & 0xff;
            if (l == 0) { if (!jumped) at[0] = p + 1; break; }
            if ((l & 0xc0) == 0xc0) {
                if (!jumped) at[0] = p + 2;
                p = ((l & 0x3f) << 8) | (d[p + 1] & 0xff);
                jumped = true;
                if (++jumps > 20) throw new IllegalStateException("loop");
                continue;
            }
            if (s.length() > 0) s.append('.');
            s.append(new String(d, p + 1, l, StandardCharsets.UTF_8));
            p += 1 + l;
        }
        return s.toString();
    }
}
