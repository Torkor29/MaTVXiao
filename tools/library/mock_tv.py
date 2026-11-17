"""A stand-in for the TV's Companion API, to look at the web app in a browser (same routes, same answers)."""
import json, os, sys, time, random, urllib.parse
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
ASSETS = sys.argv[1]; INDEX = sys.argv[2]; TOKEN = "testtoken_abcdefghijklmnop"
ART = os.path.join(ASSETS, "art")
items = []
for f in sorted(os.listdir(ART)):
    if f.endswith(".jpg"):
        items.append({"id": f[:-4], "title": ("Paysage " if f.startswith("land") else "Œuvre ") + f[-6:-4], "credit": "",
                      "license": "", "link": "", "source": "bundled", "collection": "landscape" if f.startswith("land") else "art",
                      "remote": "", "enabled": True, "added": 0})
items[3]["enabled"] = False
items[0]["title"] = "Les Nymphéas"; items[0]["credit"] = "Claude Monet"
lib = {"collections": [{"id": "art", "name": "Art"}, {"id": "landscape", "name": "Paysages"}], "items": items}
state = {"tv": {"app": "MaTV", "model": "MiTV-MOOR2", "brand": "Xiaomi", "version": "2.5.0", "android": "11"},
         "saver": {"mode": "art", "collection": "landscape", "seconds": 60, "clock": True, "caption": False},
         "library": {"count": len(items), "max": 200},
         "picks": {"mood": "all", "style": "billboard", "on": True, "moods": [{"id": a, "name": b} for a, b in [("all", "Tout"), ("laugh", "Rire"), ("action", "Action"), ("thrill", "Frissons"), ("mystery", "Enquête"), ("feel", "Émotion"), ("escape", "Évasion"), ("family", "En famille"), ("learn", "Découvrir")]]}}
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def send(self, code, body, ctype="application/json"):
        if isinstance(body, (dict, list)): body = json.dumps(body).encode()
        self.send_response(code); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def auth(self):
        q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        return self.headers.get("Authorization") == "Bearer " + TOKEN or q.get("t", [""])[0] == TOKEN
    def do_GET(self):
        p = urllib.parse.urlparse(self.path).path
        if p in ("/", "/index.html"): return self.send(200, open(os.path.join(ASSETS, "connect/index.html"), "rb").read(), "text/html; charset=utf-8")
        if p == "/icon.png": return self.send(200, open(os.path.join(ASSETS, "connect/icon.png"), "rb").read(), "image/png") if os.path.exists(os.path.join(ASSETS, "connect/icon.png")) else self.send(404, {})
        if p == "/library.json": return self.send(200, open(INDEX, "rb").read())
        if p == "/api/hello": return self.send(200, dict(state["tv"], paired=self.auth()))
        if not self.auth(): return self.send(401, {"ok": False, "error": "Ce téléphone n'est pas connecté."})
        if p == "/api/state": state["library"]["count"] = len(items); return self.send(200, state)
        if p == "/api/wallpapers": return self.send(200, dict(lib, active=state["saver"]["collection"], max=200))
        if p.startswith("/img/"): return self.send(200, open(os.path.join(ART, p[5:] + ".jpg"), "rb").read(), "image/jpeg")
        if p == "/api/apps": return self.send(200, {"apps": [{"pkg": x, "name": n} for x, n in [("netflix", "Netflix"), ("youtube", "YouTube"), ("prime", "Prime Video"), ("disney", "Disney+"), ("canal", "myCANAL"), ("live", "MaTV Live"), ("spotify", "Spotify"), ("plex", "Plex"), ("mx", "MX Player"), ("arte", "ARTE")]]})
        if p.startswith("/icon/"): return self.send(404, {})
        self.send(404, {"ok": False, "error": "inconnu"})
    def do_POST(self):
        p = urllib.parse.urlparse(self.path).path
        n = int(self.headers.get("Content-Length") or 0); body = self.rfile.read(n)
        if not self.auth(): return self.send(401, {"ok": False})
        b = json.loads(body) if body and self.headers.get("Content-Type", "").startswith("application/json") else {}
        if p == "/api/settings":
            for k in ("mode", "collection", "seconds", "clock", "caption"):
                if k in b: state["saver"][k] = b[k]
            for k in ("mood", "style"):
                if k in b: state["picks"][k] = b[k]
            return self.send(200, state)
        if p == "/api/add":
            time.sleep(1.2); it = dict(items[random.randrange(len(items))]); it.update(id="x%d" % random.randrange(10**6), remote=b.get("remote"), title=b.get("title"), collection=b.get("collection"))
            it["id"] = items[random.randrange(len(items))]["id"]; items.insert(0, it); return self.send(200, {"ok": True, "item": it})
        if p == "/api/update":
            for it in items:
                if it["id"] in b.get("ids", []):
                    for k in ("collection", "enabled", "title"):
                        if k in b: it[k] = b[k]
            return self.send(200, {"ok": True})
        if p == "/api/delete":
            items[:] = [i for i in items if i["id"] not in b.get("ids", [])]; return self.send(200, {"ok": True})
        if p == "/api/collections":
            if b["action"] == "create": cid = "c%d" % random.randrange(1000); lib["collections"].append({"id": cid, "name": b["name"]}); return self.send(200, {"ok": True, "id": cid})
            return self.send(200, {"ok": True})
        return self.send(200, {"ok": True})
ThreadingHTTPServer(("127.0.0.1", 8765), H).serve_forever()
