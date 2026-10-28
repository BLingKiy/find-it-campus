// M7 独立验收 · 面 C 降级提示截图（详情页，owner 视角）
import { createRequire } from 'node:module';
import { mkdir } from 'node:fs/promises';

const require = createRequire('C:/Project/hifox-workspaces/duancece/find-it-campus/qa/package.json');
const { chromium } = require('playwright');

const BASE = process.env.WEB_BASE ?? 'http://127.0.0.1:8088';
const SHOTS = 'C:/Project/deploy-accept/Z91-20261029-035223/ev/shots';
const RID = process.env.RECORD_ID ?? '27';
const PASSWORD = 'Demo@123456';
await mkdir(SHOTS, { recursive: true });

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const ctx = await browser.newContext({ viewport: { width: 1366, height: 1000 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' });
const page = await ctx.newPage();

await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
await page.fill('#field-studentNo', 'demo01');
await page.fill('#field-password', PASSWORD);
await page.getByRole('button', { name: '登录', exact: true }).click();
await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 15000 });
console.log('login ok -> ' + page.url());

const resp = await page.goto(`${BASE}/records/${RID}`, { waitUntil: 'networkidle' });
console.log(`GET /records/${RID} -> HTTP ${resp.status()}`);
await page.waitForTimeout(1500);

const text = await page.evaluate(() => document.body.innerText.replace(/[ \t]+/g, ' ').trim());
console.log('--- 页面正文（前 1200 字） ---');
console.log(text.slice(0, 1200));

const body = text;
console.log('--- 关键词命中 ---');
for (const kw of ['已降级', '关键词匹配', '降级', 'LOCAL_KEYWORD', '匹配']) {
  console.log(`  ${kw}: ${body.includes(kw)}`);
}
console.log('--- 卡片 ---');
const cards = await page.evaluate(() => Array.from(document.querySelectorAll('[data-testid]'))
  .map((e) => e.getAttribute('data-testid')).filter((v, i, a) => a.indexOf(v) === i));
console.log('  data-testid: ' + JSON.stringify(cards));

await page.screenshot({ path: `${SHOTS}/05-record-${RID}-degraded-hint.png`, fullPage: true });
console.log(`shot -> 05-record-${RID}-degraded-hint.png`);

await browser.close();
