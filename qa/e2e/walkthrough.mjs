// 5.1 前端三页走查（ZHYYKFX-10）
//
// 真实接口模式：web/.env.local 中 VITE_USE_MOCK=false，前端 5173 → Vite 代理 → 后端 18080 → MySQL。
// 覆盖：列表 / 详情 / 提交三页的黄金路径 + 异常路径（校验失败、后端不可用、空数据、404、500、匹配 503）。
//
// 前置：后端 18080 已起、前端 dev 5173 已起、库为 seed 初始状态。
// 运行：node qa/e2e/walkthrough.mjs
// 说明：浏览器用本机 Edge（channel=msedge），不额外下载 Chromium；选择器优先语义角色/text，
//       项目当前没有 data-testid，组件类名作为兜底（已在报告里记为改进建议）。

import { chromium } from 'playwright';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Tester, assert, eq } from '../lib/tester.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const SHOTS = resolve(here, '..', 'reports', 'screenshots');
const BASE = process.env.WEB_BASE ?? 'http://localhost:5173';

const t = new Tester('e2e-5.1');
const pageErrors = [];
const created = {};

const shot = async (page, name) => {
  const path = resolve(SHOTS, `${name}.png`);
  await page.screenshot({ path, fullPage: true });
  return path;
};

const cards = (page) => page.locator('.record-card');
const alertDanger = (page) => page.locator('.alert--danger');

await mkdir(SHOTS, { recursive: true });

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const context = await browser.newContext({
  viewport: { width: 1280, height: 900 },
  locale: 'zh-CN',
  timezoneId: 'Asia/Shanghai',
});
const page = await context.newPage();
page.on('pageerror', (err) => pageErrors.push(String(err)));
page.on('console', (msg) => {
  if (msg.type() !== 'error') return;
  const text = msg.text();
  // E-02 故意让请求连接被拒，浏览器会把这类资源失败打成 console error —— 属预期噪声，不计入 Z-01
  // 浏览器对 4xx/5xx 资源会打一条通用 console error；本轮 E-04/E-05/E-06 是刻意造出来的，
  // 其 UI 表现已有独立断言，这里只过滤「HTTP 状态」这一类噪声，其余 console 错误仍计入。
  if (/ERR_CONNECTION_REFUSED|Failed to load resource: net::|Failed to load resource: the server responded with a status of/.test(text)) return;
  pageErrors.push(`console: ${text}`);
});

// ---------------------------------------------------------------- 列表页

await t.case('E2E-L-01', 'P0', '列表页默认加载：10 条 / 共 21 条 / 3 页', async () => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: '启事列表' }).waitFor();
  await cards(page).first().waitFor();
  eq(await cards(page).count(), 10, '首屏卡片数');
  const total = await page.locator('.pagination-bar__total').innerText();
  assert(total.includes('共 21 条') && total.includes('3 页'), `分页文案不符：${total}`);
  await shot(page, '01-list-default');
});

await t.case('E2E-L-02', 'P0', '类型筛选 FOUND：卡片全部是「招领」且 URL 同步', async () => {
  await page.selectOption('#filter-type', 'FOUND');
  await page.waitForURL(/type=FOUND/);
  await page.waitForTimeout(400); // 等请求返回并重渲染（筛选是即时请求，无骨架可等）
  const tags = await page.locator('.record-card .badge').allInnerTexts();
  assert(tags.length > 0, '筛选后应有卡片');
  const typeTags = await page.locator('.record-card').evaluateAll((nodes) =>
    nodes.map((n) => n.querySelector('.badge')?.textContent?.trim() ?? ''),
  );
  assert(
    typeTags.every((x) => x === '招领'),
    `FOUND 筛选后应全部为招领，实际 ${JSON.stringify([...new Set(typeTags)])}`,
  );
  await shot(page, '02-list-filter-found');
});

await t.case('E2E-L-03', 'P0', '关键词「钱包」：命中 2 条且 URL 带 keyword', async () => {
  // 从干净 URL 进入再输入关键词：不复用 L-02 的筛选状态（重置按钮本身的问题见 L-04/DEF-02）
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await cards(page).first().waitFor();
  await page.fill('#filter-keyword', '钱包');
  await page.waitForURL(/keyword=/);
  await page.waitForFunction(() => document.querySelectorAll('.record-card').length === 2, null, { timeout: 5000 });
  const total = await page.locator('.pagination-bar__total').innerText();
  assert(total.includes('共 2 条'), `关键词结果数不符：${total}`);
  await shot(page, '03-list-keyword');
});

