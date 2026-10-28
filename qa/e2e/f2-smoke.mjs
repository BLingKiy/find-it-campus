// v2 · F2 三页面 × 三态冒烟 + 截图 + 页面 DOM 手机号扫描。
//
// 三页面：列表 / 、详情 /records/1 、提交 /records/new
// 三态：匿名 / 本人（demo01，1 号记录所有者）/ 非本人（demo02）
// 截图落 docs/testing/v2/shots/，每张底部固定条写明「身份 + 当前 URL」。
//
// 运行：node qa/e2e/f2-smoke.mjs
// 前置：后端 18080、前端 5173（VITE_USE_MOCK=false）、库为 seed 初始状态。

import { chromium } from 'playwright';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Tester, assert, eq } from '../lib/tester.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..', '..');
const SHOTS = resolve(root, 'docs', 'testing', 'v2', 'shots');
const BASE = process.env.WEB_BASE ?? 'http://localhost:5173';
const PASSWORD = 'Demo@123456';
const PHONE = /1[3-9]\d{9}/g;
const PHONE_MASK = (s) => s.replace(PHONE, (m) => `${m.slice(0, 3)}****${m.slice(7)}`);

const t = new Tester('f2-smoke');
const shots = [];
const domHits = [];
const pageErrors = [];

const browser = await chromium.launch({ channel: 'msedge', headless: true });
await mkdir(SHOTS, { recursive: true });

async function identity(name) {
  const context = await browser.newContext({
    viewport: { width: 1280, height: 900 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
  });
  const page = await context.newPage();
  page.on('pageerror', (err) => pageErrors.push(`${name}: ${String(err)}`));
  page.on('console', (msg) => {
    if (msg.type() !== 'error') return;
    const text = msg.text();
    if (/ERR_CONNECTION_REFUSED|Failed to load resource: net::|Failed to load resource: the server responded with a status of/.test(text)) return;
    pageErrors.push(`${name} console: ${text}`);
  });
  return { context, page };
}

async function loginUI(page, studentNo) {
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
  await page.fill('#field-studentNo', studentNo);
  await page.fill('#field-password', PASSWORD);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 10000 });
  await page.getByRole('button', { name: new RegExp(`${studentNo}|退出`) }).first().waitFor({ timeout: 10000 });
}

/** 页面 DOM 手机号扫描：innerText + innerHTML；命中即记录。 */
async function scanDom(state, page) {
  const text = await page.evaluate(() => document.body.innerText + '\n' + document.body.innerHTML);
  const hits = text.match(PHONE) ?? [];
  domHits.push({ state, url: page.url(), hits: hits.map(PHONE_MASK) });
  return hits;
}

/** 截图前在页面底部加固定条，写明身份与 URL；再做 fullPage 截图。 */
async function shot(page, file, label) {
  await page.evaluate(({ label, url }) => {
    document.getElementById('f2-url-banner')?.remove();
    const bar = document.createElement('div');
    bar.id = 'f2-url-banner';
    bar.textContent = `【${label}】${url}`;
    Object.assign(bar.style, {
      position: 'fixed',
      left: '0',
      right: '0',
      bottom: '0',
      zIndex: '2147483647',
      background: '#111827',
      color: '#f9fafb',
      font: '13px/1.6 Consolas, monospace',
      padding: '6px 12px',
      borderTop: '2px solid #f59e0b',
      wordBreak: 'break-all',
    });
    document.body.appendChild(bar);
  }, { label, url: page.url() });
  const path = resolve(SHOTS, file);
  await page.screenshot({ path, fullPage: true });
  shots.push({ file, label, url: page.url(), path: `docs/testing/v2/shots/${file}` });
  await page.evaluate(() => document.getElementById('f2-url-banner')?.remove());
}

const anon = await identity('anon');
const owner = await identity('owner');
const non = await identity('non');
await loginUI(owner.page, 'demo01');
await loginUI(non.page, 'demo02');

// ---------------------------------------------------------------- 列表页

await t.case('F2-SM-01', 'P0', '列表页·匿名：21 条 / 10 张卡片 / DOM 无手机号', async () => {
  await anon.page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await anon.page.getByRole('heading', { name: '启事列表' }).waitFor();
  await anon.page.locator('.record-card').first().waitFor();
  eq(await anon.page.locator('.record-card').count(), 10, '首屏卡片数');
  const total = await anon.page.locator('.pagination-bar__total').innerText();
  assert(total.includes('共 21 条'), `分页文案：${total}`);
  const hits = await scanDom('list-anon', anon.page);
  eq(hits, [], '匿名列表 DOM 手机号');
  await shot(anon.page, '01-list-anon.png', '匿名 · 列表');
});

await t.case('F2-SM-02', 'P0', '列表页·本人：卡片加载且有「我的启事」徽标', async () => {
  await owner.page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await owner.page.locator('.record-card').first().waitFor();
  eq(await owner.page.locator('.record-card').count(), 10, '首屏卡片数');
  const badges = await owner.page.getByTestId('card-mine-badge').count();
  assert(badges > 0, `本人列表应出现「我的启事」徽标，实际 ${badges}`);
  await shot(owner.page, '02-list-owner.png', '本人 demo01 · 列表');
});

await t.case('F2-SM-03', 'P0', '列表页·非本人：卡片加载 / DOM 无手机号', async () => {
  await non.page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await non.page.locator('.record-card').first().waitFor();
  eq(await non.page.locator('.record-card').count(), 10, '首屏卡片数');
  const hits = await scanDom('list-non-owner', non.page);
  eq(hits, [], '非本人列表 DOM 手机号');
  await shot(non.page, '03-list-non-owner.png', '非本人 demo02 · 列表');
});

