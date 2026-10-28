// Capture real browser screenshots of the deployed package via Playwright + Edge.
const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');

const BASE = (process.env.M6_BASE || 'http://127.0.0.1:8088').replace(/\/+$/, '');
const OUT = process.env.M6_SHOTS || '.';
const MODE = process.env.M6_MODE || 'anon';

function slug(p) {
  if (p === '/') return 'home';
  return p.replace(/^\//, '').replace(/[\/?=&]+/g, '-');
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 1 });
  const page = await context.newPage();
  const results = [];

  if (MODE === 'login') {
    const studentNo = process.env.M6_STUDENT || 'demo02';
    await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
    await page.fill('input[name="username"]', studentNo);
    await page.fill('input[type="password"]', 'Demo@123456');
    await Promise.all([
      page.waitForURL((u) => !u.pathname.endsWith('/login'), { timeout: 15000 }).catch(() => {}),
      page.click('button[type="submit"]'),
    ]);
    await page.waitForTimeout(800);
  }

  const pages = (process.env.M6_PAGES || '/,/records/1,/records/new').split(',').map((s) => s.trim()).filter(Boolean);
  for (const p of pages) {
    const url = BASE + p;
    const resp = await page.goto(url, { waitUntil: 'networkidle', timeout: 30000 });
    await page.waitForTimeout(700);
    const info = await page.evaluate(() => ({
      title: document.title,
      text: (document.body.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 1200),
      h1: Array.from(document.querySelectorAll('h1,h2')).map((e) => (e.innerText || '').trim()).filter(Boolean).slice(0, 6),
      hasDegradedBanner: Boolean(document.querySelector('[data-testid="pair-degraded-banner"]')),
      hasPairPanel: Boolean(document.querySelector('[data-testid="pair-panel"]')),
      cardCount: document.querySelectorAll('[data-testid="pair-card"]').length,
      tiers: Array.from(document.querySelectorAll('[data-testid="pair-tier"]')).map((e) => (e.innerText || '').trim()),
      scores: Array.from(document.querySelectorAll('[data-testid="pair-score"]')).map((e) => (e.innerText || '').trim()),
      degradedBadges: Array.from(document.querySelectorAll('[data-testid="pair-degraded"]')).map((e) => (e.innerText || '').trim()),
      lowCountText: (document.querySelector('[data-testid="pair-low-count"]')?.innerText || '').replace(/\s+/g, ' ').trim(),
    }));
    const file = path.join(OUT, `m6-${slug(p)}.png`);
    await page.screenshot({ path: file, fullPage: true });
    results.push({ path: p, httpStatus: resp ? resp.status() : null, url: page.url(), file, ...info });
  }

  await browser.close();
  fs.writeFileSync(path.join(OUT, 'shots.json'), JSON.stringify(results, null, 2), 'utf8');
  console.log(JSON.stringify(results, null, 2));
})().catch((e) => {
  console.error('SHOTS_FAIL', e.message);
  process.exit(1);
});
