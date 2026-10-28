// v2 · F1 前端三页走查（原 5.1 的 19 条 + 登录/三态新增）
//
// 真实接口模式：web/.env.local 中 VITE_USE_MOCK=false，前端 5173 → Vite 代理 → 后端 18080 → MySQL。
//
// v2 的页面不再是「匿名就能做一切」：写操作、匹配、我的启事都要求登录。
// 因此本轮先建立三套浏览器上下文：
//   - anon    匿名
//   - owner   demo01（1 号等记录的所有者）
//   - non      demo02（1 号的非所有者，自己也有 6/13/16 等记录）
// 列表 / 详情 / 提交三页都按「匿名 / 本人 / 非本人」三态各走一遍（E2E-A-01..03）。
//
// 前置：后端 18080、前端 5173、库为 seed 初始状态。
// 运行：node qa/e2e/walkthrough.mjs

import { chromium } from 'playwright';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Tester, assert, eq } from '../lib/tester.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const SHOTS = resolve(here, '..', 'reports', 'screenshots');
const BASE = process.env.WEB_BASE ?? 'http://localhost:5173';
const PASSWORD = 'Demo@123456';
const rid = () => Math.random().toString(36).slice(2, 10);

const t = new Tester('e2e-f1-v2');
const pageErrors = [];
const created = {};

const browser = await chromium.launch({ channel: 'msedge', headless: true });

