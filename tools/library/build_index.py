"""Packs the crawled Commons lists into the web app's library index (library/index.json)."""
import json, os, sys, time
src, out = sys.argv[1], sys.argv[2]
THEMES = [("landscapes", "Paysages"), ("mountains", "Montagnes"), ("lakes", "Lacs"), ("sea", "Mer & plages"),
          ("sunsets", "Couchers de soleil"), ("waterfalls", "Cascades"), ("forests", "Forêts"), ("clouds", "Ciel & nuages"),
          ("volcanoes", "Volcans"), ("cities", "Villes"), ("paris", "Paris"), ("japan", "Japon"),
          ("paintings", "Peintures"), ("astronomy", "Espace")]
MERGE = {"sea": ["beaches", "coasts"], "cities": ["cityscapes", "skylines"]}
themes = []
for tid, name in THEMES:
    items, seen = [], set()
    for part in MERGE.get(tid, [tid]):
        p = os.path.join(src, part + ".json")
        if not os.path.exists(p): continue
        for it in json.load(open(p)):
            if it["id"] in seen: continue
            seen.add(it["id"]); items.append(it)
    if len(items) < 8: continue
    # Largest first: the sharpest on a 4K panel lead each theme.
    items.sort(key=lambda i: -min(i["w"], 7000) * min(i["h"], 4000))
    themes.append({"id": tid, "name": name, "cover": 0, "items": items})
json.dump({"v": 1, "updated": time.strftime("%Y-%m-%d"), "source": "Wikimedia Commons featured pictures",
           "themes": themes}, open(out, "w"), ensure_ascii=False, separators=(",", ":"))
print(out, sum(len(t["items"]) for t in themes), "images,", len(themes), "themes", os.path.getsize(out) // 1024, "KB")
