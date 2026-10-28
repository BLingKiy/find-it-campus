// v2 · F2 降级路径实测（UI 全链路）：无 DEEPSEEK_API_KEY 时走一遍发布 → 出降级结果 → 页面提示可见。
//
// 断言链：POST /api/records 200 → t_record.pair_state=DEGRADED → t_pair_score.engine=LOCAL_KEYWORD
//         + degraded_reason=EMPTY_KEY → 详情页「已降级」提示可见（data-testid=pair-degraded-banner）。
//
// 运行：node qa/e2e/f2-degradation.mjs（后端进程必须未设置 DEEPSEEK_API_KEY）
// 前置：后端 18080、前端 5173、库为 seed 初始状态。

import { chromium } from 'playwright';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Tester, assert, eq } from '../lib/tester.mjs';
import { record, resetSeed, val, waitUntil } from '../lib/db.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..', '..');
const SHOTS = resolve(root, 'docs', 'testing', 'v2', 'shots');
const BASE = process.env.WEB_BASE ?? 'http://localhost:5173';

const t = new Tester('f2-degradation');
const evidence = {};
await mkdir(SHOTS, { recursive: true });

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' });
const page = await context.newPage();
const createdPosts = [];
page.on('response', (res) => {
  if (res.request().method() === 'POST' && res.url().endsWith('/api/records')) {
    createdPosts.push({ url: res.url(), status: res.status() });
  }
});

async function loginUI(studentNo) {
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
  await page.fill('#field-studentNo', studentNo);
  await page.fill('#field-password', 'Demo@123456');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 10000 });
}

await t.case('F2-DEG-UI-01', 'P0', '无 Key 发布：HTTP 200 + 跳详情 + 「已降级」提示可见', async () => {
  await loginUI('demo01');
  const title = `F2 降级实测 图书馆黑色钱包 ${Math.random().toString(36).slice(2, 8)}`;
  await page.goto(BASE + '/records/new', { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: '发布启事' }).waitFor();
  await page.check('input[name="type"][value="LOST"]');
  await page.fill('#field-title', title);
  await page.fill('#field-description', '在图书馆三楼自习区靠窗座位丢了黑色皮质钱包，里面有两张银行卡和现金。');
  await page.fill('#field-location', '图书馆三楼自习区');
  await page.fill('#field-occurredAt', '2026-10-21T15:20');
  await page.fill('#field-contact', '13800001234');
  await page.fill('#field-publisherName', '端测测');
  await page.getByRole('button', { name: '提交启事' }).click();
  await page.waitForURL(/\/records\/\d+/, { timeout: 15000 });
  await page.getByText('发布成功').waitFor();

  const recordId = Number(page.url().match(/\/records\/(\d+)/)?.[1]);
  assert(recordId > 0, `未能从 URL 取到记录 id：${page.url()}`);
  eq(createdPosts.length, 1, 'POST /api/records 次数');
  eq(createdPosts[0].status, 200, 'POST /api/records HTTP');

  // 等异步打分落 DEGRADED，再刷新页面拿「已降级」提示
  await waitUntil(async () => (await record(recordId))?.pair_state === 'DEGRADED', { timeout: 30000, label: 'pairState=DEGRADED' });
  const pair = (await val(
    `SELECT CONCAT(id, '|', engine, '|', IFNULL(degraded_reason,'NULL'), '|', score) FROM t_pair_score WHERE lost_id=${recordId} ORDER BY score DESC LIMIT 1;`,
  )) ?? '';
  const [pairId, engine, reason, score] = pair.split('|');
  eq(engine, 'LOCAL_KEYWORD', 'engine');
  eq(reason, 'EMPTY_KEY', 'degraded_reason');

  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.locator('.record-detail__title').waitFor();
  const banner = page.getByTestId('pair-degraded-banner');
  await banner.waitFor({ timeout: 15000 });
  assert(await banner.isVisible(), '「已降级」提示应可见');
  const bannerText = await banner.innerText();
  assert(bannerText.includes('已降级') && bannerText.includes('关键词匹配'), `提示文案：${bannerText}`);

  // 截图：底部固定条带 URL
  await page.evaluate(({ url }) => {
    const bar = document.createElement('div');
    bar.id = 'f2-url-banner';
    bar.textContent = `【降级实测 · 本人 demo01 · 详情 #${url.split('/').pop()}】${url}`;
    Object.assign(bar.style, {
      position: 'fixed', left: '0', right: '0', bottom: '0', zIndex: '2147483647',
      background: '#111827', color: '#f9fafb', font: '13px/1.6 Consolas, monospace',
      padding: '6px 12px', borderTop: '2px solid #f59e0b', wordBreak: 'break-all',
    });
    document.body.appendChild(bar);
  }, { url: page.url() });
  await page.screenshot({ path: resolve(SHOTS, '11-degraded-detail.png'), fullPage: true });

  const rec = await record(recordId);
  Object.assign(evidence, {
    recordId,
    postStatus: createdPosts[0].status,
    pairState: rec.pair_state,
    pairId: Number(pairId),
    engine,
    degradedReason: reason,
    score,
    bannerText,
    screenshot: 'docs/testing/v2/shots/11-degraded-detail.png',
  });
  console.log(`\n  [EV-API] DEG-UI POST /api/records → HTTP ${createdPosts[0].status}；recordId=${recordId}；pairState=${rec.pair_state}`);
  console.log(`  [EV-SQL] DEG-UI pairId=${pairId} engine=${engine} degraded_reason=${reason} score=${score}`);
  console.log(`  [EV-UI]  「已降级」提示可见：${bannerText.replace(/\s+/g, ' ')}`);
});

const summary = t.print();
const payload = { ...summary, evidence, generatedAt: new Date().toISOString() };
await writeFile(resolve(SHOTS, 'f2-degradation.json'), JSON.stringify(payload, null, 2), 'utf8');
await writeFile(resolve(here, '..', 'reports', 'f2-degradation.json'), JSON.stringify(payload, null, 2), 'utf8');

await browser.close();
await resetSeed();
console.log('\n  已 resetSeed：降级实测记录已清理，种子状态恢复');

process.exit(summary.failed > 0 ? 1 : 0);
