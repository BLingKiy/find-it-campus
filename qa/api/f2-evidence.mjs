// v2 · F2 集成回归专用证据：终态组（vF）/ 多对 pair 独立 / 降级（API 层）。
//
// 与 api/run.mjs 的关系：run.mjs 是 111 条全量契约回归；本脚本只负责把 F2 验收
// 里要求「单列一节、逐条给命令与输出」的几项，按验收条目各打一组 EV- 原始行，
// 便于报告直接引用，不靠从 111 条输出里二次摘抄。
//
// 前置：后端 18080、库为 seed 初始状态；DEEPSEEK_API_KEY 未设置（降级路径）。
// 运行：node qa/api/f2-evidence.mjs
// 结束会 resetSeed，避免本轮夹具污染后续 E2E 扫描。

import { isOk } from '../lib/client.mjs';
import { writeFile } from 'node:fs/promises';
import { contexts } from '../lib/auth.mjs';
import {
  forceRecordStatus,
  pairByEnds,
  q,
  record,
  recordSnapshot,
  resetSeed,
  rows,
  sql,
  upsertPair,
  val,
  waitForScoring,
} from '../lib/db.mjs';
import { Tester, assert, eq, reportsDir } from '../lib/tester.mjs';

const t = new Tester('f2-evidence');
const ctx = await contexts();
const rid = () => Math.random().toString(36).slice(2, 10);
const created = [];

const ev = (tag, line) => {
  const text = `[${tag}] ${line}`;
  evLines.push(text);
  console.log(`  ${text}`);
};
const evLines = [];

async function createRecordAs(api, overrides = {}) {
  const body = {
    type: 'LOST',
    title: `F2 夹具 ${rid()}`,
    description: 'F2 集成回归夹具记录，用于终态与多对 pair 取证。',
    location: 'F2 取证地点',
    occurredAt: '2026-10-20 10:00:00',
    ...overrides,
  };
  const res = await api.post('/api/records', { body });
  if (isOk(res)) created.push({ id: res.body.data.id, owner: api });
  return { res, body, id: res.body?.data?.id };
}

async function makePair({
  score,
  status = 'NEW',
  lostOwner = ctx.demo01,
  foundOwner = ctx.demo02,
  lostOverrides = {},
  foundOverrides = {},
  ...pairOpts
}) {
  const lost = await createRecordAs(lostOwner, { type: 'LOST', contact: '13800000001', ...lostOverrides });
  const found = await createRecordAs(foundOwner, { type: 'FOUND', contact: '13800000002', ...foundOverrides });
  assert(isOk(lost.res) && isOk(found.res), '夹具记录应创建成功');
  await waitForScoring([lost.id, found.id]);
  await upsertPair({ lostId: lost.id, foundId: found.id, score, status, ...pairOpts });
  const pair = await pairByEnds(lost.id, found.id);
  return { lostId: lost.id, foundId: found.id, pairId: Number(pair.id), lost, found };
}

async function makeCompleted({ score = 0.95, lostOwner = ctx.demo01, foundOwner = ctx.demo02 } = {}) {
  const marker = `F2完成${rid()}`;
  const pair = await makePair({
    score,
    status: 'MATCHED',
    lostOwner,
    foundOwner,
    lostOverrides: { title: `${marker}L 深紫色钢笔`, description: `${marker} 自习室丢失的深紫色钢笔，笔帽有划痕。` },
    foundOverrides: { title: `${marker}F 招领深紫色钢笔`, description: `${marker} 招领深紫色钢笔，笔帽有划痕。` },
    lostConfirmedAt: '2026-10-26 10:00:00',
    foundConfirmedAt: '2026-10-26 10:05:00',
  });
  const res = await lostOwner.patch(`/api/records/${pair.lostId}/status`, { body: { status: 'COMPLETED' } });
  eq(res.status, 200, '夹具标记完成');
  return { ...pair, marker };
}

// ---------------------------------------------------------------- MP：多对 pair 独立

