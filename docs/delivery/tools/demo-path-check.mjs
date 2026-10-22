/**
 * 6.2 演示路径自检（对着录屏脚本一步一步走一遍）
 *
 * 目的不是「再跑一遍 5.1 的 E2E」，而是回答一个问题：
 * **录屏脚本里写的每一个操作，在真实界面上是否都有入口、能否一次走通、数字是否与稿子一致。**
 * 每条检查都断言「入口存在 + 结果可见」，任一条失败即退出码 1。
 *
 * 前置（与录屏当天的前置完全一致）：
 *   1. MySQL 已起，库为 db/schema.sql + db/seed.sql 的初始状态（24 条记录 / 21 条待领）
 *   2. 后端已起：http://localhost:18080/api/health 返回 db=UP
 *   3. 前端已起：http://localhost:5173，web/.env.local 中 VITE_USE_MOCK=false
 *
 * 运行：
 *   cd docs/delivery/tools && npm install && node demo-path-check.mjs
 *
 * 产物：
 *   ../assets/shots/*.png        演示路径每步截图（PPT 与录屏脚本共用）
 *   ../assets/match-response.json  POST /api/match 的真实响应体（PPT 的输入输出示例）
 *   ../assets/demo-path-check.txt  本次自检的逐条结果
 */
import { chromium } from 'playwright';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ASSETS = resolve(HERE, '..', 'assets');
const SHOTS = resolve(ASSETS, 'shots');
const BASE = process.env.WEB_BASE ?? 'http://localhost:5173';
const API = process.env.API_BASE ?? 'http://localhost:18080';

const steps = [];
let failed = 0;

/** 一条演示步骤：name 与录屏脚本里的段号一致，action 内只做「人在界面上的动作 + 断言」。 */
async function step(id, name, action) {
  const started = Date.now();
  try {
    const detail = await action();
    steps.push({ id, name, ok: true, ms: Date.now() - started, detail: detail ?? '' });
    console.log(`PASS  ${id}  ${name}${detail ? `  → ${detail}` : ''}`);
  } catch (err) {
    failed += 1;
    steps.push({ id, name, ok: false, ms: Date.now() - started, detail: String(err?.message ?? err) });
    console.log(`FAIL  ${id}  ${name}\n      ${String(err?.message ?? err).split('\n')[0]}`);
  }
}

const must = (cond, message) => {
  if (!cond) throw new Error(message);
};

const shot = async (page, name) => {
  await page.screenshot({ path: resolve(SHOTS, `${name}.png`), fullPage: true });
};

await mkdir(SHOTS, { recursive: true });

// 前置：后端与前端都必须在
const health = await (await fetch(`${API}/api/health`)).json();
console.log(`前置  health: ${JSON.stringify(health.data)}`);
must(health.data?.db === 'UP', `后端/数据库未就绪：${JSON.stringify(health)}`);

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const context = await browser.newContext({
  viewport: { width: 1280, height: 900 },
  locale: 'zh-CN',
  timezoneId: 'Asia/Shanghai',
});
const page = await context.newPage();

const cards = page.locator('.record-card');
const totalText = () => page.locator('.pagination-bar__total').innerText();
const totalNum = async () => Number((await totalText()).match(/共 (\d+) 条/)?.[1] ?? -1);

// ---------------------------------------------------------------- 段 2 列表页

await step('D-01', '打开首页即列表页：10 张卡片 / 共 21 条 / 3 页', async () => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: '启事列表' }).waitFor({ timeout: 15000 });
  await cards.first().waitFor();
  const count = await cards.count();
  const text = await totalText();
  must(count === 10, `首屏卡片数应为 10，实际 ${count}`);
  must(text.includes('共 21 条') && text.includes('3 页'), `分页文案不符：${text}`);
  await shot(page, '01-list-default');
  return text.replace(/\s+/g, ' ');
});

await step('D-02', '类型筛选「招领」：URL 带 type=FOUND，卡片全部是招领', async () => {
  await page.selectOption('#filter-type', 'FOUND');
  await page.waitForURL(/type=FOUND/);
  await page.waitForFunction(() => document.querySelectorAll('.record-card').length > 0);
  // 每张卡有两个徽标（类型 + 状态），只看类型那个
  const tags = await page.locator('.record-card').evaluateAll((nodes) =>
    nodes.map((n) => n.querySelector('.badge')?.textContent?.trim() ?? ''),
  );
  must(tags.length > 0 && tags.every((t) => t === '招领'), `筛选后应全为招领，实际 ${JSON.stringify([...new Set(tags)])}`);
  await shot(page, '02-list-filter-found');
  return `URL ${new URL(page.url()).search}，${tags.length} 个徽标全是招领`;
});

