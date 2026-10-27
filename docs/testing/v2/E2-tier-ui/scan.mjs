// E2 三档展示与授权交互 · 浏览器证据脚本
//
// 前置：
//   1) 后端 feat/v2-E1-contact-unlock @ 17dcc9a 起在 18080（MySQL 3307）；
//   2) 已执行 docs/testing/v2/E2-tier-ui/fixture.sql（本地演示库夹具，不改仓库 seed）；
//   3) web 已 npm run build，preview 起在 5173（代理 /api → 18080）。
// 运行：node docs/testing/v2/E2-tier-ui/scan.mjs
//
// 说明（必须如实写进证据）：E1 的详情接口目前不返回 PairCard[]（契约 §4.2 要求返回，
// 后端只给了计数）。本脚本只在网络层给 /api/records/{1,30} 的 **详情响应** 补上 pairs[]，
// 让 E2 的 UI 能被真实浏览器驱动；contact / contact-unlock / PATCH pair 全部走真实后端，
// 号码、授权、撤回、状态机都不是假数据。等 E1 补上 pairs[] 后，删掉注入逻辑即可复跑。
import { createRequire } from 'node:module'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const qaPackage = process.env.QA_PACKAGE ?? resolve(here, '../../../../qa/package.json')
const { chromium } = createRequire(qaPackage)('playwright')

const SHOTS = here
const BASE = process.env.WEB_BASE ?? 'http://127.0.0.1:5173'
const PASSWORD = 'Demo@123456'
const PHONE_RE = /1[3-9]\d{9}/g

const state = {
  /** demo02 是否已在 pair 15 的 FOUND 端公开联系方式（真实 POST 成功后更新） */
  published: false,
  note: null
}

const report = {
  generatedAt: new Date().toISOString(),
  base: BASE,
  backend: 'feat/v2-E1-contact-unlock @ 17dcc9a (http://127.0.0.1:18080)',
  detailPairsInjected: true,
  phonePattern: PHONE_RE.source,
  cases: []
}

let failures = 0