// 已登记缺陷 DEF-02：重置筛选后 URL 仍保留旧筛选参数（useRouteQueryState 用 merge 写回，
// 默认值/空值被 prune 掉但旧键没删）。用户可见后果：刷新或复制链接后筛选又回来了。
// 判定按契约（URL 即状态）保持失败，标 KNOWN_FAIL。
await t.case(
  'E2E-L-04',
  'P1',
  '重置筛选应同时清空 URL（刷新后不应重现筛选）',
  async () => {
    await page.getByRole('button', { name: '重置筛选' }).click();
    await page.waitForFunction(() => document.querySelectorAll('.record-card').length === 10, null, { timeout: 5000 });
    const url = new URL(page.url());
    const leftovers = [...url.searchParams.entries()].filter(([k, v]) => k !== 'page' && k !== 'pageSize' && v !== '');
    eq(leftovers, [], '重置后 URL 残留的筛选参数');

    // 真正让用户踩到的场景：刷新一下，筛选又回来了
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(600);
    const afterReload = await page.locator('.pagination-bar__total').innerText();
    assert(afterReload.includes('共 21 条'), `刷新后应回到全量 21 条，实际：${afterReload}`);
  },
  { knownDefect: 'DEF-02' },
);

await t.case('E2E-L-05', 'P1', '翻到第 2 页：URL page=2 且卡片与第 1 页不重复', async () => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await cards(page).first().waitFor();
  const firstPageIds = await cards(page).evaluateAll((nodes) =>
    nodes.map((n) => n.getAttribute('href')?.split('/').pop()),
  );
  await page.getByRole('button', { name: '第 2 页' }).click();
  await page.waitForURL(/page=2/);
  await page.waitForTimeout(400);
  const secondPageIds = await cards(page).evaluateAll((nodes) =>
    nodes.map((n) => n.getAttribute('href')?.split('/').pop()),
  );
  assert(secondPageIds.length > 0, '第 2 页应有卡片');
  const overlap = secondPageIds.filter((id) => firstPageIds.includes(id));
  eq(overlap, [], '第 1 页与第 2 页重复的 id');
  await shot(page, '04-list-page2');
});

// ---------------------------------------------------------------- 详情页

await t.case('E2E-D-01', 'P0', '点卡片进详情：标题/分类/地点/描述与列表一致', async () => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await cards(page).first().waitFor();
  const firstTitle = await page.locator('.record-card__title').first().innerText();
  const firstHref = await page.locator('.record-card').first().getAttribute('href');
  await cards(page).first().click();
  await page.waitForURL(new RegExp(`${firstHref.replace(/[/\\^$*+?.()|[\]{}]/g, '\\$&')}$`));
  await page.locator('.record-detail__title').waitFor();
  eq(await page.locator('.record-detail__title').innerText(), firstTitle, '详情标题');
  const facts = await page.locator('.record-detail__fact').allInnerTexts();
  const factText = facts.join('\n');
  for (const label of ['分类', '地点', '发生时间', '发布时间', '最后更新', '联系方式', '联系人']) {
    assert(factText.includes(label), `详情缺少字段 ${label}`);
  }
  assert((await page.locator('.record-detail__description').innerText()).length > 0, '描述不应为空');
  created.detailId = firstHref.split('/').pop();
  await shot(page, '05-detail-default');
});

await t.case('E2E-D-02', 'P0', '详情页发起匹配：出现名次、相似度与排序说明', async () => {
  // 用 1 号（寻物·钱包卡证）而不是首屏的 26 号（无分类卡片）：它的对应招领帖 13 存在，
  // 才谈得上「点一下能看到结果」。26 号在当前数据下匹配为空，属正常空态，不适合当黄金路径。
  await page.goto(BASE + '/records/1', { waitUntil: 'domcontentloaded' });
  await page.locator('.record-detail__title').waitFor();
  await page.getByRole('button', { name: '开始匹配' }).click();
  await page.locator('.match-panel__list li').first().waitFor({ timeout: 10000 });
  const summary = await page.locator('.match-panel__summary').innerText();
  assert(/引擎：本地关键词匹配/.test(summary), `匹配摘要缺少引擎名：${summary}`);
  const first = await page.locator('.match-panel__list li').first().innerText();
  assert(/相似度\s*\d+%/.test(first), `结果缺少相似度：${first.slice(0, 120)}`);
  assert(/图书馆拾到黑色钱包/.test(first), `top1 应为 13 号「图书馆拾到黑色钱包」，实际：${first.slice(0, 120)}`);
  await shot(page, '06-detail-match-results');
});

await t.case('E2E-D-03', 'P0', '详情页状态流转：标记为已认领后按钮随 allowedStatuses 收缩', async () => {
  const btn = page.getByRole('button', { name: '标记为已认领' });
  await btn.click();
  await page.locator('.badge', { hasText: '已认领' }).first().waitFor({ timeout: 10000 });
  const actions = await page.locator('.record-detail__actions').innerText();
  assert(actions.includes('标记为已关闭'), `认领后应只剩「标记为已关闭」，实际：${actions}`);
  assert(!actions.includes('标记为已认领'), '认领后不应再出现「标记为已认领」');
  await shot(page, '07-detail-claimed');
});