await step('D-03', '关键词「钱包」：命中 2 条，URL 带 keyword', async () => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await cards.first().waitFor();
  await page.fill('#filter-keyword', '钱包');
  await page.waitForURL(/keyword=/);
  await page.waitForFunction(() => document.querySelectorAll('.record-card').length === 2, null, { timeout: 8000 });
  const text = await totalText();
  must(text.includes('共 2 条'), `关键词结果数不符：${text}`);
  await shot(page, '03-list-keyword');
  return text.replace(/\s+/g, ' ');
});

// ---------------------------------------------------------------- 段 3 详情页 + 匹配

let matchBody = null;

await step('D-04', '打开 1 号详情：8 个字段齐全，状态按钮由 allowedStatuses 渲染', async () => {
  await page.goto(BASE + '/records/1', { waitUntil: 'domcontentloaded' });
  await page.locator('.record-detail__title').waitFor();
  must((await page.locator('.record-detail__title').innerText()).includes('图书馆丢了黑色钱包'), '1 号标题不符');
  const facts = (await page.locator('.record-detail__fact').allInnerTexts()).join('\n');
  for (const label of ['分类', '地点', '发生时间', '发布时间', '最后更新', '联系方式', '联系人']) {
    must(facts.includes(label), `详情缺字段：${label}`);
  }
  must((await page.locator('.record-detail__description').innerText()).length > 0, '描述为空');
  const actions = await page.locator('.record-detail__actions').innerText();
  must(actions.includes('标记为已认领') && actions.includes('标记为已关闭'), `状态按钮不符：${actions}`);
  await shot(page, '04-detail');
  return '7 个信息字段 + 描述 + 两个状态按钮';
});

await step('D-05', '点「开始匹配」：真发 POST /api/match，返回 13 号且带百分比', async () => {
  const wait = page.waitForResponse((r) => r.url().includes('/api/match') && r.request().method() === 'POST', { timeout: 20000 });
  await page.getByRole('button', { name: '开始匹配' }).click();
  const res = await wait;
  matchBody = await res.json();
  await page.locator('.match-panel__list li').first().waitFor({ timeout: 15000 });

  const summary = await page.locator('.match-panel__summary').innerText();
  must(/引擎：本地关键词匹配/.test(summary), `摘要缺引擎名：${summary}`);
  const top1 = await page.locator('.match-panel__list li').first().innerText();
  must(/图书馆拾到黑色钱包/.test(top1), `top1 应为 13 号，实际：${top1.slice(0, 80)}`);
  must(/相似度\s*\d+%/.test(top1), `top1 缺相似度：${top1.slice(0, 80)}`);

  await writeFile(resolve(ASSETS, 'match-response.json'), JSON.stringify(matchBody, null, 2), 'utf8');
  await shot(page, '05-match-results');
  const item = matchBody.data.items[0];
  return `HTTP ${res.status()} code=${matchBody.code} 引擎=${matchBody.data.engine} top1=${item.recordId}@${item.score} (${item.scorePercent}%)`;
});

// ---------------------------------------------------------------- 段 4 提交并落库

const stamp = Date.now();
const title = `演示录屏记录 ${stamp}`;
const description = `2026 年 10 月 22 日下午，把一只灰色双肩包落在第三教学楼 305 靠窗的座位上，侧面有水杯袋，包里有蓝色笔记本和一副有线耳机。`;
let createdId = null;

