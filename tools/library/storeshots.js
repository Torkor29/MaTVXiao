// The demo (MaTV Connect with a TV in the page) at Google Play's phone size (1080 x 1920): the control screens.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 411, height: 731 }, deviceScaleFactor: 1080 / 411, isMobile: true, hasTouch: true });
  const p = await ctx.newPage();
  await p.goto('file://' + process.argv[2] + '#demo');
  await p.waitForTimeout(3500);
  for (const [tab, name] of [['tv', 'b1-tv'], ['discover', 'b2-discover'], ['library', 'b3-library'], ['remote', 'b4-remote']]) {
    await p.click(`nav.tabs button[data-go="${tab}"]`);
    await p.waitForTimeout(tab === 'discover' ? 4000 : 2500);
    await p.screenshot({ path: `${process.argv[3]}/${name}.png` });
  }
  await b.close();
})();
