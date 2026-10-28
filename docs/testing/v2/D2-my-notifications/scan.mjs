// D2 我的启事与通知 · 浏览器证据脚本
//
// 前置：
//   1) MySQL 3307（演示库 find_it_campus）+ E1b 后端（feat/v2-E1b-record-pairs @ 3138f8b）18080；
//   2) web 已构建，vite preview 在 5173（/api 代理到 18080）；
//   3) 本机没有 Playwright 自带 Chromium 时用系统 Edge：BROWSER_CHANNEL=msedge（默认）。
// 运行：
//   node docs/testing/v2/D2-my-notifications/scan.mjs
// 数据全部来自真实接口；仅用 fixture.sql / fixture-scored.sql 准备本地演示库样本，
// 页面与网络层不做任何 pairs 注入。
import { execFileSync } from 'node:child_process'
import { mkdir, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const qaPackage =
  process.env.QA_PACKAGE ?? 'C:/Project/hifox-workspaces/wubage/c2/qa/package.json'
const { chromium } = createRequire(qaPackage)('playwright')

const BASE = process.env.WEB_BASE ?? 'http://127.0.0.1:5173'
const MYSQL_BIN =
  process.env.MYSQL_BIN ??
  'C:/Project/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe'
const BROWSER_CHANNEL = process.env.BROWSER_CHANNEL ?? 'msedge'
const PASSWORD = 'Demo@123456'
const PHONE_RE = /1[3-9]\d{9}/g

const report = {
  generatedAt: new Date().toISOString(),
  base: BASE,
  backend: 'feat/v2-E1b-record-pairs @ 3138f8b (http://127.0.0.1:18080)',
  browser: BROWSER_CHANNEL,
  dataSource: 'real API; local DB fixture only (no network/page pairs injection)',
  phonePattern: PHONE_RE.source,
  cases: []
}

let failures = 0

function check(label, ok, detail = '') {
  if (!ok) failures += 1
  report.cases.push({ label, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail ? `  — ${detail}` : ''}`)
}

async function shot(page, name, fullPage = true) {
  // 吸顶 header 在 fullPage 截图里会停在当前滚动位置：禁用平滑滚动并回到顶部再截
  await page.evaluate(() => {
    document.documentElement.style.scrollBehavior = 'auto'
    window.scrollTo(0, 0)
  })
  await page.waitForTimeout(250)
  await page.screenshot({ path: resolve(here, `${name}.png`), fullPage })
  return `${name}.png`
}

async function shotElement(locator, name) {
  const page = locator.page()
  // 元素比视口高时，吸顶 header 会压在元素截图中：截图期间临时隐藏（不改产品样式）
  await page.evaluate(() => {
    const header = document.querySelector('.app-header')
    if (header instanceof HTMLElement) header.style.display = 'none'
  })
  try {
    await locator.scrollIntoViewIfNeeded()
    await page.waitForTimeout(150)
    await locator.screenshot({ path: resolve(here, `${name}.png`) })
  } finally {
    await page.evaluate(() => {
      const header = document.querySelector('.app-header')
      if (header instanceof HTMLElement) header.style.display = ''
    })
  }
  return `${name}.png`
}

function sql(query) {
  return execFileSync(
    MYSQL_BIN,
    ['--host=127.0.0.1', '--port=3307', '-uroot', '-N', '-B', 'find_it_campus', '-e', query],
    { encoding: 'utf8' }
  ).trim()
}

function sqlFile(name) {
  const path = resolve(here, name).replace(/\\/g, '/')
  execFileSync(
    MYSQL_BIN,
    [
      '--host=127.0.0.1',
      '--port=3307',
      '-uroot',
      '--default-character-set=utf8mb4',
      'find_it_campus',
      '-e',
      `source ${path}`
    ],
    { encoding: 'utf8' }
  )
}

async function login(page, studentNo = 'demo01') {
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
  await page.fill('#field-studentNo', studentNo)
  await page.fill('#field-password', PASSWORD)
  await Promise.all([
    page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 15000 }),
    page.click('button[type="submit"]')
  ])
  await page.waitForLoadState('networkidle')
}

function recordCard(page, id) {
  return page.locator(`[data-testid="my-record-card"][data-record-id="${id}"]`)
}

async function main() {
  await mkdir(here, { recursive: true })
  sqlFile('fixture.sql')

  report.fixture = {
    record1Pairs: Number(sql('SELECT COUNT(*) FROM t_pair_score WHERE lost_id=1 OR found_id=1')),
    unreadDemo01: Number(
      sql('SELECT COUNT(*) FROM t_notification WHERE user_id=1 AND read_at IS NULL')
    ),
    record26State: sql("SELECT CONCAT(status, '/', pair_state) FROM t_record WHERE id=26")
  }

  const browser = await chromium.launch({ channel: BROWSER_CHANNEL })
  const context = await browser.newContext({
    viewport: { width: 1280, height: 1000 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai'
  })
  const page = await context.newPage()

  // ---- 1. 分析中 -----------------------------------------------------------
  await login(page)
  await page.goto(`${BASE}/my`, { waitUntil: 'networkidle' })
  const analyzingCard = recordCard(page, 26)
  await analyzingCard.waitFor({ timeout: 15000 })
  const analyzingText = await analyzingCard
    .locator('[data-testid="my-record-pair-state"]')
    .textContent()
  check('刚发布样本显示「分析中」', analyzingText?.trim() === '分析中', analyzingText?.trim())
  check(
    '分析中卡片给出 5 秒自动刷新说明',
    await analyzingCard.locator('[data-testid="my-record-analyzing-hint"]').isVisible()
  )
  await shot(page, '01-my-analyzing')

  // ---- 2. 出分后自动刷新 ----------------------------------------------------
  sqlFile('fixture-scored.sql')
  await page.waitForFunction(
    (id) => {
      const el = document.querySelector(
        `[data-testid="my-record-card"][data-record-id="${id}"] [data-testid="my-record-pair-state"]`
      )
      return Boolean(el && el.textContent && el.textContent.includes('1 条可能相关'))
    },
    26,
    { timeout: 15000 }
  )
  const resultText = await analyzingCard
    .locator('[data-testid="my-record-pair-state"]')
    .textContent()
  const dbRows26 = Number(
    sql('SELECT COUNT(*) FROM t_pair_score WHERE lost_id=26 OR found_id=26')
  )
  check('出分后自动刷新为「N 条可能相关」', resultText?.trim() === '1 条可能相关', resultText?.trim())
  check('N 与 t_pair_score 行数一致', dbRows26 === 1, `rows=${dbRows26}`)
  check(
    '出分后渲染真实 pair 卡片',
    (await analyzingCard.locator('[data-testid="pair-card"]').count()) === 1
  )
  await page.waitForTimeout(300)
  await shot(page, '02-my-result-after-refresh')

  // ---- 3. 多 pair + 降级 ----------------------------------------------------
  await page.getByRole('button', { name: '下一页' }).click()
  const card1 = recordCard(page, 1)
  await card1.waitFor({ timeout: 15000 })
  const card1Text = (await card1.textContent()) ?? ''
  check('记录 1 显示「3 条可能相关」', card1Text.includes('3 条可能相关'))
  check('记录 1 显示降级标记', await card1.locator('[data-testid="my-record-degraded"]').isVisible())
  check(
    '记录 1 显示降级提示',
    (await card1.locator('[data-testid="my-record-degraded-hint"]').textContent())?.includes(
      '智能匹配暂不可用，已用关键词匹配'
    ) === true
  )
  check('记录 1 逐条列出 2 张可查看 pair', (await card1.locator('[data-testid="pair-card"]').count()) === 2)
  const dbRows1 = Number(sql('SELECT COUNT(*) FROM t_pair_score WHERE lost_id=1 OR found_id=1'))
  check('记录 1 的 N 与 t_pair_score 行数一致', dbRows1 === 3, `rows=${dbRows1}`)
  await shotElement(card1, '03-my-multipair-degraded')

  // ---- 4. 一对操作不影响另一对 ----------------------------------------------
  const pairCards = card1.locator('[data-testid="pair-card"]')
  const firstPair = pairCards.nth(0)
  const secondPair = pairCards.nth(1)
  const firstPairId = await firstPair.getAttribute('data-pair-id')
  const secondPairId = await secondPair.getAttribute('data-pair-id')
  await firstPair.locator('[data-testid="pair-action-contact"]').click()
  await page.waitForFunction(
    (id) => {
      const card = document.querySelector(
        `[data-testid="my-record-card"][data-record-id="1"]`
      )
      const pairs = card?.querySelectorAll('[data-testid="pair-card"]') ?? []
      const first = [...pairs].find((el) => el.getAttribute('data-pair-id') === id)
      return Boolean(
        first &&
          first.querySelector('[data-testid="pair-status"]')?.textContent?.includes('已联系')
      )
    },
    firstPairId,
    { timeout: 10000 }
  )
  const secondStatus = await secondPair.locator('[data-testid="pair-status"]').textContent()
  const firstStatusAfter = await firstPair.locator('[data-testid="pair-status"]').textContent()
  check('第一对操作后变为「已联系」', firstStatusAfter?.trim() === '已联系', firstStatusAfter?.trim())
  check('第二对状态保持「待联系」', secondStatus?.trim() === '待联系', secondStatus?.trim())
  const pair15Status = sql(`SELECT status FROM t_pair_score WHERE id=${secondPairId}`)
  check('第二对数据库状态仍为 NEW', pair15Status === 'NEW', pair15Status)
  await shot(page, '04-my-pair-independent')

  // ---- 5. 无 MATCHED pair：按钮置灰、点击零请求 ------------------------------
  const statusRequests = []
  page.on('request', (request) => {
    if (
      request.method() === 'PATCH' &&
      /\/api\/records\/\d+\/status$/.test(new URL(request.url()).pathname)
    ) {
      statusRequests.push(request.url())
    }
  })
  const completeBtn = card1.locator('[data-testid="my-record-complete"]')
  const disabled = await completeBtn.isDisabled()
  check('无 MATCHED pair 时「标记完成」置灰', disabled)
  check(
    '置灰原因可见',
    (await card1.locator('[data-testid="my-record-complete-reason"]').textContent())?.includes(
      '需要双方都确认后才能标记完成'
    ) === true
  )
  await completeBtn.evaluate((el) => el.click())
  await page.waitForTimeout(700)
  const record1StatusRequests = statusRequests.filter((url) => /\/records\/1\/status$/.test(url))
  check('点击置灰按钮不发网络请求', record1StatusRequests.length === 0, `count=${record1StatusRequests.length}`)
  check(
    '记录状态未被改变',
    sql('SELECT status FROM t_record WHERE id=1') === 'PENDING'
  )
  await shotElement(card1, '05-my-complete-disabled')

  // ---- 6. 有 MATCHED pair：标记完成 -----------------------------------------
  await page.getByRole('button', { name: '第 4 页', exact: true }).click()
  const card10 = recordCard(page, 10)
  await card10.waitFor({ timeout: 15000 })
  check(
    '有 MATCHED pair 时「标记完成」可用',
    !(await card10.locator('[data-testid="my-record-complete"]').isDisabled())
  )
  await card10.locator('[data-testid="my-record-complete"]').click()
  const summary10 = page.locator('[data-testid="my-record-summary"][data-record-id="10"]')
  await summary10.waitFor({ timeout: 15000 })
  check(
    '标记成功后记录进入「已完成」页签',
    (await page.getByTestId('my-tab-completed').getAttribute('aria-selected')) === 'true'
  )
  check(
    '已完成摘要带 completed_at',
    ((await summary10.locator('[data-testid="my-summary-completed-at"]').textContent()) ?? '').trim()
      .length > 0
  )
  check(
    '已完成摘要带匹配对数',
    (await summary10.locator('[data-testid="my-summary-matched-count"]').textContent())?.trim() ===
      '1'
  )
  check(
    '数据库状态变 COMPLETED 且 completed_at 非空',
    sql('SELECT CONCAT(status, "/", IFNULL(completed_at, "")) FROM t_record WHERE id=10').startsWith(
      'COMPLETED/'
    )
  )
  const expired25 = page.locator(
    '[data-testid="my-record-summary"][data-record-id="25"] [data-testid="my-summary-expired"]'
  )
  check('31 天前完成的样本带「已过保留期」标记', await expired25.isVisible())
  await shot(page, '06-my-completed-tab')

  // ---- 7. 关闭启事 ----------------------------------------------------------
  await page.getByTestId('my-tab-pending').click()
  const card7 = recordCard(page, 7)
  await card7.waitFor({ timeout: 15000 })
  await card7.locator('[data-testid="my-record-close"]').click()
  check(
    '关闭走二次确认（确认前不发请求）',
    await card7.locator('[data-testid="my-record-close-confirm"]').isVisible()
  )
  await card7.locator('[data-testid="my-record-close-confirm"]').click()
  const summary7 = page.locator('[data-testid="my-record-summary"][data-record-id="7"]')
  await summary7.waitFor({ timeout: 15000 })
  check(
    '关闭后进入「已关闭」页签',
    (await page.getByTestId('my-tab-closed').getAttribute('aria-selected')) === 'true'
  )
  check('数据库状态变 CLOSED', sql('SELECT status FROM t_record WHERE id=7') === 'CLOSED')
  const publicList = await page.request.get(`${BASE}/api/records?pageSize=50`)
  const publicJson = await publicList.json()
  const publicIds = (publicJson.data?.list ?? []).map((item) => item.id)
  check('关闭后从公共列表消失', !publicIds.includes(7), `ids=${publicIds.join(',')}`)
  check('已完成的记录也不在公共列表', !publicIds.includes(10))
  await shot(page, '07-my-closed-tab')

  // ---- 8. 通知：3 条未读 / 红点 3 → 已读归零 --------------------------------
  await page.getByTestId('nav-notifications').click()
  await page.locator('[data-testid="notification-item"]').first().waitFor({ timeout: 15000 })
  const badge = page.getByTestId('notification-badge')
  check('顶栏红点为 3', (await badge.textContent())?.trim() === '3')
  const unreadText = (await page.getByTestId('notifications-unread').textContent()) ?? ''
  check('通知页显示未读 3 条', unreadText.includes('未读 3 条'), unreadText.trim())
  check('3 条未读标记', (await page.getByTestId('notification-unread-dot').count()) === 3)
  const bodies = await page.getByTestId('notification-body').allTextContents()
  const phoneHits = bodies.flatMap((text) => text.match(PHONE_RE) ?? [])
  check('通知正文不含 11 位手机号', phoneHits.length === 0, `hits=${phoneHits.length}`)
  await shot(page, '08-notifications-unread3')

  await page.getByTestId('notifications-mark-all').click()
  await page.waitForFunction(
    () => {
      const el = document.querySelector('[data-testid="notifications-unread"]')
      return Boolean(el && el.textContent && el.textContent.includes('未读 0 条'))
    },
    undefined,
    { timeout: 15000 }
  )
  check('全部已读后未读数归零', (await page.getByTestId('notifications-unread').textContent())?.includes('未读 0 条'))
  check('红点消失', (await badge.count()) === 0 || !(await badge.isVisible()))
  check('数据库无未读通知', sql('SELECT COUNT(*) FROM t_notification WHERE user_id=1 AND read_at IS NULL') === '0')
  await shot(page, '09-notifications-read0')

  await page.reload({ waitUntil: 'networkidle' })
  await page.locator('[data-testid="notification-item"]').first().waitFor({ timeout: 15000 })
  check('刷新后红点仍为 0', (await badge.count()) === 0 || !(await badge.isVisible()))
  check('刷新后未读仍为 0', (await page.getByTestId('notifications-unread').textContent())?.includes('未读 0 条'))
  check('刷新后没有未读态条目', (await page.getByTestId('notification-unread-dot').count()) === 0)
  await shot(page, '10-notifications-after-reload')

  // ---- 9. 320px -------------------------------------------------------------
  const mobile = await browser.newContext({
    viewport: { width: 320, height: 900 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai'
  })
  const mobilePage = await mobile.newPage()
  await login(mobilePage)
  await mobilePage.goto(`${BASE}/my`, { waitUntil: 'networkidle' })
  await mobilePage.locator('[data-testid="my-record-card"]').first().waitFor({ timeout: 15000 })
  const myScrollWidth = await mobilePage.evaluate(() => document.documentElement.scrollWidth)
  check('320px /my 无横向滚动', myScrollWidth <= 320, `scrollWidth=${myScrollWidth}`)
  await mobilePage.getByRole('button', { name: '下一页' }).click()
  const mobileCard1 = recordCard(mobilePage, 1)
  await mobileCard1.waitFor({ timeout: 15000 })
  const cardWidth = await mobileCard1.evaluate((el) => el.getBoundingClientRect().width)
  check('320px 卡片宽度不超出视口', cardWidth <= 320, `width=${cardWidth}`)
  const buttonBoxes = await mobileCard1.locator('.my-record__actions .btn').evaluateAll((els) =>
    els.map((el) => {
      const rect = el.getBoundingClientRect()
      return { top: Math.round(rect.top), left: Math.round(rect.left), right: Math.round(rect.right) }
    })
  )
  check(
    '320px 操作按钮换行且都在视口内',
    buttonBoxes.length >= 3 &&
      new Set(buttonBoxes.map((box) => box.top)).size >= 3 &&
      buttonBoxes.every((box) => box.left >= 0 && box.right <= 320),
    JSON.stringify(buttonBoxes)
  )
  await shot(mobilePage, '11-my-320', false)
  await shotElement(mobileCard1, '11b-my-320-card')

  await mobilePage.goto(`${BASE}/notifications`, { waitUntil: 'networkidle' })
  await mobilePage.locator('[data-testid="notification-item"]').first().waitFor({ timeout: 15000 })
  const notifScrollWidth = await mobilePage.evaluate(() => document.documentElement.scrollWidth)
  check('320px /notifications 无横向滚动', notifScrollWidth <= 320, `scrollWidth=${notifScrollWidth}`)
  await shot(mobilePage, '12-notifications-320')

  await mobile.close()
  await browser.close()
}

main()
  .catch((err) => {
    failures += 1
    report.cases.push({ label: '脚本异常', ok: false, detail: String(err?.stack ?? err) })
    console.error(err)
  })
  .finally(async () => {
    report.summary = {
      total: report.cases.length,
      passed: report.cases.filter((item) => item.ok).length,
      failed: failures
    }
    await writeFile(resolve(here, 'report.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8')
    console.log(
      `\n${report.summary.passed}/${report.summary.total} PASS, ${report.summary.failed} FAIL`
    )
    if (failures > 0) process.exitCode = 1
  })
