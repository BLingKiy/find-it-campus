// 4.2 残留复验：三页跨页一致性（ZHYYKFX-10）
//
// 事实源：docs/ui-baseline.md。做法是量计算样式（getComputedStyle）与视口表现，不看截图「感觉像」。
// 口径（2026-10-22 人类拍板）：深色模式本期不做、品牌色 #2450d0、按钮圆角 6px —— 接受，不作为缺陷。
//
// 前置：前端 5173 + 后端 18080 已起，库为 seed 初始状态。
// 运行：node qa/e2e/consistency.mjs

import { chromium } from 'playwright';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Tester, assert, eq } from '../lib/tester.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const SHOTS = resolve(here, '..', 'reports', 'screenshots');
const BASE = process.env.WEB_BASE ?? 'http://localhost:5173';

const t = new Tester('consistency-5.1');
const table = [];
const note = (item, expected, actual, ok) => table.push({ item, expected, actual, ok });

await mkdir(SHOTS, { recursive: true });

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'zh-CN' });
const page = await context.newPage();

// v2：/records/new 需要登录（提交页守卫）。先以 demo01 登录，再量三页样式。
await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
await page.fill('#field-studentNo', 'demo01');
await page.fill('#field-password', 'Demo@123456');
await page.getByRole('button', { name: '登录', exact: true }).click();
await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 10000 });

const PAGES = [
  { path: '/', name: '列表页', section: '.record-list' },
  { path: '/records/new', name: '提交页', section: '.record-create' },
  { path: '/records/1', name: '详情页', section: '.record-detail' },
];

const styleOf = (sel, props) =>
  page.locator(sel).first().evaluate((el, props) => {
    const cs = getComputedStyle(el);
    return Object.fromEntries(props.map((k) => [k, cs.getPropertyValue(k)]));
  }, props);

const tokensOf = () =>
  page.evaluate(() => {
    const cs = getComputedStyle(document.documentElement);
    return {
      primary: cs.getPropertyValue('--color-primary').trim(),
      radiusControl: cs.getPropertyValue('--radius-sm').trim(),
      radiusPanel: cs.getPropertyValue('--radius-md').trim(),
      container: cs.getPropertyValue('--container-max').trim(),
      contentMax: cs.getPropertyValue('--content-max').trim(),
    };
  });

async function gotoAll(fn) {
  const collected = [];
  for (const p of PAGES) {
    await page.goto(BASE + p.path, { waitUntil: 'domcontentloaded' });
    await page.locator(p.section).waitFor();
    collected.push({ ...p, ...(await fn(p)) });
  }
  return collected;
}

// ---------------------------------------------------------------- 1. token 一致

await t.case('CON-01', 'P0', '三页读取到同一套 token（品牌色 / 圆角 / 容器宽）', async () => {
  const all = await gotoAll(async () => ({ tokens: await tokensOf() }));
  for (const p of all) {
    eq(p.tokens.primary, '#2450d0', `${p.name} 的 --color-primary`);
    eq(p.tokens.radiusControl, '6px', `${p.name} 的 --radius-sm`);
    eq(p.tokens.radiusPanel, '10px', `${p.name} 的 --radius-md`);
    eq(p.tokens.container, '1120px', `${p.name} 的 --container-max`);
    eq(p.tokens.contentMax, '760px', `${p.name} 的 --content-max`);
  }
  note('品牌色 token', '#2450d0（人类已拍板）', all.map((x) => x.tokens.primary).join(' / '), true);
  note('按钮/控件圆角 token', '6px', all.map((x) => x.tokens.radiusControl).join(' / '), true);
  note('面板圆角 token', '10px', all.map((x) => x.tokens.radiusPanel).join(' / '), true);
});

// ---------------------------------------------------------------- 2. 同类组件同值

await t.case('CON-02', 'P0', '按钮：三页所有 .btn 变体圆角一致，主按钮配色一致', async () => {
  const results = [];
  for (const p of PAGES) {
    await page.goto(BASE + p.path, { waitUntil: 'domcontentloaded' });
    await page.locator(p.section).waitFor();
    const btns = await page.locator('.btn').evaluateAll((nodes) =>
      nodes.map((n) => {
        const cs = getComputedStyle(n);
        return {
          cls: n.className,
          radius: cs.borderRadius,
          bg: cs.backgroundColor,
          color: cs.color,
          fontSize: cs.fontSize,
          height: cs.height,
        };
      }),
    );
    assert(btns.length > 0, `${p.name} 未找到按钮`);
    for (const b of btns) {
      eq(b.radius, '6px', `${p.name} 按钮「${b.cls}」的圆角`);
    }
    const primary = btns.find((b) => b.cls.includes('btn--primary'));
    assert(primary, `${p.name} 应有主按钮`);
    eq(primary.bg, 'rgb(36, 80, 208)', `${p.name} 主按钮底色（#2450d0）`);
    eq(primary.color, 'rgb(255, 255, 255)', `${p.name} 主按钮文字色`);
    results.push(`${p.name}: ${btns.length} 个按钮圆角 6px`);
  }
  note('按钮圆角（全部变体）', '6px', results.join('；'), true);
});

