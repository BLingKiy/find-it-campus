// M7 独立验收 · 三页截图（匿名 / 登录后） + 页面观察
import { createRequire } from 'node:module';
import { mkdir } from 'node:fs/promises';

const require = createRequire('C:/Project/hifox-workspaces/duancece/find-it-campus/qa/package.json');
const { chromium } = require('playwright');

const BASE = process.env.WEB_BASE ?? 'http://127.0.0.1:8088';
const SHOTS = 'C:/Project/deploy-accept/Z91-20261029-035223/ev/shots';
const PASSWORD = 'Demo@123456';

await mkdir(SHOTS, { recursive: true });

const out = [];
const log = (...a) => { const s = a.join(' '); out.push(s); console.log(s); };

const browser = await chromium.launch({ channel: 'msedge', headless: true });

async function ctx(name) {
  const c = await browser.newContext({ viewport: { width: 1366, height: 900 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' });
  const p = await c.newPage();
  const errs = [];
  p.on('pageerror', (e) => errs.push(name + ' pageerror: ' + String(e)));
  return { c, p, errs };
}

async function shot(page, file) {
  await page.screenshot({ path: `${SHOTS}/${file}`, fullPage: false });
  log(`  shot -> ${file}`);
}

async function describe(page, label) {
  const main = await page.evaluate(() => {
    const t = (sel) => document.querySelector(sel)?.textContent?.replace(/\s+/g, ' ').trim().slice(0, 160) ?? null;
    return {
      title: document.title,
      h1: t('h1'),
      h1all: Array.from(document.querySelectorAll('h1')).map((e) => e.textContent.replace(/\s+/g, ' ').trim()),
      bodyLen: document.body.innerText.replace(/\s+/g, ' ').trim().length,
      bodyHead: document.body.innerText.replace(/\s+/g, ' ').trim().slice(0, 320),
    };
  });
  log(`[${label}] url=${page.url()}`);
  log(`  document.title = ${JSON.stringify(main.title)}`);
  log(`  h1 = ${JSON.stringify(main.h1)}`);
  log(`  body.length = ${main.bodyLen}`);
  log(`  body.head = ${JSON.stringify(main.bodyHead)}`);
  return main;
}

// ---- 1. 匿名首页 ----
{
  const { c, p, errs } = await ctx('anon-home');
  const resp = await p.goto(BASE + '/', { waitUntil: 'networkidle' });
  log(`GET / -> HTTP ${resp.status()}`);
  await describe(p, 'anon-home');
  await shot(p, '01-home-anon.png');
  if (errs.length) log('  pageErrors: ' + errs.join(' | '));
  await c.close();
}

// ---- 2. 匿名详情 /records/1 ----
{
  const { c, p, errs } = await ctx('anon-detail');
  const resp = await p.goto(BASE + '/records/1', { waitUntil: 'networkidle' });
  log(`GET /records/1 -> HTTP ${resp.status()}`);
  await describe(p, 'anon-detail-1');
  await shot(p, '02-record-1-anon.png');
  if (errs.length) log('  pageErrors: ' + errs.join(' | '));
  await c.close();
}

// ---- 3. 匿名 /records/new ----
{
  const { c, p, errs } = await ctx('anon-new');
  const resp = await p.goto(BASE + '/records/new', { waitUntil: 'networkidle' });
  const httpStatus = resp.status();
  await p.waitForTimeout(1200);
  log(`GET /records/new -> HTTP ${httpStatus}; 落地 url = ${p.url()}`);
  await describe(p, 'anon-new');
  const hasForm = await p.locator('form').count();
  log(`  form count = ${hasForm}`);
  await shot(p, '03-record-new-anon.png');
  if (errs.length) log('  pageErrors: ' + errs.join(' | '));
  await c.close();
}

// ---- 4. 登录后 /records/new ----
{
  const { c, p, errs } = await ctx('owner');
  await p.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
  await p.fill('#field-studentNo', 'demo01');
  await p.fill('#field-password', PASSWORD);
  await p.getByRole('button', { name: '登录', exact: true }).click();
  await p.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 15000 });
  log(`登录 demo01 成功 -> url = ${p.url()}`);
  const resp = await p.goto(BASE + '/records/new', { waitUntil: 'networkidle' });
  log(`GET /records/new (已登录) -> HTTP ${resp.status()}; url = ${p.url()}`);
  const fields = await p.evaluate(() =>
    Array.from(document.querySelectorAll('input,textarea,select')).map((e) => ({
      tag: e.tagName.toLowerCase(), id: e.id || null, name: e.name || null,
      testid: e.getAttribute('data-testid'), ph: e.getAttribute('placeholder'),
    })));
  log('  表单控件 = ' + JSON.stringify(fields));
  await describe(p, 'owner-new');
  await shot(p, '04-record-new-loggedin.png');
  if (errs.length) log('  pageErrors: ' + errs.join(' | '));
  await c.close();
}

await browser.close();
console.log('\n---DONE---');