// ---------------------------------------------------------------- 提交页

await t.case('E2E-C-01', 'P0', '提交页黄金路径：填写 → 提交 → 跳详情并提示发布成功', async () => {
  const stamp = Date.now();
  const title = `E2E 走查记录 ${stamp}`;
  // 记录创建前的默认列表总数（PENDING），供 C-02 做 +1 判定
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await page.locator('.pagination-bar__total').waitFor();
  created.totalBefore = Number(
    (await page.locator('.pagination-bar__total').innerText()).match(/共 (\d+) 条/)?.[1] ?? -1,
  );
  const description = `这条记录由 5.1 端到端走查脚本创建（${stamp}），用于验证「提交 → 列表 → 详情」真实接口全链路与 MySQL 落库。`;
  created.title = title;

  await page.goto(BASE + '/records/new', { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: '发布启事' }).waitFor();
  await page.check('input[name="type"][value="LOST"]');
  await page.fill('#field-title', title);
  await page.fill('#field-description', description);
  await page.selectOption('#field-categoryId', '5');
  await page.fill('#field-location', 'E2E 测试地点 · 第三教学楼');
  await page.fill('#field-occurredAt', '2026-10-21T15:20');
  await page.fill('#field-contact', '13800001234');
  await page.fill('#field-publisherName', '端测测');
  await shot(page, '08-create-filled');

  await page.getByRole('button', { name: '提交启事' }).click();
  await page.waitForURL(/\/records\/\d+/, { timeout: 15000 });
  created.id = page.url().match(/\/records\/(\d+)/)?.[1];
  await page.getByText('发布成功').waitFor();
  eq(await page.locator('.record-detail__title').innerText(), title, '跳转后的详情标题');
  assert(page.url().includes('created=1'), `跳转应带 created=1：${page.url()}`);
  await shot(page, '09-create-success-detail');
});

await t.case('E2E-C-02', 'P0', '列表能看到刚提交的记录（createdAt desc 首位）', async () => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await cards(page).first().waitFor();
  eq(await page.locator('.record-card__title').first().innerText(), created.title, '列表首条标题');
  const total = await page.locator('.pagination-bar__total').innerText();
  // 总数用「创建前 +1」判定，而不是写死 22：D-03 把一条种子记录改成了 CLAIMED，硬编码会误报
  const shown = Number(total.match(/共 (\d+) 条/)?.[1] ?? -1);
  eq(shown, created.totalBefore + 1, '提交后默认列表（PENDING）总条数');
  await shot(page, '10-list-after-create');
});

await t.case('E2E-C-03', 'P1', '关键词检索刚提交的记录，详情可回跳', async () => {
  await page.fill('#filter-keyword', created.title);
  await page.waitForFunction(() => document.querySelectorAll('.record-card').length === 1, null, { timeout: 5000 });
  const href = await page.locator('.record-card').first().getAttribute('href');
  eq(href, `/records/${created.id}`, '检索结果的详情链接');
  await shot(page, '11-list-created-search');
});

// 换一个全新的浏览器上下文（没有本页的 sessionStorage / 内存状态）再打开详情：
// 如果数据只存在于 sessionStorage，这里必然空白 —— 这是「真的落库」最硬的前端侧证据。
await t.case('E2E-C-04', 'P0', '全新浏览器上下文直接打开该记录详情，内容仍完整（非 sessionStorage）', async () => {
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
  await freshPage.screenshot({ path: resolve(SHOTS, '19-detail-fresh-context.png'), fullPage: true });
  await fresh.close();
});

// ---------------------------------------------------------------- 异常路径

await t.case('E2E-E-01', 'P0', '校验失败：空表单提交出现页级摘要 + 字段级错误', async () => {
  await page.goto(BASE + '/records/new', { waitUntil: 'domcontentloaded' });
  await page.getByRole('button', { name: '提交启事' }).click();
  const summary = page.locator('.alert--danger').first();
  await summary.waitFor();
  const text = await summary.innerText();
  assert(/有 \d+ 处需要修改/.test(text), `页级摘要文案不符：${text}`);
  eq(await page.locator('#field-title').getAttribute('aria-invalid'), 'true', '标题 aria-invalid');
  assert(await page.locator('#field-title-error').count() === 1, '标题应有字段错误提示节点');
  assert(page.url().endsWith('/records/new'), '校验失败不应跳转');
  await shot(page, '12-create-validation-errors');
});