async function identity(name) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' });
  const page = await context.newPage();
  page.on('pageerror', (err) => pageErrors.push(`${name}: ${String(err)}`));
  page.on('console', (msg) => {
    if (msg.type() !== 'error') return;
    const text = msg.text();
    // 刻意造出来的连接拒绝 / 4xx/5xx 已有独立断言，过滤浏览器通用噪声
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

const anon = await identity('anon');
const owner = await identity('owner');
const non = await identity('non');
await loginUI(owner.page, 'demo01');
await loginUI(non.page, 'demo02');

const cards = (page) => page.locator('.record-card');
const cardByHref = (page, id) => page.locator(`article.record-card:has(a[href="/records/${id}"])`);
const shot = async (page, name) => {
  const path = resolve(SHOTS, `${name}.png`);
  await page.screenshot({ path, fullPage: true });
  return path;
};
await mkdir(SHOTS, { recursive: true });

// ---------------------------------------------------------------- 列表页

await t.case('E2E-L-01', 'P0', '匿名列表默认加载：10 条 / 共 21 条 / 3 页', async () => {
  await anon.page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await anon.page.getByRole('heading', { name: '启事列表' }).waitFor();
  await cards(anon.page).first().waitFor();
  eq(await cards(anon.page).count(), 10, '首屏卡片数');
  const total = await anon.page.locator('.pagination-bar__total').innerText();
  assert(total.includes('共 21 条') && total.includes('3 页'), `分页文案不符：${total}`);
  await shot(anon.page, 'v2-01-list-anon');
});

await t.case('E2E-L-02', 'P0', '匿名类型筛选 FOUND：卡片全部是「招领」且 URL 同步', async () => {
  await anon.page.selectOption('#filter-type', 'FOUND');
  await anon.page.waitForURL(/type=FOUND/);
  await anon.page.waitForTimeout(400);
  const typeTags = await anon.page.locator('.record-card').evaluateAll((nodes) =>
    nodes.map((n) => n.querySelector('.badge--type, .badge')?.textContent?.trim() ?? ''),
  );
  assert(typeTags.length > 0, '筛选后应有卡片');
  assert(typeTags.every((x) => x.includes('招领')), `FOUND 筛选后应全部为招领，实际 ${JSON.stringify([...new Set(typeTags)])}`);
  await shot(anon.page, 'v2-02-list-filter-found');
});

await t.case('E2E-L-03', 'P0', '匿名关键词「钱包」：命中 2 条且 URL 带 keyword', async () => {
  await anon.page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await cards(anon.page).first().waitFor();
  await anon.page.fill('#filter-keyword', '钱包');
  await anon.page.waitForURL(/keyword=/);
  await anon.page.waitForFunction(() => document.querySelectorAll('.record-card').length === 2, null, { timeout: 5000 });
  const total = await anon.page.locator('.pagination-bar__total').innerText();
  assert(total.includes('共 2 条'), `关键词结果数不符：${total}`);
  await shot(anon.page, 'v2-03-list-keyword');
});

await t.case('E2E-L-04', 'P1', '重置筛选同时清空 URL（刷新后不重现筛选）', async () => {
  await anon.page.getByRole('button', { name: '重置筛选' }).click();
  await anon.page.waitForFunction(() => document.querySelectorAll('.record-card').length === 10, null, { timeout: 5000 });
  const url = new URL(anon.page.url());
  const leftovers = [...url.searchParams.entries()].filter(([k, v]) => k !== 'page' && k !== 'pageSize' && v !== '');
  eq(leftovers, [], '重置后 URL 残留的筛选参数');
  await anon.page.reload({ waitUntil: 'domcontentloaded' });
  await anon.page.waitForTimeout(600);
  assert((await anon.page.locator('.pagination-bar__total').innerText()).includes('共 21 条'), '刷新后应回到全量 21 条');
});

await t.case('E2E-L-05', 'P1', '翻到第 2 页：URL page=2 且卡片与第 1 页不重复', async () => {
  await anon.page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await cards(anon.page).first().waitFor();
  const firstPageIds = await cards(anon.page).evaluateAll((nodes) => nodes.map((n) => n.querySelector('a')?.getAttribute('href')?.split('/').pop()));
  await anon.page.getByRole('button', { name: '第 2 页' }).click();
  await anon.page.waitForURL(/page=2/);
  await anon.page.waitForTimeout(400);
  const secondPageIds = await cards(anon.page).evaluateAll((nodes) => nodes.map((n) => n.querySelector('a')?.getAttribute('href')?.split('/').pop()));
  assert(secondPageIds.length > 0, '第 2 页应有卡片');
  eq(secondPageIds.filter((id) => firstPageIds.includes(id)), [], '第 1 页与第 2 页重复的 id');
  await shot(anon.page, 'v2-04-list-page2');
});

await t.case('E2E-A-01', 'P0', '列表三态：匿名全员遮蔽、本人行原值+「我的启事」、非本人对他人行仍遮蔽', async () => {
  for (const p of [anon.page, owner.page, non.page]) {
    await p.goto(BASE + '/?pageSize=50', { waitUntil: 'domcontentloaded' });
    await cards(p).first().waitFor();
  }
  eq(await anon.page.locator('[data-testid="card-mine-badge"]').count(), 0, '匿名页的「我的启事」徽标数');
  assert((await owner.page.locator('[data-testid="card-mine-badge"]').count()) > 0, '本人页应有「我的启事」徽标');
  // 1 号属于 demo01：demo02 看它没有徽标，看自己的 6 号有徽标
  eq(await cardByHref(non.page, 1).locator('[data-testid="card-mine-badge"]').count(), 0, '非本人看 #1 不应有徽标');
  eq(await cardByHref(non.page, 6).locator('[data-testid="card-mine-badge"]').count(), 1, 'demo02 看自己的 #6 应有徽标');
  // 地点遮蔽：3 号「第三教学楼201」匿名/非本人截断，本人原值
  assert((await cardByHref(anon.page, 3).innerText()).includes('第三教学楼…'), '匿名看 #3 地点应截断');
  assert(!(await cardByHref(anon.page, 3).innerText()).includes('201'), '匿名看 #3 不应出现精确房间号');
  assert((await cardByHref(owner.page, 3).innerText()).includes('第三教学楼201'), '本人看 #3 地点应原值');
  assert((await cardByHref(non.page, 3).innerText()).includes('第三教学楼…'), '非本人看 #3 地点应截断');
  await shot(non.page, 'v2-05-list-non-owner');
});

// ---------------------------------------------------------------- 详情页三态

await t.case('E2E-A-02', 'P0', '详情三态：匿名登录引导 / 本人明文+管理动作 / 非本人遮蔽横幅', async () => {
  await anon.page.goto(BASE + '/records/1', { waitUntil: 'domcontentloaded' });
  await anon.page.locator('.record-detail__title').waitFor();
  eq(await anon.page.locator('[data-testid="anonymous-banner"]').count(), 1, '匿名横幅');
  eq(await anon.page.locator('[data-testid="contact-owner"]').count(), 0, '匿名不应看到本人号码');
  eq(await anon.page.locator('[data-testid="contact-login-cta"]').count(), 1, '匿名应给登录引导');
  eq(await anon.page.locator('[data-testid="detail-mine-badge"]').count(), 0, '匿名没有本人徽标');
  await shot(anon.page, 'v2-06-detail-anon');

  await owner.page.goto(BASE + '/records/1', { waitUntil: 'domcontentloaded' });
  await owner.page.locator('.record-detail__title').waitFor();
  eq(await owner.page.locator('[data-testid="detail-mine-badge"]').count(), 1, '本人徽标');
  eq(await owner.page.locator('[data-testid="contact-owner"]').innerText(), '13800000001', '本人联系方式');
  eq(await owner.page.locator('[data-testid="anonymous-banner"]').count(), 0, '本人不应有匿名横幅');
  eq(await owner.page.locator('[data-testid="non-owner-banner"]').count(), 0, '本人不应有非本人横幅');
  assert((await owner.page.locator('.record-detail__actions').innerText()).length > 0, '本人应有状态操作区');
  await shot(owner.page, 'v2-07-detail-owner');

  await non.page.goto(BASE + '/records/1', { waitUntil: 'domcontentloaded' });
  await non.page.locator('.record-detail__title').waitFor();
  eq(await non.page.locator('[data-testid="non-owner-banner"]').count(), 1, '非本人横幅');
  eq(await non.page.locator('[data-testid="contact-owner"]').count(), 0, '非本人不应看到号码');
  eq(await non.page.locator('[data-testid="masked-placeholder"]').count() >= 1, true, '非本人应看到遮蔽占位');
  eq(await non.page.locator('[data-testid="detail-mine-badge"]').count(), 0, '非本人没有本人徽标');
  await shot(non.page, 'v2-08-detail-non-owner');
});

await t.case('E2E-A-03', 'P0', '提交页三态：匿名被守卫送回登录、本人可填、非本人同样可填（发布归自己）', async () => {
  await anon.page.goto(BASE + '/records/new', { waitUntil: 'domcontentloaded' });
  await anon.page.waitForURL(/\/login\?redirect=/);
  assert(anon.page.url().includes('redirect=%2Frecords%2Fnew'), `匿名应带 redirect：${anon.page.url()}`);
  eq(await anon.page.getByRole('heading', { name: '登录' }).count(), 1, '匿名落到登录页');
  await shot(anon.page, 'v2-09-create-anon-redirect');

  for (const [name, p] of [['本人', owner.page], ['非本人', non.page]]) {
    await p.goto(BASE + '/records/new', { waitUntil: 'domcontentloaded' });
    await p.getByRole('heading', { name: '发布启事' }).waitFor();
    eq(await p.locator('#field-title').count(), 1, `${name}应看到提交表单`);
  }
});

await t.case('E2E-D-01', 'P0', '匿名从卡片进详情：标题/分类/地点/描述与列表一致，号码不可得', async () => {
  await anon.page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await cards(anon.page).first().waitFor();
  const firstTitle = await anon.page.locator('.record-card__title').first().innerText();
  const firstHref = await anon.page.locator('.record-card a').first().getAttribute('href');
  await anon.page.locator('.record-card a').first().click();
  await anon.page.waitForURL(new RegExp(`${firstHref.replace(/[/\\^$*+?.()|[\]{}]/g, '\\$&')}$`));
  await anon.page.locator('.record-detail__title').waitFor();
  eq(await anon.page.locator('.record-detail__title').innerText(), firstTitle, '详情标题');
  const factText = (await anon.page.locator('.record-detail__fact').allInnerTexts()).join('\n');
  for (const label of ['分类', '地点', '发生时间', '发布时间', '最后更新', '联系方式', '联系人']) {
    assert(factText.includes(label), `详情缺少字段 ${label}`);
  }
  assert((await anon.page.locator('[data-testid="detail-description"]').innerText()).length > 0, '描述不应为空');
  created.detailId = firstHref.split('/').pop();
  await shot(anon.page, 'v2-10-detail-from-list');
});

await t.case('E2E-D-02', 'P0', '本人详情展示 pair 卡片（分数/档位/状态），不暴露对方号码', async () => {
  await owner.page.goto(BASE + '/records/1', { waitUntil: 'domcontentloaded' });
  await owner.page.locator('[data-testid="pair-card"]').first().waitFor();
  const card = owner.page.locator('[data-testid="pair-card"]').first();
  assert(/\d+%/.test(await card.locator('[data-testid="pair-score"]').innerText()), 'pair 卡片缺分数');
  assert((await card.locator('[data-testid="pair-tier"]').innerText()).length > 0, 'pair 卡片缺档位');
  assert((await card.locator('[data-testid="pair-status"]').innerText()).length > 0, 'pair 卡片缺状态');
  const cardText = await card.innerText();
  assert(!/1[3-9]\d{9}/.test(cardText), `pair 卡片不应直接显示号码：${cardText.slice(0, 200)}`);
  await shot(owner.page, 'v2-11-detail-pairs-owner');
});

await t.case('E2E-D-03', 'P0', '非本人详情发起匹配：出现名次、相似度与排序说明，候选不含号码', async () => {
  await non.page.goto(BASE + '/records/1', { waitUntil: 'domcontentloaded' });
  await non.page.locator('.record-detail__title').waitFor();
  await non.page.getByRole('button', { name: '开始匹配' }).click();
  await non.page.locator('.match-panel__list li').first().waitFor({ timeout: 10000 });
  const summary = await non.page.locator('.match-panel__summary').innerText();
  assert(/引擎：本地关键词匹配/.test(summary), `匹配摘要缺少引擎名：${summary}`);
  const first = await non.page.locator('.match-panel__list li').first().innerText();
  assert(/相似度\s*\d+%/.test(first), `结果缺少相似度：${first.slice(0, 120)}`);
  assert(/图书馆拾到黑色钱包/.test(first), `top1 应为 13 号「图书馆拾到黑色钱包」，实际：${first.slice(0, 120)}`);
  assert(!/1[3-9]\d{9}/.test(await non.page.locator('.match-panel').innerText()), '匹配区不应出现手机号');
  await shot(non.page, 'v2-12-detail-match');
});

await t.case('E2E-D-04', 'P0', '本人「标记为已完成」：记录 10 有 MATCHED pair，点击后移到已完成页签', async () => {
  await owner.page.goto(BASE + '/records/10', { waitUntil: 'domcontentloaded' });
  await owner.page.locator('[data-testid="detail-mine-badge"]').waitFor();
  const btn = owner.page.locator('[data-testid="action-complete"]');
  await btn.waitFor();
  assert(!(await btn.isDisabled()), '已有 MATCHED pair 时「标记为已完成」应可点');
  await btn.click();
  await owner.page.waitForURL(/\/my\?updated=completed/, { timeout: 10000 });
  await owner.page.getByText('已标记完成').first().waitFor();
  await shot(owner.page, 'v2-13-completed-my');
});

await t.case('E2E-D-05', 'P0', '完成后可见性：公共列表 0 命中；所有者详情 404 并回我的启事', async () => {
  await anon.page.goto(BASE + '/?keyword=' + encodeURIComponent('教学楼丢了一串钥匙'), { waitUntil: 'domcontentloaded' });
  await anon.page.waitForFunction(() => document.querySelectorAll('.record-card').length === 0, null, { timeout: 10000 });
  assert((await anon.page.locator('.empty__title').innerText()).includes('暂无符合条件'), 'COMPLETED 公共列表应 0 命中');
  await owner.page.goto(BASE + '/records/10', { waitUntil: 'domcontentloaded' });
  await owner.page.waitForURL(/\/my/, { timeout: 10000 });
  assert(owner.page.url().includes('/my') && /unavailable=1|updated=completed/.test(owner.page.url()), `终态详情应回我的启事：${owner.page.url()}`);
  await shot(owner.page, 'v2-14-completed-detail-redirect');
});

await t.case('E2E-D-06', 'P1', 'DIRECT 档 pair 可取号：点击后显示对方号码（三档 UI 的直看路径）', async () => {
  await owner.page.goto(BASE + '/records/1', { waitUntil: 'domcontentloaded' });
  const card = owner.page.locator('[data-testid="pair-card"]').first();
  await card.locator('[data-testid="pair-contact-button"]').click();
  await card.locator('[data-testid="pair-contact-number"]').waitFor({ timeout: 10000 });
  const number = await card.locator('[data-testid="pair-contact-number"]').innerText();
  eq(number.trim(), '13800000101', 'DIRECT 档返回的对方号码');
  await shot(owner.page, 'v2-15-pair-contact');
});

// ---------------------------------------------------------------- 提交页

await t.case('E2E-C-01', 'P0', '本人提交页黄金路径：填写 → 提交 → 跳详情并提示发布成功', async () => {
  const title = `E2E 走查记录 ${rid()}`;
  await owner.page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await owner.page.locator('.pagination-bar__total').waitFor();
  created.totalBefore = Number((await owner.page.locator('.pagination-bar__total').innerText()).match(/共 (\d+) 条/)?.[1] ?? -1);
  const description = `这条记录由 v2 端到端走查脚本创建（${rid()}），用于验证「提交 → 列表 → 详情」真实接口全链路与 MySQL 落库。`;
  created.title = title;

  await owner.page.goto(BASE + '/records/new', { waitUntil: 'domcontentloaded' });
  await owner.page.getByRole('heading', { name: '发布启事' }).waitFor();
  await owner.page.check('input[name="type"][value="LOST"]');
  await owner.page.fill('#field-title', title);
  await owner.page.fill('#field-description', description);
  await owner.page.selectOption('#field-categoryId', '5');
  await owner.page.fill('#field-location', 'E2E 测试地点 · 第三教学楼');
  await owner.page.fill('#field-occurredAt', '2026-10-21T15:20');
  await owner.page.fill('#field-contact', '13800001234');
  await owner.page.fill('#field-publisherName', '端测测');
  await shot(owner.page, 'v2-16-create-filled');

  await owner.page.getByRole('button', { name: '提交启事' }).click();
  await owner.page.waitForURL(/\/records\/\d+/, { timeout: 15000 });
  created.id = owner.page.url().match(/\/records\/(\d+)/)?.[1];
  await owner.page.getByText('发布成功').waitFor();
  eq(await owner.page.locator('.record-detail__title').innerText(), title, '跳转后的详情标题');
  assert(owner.page.url().includes('created=1'), `跳转应带 created=1：${owner.page.url()}`);
  await shot(owner.page, 'v2-17-create-success');
});

await t.case('E2E-C-02', 'P0', '列表能看到刚提交的记录（createdAt desc 首位）', async () => {
  await owner.page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await cards(owner.page).first().waitFor();
  eq(await owner.page.locator('.record-card__title').first().innerText(), created.title, '列表首条标题');
  const shown = Number((await owner.page.locator('.pagination-bar__total').innerText()).match(/共 (\d+) 条/)?.[1] ?? -1);
  eq(shown, created.totalBefore + 1, '提交后默认列表（PENDING）总条数');
  await shot(owner.page, 'v2-18-list-after-create');
});

await t.case('E2E-C-03', 'P1', '关键词检索刚提交的记录，详情可回跳', async () => {
  await owner.page.fill('#filter-keyword', created.title);
  await owner.page.waitForFunction(() => document.querySelectorAll('.record-card').length === 1, null, { timeout: 5000 });
  eq(await owner.page.locator('.record-card a').first().getAttribute('href'), `/records/${created.id}`, '检索结果的详情链接');
  await shot(owner.page, 'v2-19-list-created-search');
});

await t.case('E2E-C-04', 'P0', '全新匿名上下文直接打开该记录详情：仍走 GET /api/records/:id，且号码被遮蔽', async () => {
  const fresh = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'zh-CN' });
  const freshPage = await fresh.newPage();
  const apiCalls = [];
  freshPage.on('request', (r) => {
    if (r.url().includes('/api/records/')) apiCalls.push(r.url());
  });
  await freshPage.goto(`${BASE}/records/${created.id}`, { waitUntil: 'domcontentloaded' });
  await freshPage.locator('.record-detail__title').waitFor({ timeout: 10000 });
  eq(await freshPage.locator('.record-detail__title').innerText(), created.title, '新上下文中的详情标题');
  assert(apiCalls.length > 0, '新上下文应通过 GET /api/records/:id 取数（而不是读本地缓存）');
  eq(await freshPage.locator('[data-testid="contact-owner"]').count(), 0, '匿名不应看到本人联系方式');
  assert((await freshPage.locator('[data-testid="anonymous-banner"]').count()) === 1, '匿名横幅');
  await freshPage.screenshot({ path: resolve(SHOTS, 'v2-20-detail-fresh-anon.png'), fullPage: true });
  await fresh.close();
});

