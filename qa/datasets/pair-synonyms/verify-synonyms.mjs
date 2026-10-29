// 新默认值下的同义/陷阱可见性验证（ZHYYKFX-84 追加段）。
//
//   node qa/datasets/pair-synonyms/verify-synonyms.mjs local    # 18088，无 key
//   node qa/datasets/pair-synonyms/verify-synonyms.mjs deepseek # 18089，进程内注入 key
//
// 前置：空库 + 后端用 application.yml 的默认值启动（**不加任何 --findit.pair.* 覆盖**）。
// 断言（默认值：LOCAL 0.45/0.90/0.90/0.90，DEEPSEEK 0.80/0.95/0.90/0.95）：
//   local    : 原文对原文 1.00 → DIRECT 且 available=true；同义 0.64 → AUTHORIZABLE、available=false；
//              陷阱 0.85 → AUTHORIZABLE（不是 DIRECT）、available=false；同义 0.41 / 陷阱 0.33 → 不出卡片，只计数。
//   deepseek : 同义 0.98 → DIRECT、available=true；陷阱 0.72/0.10 → 不出卡片，只计数。

import { readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const BASE = (process.env.FINDIT_API_BASE ?? 'http://localhost:18080').replace(/\/+$/, '');
const LABEL = process.argv[2] ?? 'local';
const PASSWORD = 'Demo@123456';
const TIMEOUT_MS = 60_000;

const CASES = {
  local: [
    { id: 'S1-OO', expect: { visible: true, tier: 'DIRECT', available: true } },
    { id: 'S2-OC', expect: { visible: true, tier: 'AUTHORIZABLE', available: false } },
    { id: 'S1-OF', expect: { visible: false } },
    { id: 'T5', expect: { visible: true, tier: 'AUTHORIZABLE', available: false } },
    { id: 'T2', expect: { visible: false } },
  ],
  deepseek: [
    { id: 'S1-OC', expect: { visible: true, tier: 'DIRECT', available: true } },
    { id: 'T1', expect: { visible: false } },
    { id: 'T6', expect: { visible: false } },
  ],
};

async function request(method, path, { body, cookie } = {}) {
  const headers = { Accept: 'application/json' };
  if (cookie) headers.Cookie = cookie;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* 保留原文 */
  }
  return { status: res.status, code: json?.code ?? null, body: json, raw: text,
    setCookies: res.headers.getSetCookie?.() ?? [] };
}

function cookieOf(res) {
  for (const raw of res.setCookies ?? []) {
    const first = raw.split(';')[0].trim();
    if (first.startsWith('JSESSIONID=')) return first;
  }
  throw new Error(`登录响应没有 JSESSIONID：HTTP ${res.status}`);
}