function check(label, ok, detail = '') {
  if (!ok) failures += 1
  report.cases.push({ label, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${label}${detail ? ` — ${detail}` : ''}`)
}

async function shot(page, name, fullPage = true) {
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.waitForTimeout(120)
  await page.screenshot({ path: resolve(SHOTS, `${name}.png`), fullPage })
  return `${name}.png`
}

async function shotElement(page, selector, name) {
  const locator = page.locator(selector).first()
  await locator.scrollIntoViewIfNeeded()
  await page.waitForTimeout(120)
  await locator.screenshot({ path: resolve(SHOTS, `${name}.png`) })
  return `${name}.png`
}

function phoneHits(text) {
  return text.match(PHONE_RE) ?? []
}

/** 全局正则的 lastIndex 会被 test() 推进，判定单值要用独立非全局正则 */
function isPhone(value) {
  return /^1[3-9]\d{9}$/.test(value.trim())
}

function overlaps(a, b) {
  if (!a || !b) return false
  return !(a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height <= b.y || b.y + b.height <= a.y)
}

function pairOne() {
  return {
    pairId: 1,
    score: 0.92,
    scorePercent: 92,
    tier: 'DIRECT',
    status: 'NEW',
    matched: false,
    myConfirmed: false,
    otherConfirmed: false,
    lostConfirmedAt: null,
    foundConfirmedAt: null,
    engine: 'DEEPSEEK',
    degraded: false,
    counterpart: {
      recordId: 13,
      type: 'FOUND',
      title: '图书馆拾到黑色钱包',
      location: '图书馆三楼自习区',
      occurredAt: '2026-10-20 15:00:00',
      createdAt: '2026-10-20 15:30:00',
      status: 'PENDING'
    },
    mySide: 'LOST',
    myUnlocked: false,
    otherUnlocked: false,
    myNote: null,
    otherNote: null,
    contactAvailable: true,
    contactHint: '匹配度较高，可直接查看'
  }
}

function pairFifteenForLost() {
  return {
    pairId: 15,
    score: 0.8,
    scorePercent: 80,
    tier: 'AUTHORIZABLE',
    status: 'NEW',
    matched: false,
    myConfirmed: false,
    otherConfirmed: false,
    lostConfirmedAt: null,
    foundConfirmedAt: null,
    engine: 'LOCAL_KEYWORD',
    degraded: true,
    counterpart: {
      recordId: 30,
      type: 'FOUND',
      title: '图书馆拾到黑色钱包（E2 夹具）',
      location: '图书馆三楼自习区',
      occurredAt: '2026-10-19 18:00:00',
      createdAt: '2026-10-19 18:10:00',
      status: 'PENDING'
    },
    mySide: 'LOST',
    myUnlocked: false,
    otherUnlocked: state.published,
    myNote: null,
    otherNote: state.published ? state.note : null,
    contactAvailable: state.published,
    contactHint: state.published ? '对方已公开联系方式' : '对方尚未公开'
  }
}

function pairFifteenForFound() {
  return {
    pairId: 15,
    score: 0.8,
    scorePercent: 80,
    tier: 'AUTHORIZABLE',
    status: 'NEW',
    matched: false,
    myConfirmed: false,
    otherConfirmed: false,
    lostConfirmedAt: null,
    foundConfirmedAt: null,
    engine: 'LOCAL_KEYWORD',
    degraded: true,
    counterpart: {
      recordId: 1,
      type: 'LOST',
      title: '图书馆丢了黑色钱包',
      location: '图书馆三楼自习区',
      occurredAt: '2026-10-19 17:30:00',
      createdAt: '2026-10-20 09:10:00',
      status: 'PENDING'
    },
    mySide: 'FOUND',
    myUnlocked: state.published,
    otherUnlocked: false,
    myNote: state.published ? state.note : null,
    otherNote: null,
    contactAvailable: false,
    contactHint: '对方尚未公开'
  }
}

/**
 * 只改详情响应里的 pairs[] / lowConfidenceCount；其余字段（masking、isMine、counts）
 * 全部保留真实后端返回值。仅对记录 1（demo01）与 30（demo02）注入。
 */
async function installPairInjection(page) {
  await page.route(/\/api\/records\/(1|30)(\?|$)/, async (route) => {
    const url = new URL(route.request().url())
    const match = url.pathname.match(/\/records\/(\d+)$/)
    const id = match ? Number(match[1]) : 0
    if (id !== 1 && id !== 30) {
      await route.continue()
      return
    }
    const response = await route.fetch()
    const body = await response.json()
    if (!body?.data?.isMine) {
      await route.fulfill({ response, json: body })
      return
    }
    if (id === 1) {
      body.data.pairs = [pairOne(), pairFifteenForLost()]
      body.data.lowConfidenceCount = 1
      body.data.pairState = 'READY'
      body.data.hasMatchedPair = false
      body.data.matchedPairCount = 0
      body.data.allowedStatuses = ['CLOSED']
    } else {
      body.data.pairs = [pairFifteenForFound()]
      body.data.lowConfidenceCount = 0
      body.data.pairState = 'READY'
      body.data.hasMatchedPair = false
      body.data.matchedPairCount = 0
      body.data.allowedStatuses = ['CLOSED']
    }
    await route.fulfill({ response, json: body })
  })
}

async function newContext(browser, viewport = { width: 1280, height: 1000 }) {
  return browser.newContext({ viewport, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' })
}

async function login(page, studentNo) {
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
  await page.fill('#field-studentNo', studentNo)
  await page.fill('#field-password', PASSWORD)
  await Promise.all([
    page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 15_000 }),
    page.click('button[type=submit]')
  ])
  await page.waitForSelector('[data-testid="header-student-no"]')
}

async function pairStatus(page, pairId) {
  return page.locator(`[data-pair-id="${pairId}"] [data-testid="pair-status"]`).innerText()
}

await mkdir(SHOTS, { recursive: true })
const browser = await chromium.launch({ channel: 'msedge', headless: true })

// ---------------------------------------------------------------- 1. 三档展示（demo01，1280）
let demo01Context
{
  demo01Context = await newContext(browser)
  const page = await demo01Context.newPage()
  await installPairInjection(page)
  await login(page, 'demo01')
  await page.goto(`${BASE}/records/1`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-pair-id="1"]')
  await page.waitForSelector('[data-pair-id="15"]')

  const pairOneCard = page.locator('[data-pair-id="1"]')
  const pairFifteenCard = page.locator('[data-pair-id="15"]')
  check('0.92 卡片：分值 92%', (await pairOneCard.locator('[data-testid="pair-score"]').innerText()) === '92%')
  check(
    '0.92 卡片：直看档，联系方式按钮可用',
    await pairOneCard.locator('[data-testid="pair-contact-button"]').isEnabled()
  )
  check('0.80 卡片：显示「可能相关」', (await pairFifteenCard.locator('[data-testid="pair-tier"]').innerText()).includes('可能相关'))
  check(
    '0.80 卡片：对方未公开时按钮禁用',
    await pairFifteenCard.locator('[data-testid="pair-contact-button"]').isDisabled()
  )
  check(
    '0.80 卡片：禁用原因写给用户',
    (await pairFifteenCard.locator('[data-testid="pair-contact-hint"]').innerText()).includes('对方尚未公开')
  )
  check(
    '0.80 卡片：有公开 / 撤回开关与附言输入',
    (await pairFifteenCard.locator('[data-testid="pair-publish-switch"]').count()) === 1 &&
      (await pairFifteenCard.locator('[data-testid="pair-note"]').count()) === 1
  )
  check(
    '<0.75：只显示「存在 1 条可能相关」',
    (await page.locator('[data-testid="pair-low-count"]').innerText()).includes('存在 1 条可能相关')
  )
  check(
    '页面不出现「已解决」字样',
    !(await page.locator('body').innerText()).includes('已解决')
  )

  report.directTier = {
    shot: await shot(page, 'tier-01-direct-092'),
    cardShot: await shotElement(page, '[data-pair-id="1"]', 'tier-01-direct-092-card')
  }

  // 直看档：点按钮拿到号码（真实 GET /api/pairs/1/contact）
  await pairOneCard.locator('[data-testid="pair-contact-button"]').click()
  await page.waitForSelector('[data-pair-id="1"] [data-testid="pair-contact-number"]')
  const directNumber = await pairOneCard.locator('[data-testid="pair-contact-number"]').innerText()
  check('0.92 直看：拿到对方号码', isPhone(directNumber), directNumber)
  report.directTier.contact = directNumber
  report.directTier.contactShot = await shot(page, 'tier-01-direct-092-contact')

  // 0.80 未授权：按钮禁用 + 说明（证据图）
  report.authorizableBefore = {
    shot: await shotElement(page, '[data-pair-id="15"]', 'auth-01-before-publish')
  }

  // 多 pair 独立：只把 0.92 这条标为已联系，0.80 那条必须还是待联系
  await pairOneCard.locator('[data-testid="pair-action-contact"]').click()
  await page.waitForFunction(
    () =>
      document.querySelector('[data-pair-id="1"] [data-testid="pair-status"]')?.textContent?.trim() ===
      '已联系'
  )
  const pairOneStatusAfter = await pairStatus(page, 1)
  const pairFifteenStatusAfter = await pairStatus(page, 15)
  check('多 pair：标记一条后该条变「已联系」', pairOneStatusAfter === '已联系', pairOneStatusAfter)
  check('多 pair：另一条状态不变', pairFifteenStatusAfter === '待联系', pairFifteenStatusAfter)
  check(
    '多 pair：另一条仍可操作',
    await pairFifteenCard.locator('[data-testid="pair-action-contact"]').isEnabled()
  )
  check(
    '多 pair：页面仍无「已解决」',
    !(await page.locator('body').innerText()).includes('已解决')
  )
  report.multiPair = {
    pairOneStatus: pairOneStatusAfter,
    pairFifteenStatus: pairFifteenStatusAfter,
    shot: await shot(page, 'multi-01-independent-pairs')
  }

  // <0.75 独立样本：记录 #8 本人、联系为空、只有一条 0.60 低分 pair
  await page.goto(`${BASE}/records/8`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-testid="pair-low-count"]')
  const lowDesktopText = await page.locator('[data-testid="pair-low-count"]').innerText()
  const lowDesktopHits = phoneHits(await page.locator('body').innerText())
  check('桌面 0.60：只显示条数', lowDesktopText.includes('存在 1 条可能相关'), lowDesktopText)
  check('桌面 0.60：页面无号码片段', lowDesktopHits.length === 0, lowDesktopHits.join(','))
  report.lowTier = {
    text: lowDesktopText,
    phoneHits: lowDesktopHits,
    shot: await shot(page, 'tier-03-low-060')
  }
}

// ---------------------------------------------------------------- 2. demo02 公开 / 撤回（真实后端）
let demo02Context
{
  demo02Context = await newContext(browser)
  const page = await demo02Context.newPage()
  await installPairInjection(page)
  await login(page, 'demo02')
  await page.goto(`${BASE}/records/30`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-pair-id="15"]')
  const card = page.locator('[data-pair-id="15"]')
  check('0.80 端内：初始为未公开', (await card.locator('[data-testid="pair-publish-switch"]').getAttribute('aria-checked')) === 'false')

  await card.locator('[data-testid="pair-note"]').fill('周六下午三点图书馆三楼见，看到请联系我')
  const publishResponse = page.waitForResponse(
    (res) => res.url().includes('/api/pairs/15/contact-unlock') && res.request().method() === 'POST'
  )
  await card.locator('[data-testid="pair-publish-switch"]').click()
  const published = await publishResponse
  const publishedBody = await published.json()
  check('PUBLISH 成功', published.status() === 200 && publishedBody.code === 0, JSON.stringify(publishedBody))
  state.published = publishedBody?.data?.public === true
  state.note = publishedBody?.data?.note ?? null
  check('PUBLISH 后开关为已公开', (await card.locator('[data-testid="pair-publish-switch"]').getAttribute('aria-checked')) === 'true')
  check('PUBLISH 后本端附言已打码返回', typeof state.note === 'string' && state.note.length > 0)
  report.publish = {
    response: publishedBody,
    shot: await shotElement(page, '[data-pair-id="15"]', 'auth-02-publisher-published')
  }
}

// ---------------------------------------------------------------- 3. 对方公开后：按钮可用 + 拿到号码
{
  const page = await demo01Context.newPage()
  await installPairInjection(page)
  await page.goto(`${BASE}/records/1`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-pair-id="15"]')
  const card = page.locator('[data-pair-id="15"]')
  check(
    '对方公开后：0.80 按钮可用',
    await card.locator('[data-testid="pair-contact-button"]').isEnabled()
  )
  check(
    '对方公开后：提示改为已公开',
    (await card.locator('[data-testid="pair-contact-hint"]').innerText()).includes('对方已公开')
  )
  await card.locator('[data-testid="pair-contact-button"]').click()
  await page.waitForSelector('[data-pair-id="15"] [data-testid="pair-contact-number"]')
  const number = await card.locator('[data-testid="pair-contact-number"]').innerText()
  check('对方公开后：拿到真实号码', isPhone(number), number)
  const note = await card.locator('[data-testid="pair-contact-note"]').innerText()
  check('对方公开后：能看到对方附言', note.includes('周六下午三点'), note)
  report.afterPublish = {
    contact: number,
    note,
    shot: await shot(page, 'auth-03-after-publish'),
    cardShot: await shotElement(page, '[data-pair-id="15"]', 'auth-03-after-publish-card')
  }
  await page.close()
}

// ---------------------------------------------------------------- 4. 320px 三档（0.92 / 0.80 已授权 / 0.60）
{
  const page = await demo01Context.newPage()
  await installPairInjection(page)
  await page.setViewportSize({ width: 320, height: 900 })
  await page.goto(`${BASE}/records/1`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-pair-id="1"]')
  await page.waitForSelector('[data-pair-id="15"]')

  const overflow = await page.evaluate(() => ({
    viewport: window.innerWidth,
    scrollWidth: document.documentElement.scrollWidth,
    bodyScrollWidth: document.body.scrollWidth
  }))
  check(
    '320 档：无横向滚动',
    overflow.scrollWidth <= overflow.viewport && overflow.bodyScrollWidth <= overflow.viewport,
    JSON.stringify(overflow)
  )

  const pairOneCard = page.locator('[data-pair-id="1"]')
  await pairOneCard.locator('[data-testid="pair-contact-button"]').click()
  await page.waitForSelector('[data-pair-id="1"] [data-testid="pair-contact-number"]')
  const directBox = await pairOneCard.locator('[data-testid="pair-contact-button"]').boundingBox()
  const numberBox = await pairOneCard.locator('[data-testid="pair-contact-number"]').boundingBox()
  check('320 档 0.92：号码与按钮不重叠', !overlaps(directBox, numberBox))
  const directShot = await shot(page, 'tier-320-092')
  report.mobile = { directShot }

  const pairFifteenCard = page.locator('[data-pair-id="15"]')
  const hintBox = await pairFifteenCard.locator('[data-testid="pair-contact-hint"]').boundingBox()
  const buttonBox = await pairFifteenCard.locator('[data-testid="pair-contact-button"]').boundingBox()
  check('320 档 0.80：按钮与说明不重叠', !overlaps(buttonBox, hintBox))
  await pairFifteenCard.locator('[data-testid="pair-contact-button"]').click()
  await page.waitForSelector('[data-pair-id="15"] [data-testid="pair-contact-number"]')
  const pairFifteenButton = await pairFifteenCard.locator('[data-testid="pair-contact-button"]').boundingBox()
  const pairFifteenNumber = await pairFifteenCard.locator('[data-testid="pair-contact-number"]').boundingBox()
  check('320 档 0.80：号码与按钮不重叠', !overlaps(pairFifteenButton, pairFifteenNumber))
  const authorizableShot = await shot(page, 'tier-320-080')
  report.mobile.authorizableShot = authorizableShot

  await page.goto(`${BASE}/records/8`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-testid="pair-low-count"]')
  const lowText = await page.locator('[data-testid="pair-low-count"]').innerText()
  const lowOverflow = await page.evaluate(() => ({
    viewport: window.innerWidth,
    scrollWidth: document.documentElement.scrollWidth,
    bodyScrollWidth: document.body.scrollWidth
  }))
  const lowWrap = await page.locator('[data-testid="pair-low-count"]').evaluate((el) => {
    const title = el.querySelector('.pair-panel__low-title')
    const hint = el.querySelector('.hint')
    const read = (node) => {
      if (!node) return null
      const style = getComputedStyle(node)
      return {
        scrollWidth: node.scrollWidth,
        clientWidth: node.clientWidth,
        whiteSpace: style.whiteSpace,
        textOverflow: style.textOverflow
      }
    }
    return { title: read(title), hint: read(hint) }
  })
  const bodyText = await page.locator('body').innerText()
  const bodyHtml = await page.evaluate(() => document.documentElement.outerHTML)
  const hitsText = phoneHits(bodyText)
  const hitsHtml = phoneHits(bodyHtml)
  check('320 档 0.60：无横向滚动', lowOverflow.scrollWidth <= lowOverflow.viewport && lowOverflow.bodyScrollWidth <= lowOverflow.viewport, JSON.stringify(lowOverflow))
  check('320 档 0.60：条数文案完整', lowText.includes('存在 1 条可能相关'), lowText)
  check(
    '320 档 0.60：条数与说明换行不截断',
    lowWrap.title?.whiteSpace !== 'nowrap' &&
      lowWrap.title?.textOverflow !== 'ellipsis' &&
      lowWrap.title?.scrollWidth <= lowWrap.title?.clientWidth + 1 &&
      lowWrap.hint?.whiteSpace !== 'nowrap' &&
      lowWrap.hint?.textOverflow !== 'ellipsis' &&
      lowWrap.hint?.scrollWidth <= lowWrap.hint?.clientWidth + 1,
    JSON.stringify(lowWrap)
  )
  check('0.60 页面：正则 1[3-9]\\d{9} 零命中（DOM）', hitsText.length === 0 && hitsHtml.length === 0, `text=${hitsText.join(',')} html=${hitsHtml.join(',')}`)
  report.mobile.lowShot = await shot(page, 'tier-320-060')
  report.mobile.lowPhoneHits = { text: hitsText, html: hitsHtml }
  await page.close()
}

// ---------------------------------------------------------------- 5. 撤回后回到不可用
{
  const page = await demo02Context.newPage()
  await installPairInjection(page)
  await page.goto(`${BASE}/records/30`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-pair-id="15"]')
  const card = page.locator('[data-pair-id="15"]')
  check('撤回前：开关仍为已公开', (await card.locator('[data-testid="pair-publish-switch"]').getAttribute('aria-checked')) === 'true')
  const revokeResponse = page.waitForResponse(
    (res) => res.url().includes('/api/pairs/15/contact-unlock') && res.request().method() === 'POST'
  )
  await card.locator('[data-testid="pair-publish-switch"]').click()
  const revoked = await revokeResponse
  const revokedBody = await revoked.json()
  check('REVOKE 成功', revoked.status() === 200 && revokedBody.code === 0, JSON.stringify(revokedBody))
  state.published = revokedBody?.data?.public === true
  state.note = null
  check('撤回后：开关为未公开', (await card.locator('[data-testid="pair-publish-switch"]').getAttribute('aria-checked')) === 'false')
  check('撤回后：附言清空（输入框为空）', (await card.locator('[data-testid="pair-note"]').inputValue()) === '')
  report.revoke = {
    response: revokedBody,
    shot: await shotElement(page, '[data-pair-id="15"]', 'auth-04-publisher-revoked')
  }
  await page.close()
}

{
  const page = await demo01Context.newPage()
  await installPairInjection(page)
  await page.goto(`${BASE}/records/1`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-pair-id="15"]')
  const card = page.locator('[data-pair-id="15"]')
  check('撤回后：0.80 按钮回到禁用', await card.locator('[data-testid="pair-contact-button"]').isDisabled())
  check(
    '撤回后：提示回到「对方尚未公开」',
    (await card.locator('[data-testid="pair-contact-hint"]').innerText()).includes('对方尚未公开')
  )
  const text = await page.locator('[data-pair-id="15"]').innerText()
  check('撤回后：卡片不残留号码', phoneHits(text).length === 0, phoneHits(text).join(','))
  report.afterRevoke = {
    shot: await shot(page, 'auth-05-after-revoke'),
    cardShot: await shotElement(page, '[data-pair-id="15"]', 'auth-05-after-revoke-card')
  }
  await page.close()
}

// ---------------------------------------------------------------- 6. 降级态（LOCAL_KEYWORD）
{
  const page = await demo01Context.newPage()
  await installPairInjection(page)
  await page.goto(`${BASE}/records/1`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('[data-pair-id="15"]')
  const banner = await page.locator('[data-testid="pair-degraded-banner"]').innerText()
  const badge = await page.locator('[data-pair-id="15"] [data-testid="pair-degraded"]').innerText()
  check('降级：卡片标注「已降级」', badge.trim() === '已降级', badge)
  check('降级：提示「智能匹配暂不可用，已用关键词匹配」', banner.includes('智能匹配暂不可用，已用关键词匹配'), banner)
  report.degraded = {
    badge,
    banner,
    shot: await shotElement(page, '[data-testid="pair-panel"]', 'degraded-01-card-and-banner')
  }
  await page.close()
}

await demo01Context.close()
await demo02Context.close()
await browser.close()

report.failures = failures
report.passed = report.cases.filter((item) => item.ok).length
report.total = report.cases.length
await writeFile(resolve(SHOTS, 'report.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8')
console.log(`\n${report.passed}/${report.total} PASS, ${failures} FAIL`)
process.exitCode = failures === 0 ? 0 : 1