// ---------------------------------------------------------------- 异常路径

await t.case('E2E-E-01', 'P0', '本人提交页校验失败：空表单出现页级摘要 + 字段级错误', async () => {
  await owner.page.goto(BASE + '/records/new', { waitUntil: 'domcontentloaded' });
  await owner.page.getByRole('button', { name: '提交启事' }).click();
  const summary = owner.page.locator('.alert--danger').first();
  await summary.waitFor();
  const text = await summary.innerText();
  assert(/有 \d+ 处需要修改/.test(text), `页级摘要文案不符：${text}`);
  eq(await owner.page.locator('#field-title').getAttribute('aria-invalid'), 'true', '标题 aria-invalid');
  assert((await owner.page.locator('#field-title-error').count()) === 1, '标题应有字段错误提示节点');
  assert(owner.page.url().endsWith('/records/new'), '校验失败不应跳转');
  await shot(owner.page, 'v2-21-create-validation');
});

await t.case('E2E-E-02', 'P0', '匿名后端不可用：列表显示错误条，恢复后「重试」能拉到数据', async () => {
  const isApi = (url) => url.pathname.startsWith('/api/');
  await anon.page.route(isApi, (route) => route.abort('connectionrefused'));
  await anon.page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await anon.page.locator('.alert--danger').first().waitFor({ timeout: 10000 });
  const text = await anon.page.locator('.alert--danger').first().innerText();
  assert(text.includes('网络请求失败'), `错误文案应说明网络失败：${text}`);
  eq(await anon.page.getByRole('button', { name: '重试' }).count(), 1, '应提供「重试」按钮');
  await shot(anon.page, 'v2-22-list-backend-down');
  await anon.page.unroute(isApi);
  await anon.page.getByRole('button', { name: '重试' }).click();
  await cards(anon.page).first().waitFor({ timeout: 10000 });
  assert((await cards(anon.page).count()) > 0, '重试后应恢复出卡片');
  await shot(anon.page, 'v2-23-list-backend-recovered');
});

