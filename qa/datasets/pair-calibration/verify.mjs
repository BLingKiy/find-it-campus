// 新阈值下的可见性验证（ZHYYKFX-84）：发布 → 出分 → 卡片是否可见。
//
//   node qa/datasets/pair-calibration/verify.mjs local      # 18088，无 key（LOCAL_KEYWORD）
//   node qa/datasets/pair-calibration/verify.mjs deepseek   # 18089，进程内注入 key（DEEPSEEK）
//
// 前置：空库 + 后端用 application.yml 的新默认值启动（不加任何 --findit.pair.* 覆盖）。
// 断言：P01（应匹配）出卡片且 tier=DIRECT；P13（不应匹配）不出卡片，只进 lowConfidenceCount。
// 每对发布后立刻关闭两端，保证 lowConfidenceCount 不会被别的样本污染。

import { readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const BASE = (process.env.FINDIT_API_BASE ?? 'http://localhost:18080').replace(/\/+$/, '');
const LABEL = process.argv[2] ?? 'local';
const POS_ID = process.argv[3] ?? 'P01';
const NEG_ID = process.argv[4] ?? 'P13';
const PASSWORD = 'Demo@123456';
const TIMEOUT_MS = 90_000;

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
  return { id: res.body.data.id, http: res.status };
}

async function patchClosed(cookie, id) {
  const res = await request('PATCH', `/api/records/${id}/status`, { cookie, body: { status: 'CLOSED' } });
  return { http: res.status, code: res.code };
}

/** 轮询一条记录的 owner 详情，直到 pairState 终态（READY/DEGRADED）。 */
async function waitRecordSettled(cookie, recordId) {
  const startedAt = Date.now();
  let last = null;
  while (Date.now() - startedAt < TIMEOUT_MS) {
    const res = await request('GET', `/api/records/${recordId}`, { cookie });
    if (res.status !== 200 || res.code !== 0) throw new Error(`读详情 ${recordId} 失败：HTTP ${res.status}`);
    last = res.body.data;
    if (last.pairState === 'READY' || last.pairState === 'DEGRADED') {
      return { detail: last, http: res.status, elapsedMs: Date.now() - startedAt };
    }
    await sleep(400);
  }
  return { detail: last, http: 200, elapsedMs: Date.now() - startedAt, timeout: true };
}

/**
 * 两端都打分完成后，再看 LOST 详情里的 card / lowConfidenceCount。
 *
 * <p>为什么必须等两端都 settle：LOST 先发布时它自己会立刻变成 READY（没有候选、0 条 pair），
 * 只看 LOST 的 pairState 会在 FOUND 还没打分的瞬间就返回，读到 low=0 的假阴性。
 */
async function waitPairFacts(cookie, lostId, foundId, expectCard) {
  const startedAt = Date.now();
  let last = null;
  while (Date.now() - startedAt < TIMEOUT_MS) {
    const res = await request('GET', `/api/records/${lostId}`, { cookie });
    if (res.status !== 200 || res.code !== 0) throw new Error(`读详情失败：HTTP ${res.status}`);
    last = res.body.data;
    const card = (last.pairs ?? []).find((p) => p.counterpart?.recordId === foundId) ?? null;
    if (expectCard && card) return { detail: last, card, http: res.status, elapsedMs: Date.now() - startedAt };
    if (!expectCard && (last.lowConfidenceCount ?? 0) > 0) {
      return { detail: last, card: null, http: res.status, elapsedMs: Date.now() - startedAt };
    }
    await sleep(400);
  }
  const card = ((last?.pairs) ?? []).find((p) => p.counterpart?.recordId === foundId) ?? null;
  return { detail: last, card, http: 200, elapsedMs: Date.now() - startedAt, timeout: true };
}

async function runPair(dataset, id, lostCookie, foundCookie, expectCard) {
  const sample = dataset.pairs.find((p) => p.id === id);
  if (!sample) throw new Error(`标注集里没有 ${id}`);
  const lost = await publish(lostCookie, sample.lost);
  const found = await publish(foundCookie, sample.found);
  const lostSettled = await waitRecordSettled(lostCookie, lost.id);
  const foundSettled = await waitRecordSettled(foundCookie, found.id);
  const waited = await waitPairFacts(lostCookie, lost.id, found.id, expectCard);
  const card = waited.card;
  const result = {
    sample: id,
    class: sample.class,
    expect: sample.expect,
    lostId: lost.id,
    foundId: found.id,
    publishHttp: [lost.http, found.http],
    detailHttp: waited.http,
    pairState: waited.detail.pairState,
    lostPairState: lostSettled.detail.pairState,
    foundPairState: foundSettled.detail.pairState,
    lowConfidenceCount: waited.detail.lowConfidenceCount,
    timedOut: Boolean(waited.timeout),
    card: card && {
      pairId: card.pairId,
      score: card.score,
      scorePercent: card.scorePercent,
      engine: card.engine,
      tier: card.tier,
      degraded: card.degraded,
      contactAvailable: card.contactAvailable,
      contactHint: card.contactHint,
    },
    waitMs: waited.elapsedMs,
    closed: [await patchClosed(foundCookie, found.id), await patchClosed(lostCookie, lost.id)],
    closeHttp: [],
  };
  result.closeHttp = result.closed.map((c) => c.http);
  return result;
}

async function main() {
  const dataset = JSON.parse(await readFile(join(here, 'pairs.json'), 'utf8'));
  const lostCookie = await login('demo02');
  const foundCookie = await login('demo03');
  const positive = await runPair(dataset, POS_ID, lostCookie, foundCookie, true);
  const negative = await runPair(dataset, NEG_ID, lostCookie, foundCookie, false);

  const checks = {
    positiveCardVisible: Boolean(positive.card),
    positiveTierDirect: positive.card?.tier === 'DIRECT',
    positiveEngineExpected: positive.card?.engine === (LABEL === 'local' ? 'LOCAL_KEYWORD' : 'DEEPSEEK'),
    positiveNoLowConfidence: positive.lowConfidenceCount === 0,
    negativeCardHidden: negative.card === null,
    negativeCountedLowConfidence: negative.lowConfidenceCount === 1,
  };
  const evidence = {
    label: LABEL,
    apiBase: BASE,
    runAt: new Date().toISOString(),
    config: 'application.yml 默认值（语义引擎 0.95/0.80/0.90，local 覆盖 0.65/0.45/0.55）',
    positive,
    negative,
    checks,
    passed: Object.values(checks).every(Boolean),
  };
  const out = join(here, 'results', `verify-${LABEL}.json`);
  await writeFile(out, `${JSON.stringify(evidence, null, 2)}\n`, 'utf8');
  console.log(`[positive ${POS_ID}] pairs=${(positive.card ? 1 : 0)} score=${positive.card?.score} tier=${positive.card?.tier} low=${positive.lowConfidenceCount}`);
  console.log(`[negative ${NEG_ID}] card=${negative.card ? 'VISIBLE' : 'hidden'} low=${negative.lowConfidenceCount} state=${negative.pairState}`);
  console.log(`checks=${JSON.stringify(checks)}`);
  console.log(`passed=${evidence.passed} → qa/datasets/pair-calibration/results/verify-${LABEL}.json`);
  if (!evidence.passed) process.exitCode = 1;
}

main().catch((e) => {
  console.error(e.message);
  process.exit(1);
});