await step('D-06', '提交页：填全 8 个字段后提交，跳详情并提示「发布成功」', async () => {
  await page.goto(BASE + '/records/new', { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: '发布启事' }).waitFor();
  await page.check('input[name="type"][value="LOST"]');
  await page.fill('#field-title', title);
  await page.fill('#field-description', description);
  await page.selectOption('#field-categoryId', { label: /包/ }).catch(async () => {
    await page.selectOption('#field-categoryId', '5');
  });
  await page.fill('#field-location', '第三教学楼305');
  await page.fill('#field-occurredAt', '2026-10-21T15:20');
  await page.fill('#field-contact', '13800001234');
  await page.fill('#field-publisherName', '张同学');
  await shot(page, '06-create-filled');

  await page.getByRole('button', { name: '提交启事' }).click();
  await page.waitForURL(/\/records\/\d+/, { timeout: 20000 });
  createdId = page.url().match(/\/records\/(\d+)/)?.[1];
  await page.getByText('发布成功').waitFor();
  const shown = await page.locator('.record-detail__title').innerText();
  must(shown === title, `跳转后的详情标题不符：${shown}`);
  must(page.url().includes('created=1'), `跳转应带 created=1：${page.url()}`);
  await shot(page, '07-create-success');
  return `新记录 id=${createdId}，URL ${new URL(page.url()).pathname}${new URL(page.url()).search}`;
});

await step('D-07', '回列表：刚提交的记录在首位，总数 21 → 22', async () => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await cards.first().waitFor();
  must((await page.locator('.record-card__title').first().innerText()) === title, '列表首位不是刚提交的记录');
  const total = await totalNum();
  must(total === 22, `总数应为 22，实际 ${total}`);
  await shot(page, '08-list-after-create');
  return `首位 = ${title.slice(0, 12)}…，共 ${total} 条`;
});

await step('D-08', '直查 MySQL：刚才那条真的在库里（不是前端缓存）', async () => {
  const res = await fetch(`${API}/api/records/${createdId}`);
  const body = await res.json();
  must(body.code === 0 && body.data.title === title, `接口回读不符：${JSON.stringify(body).slice(0, 200)}`);
  return `GET /api/records/${createdId} → id=${body.data.id} status=${body.data.status} category=${body.data.categoryName}`;
});

// ---------------------------------------------------------------- 段 5 状态流转

await step('D-09', '状态流转：1 号标为已认领，按钮收缩为只剩「标记为已关闭」', async () => {
  await page.goto(BASE + '/records/1', { waitUntil: 'domcontentloaded' });
  await page.locator('.record-detail__title').waitFor();
  await page.getByRole('button', { name: '标记为已认领' }).click();
  await page.locator('.badge', { hasText: '已认领' }).first().waitFor({ timeout: 15000 });
  const actions = await page.locator('.record-detail__actions').innerText();
  must(!actions.includes('标记为已认领'), `认领后不应再有「标记为已认领」：${actions}`);
  must(actions.includes('标记为已关闭'), `认领后应剩「标记为已关闭」：${actions}`);
  await shot(page, '09-detail-claimed');
  return `badge=已认领，剩余动作：${actions.replace(/\s+/g, ' ').trim()}`;
});

await step('D-10', '已认领的记录退出默认列表：关键词「钱包」从 2 条变 1 条', async () => {
  await page.goto(BASE + '/?keyword=' + encodeURIComponent('钱包'), { waitUntil: 'domcontentloaded' });
  await cards.first().waitFor();
  const total = await totalNum();
  must(total === 1, `关键词「钱包」在认领后应只剩 1 条（13 号），实际 ${total}`);
  await shot(page, '10-list-after-claim');
  return `共 ${total} 条（默认 status=PENDING 把已认领的 1 号过滤掉了）`;
});

await browser.close();

// ---------------------------------------------------------------- 汇总

const lines = [
  '# 6.2 演示路径自检结果',
  '',
  `- 运行时间：${new Date().toISOString()}`,
  `- 前端：${BASE}　后端：${API}`,
  `- 库状态：db/seed.sql 初始数据（24 条 / 21 条待领）`,
  `- 结果：${steps.length - failed}/${steps.length} 通过${failed ? `，${failed} 条失败` : ''}`,
  '',
  '| 段号 | 操作 | 结果 | 观察到的事实 |',
  '|---|---|---|---|',
  ...steps.map((s) => `| ${s.id} | ${s.name} | ${s.ok ? '通过' : '**失败**'} | ${s.detail.replace(/\|/g, '\\|')} |`),
  '',
];
await writeFile(resolve(ASSETS, 'demo-path-check.txt'), lines.join('\n'), 'utf8');

console.log(`\n=== 汇总：${steps.length - failed}/${steps.length} 通过 ===`);
if (failed) {
  console.log('有失败步骤：先改脚本或修入口，不要把跑不通的段落留在录屏稿里。');
  process.exit(1);
}