await t.case('F2-MP-01', 'P0', '一条招领 × 两条寻物各成一对：处理 A 不影响 B，招领仍在候选池', async () => {
  const marker = `F2多对${rid()}`;
  const foundTitle = `${marker} 紫色雨伞招领`;
  const found = await createRecordAs(ctx.demo03, {
    type: 'FOUND',
    title: foundTitle,
    description: `${marker} 在食堂捡到一把紫色自动雨伞，伞柄有熊图案。`,
    location: '第二食堂二楼',
  });
  const lostA = await createRecordAs(ctx.demo01, { type: 'LOST', title: `${marker} 寻紫色雨伞 A`, description: `${marker} 丢了一把紫色自动雨伞，伞柄有熊图案。`, location: '第二食堂二楼' });
  const lostB = await createRecordAs(ctx.demo02, { type: 'LOST', title: `${marker} 寻紫色雨伞 B`, description: `${marker} 丢了一把紫色自动雨伞，伞柄有熊图案。`, location: '第二食堂二楼' });
  for (const r of [found, lostA, lostB]) eq(r.res.status, 200, '夹具记录创建');
  await waitForScoring([found.id, lostA.id, lostB.id]);
  await upsertPair({ lostId: lostA.id, foundId: found.id, score: 0.97 });
  await upsertPair({ lostId: lostB.id, foundId: found.id, score: 0.94 });

  const pA = await pairByEnds(lostA.id, found.id);
  const pB = await pairByEnds(lostB.id, found.id);
  const statusBefore = await rows(
    `SELECT id, status FROM t_pair_score WHERE found_id=${Number(found.id)} ORDER BY lost_id;`,
    ['id', 'status'],
  );
  ev('EV-SQL', `MP 初始 pair：${statusBefore.map((x) => `${x.id}=${x.status}`).join(' / ')}（同一招领 foundId=${found.id}）`);

  const cA = await ctx.demo01.patch(`/api/pairs/${pA.id}`, { body: { action: 'CONTACT' } });
  eq(cA.status, 200, 'A 标为已联系');
  const afterA = await pairByEnds(lostA.id, found.id);
  const afterB = await pairByEnds(lostB.id, found.id);
  eq(afterA.status, 'CONTACTED', 'A 状态');
  eq(afterB.status, 'NEW', 'B 仍应为 NEW');

  const cB = await ctx.demo02.patch(`/api/pairs/${pB.id}`, { body: { action: 'CONTACT' } });
  eq(cB.status, 200, 'B 在 A 已联系后仍可联系');
  eq((await pairByEnds(lostB.id, found.id)).status, 'CONTACTED', 'B 状态');

  const match = await ctx.demo01.post('/api/match', { body: { text: foundTitle, type: 'FOUND', topN: 20, minScore: 0 } });
  eq(match.status, 200, 'match');
  const stillCandidate = match.body.data.items.some((i) => i.recordId === found.id);
  assert(stillCandidate, '招领应仍在候选池（无占用语义）');

  for (const id of [lostA.id, lostB.id, found.id]) eq((await record(id)).status, 'PENDING', `记录 ${id} 状态`);
  const rowCount = Number(
    await val(`SELECT COUNT(*) FROM t_pair_score WHERE found_id=${Number(found.id)} AND lost_id IN (${Number(lostA.id)},${Number(lostB.id)});`),
  );
  eq(rowCount, 2, '两条目标寻物与该招领的 pair 行数');
  const allPairs = Number(await val(`SELECT COUNT(*) FROM t_pair_score WHERE found_id=${Number(found.id)};`));

  ev('EV-API', `MP CONTACT A(pairId=${pA.id}) → HTTP ${cA.status}；A=${afterA.status}，B=${afterB.status}；CONTACT B(pairId=${pB.id}) → HTTP ${cB.status}/${(await pairByEnds(lostB.id, found.id)).status}`);
  ev('EV-API', `MP /api/match 命中招领 recordId=${found.id}：${stillCandidate}；三条记录 status 均 PENDING`);
  ev('EV-SQL', `MP 目标 pair（A/B）行数=${rowCount}；该招领自动评分产生 pair 共 ${allPairs} 行（多条寻物各自成对，互不覆盖）`);
});

// ---------------------------------------------------------------- TERM：终态组（vF）

await t.case('F2-TERM-01', 'P0', '未达 MATCHED 调「标记完成」→ 409 + 20005，status/completed_at/updated_at 零变化', async () => {
  const made = await createRecordAs(ctx.demo01, { title: `F2 未完成 ${rid()}` });
  eq(made.res.status, 200, '发布');
  await new Promise((r) => setTimeout(r, 1100));
  const before = await recordSnapshot(made.id);
  const res = await ctx.demo01.patch(`/api/records/${made.id}/status`, { body: { status: 'COMPLETED' } });
  eq(res.status, 409, 'HTTP');
  eq(res.body.code, 20005, '业务码');
  eq(res.body.data.currentStatus, 'PENDING', 'currentStatus');
  eq(res.body.data.required, 'MATCHED_PAIR', 'required');
  eq(res.body.data.matchedPairCount, 0, 'matchedPairCount');
  const after = await recordSnapshot(made.id);
  eq(after, before, '记录快照');
  ev('EV-API', `TERM-01 PATCH COMPLETED recordId=${made.id} → HTTP ${res.status} / code=${res.body.code} / ${JSON.stringify(res.body.data)}`);
  ev('EV-SQL', `TERM-01 前 ${JSON.stringify(before)}，后 ${JSON.stringify(after)}（零写入）`);
});

