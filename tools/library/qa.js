// Visual QA of the web app: for every viewport and screen, flags covered text, horizontal overflow, text spilling out
// of its box, and the last content hidden under the tab bar; saves screenshots to look at.
const { chromium, devices } = require('playwright');
const OUT = process.argv[2] || '../qa';
const VIEWPORTS = [
  ['se', { ...devices['iPhone SE'], deviceScaleFactor: 2 }],
  ['iphone', { ...devices['iPhone 14 Pro'], deviceScaleFactor: 2 }],
  ['pixel', { ...devices['Pixel 7'], deviceScaleFactor: 2 }],
  ['ipad', { ...devices['iPad (gen 7)'], deviceScaleFactor: 1 }],
  ['desktop', { viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 }],
];
const STATES = ['pair', 'tv', 'discover', 'search', 'library', 'select', 'remote', 'pick', 'wall', 'import', 'bulkmove', 'toast'];

const CHECK = () => {
  const issues = [];
  const fixed = [...document.querySelectorAll('nav.tabs, #selbar, #toast, #sheet, #scrim')];
  const sheetOpen = document.querySelector('#sheet.on');
  const scope = sheetOpen || document.querySelector('#root');
  const hide = sheetOpen ? [] : fixed;
  const was = hide.map(e => e.style.visibility);
  hide.forEach(e => e.style.visibility = 'hidden');
  if (document.documentElement.scrollWidth > innerWidth + 1) issues.push(`horizontal overflow ${document.documentElement.scrollWidth} > ${innerWidth}`);
  const texts = [...scope.querySelectorAll('*')].filter(e => {
    if (!e.offsetParent && getComputedStyle(e).position !== 'fixed') return false;
    const own = [...e.childNodes].some(n => n.nodeType === 3 && n.textContent.trim());
    return own && getComputedStyle(e).visibility !== 'hidden' && getComputedStyle(e).opacity !== '0';
  });
  for (const e of texts) {
    const r = e.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) continue;
    // Inside a scroller that clips it on purpose (a rail, chips row): only judge what is in view.
    let clip = e.parentElement, hidden = false;
    while (clip && clip !== document.body) {
      const cs = getComputedStyle(clip);
      if (/(auto|scroll|hidden)/.test(cs.overflowX + cs.overflowY)) {
        const cr = clip.getBoundingClientRect();
        if (r.right <= cr.left + 1 || r.left >= cr.right - 1 || r.bottom <= cr.top + 1 || r.top >= cr.bottom - 1) hidden = true;
      }
      clip = clip.parentElement;
    }
    if (hidden) continue;
    const pts = [[r.left + 3, r.top + r.height / 2], [r.right - 3, r.top + r.height / 2], [r.left + r.width / 2, r.top + r.height / 2]];
    for (const [x, y] of pts) {
      if (x < 0 || y < 0 || x > innerWidth || y > innerHeight) continue;
      const hit = document.elementFromPoint(x, y);
      if (!hit || hit === e || e.contains(hit) || hit.contains(e)) continue;
      // An overlay that is part of the same tile (a badge on a picture) is fine; text under another element is not.
      issues.push(`covered: "${e.textContent.trim().slice(0, 40)}" by <${hit.tagName.toLowerCase()} class="${hit.className}">`);
      break;
    }
    const cs = getComputedStyle(e);
    if (e.scrollWidth > e.clientWidth + 2 && cs.textOverflow !== 'ellipsis' && cs.overflow !== 'visible' && e.clientWidth > 0)
      issues.push(`clipped text: "${e.textContent.trim().slice(0, 40)}" (${e.scrollWidth} > ${e.clientWidth})`);
    // Text wider than its parent box (spills out of a button or a pill).
    const p = e.parentElement && e.parentElement.getBoundingClientRect();
    if (p && (r.right > p.right + 2 || r.left < p.left - 2) && getComputedStyle(e.parentElement).overflowX === 'visible')
      issues.push(`spills: "${e.textContent.trim().slice(0, 40)}" out of <${e.parentElement.tagName.toLowerCase()} class="${e.parentElement.className}">`);
  }
  hide.forEach((e, i) => e.style.visibility = was[i]);
  for (const bar of document.querySelectorAll('nav.tabs, #selbar.on')) {
    const kids = [...bar.querySelectorAll('span, b')].filter(x => x.offsetParent && x.getBoundingClientRect().width > 0);
    for (let i = 0; i < kids.length; i++) for (let j = i + 1; j < kids.length; j++) {
      const a = kids[i].getBoundingClientRect(), b = kids[j].getBoundingClientRect();
      if (a.left < b.right - 1 && b.left < a.right - 1 && a.top < b.bottom - 1 && b.top < a.bottom - 1) issues.push(`bar labels touch: "${kids[i].textContent}" / "${kids[j].textContent}"`);
      if (Math.abs(a.top - b.top) < 4 && (a.right > b.left - 4 && a.left < b.left)) issues.push(`bar labels too close: "${kids[i].textContent}" / "${kids[j].textContent}"`);
    }
    const br = bar.getBoundingClientRect();
    if (br.left < -1 || br.right > innerWidth + 1) issues.push(`${bar.id || 'tab bar'} wider than the screen`);
    for (const k of kids) { const r = k.getBoundingClientRect(); if (r.right > br.right + 1 || r.left < br.left - 1) issues.push(`"${k.textContent}" sticks out of its bar`); }
  }
  return [...new Set(issues)];
};

