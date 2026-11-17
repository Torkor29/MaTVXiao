#!/usr/bin/env python3
"""A fake Android TV adbd for testing MaTV Installer's ADB client.

Checks what a real adbd checks: message magic, the RSA signature of the token (PKCS#1 v1.5 block with the SHA-1
prefix, verified with the key parsed from adbd's RSAPublicKey struct), max payload size, one WRTE at a time
(OKAY flow control), and the exact byte count of a streamed `cmd package install -S`.

  fake_tv.py PORT [--old] [--maxdata N] [--deny]
    --old   behave like Android 8-10: never answer the connection that offered the key (a new one must sign in)
"""
import base64, hashlib, os, socket, struct, sys, threading, time

CNXN, AUTH, OPEN, OKAY, CLSE, WRTE = 0x4e584e43, 0x48545541, 0x4e45504f, 0x59414b4f, 0x45534c43, 0x45545257
PREFIX = bytes([0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14])

args = sys.argv[1:]
PORT = int(args[0])
OLD = "--old" in args
DENY = "--deny" in args
MAXDATA = int(args[args.index("--maxdata") + 1]) if "--maxdata" in args else 256 * 1024
KNOWN = []          # (n, e) of accepted keys
LOG = open(os.environ.get("FAKE_LOG", "/tmp/fake_tv.log"), "a")
installed = {"io.freemytv": "2.7.1"}

def log(*a):
    LOG.write(" ".join(str(x) for x in a) + "\n"); LOG.flush()

def recv_all(c, n):
    b = b""
    while len(b) < n:
        x = c.recv(n - len(b))
        if not x: raise EOFError
        b += x
    return b

def read(c):
    h = recv_all(c, 24)
    cmd, a0, a1, ln, ck, magic = struct.unpack("<6I", h)
    assert magic == cmd ^ 0xffffffff, "bad magic"
    data = recv_all(c, ln) if ln else b""
    assert sum(data) & 0xffffffff == ck, "bad checksum"
    return cmd, a0, a1, data

def send(c, cmd, a0, a1, data=b""):
    c.sendall(struct.pack("<6I", cmd, a0, a1, len(data), sum(data) & 0xffffffff, cmd ^ 0xffffffff) + data)

def parse_key(blob):
    text = blob.rstrip(b"\0").decode()
    b64, name = text.split(" ", 1)
    raw = base64.b64decode(b64)
    assert len(raw) == 524, len(raw)
    words, n0inv = struct.unpack("<II", raw[:8])
    assert words == 64
    n = int.from_bytes(raw[8:264], "little")
    rr = int.from_bytes(raw[264:520], "little")
    e = struct.unpack("<I", raw[520:524])[0]
    assert rr == pow(2, 4096, n), "bad rr"
    assert ((n % 2**32) * (n0inv % 2**32)) % 2**32 == 2**32 - 1, "bad n0inv"
    return (n, e), name

def verify(key, token, sig):
    n, e = key
    m = pow(int.from_bytes(sig, "big"), e, n).to_bytes(256, "big")
    want = b"\x00\x01" + b"\xff" * (256 - 3 - len(PREFIX) - len(token)) + b"\x00" + PREFIX + token
    return m == want

SHELL = {
    "getprop ro.product.brand": "Xiaomi\n",
    "getprop ro.product.model": "MiTV-MOOR2\n",
    "getprop ro.build.version.release": "11\n",
    "getprop ro.product.cpu.abilist": "armeabi-v7a,armeabi\n",
    "getprop ro.serialno": "FAKE123\n",
}

def shell_out(cmd):
    if cmd in SHELL: return SHELL[cmd]
    if cmd.startswith("dumpsys package "):
        p = cmd.split()[2].split("|")[0].strip()
        return ("    versionName=%s\n" % installed[p]) if p in installed else ""
    if cmd.startswith("pm list packages"):
        return "".join("package:%s\n" % p for p in installed)
    if cmd.startswith("am start"):
        return "Starting: Intent { cmp=io.freemytv/.MaTVSettingsActivity }\n"
    return ""

def session(c, addr):
    c.settimeout(60)
    token = os.urandom(20)
    authed = False
    next_id = 100
    streams = {}
    try:
        cmd, a0, a1, data = read(c)
        assert cmd == CNXN, "first must be CNXN"
        assert data.startswith(b"host::"), data
        send(c, AUTH, 1, 0, token)
        while True:
            cmd, a0, a1, data = read(c)
            if not authed:
                assert cmd == AUTH, hex(cmd)
                if a0 == 2:
                    if any(verify(k, token, data) for k in KNOWN):
                        authed = True; log("signed-in", addr)
                        send(c, CNXN, 0x01000001, MAXDATA, b"device::ro.product.name=moor;\0")
                    else:
                        token = os.urandom(20); send(c, AUTH, 1, 0, token)
                elif a0 == 3:
                    key, name = parse_key(data)
                    log("prompt shown for", name)
                    time.sleep(1.5)
                    if DENY:
                        log("denied"); return
                    KNOWN.append(key); log("allowed")
                    if OLD:
                        time.sleep(30); return   # this connection never hears back
                    authed = True
                    send(c, CNXN, 0x01000001, MAXDATA, b"device::\0")
                continue
            if cmd == OPEN:
                svc = data.rstrip(b"\0").decode()
                local = a0; next_id += 1; rid = next_id
                send(c, OKAY, rid, local)
                if svc.startswith("shell:"):
                    out = shell_out(svc[6:]).encode()
                    if out:
                        send(c, WRTE, rid, local, out)
                        r = read(c); assert r[0] == OKAY and r[1] == local and r[2] == rid, r
                    send(c, CLSE, rid, local)
                    r = read(c); assert r[0] == CLSE, r
                elif svc.startswith("exec:cmd package install -r -S "):
                    size = int(svc.split()[-1]); got = 0; h = hashlib.sha256(); chunks = 0
                    while got < size:
                        r = read(c)
                        assert r[0] == WRTE and r[1] == local and r[2] == rid, r[:3]
                        assert len(r[3]) <= MAXDATA, "payload over maxdata"
                        got += len(r[3]); h.update(r[3]); chunks += 1
                        send(c, OKAY, rid, local)
                    assert got == size, (got, size)
                    log("installed", size, h.hexdigest(), "chunks", chunks)
                    send(c, WRTE, rid, local, b"Success\n")
                    r = read(c); assert r[0] == OKAY
                    send(c, CLSE, rid, local)
                    r = read(c); assert r[0] == CLSE
                else:
                    send(c, CLSE, rid, local)
    except EOFError:
        pass
    except Exception as e:
        log("ERROR", repr(e))
    finally:
        c.close()

s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("127.0.0.1", PORT)); s.listen(8)
log("listening", PORT, "old" if OLD else "new", "maxdata", MAXDATA)
while True:
    c, a = s.accept()
    threading.Thread(target=session, args=(c, a), daemon=True).start()