await t.case('E2E-E-02', 'P0', '后端不可用：列表显示错误条，恢复后「重试」能拉到数据', async () => {
  // 注意不能用 '**/api/**'：前端自己的 src/api/*.ts 也被 dev server 以 /src/api/… 加载，
  // 用 glob 会连模块一起 abort，页面直接白屏（第一轮就踩了）。只挡真正的后端路径。
  const isApi = (url) => url.pathname.startsWith('/api/');
  await page.route(isApi, (route) => route.abort('connectionrefused'));
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await alertDanger(page).first().waitFor({ timeout: 10000 });
  const text = await alertDanger(page).first().innerText();
  assert(text.includes('网络请求失败'), `错误文案应说明网络失败：${text}`);
  assert(await page.getByRole('button', { name: '重试' }).count() === 1, '应提供「重试」按钮');
  await shot(page, '13-list-backend-down');

  await page.unroute(isApi);
  await page.getByRole('button', { name: '重试' }).click();
  await cards(page).first().waitFor({ timeout: 10000 });
  assert((await cards(page).count()) > 0, '重试后应恢复出卡片');
  await shot(page, '14-list-backend-recovered');
});

await t.case('E2E-E-03', 'P1', '空数据：显示空态而不是错误或空白页', async () => {
  const isEmptyList = (url) => url.pathname === '/api/records';
  await page.route(isEmptyList, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        message: 'ok',
        requestId: 'e2e-empty',
        data: { page: 1, pageSize: 10, total: 0, totalPages: 0, list: [], hasNext: false },
      }),
    }),
  );
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await page.locator('.empty__title').waitFor();
  eq(await page.locator('.empty__title').innerText(), '暂无符合条件的启事', '空态标题');
  assert(await alertDanger(page).count() === 0, '空数据不应显示错误条');
  await shot(page, '15-list-empty');
  await page.unroute(isEmptyList);
});

await t.case('E2E-E-04', 'P1', '详情 404：显示「记录不存在或已删除」空态，不给重试', async () => {
  await page.goto(BASE + '/records/999999', { waitUntil: 'domcontentloaded' });
  await page.locator('.empty__title').waitFor();
  eq(await page.locator('.empty__title').innerText(), '记录不存在或已删除', '404 空态标题');
  assert(await page.getByRole('button', { name: '重试' }).count() === 0, '404 不应给重试按钮');
  await shot(page, '16-detail-not-found');
});

await t.case('E2E-E-05', 'P1', '接口 500：错误条带 requestId，便于按 id 查日志', async () => {
  await page.route(
    (url) => url.pathname === '/api/records/13',
    (route) =>
      route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 10005,
          message: '服务器内部错误',
          data: null,
          requestId: 'e2e-req-5.1-500',
        }),
      }),
  );
  await page.goto(BASE + '/records/13', { waitUntil: 'domcontentloaded' });
  await alertDanger(page).first().waitFor();
  const text = await alertDanger(page).first().innerText();
  assert(text.includes('请求失败，请稍后再试'), `500 文案不符：${text}`);
  assert(text.includes('e2e-req-5.1-500'), `错误条应带 requestId：${text}`);
  await shot(page, '17-detail-500-requestid');
  await page.unroute((url) => url.pathname === '/api/records/13');
});

await t.case('E2E-E-06', 'P1', '匹配服务 503：只降级匹配区，详情主体照常可用', async () => {
  const isMatch = (url) => url.pathname === '/api/match';
  await page.route(isMatch, (route) =>
    route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({ code: 30002, message: '匹配引擎不可用', data: { engine: 'external' }, requestId: 'e2e-match-503' }),
    }),
  );
  await page.goto(BASE + '/records/15', { waitUntil: 'domcontentloaded' });
  await page.locator('.record-detail__title').waitFor();
  await page.getByRole('button', { name: '开始匹配' }).click();
  await page.locator('.match-panel .alert--danger').waitFor({ timeout: 10000 });
  const panelText = await page.locator('.match-panel .alert--danger').innerText();
  assert(panelText.includes('匹配服务暂不可用'), `匹配区降级文案不符：${panelText}`);
  assert((await page.locator('.record-detail__title').isVisible()), '详情主体不应被匹配错误顶掉');
  await shot(page, '18-detail-match-503');
  await page.unroute(isMatch);
});

// ---------------------------------------------------------------- 汇总

await t.case('E2E-Z-01', 'P2', '全程无未捕获的前端异常', async () => {
  assert(pageErrors.length === 0, `捕获到前端报错：${pageErrors.slice(0, 5).join(' | ')}`);
});

await browser.close();

const summary = t.print();
const paths = await t.writeReports(resolve(here, '..', 'reports'));
await writeFile(resolve(here, '..', 'reports', 'e2e-created.json'), JSON.stringify(created, null, 2), 'utf8');
console.log(`\n  截图目录：${SHOTS}`);
console.log(`  报告：${paths.txtPath}`);
console.log(`  本轮通过 UI 创建的记录 id=${created.id}（供 MySQL 落库取证用）`);
process.exit(summary.failed > 0 ? 1 : 0);