await t.case('E2E-E-03', 'P1', '匿名空数据：显示空态而不是错误或空白页', async () => {
  const isEmptyList = (url) => url.pathname === '/api/records';
  await anon.page.route(isEmptyList, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 0, message: 'ok', requestId: 'e2e-empty', data: { page: 1, pageSize: 10, total: 0, totalPages: 0, list: [], hasNext: false } }),
    }),
  );
  await anon.page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await anon.page.locator('.empty__title').waitFor();
  eq(await anon.page.locator('.empty__title').innerText(), '暂无符合条件的启事', '空态标题');
  eq(await anon.page.locator('.alert--danger').count(), 0, '空数据不应显示错误条');
  await shot(anon.page, 'v2-24-list-empty');
  await anon.page.unroute(isEmptyList);
});

await t.case('E2E-E-04', 'P1', '匿名详情 404：显示记录不存在空态，不给重试', async () => {
  await anon.page.goto(BASE + '/records/999999', { waitUntil: 'domcontentloaded' });
  await anon.page.locator('.empty__title').waitFor();
  eq(await anon.page.locator('.empty__title').innerText(), '记录不存在或不可查看', '404 空态标题');
  eq(await anon.page.getByRole('button', { name: '重试' }).count(), 0, '404 不应给重试按钮');
  await shot(anon.page, 'v2-25-detail-not-found');
});

