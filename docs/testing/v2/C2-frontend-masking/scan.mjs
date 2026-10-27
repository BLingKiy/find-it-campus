// C2 前端遮蔽态 · 三态 DOM 扫描 + 截图（真实后端，非 mock）
//
// 前置：后端 feat/v2-C1-masking @ 0fff831 已起在 18080（MySQL 3307 + db/seed.sql）；
//       前端已 `npm run build` 并 `npm run preview -- --host 127.0.0.1 --port 5173`。
//       证据只对真实响应做，脚本内不含任何 mock 数据。
// 运行：node docs/testing/v2/C2-frontend-masking/scan.mjs
//
// 三态：
//   anonymous  → demo02 的 PENDING 启事 #13（非本人 + 未登录）
//   non-owner  → demo01 登录后看 #13（登录但仍非本人）
//   owner      → demo02 登录后看 #16（本人）+ 列表 + 编辑 / 关闭流程
import { createRequire } from 'node:module'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
// playwright 只在 qa/ 里安装；证据脚本不额外拉依赖
const { chromium } = createRequire(resolve(here, '../../../../qa/package.json'))('playwright')

const SHOTS = here
// 对「生产构建 + preview 代理」扫描：dev server 的脚本 URL 会带 ?t=<毫秒戳>，
// 那段 13 位数字里能截出 11 位串，会把「DOM 无手机号」判成误报。
// preview 也监听 5173：后端 CORS 白名单只有 5173，换端口会被 403 挡在登录之外。
const BASE = process.env.WEB_BASE ?? 'http://127.0.0.1:5173'
const PASSWORD = 'Demo@123456'
/** 验收口径：11 位手机号串 */
const PHONE_RE = /1[3-9]\d{9}/g

const NON_OWNER_DETAIL = '/records/13' // publisher_id=2（demo02）
const OWNER_DETAIL = '/records/16' // publisher_id=2（demo02），PENDING

const report = {
  generatedAt: new Date().toISOString(),
  base: BASE,
  backend: 'feat/v2-C1-masking @ 0fff831 (http://127.0.0.1:18080)',
  phonePattern: PHONE_RE.source,
  cases: []
}

let failures = 0