await t.case('F2-TERM-02', 'P0', 'COMPLETED：列表/搜索 0 命中、匹配仍召回、0.90 门槛决定详情可见性', async () => {
  const fixture = await makeCompleted({ score: 0.95 });
  const id = fixture.lostId;
  eq((await record(id)).status, 'COMPLETED', 'COMPLETED 落库');
  assert((await record(id)).completed_at !== 'NULL', 'completed_at 已写入');

  const list = await ctx.anon.get('/api/records?status=ALL&pageSize=50');
  const listed = list.body.data.list.some((x) => x.id === id);
  const search = await ctx.anon.get('/api/records?keyword=' + encodeURIComponent(`${fixture.marker}L`));
  const match = await ctx.demo01.post('/api/match', { body: { text: `${fixture.marker} 深紫色钢笔`, type: 'LOST', topN: 20, minScore: 0 } });
  const hit = match.body.data.items.find((i) => i.recordId === id);
  const owner = await ctx.demo01.get(`/api/records/${id}`);
  const qualified = await ctx.demo02.get(`/api/records/${id}`);
  const other = await ctx.demo03.get(`/api/records/${id}`);

  eq(listed, false, '公共列表命中');
  eq(search.body.data.total, 0, '搜索命中');
  assert(hit, '保留期内 COMPLETED 应被召回');
  eq(hit.recordStatus, 'COMPLETED', 'recordStatus');
  eq(hit.detailAccess, 'RESTRICTED', 'detailAccess');
  eq(owner.status, 404, '所有者详情');
  eq(qualified.status, 200, 'score 0.95 合格对端详情');
  eq(other.status, 404, '非合格对端详情');

  ev('EV-API', `TERM-02 recordId=${id}（score=0.95）：列表命中=${listed}；搜索 total=${search.body.data.total}；match 召回=${Boolean(hit)} detailAccess=${hit?.detailAccess}`);
  ev('EV-API', `TERM-02 详情：所有者 HTTP ${owner.status}；合格对端(demo02) HTTP ${qualified.status}；非合格(demo03) HTTP ${other.status}`);
  ev('EV-SQL', `TERM-02 t_record.status=${(await record(id)).status} completed_at=${(await record(id)).completed_at}`);
});

await t.case('F2-TERM-03', 'P0', 'COMPLETED 分数 0.80 的非合格对端详情 404（阈值是硬门槛）', async () => {
  const fixture = await makeCompleted({ score: 0.80 });
  const res = await ctx.demo02.get(`/api/records/${fixture.lostId}`);
  eq(res.status, 404, 'HTTP');
  eq(res.body.code, 20001, '业务码');
  ev('EV-API', `TERM-03 recordId=${fixture.lostId} pair score=0.80 < 0.90：合格对端详情 HTTP ${res.status}/code=${res.body.code}`);
});

await t.case('F2-TERM-04', 'P0', 'CLOSED：列表与匹配双 0、详情三身份 404、pair 取号/改状态 403 + 50005', async () => {
  const pair = await makePair({ score: 0.96, status: 'CONTACTED' });
  const closed = await ctx.demo01.patch(`/api/records/${pair.lostId}/status`, { body: { status: 'CLOSED' } });
  eq(closed.status, 200, '关闭');
  const closedTitle = pair.lost.body.title;
  const list = await ctx.anon.get('/api/records?status=ALL&pageSize=50');
  const listed = list.body.data.list.some((x) => x.id === pair.lostId);
  const search = await ctx.anon.get('/api/records?keyword=' + encodeURIComponent(closedTitle));
  const match = await ctx.demo01.post('/api/match', { body: { text: closedTitle, topN: 20, minScore: 0 } });
  const inMatch = match.body.data.items.some((i) => i.recordId === pair.lostId);
  const details = {};
  for (const [label, api] of [['anon', ctx.anon], ['owner', ctx.demo01], ['other', ctx.demo03]]) {
    details[label] = (await api.get(`/api/records/${pair.lostId}`)).status;
  }
  const contact = await ctx.demo01.get(`/api/pairs/${pair.pairId}/contact`);
  const patch = await ctx.demo02.patch(`/api/pairs/${pair.pairId}`, { body: { action: 'CONTACT' } });

  eq(listed, false, '列表命中');
  eq(search.body.data.total, 0, '搜索命中');
  eq(inMatch, false, '匹配召回');
  eq(details, { anon: 404, owner: 404, other: 404 }, '详情三身份');
  eq(contact.status, 403, 'pair 取号 HTTP');
  eq(contact.body.code, 50005, 'pair 取号业务码');
  eq(patch.status, 403, 'pair 改状态 HTTP');
  eq(patch.body.code, 50005, 'pair 改状态业务码');

  ev('EV-API', `TERM-04 recordId=${pair.lostId} CLOSED：列表命中=${listed}；搜索 total=${search.body.data.total}；match 召回=${inMatch}`);
  ev('EV-API', `TERM-04 详情：${JSON.stringify(details)}；pair contact HTTP ${contact.status}/code=${contact.body.code}；pair CONTACT HTTP ${patch.status}/code=${patch.body.code}`);
  ev('EV-SQL', `TERM-04 pairId=${pair.pairId} status=${(await pairByEnds(pair.lostId, pair.foundId)).status}；对端记录 status=${(await record(pair.foundId)).status}`);
});

