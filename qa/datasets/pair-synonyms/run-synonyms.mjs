// 近似同义转换专项采集器（ZHYYKFX-84 / ZHYYKFX-85 共用样本）。
//
// 读 variants.json：6 件物品 × 4 种措辞 → 每件 7 对（O↔O 基线 + 6 种两两组合），
// 另加 6 对「不同物但描述高度重叠」的陷阱负例，共 48 对。
//
// 采集口径与 run.mjs 完全一致：逐对发布 → 等两端打分完成 → 读 LOST 详情里的
// score/engine/tier → 关闭两端。**两套阈值都必须**临时压到全放行（见 README）：
// 启动参数同时加 --findit.pair.* 与 --findit.pair.overrides.local.*，漏掉 local 那套
// 会把 0.45 以下的同义对误判成「没出卡片」。
//
//   FINDIT_API_BASE=http://localhost:18086 node qa/datasets/pair-synonyms/run-synonyms.mjs local
//   FINDIT_API_BASE=http://localhost:18087 node qa/datasets/pair-synonyms/run-synonyms.mjs deepseek
//
// 输出 results/syn-<label>.json。

import { readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const BASE = (process.env.FINDIT_API_BASE ?? 'http://localhost:18080').replace(/\/+$/, '');
const LABEL = process.argv[2] ?? 'local';
const PASSWORD = 'Demo@123456';
const POLL_TIMEOUT_MS = 30_000;
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

// 把一条措辞展开成可发布的记录；contact/publisher 由账号侧固定，不参与打分。
function record(type, phrasing, contact, publisherName) {
  return {
    type,
    title: phrasing.title,
    description: phrasing.description,
    categoryId: phrasing.categoryId,
    location: phrasing.location,
    occurredAt: '2026-10-25 15:20:00',
    contact,
    publisherName,
  };
}

const COMBOS = ['OC', 'OF', 'OS', 'CF', 'CS', 'FS'];

function buildCases(dataset) {
  const cases = [];
  for (const item of dataset.items) {
    const p = item.phrasings;
    for (const label of Object.keys(p)) {
      p[label].categoryId = item.categoryId;
    }
    cases.push({
      id: `${item.itemId}-OO`,
      kind: 'baseline',
      itemId: item.itemId,
      combo: 'OO',
      phrasing: ['O', 'O'],
      object: item.object,
      expect: 'MATCH',
      reason: '原文对原文，作为分数保持率的分母',
      lost: p.O,
      found: p.O,
    });
    for (const combo of COMBOS) {
      const [left, right] = combo.split('');
      cases.push({
        id: `${item.itemId}-${combo}`,
        kind: combo.includes('O') ? 'synonym' : 'cross',
        itemId: item.itemId,
        combo,
        phrasing: [left, right],
        object: item.object,
        expect: 'MATCH',
        reason: `${item.object}：${left} 与 ${right} 是同一物品的不同措辞`,
        lost: p[left],
        found: p[right],
      });
    }
  }
  for (const trap of dataset.traps) {
    cases.push({
      id: trap.id,
      kind: 'trap',
      itemId: trap.itemId,
      combo: 'TRAP',
      phrasing: ['O', 'O'],
      object: trap.object,
      expect: 'NO_MATCH',
      reason: trap.reason,
      lost: { ...trap.lost, categoryId: trap.categoryId },
      found: { ...trap.found, categoryId: trap.categoryId },
    });
  }
  return cases;
}

async function main() {
  const dataset = JSON.parse(await readFile(join(here, 'variants.json'), 'utf8'));
  const cases = buildCases(dataset);
  const lostCookie = await login('demo02');
  const foundCookie = await login('demo03');
  const run = {
    label: LABEL,
    dataset: 'qa/datasets/pair-synonyms/variants.json',
    apiBase: BASE,
    startedAt: new Date().toISOString(),
    thresholdOverride: 'unlock=0.00 / direct=0.01 / notify=0.00 / completed_visible=0.00（采集专用，未落配置）',
    total: cases.length,
    pairs: [],
  };
  const out = join(here, 'results', `syn-${LABEL}.json`);

  for (const [index, item] of cases.entries()) {
    const startedAt = Date.now();
    const row = {
      id: item.id,
      kind: item.kind,
      itemId: item.itemId,
      combo: item.combo,
      phrasing: item.phrasing,
      object: item.object,
      expect: item.expect,
      reason: item.reason,
    };
    try {
      row.lostId = await create(lostCookie, record('LOST', item.lost, '13800000002', '演示乙'));
      row.foundId = await create(foundCookie, record('FOUND', item.found, '13800000003', '演示丙'));
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
        : `score=${Number(row.score).toFixed(2)} engine=${row.engine} expect=${row.expect}`;
      console.log(`[${index + 1}/${cases.length}] ${row.id} ${row.kind} ${verdict} (${row.totalMs}ms)`);
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
  console.log(`结果文件：qa/datasets/pair-synonyms/results/syn-${LABEL}.json`);
  if (run.summary.errors > 0) process.exitCode = 1;
}

main().catch((e) => {
  console.error(e.message);
  process.exit(1);
});