await t.case('CON-03', 'P0', '表单控件：高度 / 圆角 / 描边一致（列表筛选区 + 提交表单）', async () => {
  const specs = [];
  for (const p of PAGES) {
    await page.goto(BASE + p.path, { waitUntil: 'domcontentloaded' });
    await page.locator(p.section).waitFor();
    if ((await page.locator('.control').count()) === 0) continue;
    const c = await styleOf('.control', ['border-radius', 'height', 'border-color', 'font-size', 'background-color']);
    specs.push({ page: p.name, ...c });
  }
  // 详情页没有表单控件（只有按钮与文本），所以是 2 页而不是 3 页 —— 这是页面职责差异，不是样式不一致
  eq(specs.length, 2, '应有控件的页面数（列表筛选区 / 提交表单）');
  for (const s of specs) {
    eq(s['border-radius'], '6px', `${s.page} 控件圆角`);
    eq(s.height, '40px', `${s.page} 控件高度`);
    eq(s['border-color'], 'rgb(139, 148, 164)', `${s.page} 控件描边色（--color-control-border）`);
    eq(s['font-size'], '14px', `${s.page} 控件字号`);
  }
  note('控件圆角 / 高度 / 描边', '6px / 40px / rgb(139,148,164)', specs.map((s) => `${s.page} ${s['border-radius']} ${s.height} ${s['border-color']}`).join('；'), true);
});

await t.case('CON-04', 'P1', '面板与徽标：面板 10px、徽标 pill，卡片与面板各用一档间距', async () => {
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  const card = await styleOf('.record-card', ['border-radius', 'padding', 'border-color', 'background-color']);
  const badge = await styleOf('.badge', ['border-radius', 'height', 'font-size']);

  await page.goto(BASE + '/records/new', { waitUntil: 'domcontentloaded' });
  const createPanel = await styleOf('.panel', ['border-radius', 'padding', 'border-color', 'background-color']);

  await page.goto(BASE + '/records/1', { waitUntil: 'domcontentloaded' });
  const detailPanel = await styleOf('.panel', ['border-radius', 'padding', 'border-color', 'background-color']);
  const detailBadge = await styleOf('.badge', ['border-radius', 'height', 'font-size']);

  eq(card['border-radius'], '10px', '列表卡片圆角');
  eq(createPanel['border-radius'], '10px', '提交页面板圆角');
  eq(detailPanel['border-radius'], '10px', '详情页面板圆角');
  // 基线只要求「同一类元素同值」：面板统一 --sp-5(20px)、卡片统一 --sp-4(16px)，两档各自一致即可
  eq(card.padding, '16px', '列表卡片内边距（--sp-4）');
  eq(createPanel.padding, '20px', '提交页面板内边距（--sp-5）');
  eq(detailPanel.padding, '20px', '详情页面板内边距（--sp-5）');
  eq(card['border-color'], detailPanel['border-color'], '卡片与面板描边');

  eq(badge['border-radius'], '999px', '列表徽标圆角（pill）');
  eq(detailBadge['border-radius'], '999px', '详情徽标圆角（pill）');
  eq(badge.height, detailBadge.height, '两页徽标高度');
  eq(badge['font-size'], detailBadge['font-size'], '两页徽标字号');

  note('面板/卡片圆角', '10px', `卡片 ${card['border-radius']} · 提交页 ${createPanel['border-radius']} · 详情页 ${detailPanel['border-radius']}`, true);
  note('内边距（同类同值）', '卡片 16px / 面板 20px', `${card.padding} / ${createPanel.padding} / ${detailPanel.padding}`, true);
  note('徽标（pill）', '999px / 22px / 12px', `${badge['border-radius']} / ${badge.height} / ${badge['font-size']}`, true);
});

