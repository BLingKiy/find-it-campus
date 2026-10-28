// 阈值校准采集器（ZHYYKFX-84）：把 qa/datasets/pair-calibration/pairs.json 的 20 对样本
// 逐对发布 → 等打分 → 记录 score/engine/tier → 关闭两端，保证同一时刻只有一对 PENDING。
//
// 只走 HTTP，不直连 DB：本脚本要在没有子进程管道的环境里也能跑。
// token/latency 由 DB 侧核对（见本目录 README 的 SQL），卡片 API 里没有这两列。
//
// 前置（两条引擎各跑一遍）：
//   1. 空库：findit_z84_local / findit_z84_ds，执行 db/schema.sql + db/seed.sql
//   2. 起后端时把四个阈值压到「全放行」，否则低分 pair 不出卡片、拿不到分数：
//        --findit.pair.contact_unlock_threshold=0.00
//        --findit.pair.contact_direct_threshold=0.01
//        --findit.pair.notify_threshold=0.00
//        --findit.pair.completed_visible_threshold=0.00
//      这是**采集专用**的临时覆盖，不写进 application.yml。
//   3. node qa/datasets/pair-calibration/run.mjs local（无 DEEPSEEK_API_KEY）
//      node qa/datasets/pair-calibration/run.mjs deepseek（进程内注入 DEEPSEEK_API_KEY）
//
// 输出：results/<label>.json（逐对原始分数，供 analyze.mjs 统计）。

import { readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const BASE = (process.env.FINDIT_API_BASE ?? 'http://localhost:18080').replace(/\/+$/, '');
const LABEL = process.argv[2] ?? 'local';
const PASSWORD = 'Demo@123456';
const POLL_TIMEOUT_MS = 90_000;
const POLL_INTERVAL_MS = 400;

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
    /* 非 JSON 响应保留原文供排查 */
  }
  return { status: res.status, body: json, raw: text, setCookies: res.headers.getSetCookie?.() ?? [] };
}

function cookieOf(res) {
  for (const raw of res.setCookies) {
    const first = raw.split(';')[0].trim();
    if (first.startsWith('JSESSIONID=')) return first;
  }
  throw new Error(`登录响应没有 JSESSIONID：HTTP ${res.status} / ${res.raw.slice(0, 200)}`);
}

async function login(studentNo) {
  const res = await request('POST', '/api/auth/login', {
    body: { studentNo, password: PASSWORD },
  });
  if (res.status !== 200 || res.body?.code !== 0) {
    throw new Error(`登录 ${studentNo} 失败：HTTP ${res.status} / ${res.raw.slice(0, 200)}`);
  }
  return cookieOf(res);
}

async function create(cookie, payload) {
  const res = await request('POST', '/api/records', { cookie, body: payload });
  if (res.status !== 200 || res.body?.code !== 0) {
    throw new Error(`发布失败 ${payload.title}：HTTP ${res.status} / ${res.raw.slice(0, 300)}`);
  }
  return res.body.data.id;
}

async function close(cookie, recordId) {
  const res = await request('PATCH', `/api/records/${recordId}/status`, {
    cookie,
    body: { status: 'CLOSED' },
  });
  // 关闭失败不阻断采集：下一对开始前会重试清理，最坏情况多出几对交叉 pair
  return res.status === 200 && res.body?.code === 0;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function waitForPair(cookie, lostId, foundId) {
  const startedAt = Date.now();
  let last = null;
  while (Date.now() - startedAt < POLL_TIMEOUT_MS) {
    const res = await request('GET', `/api/records/${lostId}`, { cookie });
    if (res.status !== 200 || res.body?.code !== 0) {
      throw new Error(`读详情失败：HTTP ${res.status} / ${res.raw.slice(0, 200)}`);
    }
    const data = res.body.data;
    last = data;
    const settled = data.pairState === 'READY' || data.pairState === 'DEGRADED';
    const card = (data.pairs ?? []).find((p) => p.counterpart?.recordId === foundId);
    if (settled && card) {
      return { card, pairState: data.pairState, lowConfidenceCount: data.lowConfidenceCount, elapsedMs: Date.now() - startedAt };
    }
    await sleep(POLL_INTERVAL_MS);
  }
  return {
    card: null,
    pairState: last?.pairState ?? null,
    lowConfidenceCount: last?.lowConfidenceCount ?? null,
    elapsedMs: Date.now() - startedAt,
    error: 'TIMEOUT_WAITING_PAIR',
  };
}

async function main() {
  const dataset = JSON.parse(await readFile(join(here, 'pairs.json'), 'utf8'));
  const lostCookie = await login('demo02');
  const foundCookie = await login('demo03');
  const run = {
    label: LABEL,
    apiBase: BASE,
    startedAt: new Date().toISOString(),
    thresholdOverride: 'unlock=0.00 / direct=0.01 / notify=0.00 / completed_visible=0.00（采集专用，未落配置）',
    dataset: `qa/datasets/pair-calibration/pairs.json（${dataset.pairs.length} 对）`,
    pairs: [],
  };
  const out = join(here, 'results', `${LABEL}.json`);

  for (const [index, pair] of dataset.pairs.entries()) {
    const startedAt = Date.now();
    const row = { id: pair.id, class: pair.class, expect: pair.expect, reason: pair.reason };
    try {
      row.lostId = await create(lostCookie, pair.lost);
      row.foundId = await create(foundCookie, pair.found);
      const waited = await waitForPair(lostCookie, row.lostId, row.foundId);
      row.score = waited.card?.score ?? null;
      row.engine = waited.card?.engine ?? null;
      row.tier = waited.card?.tier ?? null;
      row.degraded = waited.card?.degraded ?? null;
      row.pairState = waited.pairState;
      row.lowConfidenceCount = waited.lowConfidenceCount;
      row.waitMs = waited.elapsedMs;
      if (!waited.card) row.error = waited.error ?? 'PAIR_CARD_NOT_VISIBLE';
    } catch (e) {
      row.error = e.message;
    } finally {
      if (row.foundId) row.foundClosed = await close(foundCookie, row.foundId);
      if (row.lostId) row.lostClosed = await close(lostCookie, row.lostId);
      row.totalMs = Date.now() - startedAt;
      run.pairs.push(row);
      await writeFile(out, `${JSON.stringify(run, null, 2)}\n`, 'utf8');
      const verdict = row.error
        ? `ERROR ${row.error}`
        : `score=${Number(row.score).toFixed(2)} engine=${row.engine} tier=${row.tier} expect=${row.expect}`;
      console.log(`[${index + 1}/${dataset.pairs.length}] ${row.id} ${pair.class} ${verdict} (${row.totalMs}ms)`);
    }
  }

  run.finishedAt = new Date().toISOString();
  const scored = run.pairs.filter((p) => typeof p.score === 'number');
  run.summary = {
    total: run.pairs.length,
    scored: scored.length,
    errors: run.pairs.filter((p) => p.error).length,
    engines: [...new Set(scored.map((p) => p.engine))],
  };
  await writeFile(out, `${JSON.stringify(run, null, 2)}\n`, 'utf8');
  console.log(`\n${LABEL}: ${run.summary.scored}/${run.summary.total} 对拿到分数，engine=${run.summary.engines.join(',')}，错误 ${run.summary.errors}`);
  console.log(`结果文件：qa/datasets/pair-calibration/results/${LABEL}.json`);
  if (run.summary.errors > 0) process.exitCode = 1;
}

main().catch((e) => {
  console.error(e.message);
  process.exit(1);
});
