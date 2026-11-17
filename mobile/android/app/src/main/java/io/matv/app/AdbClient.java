package io.matv.app;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;

/**
 * ADB over Wi-Fi, from the phone to a TV (port 5555, open while the TV's USB debugging is on). The same protocol as
 * the computer's adb: https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/protocol.txt
 * The first time, the TV asks "Allow USB debugging?" for this phone's key; "Always allow" remembers it.
 * Pure Java (no Android classes) so it can be tested on a computer against a fake TV.
 */
final class AdbClient implements Closeable {
    static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257, STLS = 0x534c5453;
    private static final int VERSION = 0x01000001, MAX_DATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    /** Start of the PKCS#1 block of a SHA-1 signature (adbd treats the token as if it were the digest). */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    /** Told when the TV shows "Allow USB debugging?", so the screen can say what to do. */
    interface Approval { void waiting(); }

    interface Progress { void at(long done, long total); }

    private static final class Message {
        int command, arg0, arg1;
        byte[] data;
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;
    /** The most the TV accepts in one message (from its CNXN). */
    private int maxData = 4096;

    private AdbClient(Socket socket) throws IOException {
        this.socket = socket;
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    /** Connects and signs in. If the TV doesn't know this phone yet, waits up to `approvalMs` for "Allow". */
    static AdbClient connect(String host, int port, File keyDir, String keyName, int approvalMs, Approval approval) throws IOException {
        KeyPair keys = keys(keyDir);
        AdbClient adb = open(host, port, 4000);
        try {
            adb.hello();
            boolean signed = false, offeredKey = false;
            long until = 0;
            while (true) {
                Message m;
                try {
                    m = adb.read();
                } catch (EOFException | SocketTimeoutException e) {
                    throw new IOException(offeredKey ? "La TV n'a pas autorisé ce téléphone." : "La TV ne répond pas.");
                }
                if (m.command == CNXN) {
                    adb.connected(m);
                    return adb;
                }
                if (m.command == STLS) {
                    throw new IOException("Cette TV utilise le débogage sans fil avec code d'appairage. Active plutôt « Débogage USB » dans les options pour les développeurs.");
                }
                if (m.command != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("Réponse inattendue de la TV.");
                if (!signed) {
                    adb.send(AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
                    signed = true;
                } else if (!offeredKey) {
                    adb.send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob(keys.getPublic(), keyName));
                    offeredKey = true;
                    if (approval != null) approval.waiting();
                    until = System.currentTimeMillis() + approvalMs;
                    // Android 8 to 10 give the answer to the first connection that offered a key; a fresh connection
                    // that only signs gets in once "Always allow" has saved it. Newer TVs answer on this one.
                    adb.socket.setSoTimeout(2000);
                    while (true) {
                        try {
                            Message r = adb.read();
                            if (r.command == CNXN) {
                                adb.connected(r);
                                return adb;
                            }
                            throw new IOException("La TV a refusé ce téléphone.");
                        } catch (EOFException closed) {
                            // Refused, or an older TV that answers on another connection: one last look.
                            for (int i = 0; i < 3; i++) {
                                AdbClient fresh = signedIn(host, port, keys);
                                if (fresh != null) {
                                    adb.close();
                                    return fresh;
                                }
                                try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
                            }
                            throw new IOException("La TV a refusé ce téléphone.");
                        } catch (SocketTimeoutException waiting) {
                            if (System.currentTimeMillis() > until) throw new IOException("La TV n'a pas autorisé ce téléphone.");
                            AdbClient fresh = signedIn(host, port, keys);
                            if (fresh != null) {
                                adb.close();
                                return fresh;
                            }
                        }
                    }
                } else {
                    throw new IOException("La TV a refusé ce téléphone.");
                }
            }
        } catch (IOException | RuntimeException e) {
            adb.close();
            throw e;
        }
    }

    private static AdbClient open(String host, int port, int connectMs) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), connectMs);
            s.setSoTimeout(10_000);
            s.setTcpNoDelay(true);
            return new AdbClient(s);
        } catch (IOException | RuntimeException e) {
            try { s.close(); } catch (IOException ignored) {}
            throw new IOException("Débogage USB désactivé sur la TV (port " + port + " fermé).");
        }
    }

    private void hello() throws IOException {
        send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
    }

    private void connected(Message cnxn) throws IOException {
        maxData = Math.max(1024, Math.min(cnxn.arg1, MAX_DATA));
        socket.setSoTimeout(30_000);
    }

    /** A new connection that only signs (never offers the key, so no second prompt); null while the TV doesn't know it. */
    private static AdbClient signedIn(String host, int port, KeyPair keys) {
        AdbClient adb = null;
        try {
            adb = open(host, port, 2000);
            adb.socket.setSoTimeout(4000);
            adb.hello();
            Message m = adb.read();
            if (m.command == AUTH && m.arg0 == AUTH_TOKEN) {
                adb.send(AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
                m = adb.read();
            }
            if (m.command == CNXN) {
                adb.connected(m);
                return adb;
            }
        } catch (IOException | RuntimeException ignored) {
        }
        if (adb != null) adb.close();
        return null;
    }

    /** Runs a command on the TV and returns what it printed. */
    String shell(String command) throws IOException {
        return exec("shell:" + command, null, 0, null);
    }

    /**
     * Installs an APK by streaming it to `cmd package install -S` (Android 7+): no copy on the TV's storage.
     * Returns the TV's answer ("Success", or the reason it refused). The TV may show Play Protect meanwhile, so the
     * answer can take a while.
     */
    String install(InputStream apk, long size, Progress progress) throws IOException {
        int old = socket.getSoTimeout();
        socket.setSoTimeout(240_000);
        try {
            return exec("exec:cmd package install -r -S " + size, apk, size, progress).trim();
        } finally {
            socket.setSoTimeout(old);
        }
    }

    private String exec(String service, InputStream body, long size, Progress progress) throws IOException {
        int local = nextLocalId++;
        send(OPEN, local, 0, (service + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int remote = 0;
        while (remote == 0) {
            Message m = read();
            if (m.arg1 != local) continue;
            if (m.command == OKAY) remote = m.arg0;
            else if (m.command == CLSE) throw new IOException("La TV a refusé la commande.");
        }
        if (body != null) {
            byte[] buffer = new byte[Math.min(maxData, 64 * 1024)];
            long sent = 0;
            while (sent < size) {
                int n = body.read(buffer, 0, (int) Math.min(buffer.length, size - sent));
                if (n < 0) throw new EOFException("Fichier incomplet.");
                send(WRTE, local, remote, Arrays.copyOf(buffer, n));
                sent += n;
                if (progress != null) progress.at(sent, size);
                // One write at a time: the TV says OKAY before the next (and may answer early, on an error).
                boolean acked = false;
                while (!acked) {
                    Message m = read();
                    if (m.arg1 != local) continue;
                    if (m.command == OKAY) acked = true;
                    else if (m.command == WRTE) { result.write(m.data); send(OKAY, local, remote, null); }
                    else if (m.command == CLSE) { send(CLSE, local, remote, null); return result.toString("UTF-8"); }
                }
            }
        }
        while (true) {
            Message m = read();
            if (m.arg1 != local) continue;
            if (m.command == WRTE) {
                result.write(m.data);
                send(OKAY, local, remote, null);
            } else if (m.command == CLSE) {
                send(CLSE, local, remote, null);
                return result.toString("UTF-8");
            }
        }
    }

    @Override public void close() {
        try { socket.close(); } catch (IOException ignored) {}
    }

    private Message read() throws IOException {
        byte[] header = new byte[24];
        in.readFully(header);
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int length = b.getInt();
        b.getInt(); // checksum (newer adbd doesn't check it)
        int magic = b.getInt();
        if ((m.command ^ 0xffffffff) != magic) throw new IOException("Message ADB invalide.");
        if (length < 0 || length > 1024 * 1024) throw new IOException("Message ADB trop grand.");
        m.data = new byte[length];
        in.readFully(m.data);
        return m;
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        int length = data == null ? 0 : data.length;
        int sum = 0;
        if (data != null) for (byte x : data) sum += x & 0xff;
        ByteBuffer b = ByteBuffer.allocate(24 + length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(length).putInt(sum).putInt(command ^ 0xffffffff);
        if (data != null) b.put(data);
        out.write(b.array());
        out.flush();
    }

    // MARK: - This phone's key (private storage; never logged)

    static synchronized KeyPair keys(File dir) throws IOException {
        File privateFile = new File(dir, "adbkey"), publicFile = new File(dir, "adbkey.pub");
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            if (privateFile.exists() && publicFile.exists()) {
                try {
                    return new KeyPair(factory.generatePublic(new X509EncodedKeySpec(readFile(publicFile))),
                            factory.generatePrivate(new PKCS8EncodedKeySpec(readFile(privateFile))));
                } catch (GeneralSecurityException | IOException ignored) {
                    // Unreadable: a new key, and the TV asks again.
                }
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            writeFile(privateFile, pair.getPrivate().getEncoded());
            writeFile(publicFile, pair.getPublic().getEncoded());
            return pair;
        } catch (GeneralSecurityException e) {
            throw new IOException("Clé ADB : " + e.getMessage());
        }
    }

    static byte[] sign(PrivateKey key, byte[] token) throws IOException {
        try {
            byte[] block = new byte[256];
            block[1] = 1;
            int separator = block.length - SHA1_PREFIX.length - token.length - 1;
            for (int i = 2; i < separator; i++) block[i] = (byte) 0xff;
            System.arraycopy(SHA1_PREFIX, 0, block, separator + 1, SHA1_PREFIX.length);
            System.arraycopy(token, 0, block, separator + 1 + SHA1_PREFIX.length, token.length);
            Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return cipher.doFinal(block);
        } catch (GeneralSecurityException e) {
            throw new IOException("Signature ADB : " + e.getMessage());
        }
    }

    /** Public key in adbd's format (its RSAPublicKey struct in base64, then a name the TV shows). */
    static byte[] publicKeyBlob(PublicKey key, String name) {
        RSAPublicKey rsa = (RSAPublicKey) key;
        BigInteger n = rsa.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64);
        b.putInt(n0inv.intValue());
        b.put(littleEndian(n, 256));
        b.put(littleEndian(rr, 256));
        b.putInt(rsa.getPublicExponent().intValue());
        String text = Base64.getEncoder().encodeToString(b.array()) + " " + name + "\0";
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] littleEndian(BigInteger value, int size) {
        byte[] big = value.toByteArray();
        byte[] little = new byte[size];
        for (int i = 0; i < size && i < big.length; i++) little[i] = big[big.length - 1 - i];
        return little;
    }

    private static byte[] readFile(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) throw new IOException("Impossible d'enregistrer " + f.getName());
    }
}