await t.case('CON-05', 'P1', '页面头（.page-head）三页同字号、同容器宽、同内边距', async () => {
  const rows = [];
  for (const p of PAGES) {
    await page.goto(BASE + p.path, { waitUntil: 'domcontentloaded' });
    await page.locator(p.section).waitFor();
    const title = await styleOf('.page-head__title', ['font-size', 'line-height', 'color', 'letter-spacing']);
    const sectionBox = await page.locator(p.section).boundingBox();
    const container = await page.locator('.app-main > .container').evaluate((el) => {
      const cs = getComputedStyle(el);
      const r = el.getBoundingClientRect();
      return { width: Math.round(r.width), pad: `${cs.paddingLeft}/${cs.paddingRight}` };
    });
    rows.push({ page: p.name, ...title, width: Math.round(sectionBox.width), container });
  }
  for (const r of rows) {
    eq(r['font-size'], '30px', `${r.page} 页标题字号（1280 视口，clamp 上限）`);
    eq(r['font-size'], rows[0]['font-size'], `${r.page} 与列表页字号一致`);
    eq(r.container.width, 1120, `${r.page} 内容容器宽（--container-max）`);
    eq(r.container.pad, '24px/24px', `${r.page} 容器左右内边距（≥768 档）`);
    eq(r.width, rows[0].width, `${r.page} 与列表页的正文宽度`);
    eq(r.color, 'rgb(26, 32, 51)', `${r.page} 标题颜色（--color-text）`);
  }
  note('页标题字号 / 容器宽 / 内边距 / 正文宽', '30px / 1120px / 24px / 1072px', rows.map((r) => `${r.page} ${r['font-size']} ${r.container.width} ${r.container.pad} ${r.width}`).join('；'), true);
});

// ---------------------------------------------------------------- 3. 状态一致

await t.case('CON-06', 'P1', '加载态：列表与详情用同一骨架类（.skeleton），不是各自一套', async () => {
  // 用延迟响应制造加载窗口，观察骨架屏是否来自同一套 .skeleton
  const isDataCall = (url) => url.pathname === '/api/records' || /^\/api\/records\/\d+$/.test(url.pathname);
  await page.route(isDataCall, async (route) => {
    await new Promise((r) => setTimeout(r, 1200));
    // 观察完骨架后页面会立刻跳走，在途请求被浏览器取消 —— 这里的 continue 失败属预期，吞掉即可
    try {
      await route.continue();
    } catch {
      /* aborted by navigation */
    }
  });

  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await page.locator('.skeleton').first().waitFor({ timeout: 5000 });
  const listSkeleton = await page.locator('.skeleton').first().evaluate((el) => {
    const cs = getComputedStyle(el);
    return { radius: cs.borderRadius, bg: cs.backgroundColor };
  });

  await page.goto(BASE + '/records/1', { waitUntil: 'domcontentloaded' });
  await page.locator('.skeleton').first().waitFor({ timeout: 5000 });
  const detailSkeleton = await page.locator('.skeleton').first().evaluate((el) => {
    const cs = getComputedStyle(el);
    return { radius: cs.borderRadius, bg: cs.backgroundColor };
  });
  await page.unroute(isDataCall);

  eq(listSkeleton.radius, detailSkeleton.radius, '列表与详情的骨架圆角');
  eq(listSkeleton.bg, detailSkeleton.bg, '列表与详情的骨架底色');
  note('加载骨架（列表/详情）', '同一 .skeleton 样式', `radius ${listSkeleton.radius} · bg ${listSkeleton.bg}`, true);
});

await t.case('CON-07', 'P1', '空态与错误态：三页共用 .empty / .alert--danger 结构', async () => {
  // 列表空态 / 详情 404 空态：用匿名上下文。v2 登录用户遇到记录 404 会被引导回「我的启事」，
  // 量不到详情页自己的空态。
  const anonCtx = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'zh-CN' });
  const anonPage = await anonCtx.newPage();
  const isEmptyList = (url) => url.pathname === '/api/records';
  await anonPage.route(isEmptyList, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 0,
        message: 'ok',
        requestId: 'con-empty',
        data: { page: 1, pageSize: 10, total: 0, totalPages: 0, list: [], hasNext: false },
      }),
    }),
  );
  await anonPage.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await anonPage.locator('.empty').waitFor();
  const emptyList = await anonPage.locator('.empty__title').first().evaluate((el) => { const cs = getComputedStyle(el); return { 'font-size': cs.fontSize, color: cs.color }; });
  await anonPage.unroute(isEmptyList);

  // 详情 404 空态（真实接口）
  await anonPage.goto(BASE + '/records/999999', { waitUntil: 'domcontentloaded' });
  await anonPage.locator('.empty').waitFor();
  const emptyDetail = await anonPage.locator('.empty__title').first().evaluate((el) => { const cs = getComputedStyle(el); return { 'font-size': cs.fontSize, color: cs.color }; });
  await anonCtx.close();

  // 错误态：列表请求 500
  await page.route(isEmptyList, (route) =>
    route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({ code: 10005, message: '服务器内部错误', data: null, requestId: 'con-500' }),
    }),
  );
  await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await page.locator('.alert--danger').waitFor();
  const errorBox = await styleOf('.alert--danger', ['border-radius', 'padding', 'background-color']);
  await page.unroute(isEmptyList);

  eq(emptyList, emptyDetail, '列表空态与详情空态的标题样式');
  // 提示条与面板同档（10px），不是控件档：它是「白底带框的块」，与 .panel 同一视觉层级
  eq(errorBox['border-radius'], '10px', '错误条圆角（面板档）');
  note('空态标题样式', '两页一致', JSON.stringify(emptyList), true);
  note('错误条圆角', '10px（面板档）', errorBox['border-radius'], true);
});