await t.case('E2E-E-05', 'P1', '接口 500：错误条带 requestId，便于按 id 查日志', async () => {
  const target = (url) => url.pathname === '/api/records/13';
  await anon.page.route(target, (route) =>
    route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({ code: 10005, message: '服务器内部错误', data: null, requestId: 'e2e-f1-500' }),
    }),
  );
  await anon.page.goto(BASE + '/records/13', { waitUntil: 'domcontentloaded' });
  await anon.page.locator('.alert--danger').first().waitFor();
  const text = await anon.page.locator('.alert--danger').first().innerText();
  assert(text.includes('请求失败，请稍后再试'), `500 文案不符：${text}`);
  assert(text.includes('e2e-f1-500'), `错误条应带 requestId：${text}`);
  await shot(anon.page, 'v2-26-detail-500-requestid');
  await anon.page.unroute(target);
});

await t.case('E2E-E-06', 'P1', '匹配服务 503：非本人详情只降级匹配区，详情主体照常可用', async () => {
  const isMatch = (url) => url.pathname === '/api/match';
  await non.page.route(isMatch, (route) =>
    route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({ code: 30002, message: '匹配引擎不可用', data: { engine: 'external' }, requestId: 'e2e-match-503' }),
    }),
  );
  await non.page.goto(BASE + '/records/15', { waitUntil: 'domcontentloaded' });
  await non.page.locator('.record-detail__title').waitFor();
  await non.page.getByRole('button', { name: '开始匹配' }).click();
  await non.page.locator('.match-panel .alert--danger').waitFor({ timeout: 10000 });
  const panelText = await non.page.locator('.match-panel .alert--danger').innerText();
  assert(panelText.includes('匹配服务暂不可用'), `匹配区降级文案不符：${panelText}`);
  assert(await non.page.locator('.record-detail__title').isVisible(), '详情主体不应被匹配错误顶掉');
  await shot(non.page, 'v2-27-detail-match-503');
  await non.page.unroute(isMatch);
});

await t.case('E2E-Z-01', 'P2', '全程无未捕获的前端异常', async () => {
  assert(pageErrors.length === 0, `捕获到前端报错：${pageErrors.slice(0, 5).join(' | ')}`);
});

await browser.close();

const summary = t.print();
const paths = await t.writeReports(resolve(here, '..', 'reports'));
await writeFile(resolve(here, '..', 'reports', 'e2e-created.json'), JSON.stringify(created, null, 2), 'utf8');
console.log(`\n  截图目录：${SHOTS}`);
console.log(`  报告：${paths.txtPath}`);
console.log(`  本轮通过 UI 创建的记录 id=${created.id}（供落库取证用）`);
process.exit(summary.failed > 0 ? 1 : 0);