(async () => {
  const proxy = process.env.HTTPS_PROXY ? { server: process.env.HTTPS_PROXY, bypass: '127.0.0.1' } : undefined;
  const browser = await chromium.launch({ proxy });
  const base = 'http://127.0.0.1:8765/';
  let total = 0;
  for (const [vn, opts] of VIEWPORTS) {
    const ctx = await browser.newContext({ ...opts, ignoreHTTPSErrors: true });
    const page = await ctx.newPage();
    page.on('pageerror', e => console.log(`[${vn}] PAGEERROR ${e.message}`));
    for (const st of STATES) {
      await page.goto('about:blank');
      if (st === 'pair') { await page.goto(base); await page.evaluate(() => localStorage.clear()); await page.goto(base + '?p'); await page.waitForTimeout(700); }
      else {
        await page.goto(base + '#k=testtoken_abcdefghijklmnop'); await page.waitForTimeout(700);
        const tab = { pick: 'discover', search: 'discover', wall: 'library', import: 'library', select: 'library', bulkmove: 'library', toast: 'tv' }[st] || st;
        await page.evaluate(t => document.querySelector(`nav.tabs button[data-go="${t}"]`).click(), tab);
        await page.waitForTimeout(st === 'discover' || st === 'pick' ? 2500 : 900);
        const click = sel => page.evaluate(s => document.querySelector(s).click(), sel);
        if (st === 'pick') { await click('[data-pick="1"]'); await page.waitForTimeout(1800); }
        if (st === 'wall') { await click('[data-wall]'); await page.waitForTimeout(1000); }
        if (st === 'search') { await page.fill('#q', 'Islande'); await page.press('#q', 'Enter'); await page.waitForTimeout(800); }
        if (st === 'select' || st === 'bulkmove') {
          await click('[data-act="select"]'); await page.waitForTimeout(300);
          await page.evaluate(() => [...document.querySelectorAll('[data-wall]')].slice(0, 3).forEach(b => b.click())); await page.waitForTimeout(500);
          if (st === 'bulkmove') { await click('[data-act="bulkmove"]'); await page.waitForTimeout(700); }
        }
        if (st === 'import') {
          const [fc] = await Promise.all([page.waitForEvent('filechooser'), click('[data-act="import"]')]);
          await fc.setFiles(['/home/claude/matv/launcher/app/assets/art/land03.jpg', '/home/claude/matv/launcher/app/assets/art/art02.jpg']);
          await page.waitForTimeout(1200);
        }
        if (st === 'toast') { await click('[data-set="mood"][data-v="feel"]'); await page.waitForTimeout(400); await page.screenshot({ path: `${OUT}/${vn}-toast-now.png` }); }
      }
      const issues = await page.evaluate(CHECK);
      // Scrolled to the end: the last thing on the page must sit above the tab bar.
      const bottom = await page.evaluate(() => {
        if (document.querySelector('#sheet.on') || !document.querySelector('nav.tabs')) return null;
        scrollTo(0, document.body.scrollHeight);
        const nav = document.querySelector('nav.tabs').getBoundingClientRect();
        if (nav.height > innerHeight / 2) return null;   // the desktop column
        const els = [...document.querySelectorAll('#main .view > *')];
        const last = els[els.length - 1].getBoundingClientRect();
        return last.bottom > nav.top + 1 ? `last block hidden under the tab bar (${Math.round(last.bottom)} > ${Math.round(nav.top)})` : null;
      });
      if (bottom) issues.push(bottom);
      await page.evaluate(() => scrollTo(0, 0)); await page.waitForTimeout(150);
      await page.screenshot({ path: `${OUT}/${vn}-${st}.png` });
      if (!['pick', 'wall', 'import', 'bulkmove', 'pair'].includes(st)) {
        await page.evaluate(() => scrollTo(0, document.body.scrollHeight)); await page.waitForTimeout(250);
        await page.screenshot({ path: `${OUT}/${vn}-${st}-end.png` });
      }
      total += issues.length;
      console.log(`${vn.padEnd(8)} ${st.padEnd(9)} ${issues.length ? issues.join(' | ') : 'ok'}`);
    }
    await ctx.close();
  }
  console.log('TOTAL', total);
  await browser.close();
})();