// ---------------------------------------------------------------- 4. 响应式

await t.case('CON-08', 'P0', '320 / 768 / 1280 三档 × 三页均无横向溢出', async () => {
  const results = [];
  for (const width of [320, 768, 1280]) {
    await page.setViewportSize({ width, height: 900 });
    for (const p of PAGES) {
      await page.goto(BASE + p.path, { waitUntil: 'domcontentloaded' });
      await page.locator(p.section).waitFor();
      await page.waitForTimeout(200);
      const [scrollWidth, innerWidth] = await page.evaluate(() => [
        document.documentElement.scrollWidth,
        window.innerWidth,
      ]);
      assert(
        scrollWidth <= innerWidth + 1,
        `${p.name} @${width}px 出现横向溢出：scrollWidth=${scrollWidth} innerWidth=${innerWidth}`,
      );
      results.push(`${p.name}@${width}:${scrollWidth}/${innerWidth}`);
      await page.screenshot({
        path: resolve(SHOTS, `responsive-${width}-${p.path === '/' ? 'list' : p.path.includes('new') ? 'create' : 'detail'}.png`),
        fullPage: true,
      });
    }
  }
  await page.setViewportSize({ width: 1280, height: 900 });
  note('横向溢出（320/768/1280 × 三页）', 'scrollWidth ≤ innerWidth', results.join('，'), true);
});

await t.case('CON-09', 'P2', '深色模式本期不做：暗色偏好下颜色不变（口径接受，不计缺陷）', async () => {
  const dark = await browser.newContext({ viewport: { width: 1280, height: 900 }, colorScheme: 'dark', locale: 'zh-CN' });
  const darkPage = await dark.newPage();
  await darkPage.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
  await darkPage.locator('.record-list').waitFor();
  const bg = await darkPage.evaluate(() => getComputedStyle(document.body).backgroundColor);
  const cardBg = await darkPage.evaluate(() => getComputedStyle(document.querySelector('.record-card')).backgroundColor);
  await dark.close();

  eq(bg, 'rgb(244, 246, 251)', '暗色偏好下的页面底色（应仍为浅色 --color-bg）');
  eq(cardBg, 'rgb(255, 255, 255)', '暗色偏好下的卡片底色');
  note('深色模式', '本期不做（人类已拍板）', `暗色偏好下 body=${bg}，卡片=${cardBg}`, true);
});

await browser.close();

// ---------------------------------------------------------------- 输出

const fails = t.results.filter((r) => r.status !== 'PASS');
const lines = [];
lines.push('# 4.2 跨页一致性复验（量计算样式，不靠肉眼）');
lines.push('');
lines.push(`- 时间：${new Date().toISOString()} · 视口 1280（响应式用例另测 320/768） · 浏览器：Edge（Playwright channel=msedge）`);
lines.push(`- 用例：${t.results.length} · 通过 ${t.passed} · 失败 ${t.failed}`);
lines.push('');
lines.push('| 检查项 | 期望 | 实测 | 结论 |');
lines.push('|---|---|---|---|');
for (const r of table) lines.push(`| ${r.item} | ${r.expected} | ${r.actual} | ${r.ok ? '一致' : '不一致'} |`);
lines.push('');
if (fails.length > 0) {
  lines.push('## 不通过项');
  lines.push('');
  for (const f of fails) lines.push(`- ${f.id} ${f.name}：${f.reason}`);
  lines.push('');
}
lines.push(
  `**结论**：${fails.length === 0 ? '通过' : '不通过'} —— ${t.passed}/${t.results.length} 条用例通过；` +
    '三页共用同一套 token 与原始类，按钮 6px / 面板 10px / 徽标 pill / 控件 40px 在三页取到同值。',
);

const path = resolve(here, '..', 'reports', 'consistency-5.1.md');
await writeFile(path, lines.join('\n'), 'utf8');
const rep = await t.writeReports(resolve(here, '..', 'reports'));
t.print();
console.log(`\n报告：${path}`);
console.log(`报告：${rep.txtPath}`);
process.exit(t.failed > 0 ? 1 : 0);
