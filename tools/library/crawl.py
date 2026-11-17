"""Builds the online library index from Wikimedia Commons featured pictures (landscape, large, JPEG)."""
import json, sys, time, urllib.request, urllib.parse, re, html

UA = "MaTV/2.5 (https://github.com/Torkor29/MaTVXiao; TV wallpaper library index)"
API = "https://commons.wikimedia.org/w/api.php"

def get(params):
    params = dict(params, action="query", format="json", prop="imageinfo",
                  iiprop="url|size|mime|extmetadata", iiurlwidth="960",
                  iiextmetadatafilter="ObjectName|Artist|LicenseShortName|DateTimeOriginal")
    url = API + "?" + urllib.parse.urlencode(params)
    for attempt in range(8):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=40) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            wait = int(e.headers.get("retry-after") or 30) + 2
            print("  wait", e.code, wait, file=sys.stderr); time.sleep(wait)
        except Exception as e:
            print("  err", e, file=sys.stderr); time.sleep(10)
    raise SystemExit("gave up")

def clean(s):
    s = re.sub(r"<[^>]+>", "", s or "")
    s = html.unescape(s).strip()
    return re.sub(r"\s+", " ", s)

def crawl(source, limit=400):
    out, cont = [], {}
    while len(out) < limit:
        if source.startswith("Category:"):
            p = dict(generator="categorymembers", gcmtitle=source, gcmtype="file", gcmlimit="50")
        else:
            p = dict(generator="images", titles=source, gimlimit="50")
        p.update(cont)
        d = get(p)
        for pg in d.get("query", {}).get("pages", {}).values():
            ii = (pg.get("imageinfo") or [{}])[0]
            w, h = ii.get("width", 0), ii.get("height", 1)
            if ii.get("mime") != "image/jpeg" or w < 2600 or not (1.35 <= w / h <= 2.2):
                continue
            meta = ii.get("extmetadata", {})
            title = clean(meta.get("ObjectName", {}).get("value")) or pg["title"][5:].rsplit(".", 1)[0]
            thumb = ii.get("thumburl", "")
            big = ii["url"] if w <= 3840 else thumb.replace("/960px-", "/3840px-")
            if "/960px-" not in thumb:
                continue
            out.append({"id": pg["pageid"], "t": title[:90], "a": clean(meta.get("Artist", {}).get("value"))[:70],
                        "l": clean(meta.get("LicenseShortName", {}).get("value")), "w": w, "h": h,
                        "th": thumb, "u": big, "p": "https://commons.wikimedia.org/wiki/" + urllib.parse.quote(pg["title"].replace(" ", "_"))})
        if "continue" not in d:
            break
        cont = {k: v for k, v in d["continue"].items()}
        time.sleep(4)
    return out

if __name__ == "__main__":
    name, source = sys.argv[1], sys.argv[2]
    items = crawl(source)
    json.dump(items, open(f"/home/claude/work/lib/{name}.json", "w"), ensure_ascii=False)
    print(name, len(items))