async function login(studentNo) {
  const res = await request('POST', '/api/auth/login', { body: { studentNo, password: PASSWORD } });
  if (res.status !== 200 || res.code !== 0) throw new Error(`登录 ${studentNo} 失败：HTTP ${res.status}`);
  return cookieOf(res);
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function publish(cookie, payload) {
  const res = await request('POST', '/api/records', { cookie, body: payload });
  if (res.status !== 200 || res.code !== 0) {
    throw new Error(`发布「${payload.title}」失败：HTTP ${res.status} / ${res.raw.slice(0, 200)}`);
  }
  return res.body.data.id;
}

async function close(cookie, id) {
  const res = await request('PATCH', `/api/records/${id}/status`, { cookie, body: { status: 'CLOSED' } });
  return res.status;
}

function buildCase(dataset, id) {
  const trap = dataset.traps.find((t) => t.id === id);
  if (trap) {
    return {
      id,
      kind: 'trap',
      expect: 'NO_MATCH',
      lost: { ...trap.lost, categoryId: trap.categoryId },
      found: { ...trap.found, categoryId: trap.categoryId },
    };
  }
  const [itemId, combo] = id.split('-');
  const item = dataset.items.find((i) => i.itemId === itemId);
  if (!item || !combo) throw new Error(`样本里没有 ${id}`);
  const [left, right] = combo.split('');
  const withCat = (p) => ({ ...p, categoryId: item.categoryId });
  return { id, kind: combo, expect: 'MATCH', lost: withCat(item.phrasings[left]), found: withCat(item.phrasings[right]) };
}

async function runCase(dataset, spec, lostCookie, foundCookie) {
  const sample = buildCase(dataset, spec.id);
  const make = (type, phrasing, contact, publisherName) => ({
    type,
    title: phrasing.title,
    description: phrasing.description,
    categoryId: phrasing.categoryId,
    location: phrasing.location,
    occurredAt: '2026-10-25 15:20:00',
    contact,
    publisherName,
  });
  const lostId = await publish(lostCookie, make('LOST', sample.lost, '13800000002', '演示乙'));
  const foundId = await publish(foundCookie, make('FOUND', sample.found, '13800000003', '演示丙'));

  const startedAt = Date.now();
  let detail = null;
  let card = null;
  while (Date.now() - startedAt < TIMEOUT_MS) {
    const res = await request('GET', `/api/records/${lostId}`, { cookie: lostCookie });
    if (res.status !== 200 || res.code !== 0) throw new Error(`读详情失败：HTTP ${res.status}`);
    detail = res.body.data;
    card = (detail.pairs ?? []).find((p) => p.counterpart?.recordId === foundId) ?? null;
    if (card) break;
    if (detail.pairState === 'READY' || detail.pairState === 'DEGRADED') {
      if ((detail.lowConfidenceCount ?? 0) > 0) break;
    }
    await sleep(400);
  }

  let contact = null;
  if (card) {
    const res = await request('GET', `/api/pairs/${card.pairId}/contact`, { cookie: lostCookie });
    contact = { http: res.status, code: res.code, available: res.body?.data?.available ?? null, contactPresent: Boolean(res.body?.data?.contact), reason: res.body?.data?.reason ?? null, message: res.body?.data?.message ?? null };
  }

  const result = {
    id: spec.id,
    kind: sample.kind,
    expect: sample.expect,
    lostId,
    foundId,
    pairState: detail?.pairState ?? null,
    lowConfidenceCount: detail?.lowConfidenceCount ?? null,
    waitMs: Date.now() - startedAt,
    card: card && {
      pairId: card.pairId,
      score: card.score,
      scorePercent: card.scorePercent,
      engine: card.engine,
      tier: card.tier,
      degraded: card.degraded,
      contactAvailable: card.contactAvailable,
    },
    contact,
    closeHttp: [await close(foundCookie, foundId), await close(lostCookie, lostId)],
    checks: {
      visible: spec.expect.visible === Boolean(card),
      tier: spec.expect.tier === undefined || spec.expect.tier === card?.tier,
      available: spec.expect.available === undefined || spec.expect.available === contact?.available,
    },
  };
  result.passed = Object.values(result.checks).every(Boolean);
  return result;
}

async function main() {
  const dataset = JSON.parse(await readFile(join(here, 'variants.json'), 'utf8'));
  const specs = CASES[LABEL];
  if (!specs) throw new Error(`未知 label：${LABEL}（可选 local / deepseek）`);
  const lostCookie = await login('demo02');
  const foundCookie = await login('demo03');

  const cases = [];
  for (const spec of specs) {
    cases.push(await runCase(dataset, spec, lostCookie, foundCookie));
  }
  const evidence = {
    label: LABEL,
    apiBase: BASE,
    runAt: new Date().toISOString(),
    config: 'application.yml 默认值（DEEPSEEK 0.95/0.80/0.90/0.95；LOCAL 覆盖 0.90/0.45/0.90/0.90）',
    cases,
    passed: cases.every((c) => c.passed),
  };
  const out = join(here, 'results', `verify-syn-${LABEL}.json`);
  await writeFile(out, `${JSON.stringify(evidence, null, 2)}\n`, 'utf8');
  for (const c of cases) {
    console.log(`[${c.id}] card=${c.card ? `${c.card.score}/${c.card.tier}` : 'hidden'} low=${c.lowConfidenceCount} contact=${c.contact?.http ?? '-'} checks=${JSON.stringify(c.checks)}`);
  }
  console.log(`passed=${evidence.passed} → ${out}`);
  if (!evidence.passed) process.exitCode = 1;
}

main().catch((e) => {
  console.error(e.message);
  process.exit(1);
});
