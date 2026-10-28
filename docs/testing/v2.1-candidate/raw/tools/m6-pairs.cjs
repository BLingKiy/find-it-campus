// Publish one calibration pair and report the server-side card facts.
// Usage: node m6-pairs.cjs <mode> <sampleId> [expectTier]
//   mode=verify  -> publish LOST+FOUND and wait for the card / low-count
//   mode=degrade -> same, but waits until the owner record pairState=DEGRADED
//   mode=confirm -> confirm+unlock the pair from both sides (pairId in M6_PAIR_ID)
const fs = require('node:fs');
const path = require('node:path');

const BASE = (process.env.M6_BASE || 'http://127.0.0.1:8088').replace(/\/+$/, '');
const MODE = process.argv[2] || 'verify';
const SAMPLE = process.argv[3] || 'P01';
const EXPECT = process.argv[4] || '';
const DATASET = process.env.M6_DATASET || 'C:/Project/hifox-workspaces/panshishi/m6-work/qa/datasets/pair-calibration/pairs.json';
const PASSWORD = 'Demo@123456';
const TIMEOUT_MS = 120000;

function cookieOf(res) {
  const m = (res.setCookie || []).join(';').match(/JSESSIONID=[^;,\s]+/);
  if (!m) throw new Error(`login response has no JSESSIONID: HTTP ${res.status}`);
  return m[0];
}

async function request(method, p, { body, cookie } = {}) {
  const headers = { Accept: 'application/json' };
  if (cookie) headers.Cookie = cookie;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(`${BASE}${p}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch {}
  return { status: res.status, code: json?.code ?? null, json, raw: text,
    setCookie: res.headers.getSetCookie ? res.headers.getSetCookie() : [res.headers.get('set-cookie') || ''].filter(Boolean) };
}

async function login(studentNo) {
  const res = await request('POST', '/api/auth/login', { body: { studentNo, password: PASSWORD } });
  if (res.status !== 200 || res.code !== 0) throw new Error(`login ${studentNo}: HTTP ${res.status} ${res.raw.slice(0, 200)}`);
  return cookieOf(res);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function publish(cookie, payload) {
  const res = await request('POST', '/api/records', { cookie, body: payload });
  if (res.status !== 200 || res.code !== 0) throw new Error(`publish ${payload.title}: HTTP ${res.status} ${res.raw.slice(0, 300)}`);
  return res.json.data.id;
}

async function detail(cookie, id) {
  const res = await request('GET', `/api/records/${id}`, { cookie });
  if (res.status !== 200 || res.code !== 0) throw new Error(`detail ${id}: HTTP ${res.status} ${res.raw.slice(0, 200)}`);
  return res.json.data;
}

async function waitPairFacts(cookie, lostId, foundId, wantDegraded) {
  const started = Date.now();
  let last = null;
  while (Date.now() - started < TIMEOUT_MS) {
    last = await detail(cookie, lostId);
    const card = (last.pairs ?? []).find((p) => p.counterpart?.recordId === foundId) ?? null;
    if (card && (!wantDegraded || card.degraded)) return { detail: last, card, elapsedMs: Date.now() - started };
    if (!card && (last.lowConfidenceCount ?? 0) > 0) return { detail: last, card: null, elapsedMs: Date.now() - started };
    await sleep(500);
  }
  return { detail: last, card: ((last?.pairs) ?? []).find((p) => p.counterpart?.recordId === foundId) ?? null, elapsedMs: Date.now() - started, timeout: true };
}

async function waitSettled(cookie, id) {
  const started = Date.now();
  let last = null;
  while (Date.now() - started < TIMEOUT_MS) {
    last = await detail(cookie, id);
    if (last.pairState === 'READY' || last.pairState === 'DEGRADED') return last;
    await sleep(400);
  }
  return last;
}

(async () => {
  const dataset = JSON.parse(fs.readFileSync(DATASET, 'utf8'));
  const sample = dataset.pairs.find((p) => p.id === SAMPLE);
  if (!sample) throw new Error(`sample ${SAMPLE} not found`);

  if (MODE === 'confirm') {
    const pairId = process.env.M6_PAIR_ID;
    if (!pairId) throw new Error('M6_PAIR_ID required');
    const lostCookie = await login(process.env.M6_LOST_USER || 'demo02');
    const foundCookie = await login(process.env.M6_FOUND_USER || 'demo03');
    const out = { pairId: Number(pairId), confirm: [], unlock: [] };
    for (const [who, cookie] of [['lost', lostCookie], ['found', foundCookie]]) {
      const c = await request('PATCH', `/api/pairs/${pairId}`, { cookie, body: { action: 'CONFIRM' } });
      out.confirm.push({ who, http: c.status, code: c.code, raw: c.raw.slice(0, 300) });
      const u = await request('POST', `/api/pairs/${pairId}/contact-unlock`, { cookie, body: { note: 'm6 保留性验证 ' + who } });
      out.unlock.push({ who, http: u.status, code: u.code, raw: u.raw.slice(0, 300) });
    }
    const d = await detail(lostCookie, Number(process.env.M6_LOST_ID));
    out.pair = (d.pairs ?? []).find((p) => p.pairId === Number(pairId)) ?? null;
    console.log(JSON.stringify(out, null, 2));
    return;
  }

  const lostCookie = await login('demo02');
  const foundCookie = await login('demo03');
  const lostId = await publish(lostCookie, sample.lost);
  const foundId = await publish(foundCookie, sample.found);
  await waitSettled(lostCookie, lostId);
  await waitSettled(foundCookie, foundId);
  const waited = await waitPairFacts(lostCookie, lostId, foundId, MODE === 'degrade');
  const card = waited.card;
  const facts = {
    mode: MODE,
    sample: SAMPLE,
    class: sample.class,
    expect: sample.expect,
    expectedTier: EXPECT || null,
    lostId,
    foundId,
    publishHttp: 200,
    pairState: waited.detail?.pairState ?? null,
    lowConfidenceCount: waited.detail?.lowConfidenceCount ?? null,
    timedOut: Boolean(waited.timeout),
    waitMs: waited.elapsedMs,
    card: card && {
      pairId: card.pairId,
      score: card.score,
      scorePercent: card.scorePercent,
      engine: card.engine,
      tier: card.tier,
      degraded: card.degraded,
      contactAvailable: card.contactAvailable,
      contactHint: card.contactHint,
      status: card.status,
      matched: card.matched,
    },
    passed: null,
  };
  if (MODE === 'verify') {
    if (EXPECT === 'HIDDEN') facts.passed = card === null && (waited.detail?.lowConfidenceCount ?? 0) >= 1;
    else if (EXPECT) facts.passed = Boolean(card) && card.tier === EXPECT && (waited.detail?.lowConfidenceCount ?? 0) === 0;
    else facts.passed = Boolean(card);
  } else if (MODE === 'degrade') {
    facts.passed = Boolean(card) && card.engine === 'LOCAL_KEYWORD' && card.degraded === true;
  }
  console.log(JSON.stringify(facts, null, 2));
  if (facts.passed === false) process.exitCode = 1;
})().catch((e) => {
  console.error('PAIR_FAIL', e.message);
  process.exit(1);
});
