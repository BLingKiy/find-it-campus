// M7 独立验收 · 面 D 阈值与卡片可见（端测测独立脚本，非交付方 verify.mjs）
// 用 qa/datasets/pair-calibration/pairs.json 原样样本，不改标注集。
// 用法：node faced.mjs <apiBase> <label:local|deepseek>
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';

const BASE = (process.argv[2] ?? 'http://127.0.0.1:18085').replace(/\/+$/, '');
const LABEL = process.argv[3] ?? 'local';
const DATASET = 'C:/Project/hifox-workspaces/duancece/find-it-campus/qa/datasets/pair-calibration/pairs.json';
const PASSWORD = 'Demo@123456';

const raw = await readFile(DATASET);
console.log(`dataset sha256 = ${createHash('sha256').update(raw).digest('hex').toUpperCase()}`);
const dataset = JSON.parse(raw.toString('utf8'));

async function req(method, path, { body, cookie } = {}) {
  const headers = { Accept: 'application/json' };
  if (cookie) headers.Cookie = cookie;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* raw */ }
  return { status: res.status, code: json?.code ?? null, body: json, raw: text, setCookies: res.headers.getSetCookie?.() ?? [] };
}

async function login(studentNo) {
  const r = await req('POST', '/api/auth/login', { body: { studentNo, password: PASSWORD } });
  if (r.status !== 200) throw new Error(`login ${studentNo} HTTP ${r.status} ${r.raw.slice(0, 120)}`);
  const c = r.setCookies.map((s) => s.split(';')[0]).find((s) => s.startsWith('JSESSIONID='));
  if (!c) throw new Error('no JSESSIONID');
  return c;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitSettled(cookie, id) {
  const t0 = Date.now();
  let last = null;
  while (Date.now() - t0 < 60000) {
    const r = await req('GET', `/api/records/${id}`, { cookie });
    last = r.body?.data;
    if (last && (last.pairState === 'READY' || last.pairState === 'DEGRADED')) return last;
    await sleep(400);
  }
  return last;
}

async function waitCard(cookie, lostId, foundId, expectCard) {
  const t0 = Date.now();
  let last = null;
  while (Date.now() - t0 < 60000) {
    const r = await req('GET', `/api/records/${lostId}`, { cookie });
    last = r.body?.data;
    const card = (last?.pairs ?? []).find((p) => p.counterpart?.recordId === foundId) ?? null;
    if (expectCard && card) return { detail: last, card };
    if (!expectCard && (last?.lowConfidenceCount ?? 0) > 0 && !card) return { detail: last, card: null };
    await sleep(400);
  }
  const card = (last?.pairs ?? []).find((p) => p.counterpart?.recordId === foundId) ?? null;
  return { detail: last, card };
}

const lostCookie = await login('demo02');
const foundCookie = await login('demo03');
const out = { apiBase: BASE, label: LABEL, runAt: new Date().toISOString(), pairs: [] };

for (const [id, expectCard] of [['P01', true], ['P13', false]]) {
  const s = dataset.pairs.find((p) => p.id === id);
  if (!s) throw new Error('no sample ' + id);
  const l = await req('POST', '/api/records', { cookie: lostCookie, body: s.lost });
  const f = await req('POST', '/api/records', { cookie: foundCookie, body: s.found });
  if (l.status !== 200 || f.status !== 200) throw new Error(`publish ${id} failed ${l.status}/${f.status} ${l.raw.slice(0, 150)}`);
  const lostId = l.body.data.id, foundId = f.body.data.id;
  await waitSettled(lostCookie, lostId);
  await waitSettled(foundCookie, foundId);
  const w = await waitCard(lostCookie, lostId, foundId, expectCard);
  const card = w.card;
  const row = {
    sample: id, expect: s.expect, class: s.class, lostId, foundId,
    publishHttp: [l.status, f.status],
    pairState: w.detail?.pairState,
    cardVisible: Boolean(card),
    card: card && {
      score: card.score, scorePercent: card.scorePercent, engine: card.engine,
      tier: card.tier, degraded: card.degraded, contactAvailable: card.contactAvailable,
      contactHint: card.contactHint,
    },
    lowConfidenceCount: w.detail?.lowConfidenceCount,
    matchedCount: w.detail?.matchedCount,
  };
  out.pairs.push(row);
  console.log(`[${id}] score=${card?.score ?? '(无卡片)'} tier=${card?.tier ?? '-'} engine=${card?.engine ?? '-'} ` +
              `degraded=${card?.degraded ?? '-'} 卡片可见=${row.cardVisible} lowConfidenceCount=${row.lowConfidenceCount}`);
  // 关闭两端，避免污染下一对样本的 lowConfidenceCount
  await req('PATCH', `/api/records/${foundId}/status`, { cookie: foundCookie, body: { status: 'CLOSED' } });
  await req('PATCH', `/api/records/${lostId}/status`, { cookie: lostCookie, body: { status: 'CLOSED' } });
}

const p01 = out.pairs[0], p13 = out.pairs[1];
const checks = LABEL === 'local'
  ? {
      p01CardVisible: p01.cardVisible,
      p01TierAuthorizable: p01.card?.tier === 'AUTHORIZABLE',
      p01EngineLocal: p01.card?.engine === 'LOCAL_KEYWORD',
      p01ScoreAround073: typeof p01.card?.score === 'number' && p01.card.score >= 0.70 && p01.card.score <= 0.78,
      p13NoCard: !p13.cardVisible,
      p13LowCount1: p13.lowConfidenceCount === 1,
    }
  : {
      p01CardVisible: p01.cardVisible,
      p01TierDirect: p01.card?.tier === 'DIRECT',
      p01EngineDeepseek: p01.card?.engine === 'DEEPSEEK',
      p13NoCard: !p13.cardVisible,
      p13LowCount1: p13.lowConfidenceCount === 1,
    };
out.checks = checks;
out.passed = Object.values(checks).every(Boolean);
await writeFile(`C:/Project/deploy-accept/Z91-20261029-035223/ev/faced-${LABEL}.json`, JSON.stringify(out, null, 2) + '\n', 'utf8');
console.log('checks=' + JSON.stringify(checks));
console.log('passed=' + out.passed);
if (!out.passed) process.exitCode = 1;
