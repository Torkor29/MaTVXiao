// Opens the web app's demo (no TV) from a file:// URL like the apps do, goes through every tab, and reports errors.
const { chromium, devices } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ ...devices['iPhone 14 Pro'], deviceScaleFactor: 2 });
  const p = await ctx.newPage();
  const errors = [];
  p.on('pageerror', e => errors.push('pageerror ' + e.message));
  p.on('console', m => { if (m.type() === 'error') errors.push('console ' + m.text()); });
  await p.goto('file://' + process.argv[2] + '#demo');
  await p.waitForTimeout(2500);
  for (const tab of ['tv', 'discover', 'library', 'remote']) {
    await p.click(`nav.tabs button[data-go="${tab}"]`);
    await p.waitForTimeout(tab === 'discover' ? 3000 : 1200);
    await p.screenshot({ path: `/tmp/demo-${tab}.png` });
  }
  await p.click('nav.tabs button[data-go="tv"]'); await p.waitForTimeout(500);
  await p.click('[data-set="mood"][data-v="laugh"]'); await p.waitForTimeout(600);
  const mood = await p.$eval('[data-set="mood"][data-v="laugh"]', e => e.getAttribute('aria-pressed'));
  await p.click('nav.tabs button[data-go="discover"]'); await p.waitForTimeout(2500);
  const q = await p.$('[data-quick]'); if (q) { await q.click(); await p.waitForTimeout(800); }
  await p.click('nav.tabs button[data-go="library"]'); await p.waitForTimeout(800);
  const n = await p.$$eval('[data-wall]', a => a.length);
  console.log(JSON.stringify({ mood, walls: n, errors }, null, 1));
  await b.close();
})();