function check(label, ok, detail = '') {
  if (!ok) failures += 1
  report.cases.push({ label, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ` — ${detail}` : ''}`)
}

async function shot(page, name) {
  // 吸顶 header 在 fullPage 截图里会停在"当前滚动位置"，先回到顶部再截，避免证据看起来像布局错位
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.waitForTimeout(150)
  const path = resolve(SHOTS, `${name}.png`)
  await page.screenshot({ path, fullPage: true })
  return `C2-frontend-masking/${name}.png`
}

async function scan(page) {
  const html = await page.evaluate(() => document.documentElement.outerHTML)
  const text = await page.evaluate(() => document.body.innerText)
  const masked = await page.locator('[data-testid="masked-placeholder"]').allInnerTexts()
  return {
    url: page.url(),
    phoneHitsInHtml: html.match(PHONE_RE) ?? [],
    phoneHitsInText: text.match(PHONE_RE) ?? [],
    maskedCount: masked.length,
    maskedTexts: masked,
    manageEntryCount: await page.locator('[data-testid="own-record-actions"]').count(),
    editEntryCount: await page.locator('[data-testid="action-edit"]').count(),
    closeEntryCount: await page.locator('[data-testid="action-close"]').count(),
    telLinks: await page.locator('a[href^="tel:"]').count()
  }
}

async function newContext(browser) {
  return browser.newContext({
    viewport: { width: 1280, height: 1000 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai'
  })
}

async function login(page, studentNo) {
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
  await page.fill('#field-studentNo', studentNo)
  await page.fill('#field-password', PASSWORD)
  await Promise.all([
    page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 10_000 }),
    page.click('button[type=submit]')
  ])
  await page.waitForSelector('[data-testid="header-student-no"]')
}

await mkdir(SHOTS, { recursive: true })

const browser = await chromium.launch({ channel: 'msedge', headless: true })

// ---------------------------------------------------------------- 1. 匿名详情
{
  const context = await newContext(browser)
  const page = await context.newPage()
  await page.goto(`${BASE}${NON_OWNER_DETAIL}`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-testid="anonymous-banner"]')

  const s = await scan(page)
  report.anonymousDetail = { ...s, shot: await shot(page, 'detail-01-anonymous') }

  check('匿名详情：DOM 无 11 位手机号', s.phoneHitsInHtml.length === 0 && s.phoneHitsInText.length === 0)
  check('匿名详情：联系方式渲染「登录后可查看」占位', s.maskedCount >= 1 && s.maskedTexts.includes('登录后可查看'))
  check('匿名详情：无管理入口', s.manageEntryCount === 0)
  check('匿名详情：无 tel: 链接', s.telLinks === 0)

  // 未登录点「查看联系方式」→ /login?redirect=当前详情页
  await page.click('[data-testid="contact-login-cta"]')
  await page.waitForURL(/\/login\?redirect=/, { timeout: 10_000 })
  const loginUrl = new URL(page.url())
  const redirect = loginUrl.searchParams.get('redirect')
  report.anonymousDetail.loginRedirect = { url: page.url(), redirect }
  report.anonymousDetail.loginShot = await shot(page, 'detail-02-anonymous-login-cta')

  check('匿名点「查看联系方式」→ /login', loginUrl.pathname === '/login')
  check('匿名点「查看联系方式」→ 带回跳地址', redirect === NON_OWNER_DETAIL, String(redirect))
  await context.close()
}

// ---------------------------------------------------------------- 2. 匿名列表
{
  const context = await newContext(browser)
  const page = await context.newPage()
  await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.record-card')

  const s = await scan(page)
  report.anonymousList = { ...s, shot: await shot(page, 'list-01-anonymous') }

  check('匿名列表：DOM 无 11 位手机号', s.phoneHitsInHtml.length === 0 && s.phoneHitsInText.length === 0)
  check('匿名列表：无管理入口', s.manageEntryCount === 0)
  await context.close()
}

// ---------------------------------------------------------------- 3. 登录非本人
{
  const context = await newContext(browser)
  const page = await context.newPage()
  await login(page, 'demo01')
  await page.goto(`${BASE}${NON_OWNER_DETAIL}`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-testid="non-owner-banner"]')

  const s = await scan(page)
  report.nonOwnerDetail = { ...s, shot: await shot(page, 'detail-03-non-owner') }

  check('登录非本人详情：DOM 无 11 位手机号', s.phoneHitsInHtml.length === 0 && s.phoneHitsInText.length === 0)
  check('登录非本人详情：遮蔽位仍是「登录后可查看」', s.maskedCount >= 1)
  check('登录非本人详情：无编辑 / 关闭入口', s.manageEntryCount === 0)

  // 列表：本人卡片有入口、非本人卡片没有；不同身份不串
  await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('.record-card')
  const list = await scan(page)
  const badges = await page.locator('[data-testid="card-mine-badge"]').count()
  const ownerCards = await page.locator('.record-card').count()
  const ownRows = await page
    .locator('.record-card')
    .evaluateAll((cards) =>
      cards.map((card) => ({
        id: card.querySelector('.record-card__id')?.textContent?.trim(),
        mine: Boolean(card.querySelector('[data-testid="card-mine-badge"]')),
        manage: Boolean(card.querySelector('[data-testid="own-record-actions"]'))
      }))
    )
  report.nonOwnerList = { ...list, shot: await shot(page, 'list-02-non-owner'), cards: ownRows }

  check('非本人列表：DOM 无 11 位手机号', list.phoneHitsInHtml.length === 0 && list.phoneHitsInText.length === 0)
  check(
    '非本人列表：管理入口只出现在本人卡片上',
    ownRows.every((row) => row.mine === row.manage) && badges > 0 && badges < ownerCards,
    `本人卡片 ${badges} / 共 ${ownerCards}`
  )
  await context.close()
}

// ---------------------------------------------------------------- 4. 本人详情 + 列表
{
  const context = await newContext(browser)
  const page = await context.newPage()
  await login(page, 'demo02')
  await page.goto(`${BASE}${OWNER_DETAIL}`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-testid="own-record-actions"]')

  const s = await scan(page)
  report.ownerDetail = { ...s, shot: await shot(page, 'detail-04-owner') }

  // 本人详情必然含自己的号码（本人数据回显，C1 验收要求）——记录在报告里，不做"无号码"断言
  check('本人详情：出现编辑与关闭入口', s.editEntryCount === 1 && s.closeEntryCount === 1)
  check('本人详情：联系方式为本人原文（非占位）', s.maskedCount === 0)
  check(
    '本人详情：未达 MATCHED 时「标记完成」不可点，给出说明',
    (await page.locator('[data-testid="complete-hint"]').count()) === 1
  )

  // 本人列表
  await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-testid="own-record-actions"]')
  const listScan = await scan(page)
  report.ownerList = { ...listScan, shot: await shot(page, 'list-03-owner') }
  check('本人列表：出现管理入口', listScan.manageEntryCount > 0)
  check('本人列表：无遮蔽占位（本人数据不遮蔽）', listScan.maskedCount === 0)

  // 编辑流程：预填 → 保存 → 回详情
  await page.goto(`${BASE}${OWNER_DETAIL}`, { waitUntil: 'domcontentloaded' })
  await page.click('[data-testid="action-edit"]')
  await page.waitForURL(`${BASE}${OWNER_DETAIL}/edit`)
  await page.waitForSelector('#field-title')
  const prefilledTitle = await page.inputValue('#field-title')
  report.ownerEdit = {
    url: page.url(),
    prefilledTitle,
    shot: await shot(page, 'detail-05-owner-edit-form')
  }
  check('本人详情点「编辑」→ 进入编辑页且表单已预填', prefilledTitle.length > 0)

  const editedTitle = `${prefilledTitle}（已核对）`
  await page.fill('#field-title', editedTitle)
  await Promise.all([
    page.waitForURL(`${BASE}${OWNER_DETAIL}?updated=1`, { timeout: 10_000 }),
    page.click('button[type=submit]')
  ])
  await page.waitForSelector('text=修改已保存')
  const savedTitle = await page.locator('.record-detail__title').innerText()
  report.ownerEdit.savedTitle = savedTitle
  report.ownerEdit.savedShot = await shot(page, 'detail-06-owner-edit-saved')
  check('编辑保存后回详情且标题已更新', savedTitle.trim() === editedTitle)

  // 关闭流程：关闭 → 确认 → 我的启事提示
  await page.click('[data-testid="action-close"]')
  await page.click('[data-testid="action-close-confirm"]')
  await page.waitForURL(/\/my\?updated=closed/, { timeout: 10_000 })
  await page.waitForSelector('[data-testid="my-records-notice"]')
  const notice = await page.locator('[data-testid="my-records-notice"]').innerText()
  report.ownerClose = {
    url: page.url(),
    notice,
    shot: await shot(page, 'detail-07-owner-closed-my')
  }
  check('本人详情点「关闭」→ 进入关闭流程并落到我的启事', page.url().includes('/my?updated=closed'))
  check('关闭后我的启事给出一次性结果提示', notice.includes('已关闭'))

  // 关闭后：公共列表 / 匿名详情都不再可见（CLOSED 任何身份 404）
  const api = await page.request.get(`${BASE}/api/records/16`)
  const body = await api.json()
  report.ownerClose.afterCloseApi = { status: api.status(), code: body.code, message: body.message }
  check('关闭后详情接口 404（code 20001）', api.status() === 404 && body.code === 20001)

  // 列表卡片上的关闭入口：卡片是「标题链接拉伸覆盖整卡」，操作区必须压在链接之上，
  // 否则点「关闭」会被链接截走，看起来像点了没反应。用同一张列表页真实点一遍。
  await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' })
  const card = page.locator('.record-card').filter({ hasText: '一串宿舍钥匙丢了' }).first()
  await card.waitFor()
  await card.locator('[data-testid="action-close"]').click()
  await card.locator('[data-testid="action-close-confirm"]').click()
  await page.waitForSelector('[data-testid="list-closed-notice"]', { timeout: 10_000 })
  const listNotice = await page.locator('[data-testid="list-closed-notice"]').innerText()
  const remaining = await page
    .locator('.record-card')
    .filter({ hasText: '一串宿舍钥匙丢了' })
    .count()
  report.listClose = {
    url: page.url(),
    notice: listNotice,
    remainingCards: remaining,
    shot: await shot(page, 'list-04-owner-closed')
  }
  check('本人列表点「关闭」→ 卡片即时移除并给出提示', remaining === 0 && listNotice.includes('已关闭'))

  await context.close()
}

await browser.close()

report.summary = { total: report.cases.length, failed: failures }
await writeFile(resolve(SHOTS, 'dom-scan.json'), JSON.stringify(report, null, 2), 'utf8')

const lines = [
  `# C2 DOM 扫描输出（${report.generatedAt}）`,
  `BASE=${BASE}`,
  `后端=${report.backend}`,
  `手机号正则=${report.phonePattern}`,
  '',
  ...report.cases.map((c) => `${c.ok ? 'PASS' : 'FAIL'}\t${c.label}${c.detail ? `\t${c.detail}` : ''}`),
  '',
  `合计 ${report.summary.total} 项，失败 ${report.summary.failed} 项`
]
await writeFile(resolve(SHOTS, 'dom-scan.txt'), lines.join('\n'), 'utf8')

console.log(`\n${failures === 0 ? 'ALL PASS' : `${failures} FAILED`} (${report.cases.length} checks)`)
process.exit(failures === 0 ? 0 : 1)