await t.case('F2-TERM-05', 'P0', '到期 COMPLETED（completed_at + 31 天）：匹配消失、详情 404、status 不被自动改写', async () => {
  const fixture = await makeCompleted({ score: 0.95 });
  const id = fixture.lostId;
  const before = await ctx.demo01.post('/api/match', { body: { text: `${fixture.marker} 深紫色钢笔`, type: 'LOST', topN: 20, minScore: 0 } });
  const hitBefore = before.body.data.items.some((i) => i.recordId === id);
  await sql(`UPDATE t_record SET completed_at = DATE_SUB(NOW(), INTERVAL 31 DAY) WHERE id=${Number(id)};`);
  const after = await ctx.demo01.post('/api/match', { body: { text: `${fixture.marker} 深紫色钢笔`, type: 'LOST', topN: 20, minScore: 0 } });
  const hitAfter = after.body.data.items.some((i) => i.recordId === id);
  const detail = await ctx.demo02.get(`/api/records/${id}`);
  const rec = await record(id);

  assert(hitBefore, '到期前应可召回');
  eq(hitAfter, false, '到期后召回');
  eq(detail.status, 404, '到期后合格对端详情');
  eq(rec.status, 'COMPLETED', 'status 不被自动改写');
  assert(rec.completed_at !== 'NULL', 'completed_at 保留');

  ev('EV-API', `TERM-05 recordId=${id}：31 天前完成时召回=${hitBefore}，到期后召回=${hitAfter}；合格对端详情 HTTP ${detail.status}`);
  ev('EV-SQL', `TERM-05 到期后 t_record.status=${rec.status} completed_at=${rec.completed_at}（status 未被改写）`);
});

// ---------------------------------------------------------------- DEG：降级（API 层）

await t.case('F2-DEG-01', 'P0', '无 DEEPSEEK_API_KEY 发布：200、pair 落 LOCAL_KEYWORD + EMPTY_KEY、记录 pairState=DEGRADED', async () => {
  const made = await createRecordAs(ctx.demo01, {
    type: 'LOST',
    title: `F2降级 图书馆黑色钱包 ${rid()}`,
    description: '在图书馆三楼自习区丢了黑色皮质钱包，里面有两张银行卡和现金。',
    location: '图书馆三楼自习区',
  });
  eq(made.res.status, 200, '发布 HTTP');
  eq(made.res.body.code, 0, '发布业务码');
  await waitForScoring(made.id);
  const rec = await record(made.id);
  eq(rec.pair_state, 'DEGRADED', 'pairState');
  const pair = await val(
    `SELECT CONCAT(engine, '|', IFNULL(degraded_reason,'NULL'), '|', score) FROM t_pair_score WHERE lost_id=${Number(made.id)} LIMIT 1;`,
  );
  assert(pair, '应至少生成一条 pair');
  const [engine, reason, score] = pair.split('|');
  eq(engine, 'LOCAL_KEYWORD', 'engine');
  eq(reason, 'EMPTY_KEY', 'degradedReason');

  const match = await ctx.demo01.post('/api/match', { body: { text: '图书馆 黑色钱包 银行卡', type: 'FOUND', topN: 5, minScore: 0.2 } });
  eq(match.status, 200, '/api/match HTTP');
  eq(match.body.data.engine, 'LOCAL_KEYWORD', '/api/match engine');

  ev('EV-API', `DEG-01 recordId=${made.id} POST /api/records → HTTP ${made.res.status}/code=${made.res.body.code}；pairState=${rec.pair_state}`);
  ev('EV-SQL', `DEG-01 pair engine=${engine} degraded_reason=${reason} score=${score}`);
  ev('EV-API', `DEG-01 /api/match → HTTP ${match.status} engine=${match.body.data.engine}（非 503）`);
});

const summary = t.print();
const written = await t.writeReports(reportsDir());
await writeFile(reportsDir('f2-evidence-console.txt'), evLines.join('\n') + '\n', 'utf8');
console.log(`\n  EV 原始行：${reportsDir('f2-evidence-console.txt')}`);
console.log(`  报告：${written.jsonPath}\n        ${written.txtPath}`);

await resetSeed();
console.log('\n  已 resetSeed：种子状态已恢复（本轮夹具不残留）');

process.exit(summary.failed > 0 ? 1 : 0);