// ---------------------------------------------------------------- 详情页

await t.case('F2-SM-04', 'P0', '详情页·匿名：#1 标题可见、匿名横幅与登录引导、无号码', async () => {
  await anon.page.goto(BASE + '/records/1', { waitUntil: 'domcontentloaded' });
  await anon.page.locator('.record-detail__title').waitFor();
  eq(await anon.page.locator('.record-detail__title').innerText(), '图书馆丢了黑色钱包', '详情标题');
  await anon.page.getByText('你正在匿名浏览').waitFor();
  assert(await anon.page.getByTestId('contact-login-cta').isVisible(), '匿名应显示「查看联系方式」');
  assert(await anon.page.getByTestId('masked-placeholder').first().isVisible(), '匿名应显示遮蔽占位');
  const hits = await scanDom('detail-anon', anon.page);
  eq(hits, [], '匿名详情 DOM 手机号');
  await shot(anon.page, '04-detail-anon.png', '匿名 · 详情 #1');
});

await t.case('F2-SM-05', 'P0', '详情页·本人：#1 明文联系方式可见', async () => {
  await owner.page.goto(BASE + '/records/1', { waitUntil: 'domcontentloaded' });
  await owner.page.locator('.record-detail__title').waitFor();
  const contact = await owner.page.getByTestId('contact-owner').innerText();
  eq(contact, '13800000001', '本人看到的联系方式');
  await shot(owner.page, '05-detail-owner.png', '本人 demo01 · 详情 #1');
});

await t.case('F2-SM-06', 'P0', '详情页·非本人：#1 只见遮蔽占位，DOM 无手机号', async () => {
  await non.page.goto(BASE + '/records/1', { waitUntil: 'domcontentloaded' });
  await non.page.locator('.record-detail__title').waitFor();
  assert(await non.page.getByTestId('masked-placeholder').first().isVisible(), '非本人应看到遮蔽占位');
  eq(await non.page.getByTestId('contact-owner').count(), 0, '非本人不应看到本人联系方式节点');
  const hits = await scanDom('detail-non-owner', non.page);
  eq(hits, [], '非本人详情 DOM 手机号');
  await shot(non.page, '06-detail-non-owner.png', '非本人 demo02 · 详情 #1');
});

// ---------------------------------------------------------------- 提交页

await t.case('F2-SM-07', 'P0', '提交页·匿名：被守卫重定向到登录并带 redirect', async () => {
  await anon.page.goto(BASE + '/records/new', { waitUntil: 'domcontentloaded' });
  await anon.page.waitForURL(/\/login/, { timeout: 10000 });
  const url = anon.page.url();
  assert(url.includes('redirect=%2Frecords%2Fnew'), `redirect 参数：${url}`);
  await anon.page.getByRole('heading', { name: /登录/ }).waitFor();
  await shot(anon.page, '07-create-anon.png', '匿名 · 提交页被守卫');
});

await t.case('F2-SM-08', 'P0', '提交页·本人：表单可填', async () => {
  await owner.page.goto(BASE + '/records/new', { waitUntil: 'domcontentloaded' });
  await owner.page.getByRole('heading', { name: '发布启事' }).waitFor();
  assert(await owner.page.locator('#field-title').isVisible(), '标题输入框应可见');
  await shot(owner.page, '08-create-owner.png', '本人 demo01 · 提交页');
});

await t.case('F2-SM-09', 'P0', '提交页·非本人：表单可填（发布归自己）', async () => {
  await non.page.goto(BASE + '/records/new', { waitUntil: 'domcontentloaded' });
  await non.page.getByRole('heading', { name: '发布启事' }).waitFor();
  assert(await non.page.locator('#field-title').isVisible(), '标题输入框应可见');
  await shot(non.page, '09-create-non-owner.png', '非本人 demo02 · 提交页');
});

// ---------------------------------------------------------------- 通知页 DOM

await t.case('F2-SM-10', 'P1', '通知页 DOM 无手机号（demo02 收件人）', async () => {
  await non.page.goto(BASE + '/notifications', { waitUntil: 'domcontentloaded' });
  await non.page.getByTestId('notifications-list').or(non.page.getByText('暂无通知')).first().waitFor({ timeout: 10000 });
  const hits = await scanDom('notifications-demo02', non.page);
  eq(hits, [], '通知页 DOM 手机号');
  await non.page.screenshot({ path: resolve(SHOTS, '10-notifications-non-owner.png'), fullPage: true });
  shots.push({ file: '10-notifications-non-owner.png', label: '非本人 demo02 · 通知', url: non.page.url(), path: 'docs/testing/v2/shots/10-notifications-non-owner.png' });
});

const summary = t.print();
const result = {
  ...summary,
  shots,
  domScan: { regex: '1[3-9]\\d{9}', probes: domHits.length, hits: domHits.reduce((n, x) => n + x.hits.length, 0), cases: domHits },
  pageErrors,
};
await writeFile(resolve(SHOTS, 'f2-smoke.json'), JSON.stringify(result, null, 2), 'utf8');
await writeFile(resolve(here, '..', 'reports', 'f2-smoke.json'), JSON.stringify(result, null, 2), 'utf8');

console.log(`\n  截图 ${shots.length} 张 → docs/testing/v2/shots/`);
console.log(`  DOM 扫描：${domHits.length} 个页面，命中 ${result.domScan.hits}`);
if (pageErrors.length) console.log(`  页面错误：${JSON.stringify(pageErrors)}`);

await browser.close();
process.exit(summary.failed > 0 || result.domScan.hits > 0 || pageErrors.length > 0 ? 1 : 0);
