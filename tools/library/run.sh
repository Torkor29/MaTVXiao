set -u
while IFS='|' read -r name src; do
  [ -s "$name.json" ] && continue
  python3 -I crawl.py "$name" "$src"; sleep 8
done <<'L'
mountains|Category:Featured pictures of mountains
lakes|Category:Featured pictures of lakes
sunsets|Category:Featured pictures of sunsets
beaches|Category:Featured pictures of beaches
coasts|Category:Featured pictures of coasts
waterfalls|Category:Featured pictures of waterfalls
forests|Category:Featured pictures of forests
volcanoes|Category:Featured pictures of volcanoes
clouds|Category:Featured pictures of clouds
cityscapes|Category:Featured pictures of cityscapes
skylines|Category:Featured pictures of skylines
paris|Category:Featured pictures of Paris
japan|Category:Featured pictures of Japan
paintings|Commons:Featured pictures/Artwork/Paintings
astronomy|Commons:Featured pictures/Astronomy
L
echo DONE
