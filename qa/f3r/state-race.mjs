// v2 · F3-R 项 6：P2-1 终态竞态复验（HTTP 出口 + EV-SQL + 真并发编排）。
//
// P2-1 的原始担忧：update 的「Java 侧查 PENDING」与「UPDATE」之间不是同一时刻，
// 并发 PATCH 先提交 CLOSED 后，PUT 仍可能改写终态记录正文。
// 修复：RecordMapper.xml:229 的 updateContent 加 `AND status='PENDING'`，
// 0 行时走 selectStatusByIdForUpdate（锁定读当前值）翻译成 20003/20001。
//
// 本轮要证的三件事，缺一件都算没复验：
//   ① 终态 PUT 在 HTTP 出口就是预期错误码，且**库里一个字节都没变**（EV-SQL 全行对照）；
//   ② 错误码顺序没被改坏：非所有者/匿名不能因为 P2-1 的 20003 而推断出「这条已终态」；
//   ③ 竞态窗口是真的存在、且真的被条件更新挡住 —— 用 InnoDB 行锁把交错排在指定位次上，
//      而不是只发两个并发请求碰运气。同时给一条 SQL 层反证：去掉 status 条件的同一条 UPDATE
//      确实能改写终态行（证明挡住它的是那个条件，不是运气）。
//
// 不改任何业务代码：②③ 里的对照 SQL 由测试自己发，应用走的仍是原路径。
//
// 运行：node qa/f3r/state-race.mjs
// 前置：后端 18080、MySQL 3306（qa/lib/db.mjs 的 MYSQL_* 环境变量）
// 输出：qa/reports/f3r-state-race.txt / .json

import { execFile } from 'node:child_process';
import { writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { brief, isOk } from '../lib/client.mjs';
import { contexts } from '../lib/auth.mjs';
import { MYSQL, DB, sql, val, rows, resetSeed } from '../lib/db.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..', '..');
const PASS_TEXT = 'Demo@123456';
const SENTINEL = '2026-10-20 12:00:00'; // 冻结 updated_at，任何写入都会让它前进，从而可检出

const lines = [];
const results = [];
const say = (s) => {
  lines.push(s);
  console.log(s);
};
function record(name, pass, detail) {
  results.push({ name, pass, detail });
  say(`${pass ? 'PASS' : 'FAIL'}  ${name}`);
  for (const d of [].concat(detail)) say(`        ${d}`);
}

const COLUMNS = [
  'id', 'type', 'status', 'title', 'description', 'location', 'contact', 'pair_state',
  'completed_at', 'updated_at', 'deleted_at', 'category_id', 'publisher_name',
];

/** 全行状态指纹：任何一列变化都会在字符串上体现，用于「零写入」对照。 */
async function fingerprint(id) {
  const list = await rows(
    `SELECT CONCAT_WS(0x1F,
              id, type, status, title, description, location, IFNULL(contact,'NULL'), pair_state,
              IFNULL(DATE_FORMAT(completed_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL'),
              DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f'),
              IFNULL(DATE_FORMAT(deleted_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL'),
              IFNULL(category_id,'NULL'), IFNULL(publisher_name,'NULL'))
     FROM t_record WHERE id=${Number(id)};`,
    ['fp'],
  );
  return list[0]?.fp ?? null;
}

const ROW_COLS = ['id', 'type', 'status', 'title', 'updated_at', 'completed_at', 'pair_state'];

async function rowState(id) {
  const list = await rows(
    `SELECT id, type, status, title,
            IFNULL(DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL') AS updated_at,
            IFNULL(DATE_FORMAT(completed_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL') AS completed_at, pair_state
     FROM t_record WHERE id=${Number(id)};`,
    ROW_COLS,
  );
  return list[0] ?? null;
}

/** 冻结终态夹具：走库是因为契约不允许客户端直传 status/completed_at（也只有这样 updated_at 才不前进）。 */
async function forceTerminal(id, status, at = SENTINEL) {
  await sql(
    `UPDATE t_record SET status='${status}', completed_at=${status === 'COMPLETED' ? `'${at}'` : 'NULL'}, updated_at='${at}' WHERE id=${Number(id)};`,
  );
}

/** 后台发一条 SQL（可用于持锁/夹进交错点），不阻塞调用方。 */
function mysqlAsync(statement) {
  const started = Date.now();
  const p = new Promise((res) => {
    execFile(MYSQL, ['-u', 'root', DB, '-N', '-B', '-e', statement], { encoding: 'utf8', maxBuffer: 4 * 1024 * 1024 },
      (err, stdout) => res({ err: err?.message ?? null, out: stdout.trim(), ms: Date.now() - started }));
  });
  return p;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const ctx = await contexts();
const created = [];

async function createAs(api, title) {
  const res = await api.post('/api/records', {
    body: {
      type: 'LOST',
      title,
      description: 'F3-R 项6 竞态复验用临时记录，正文用于检验终态 PUT 是否改写。',
      location: 'QA 竞态地点',
      occurredAt: '2026-10-20 10:00:00',
    },
  });
  if (!isOk(res)) throw new Error(`创建夹具失败：${brief(res)}`);
  created.push(res.body.data.id);
  return res.body.data.id;
}

const putBody = (title) => ({
  type: 'LOST',
  title,
  description: 'PUT 试图写入的新正文（若出现在库里即说明终态被改写）。',
  location: 'QA 竞态地点',
  occurredAt: '2026-10-20 10:00:00',
  contact: '13900000000',
});

say('# F3-R 项6 · P2-1 终态竞态复验 · ' + new Date().toISOString());
say(`- 基线分支 ${process.env.F3_BRANCH ?? 'test/v2-F3-integration-rescan'}；被验对象 updateContent WHERE id AND deleted_at IS NULL AND status='PENDING'`);
say(`- 夹具终态由 SQL 置位并把 updated_at 冻结在 ${SENTINEL}；任何写入都会推进 updated_at，故「零写入」可判`);
say('');

// =================================================================== ① 终态 PUT 的错误码 + 零写入
say('## ① 所有者对终态记录发 PUT：期望 409 + 20003，且全行指纹不变');
say('');

const c1 = await createAs(ctx.demo01, 'QA F3R-C1 对照-PENDING可改');
const c1Put = await ctx.demo01.put(`/api/records/${c1}`, { body: putBody('QA F3R-C1 已被 PUT 改写') });
const c1Row = await rowState(c1);
const controlOk = isOk(c1Put) && c1Row.title === 'QA F3R-C1 已被 PUT 改写';
record('C1 对照组：PENDING 记录 PUT 必须仍然成功并写入（否则下面的 PASS 没有意义）', controlOk, [
  `HTTP ${c1Put.status} / code=${c1Put.body?.code}`,
  `库内 title=${JSON.stringify(c1Row?.title)} updated_at=${c1Row?.updated_at}`,
]);

for (const [status, tag] of [['COMPLETED', 'C2'], ['CLOSED', 'C3']]) {
  const id = await createAs(ctx.demo01, `QA F3R-${tag} ${status} 原正文`);
  await forceTerminal(id, status);
  const before = await fingerprint(id);
  const res = await ctx.demo01.put(`/api/records/${id}`, { body: putBody(`QA F3R-${tag} PUT 改写后的正文`) });
  const after = await fingerprint(id);
  const changed = before !== after;
  const cols = [];
  if (changed) {
    const b = before.split(String.fromCharCode(31));
    const a = after.split(String.fromCharCode(31));
    for (let i = 0; i < b.length; i++) if (a[i] !== b[i]) cols.push(`${COLUMNS[i]}: ${b[i]} → ${a[i]}`);
  }
  record(
    `${tag} 所有者 PUT ${status} 记录：期望 HTTP 409 + code 20003 且全行零写入`,
    res.status === 409 && res.body?.code === 20003 && !changed,
    [`HTTP ${res.status} / code=${res.body?.code} / message=${JSON.stringify(res.body?.message)}`,
      `响应：${brief(res)}`,
      changed ? `指纹变化列：${cols.join(' | ')}` : `指纹逐字符相同（${before.length} 字符，含 updated_at=${SENTINEL}.000000 未前进）`,
      `pair_state 未被重置：${(await val(`SELECT pair_state FROM t_record WHERE id=${id};`))}`],
  );
}

// =================================================================== ① 续 删除 / 非法流转
say('');
say('## ① 续 已删除记录 PUT、终态之间的 PATCH');
say('');

const c4 = await createAs(ctx.demo01, 'QA F3R-C4 待软删');
const c4del = await ctx.demo01.del(`/api/records/${c4}`);
const c4Before = await fingerprint(c4);
const c4Put = await ctx.demo01.put(`/api/records/${c4}`, { body: putBody('QA F3R-C4 PUT 已删记录') });
const c4After = await fingerprint(c4);
record('C4 所有者 PUT 已软删记录：期望 404 + 20001 且零写入',
  c4Put.status === 404 && c4Put.body?.code === 20001 && c4Before === c4After,
  [`DELETE：HTTP ${c4del.status} / code=${c4del.body?.code}`, `PUT：${brief(c4Put)}`,
    c4Before === c4After ? '指纹相同（deleted_at 未被改写）' : '指纹发生变化']);

const c5 = await createAs(ctx.demo01, 'QA F3R-C5 终态间流转');
await forceTerminal(c5, 'COMPLETED');
const c5Before = await fingerprint(c5);
const c5Patch = await ctx.demo01.patch(`/api/records/${c5}/status`, { body: { status: 'CLOSED' } });
const c5After = await fingerprint(c5);
record('C5 COMPLETED → CLOSED 的 PATCH：期望 409 + 20002（非法流转）且零写入',
  c5Patch.status === 409 && c5Patch.body?.code === 20002 && c5Before === c5After,
  [`PATCH：${brief(c5Patch)}`, c5Before === c5After ? '指纹相同' : '指纹发生变化']);

const c6 = await createAs(ctx.demo01, 'QA F3R-C6 CLOSED→COMPLETED');
await forceTerminal(c6, 'CLOSED');
const c6Before = await fingerprint(c6);
const c6Patch = await ctx.demo01.patch(`/api/records/${c6}/status`, { body: { status: 'COMPLETED' } });
const c6After = await fingerprint(c6);
record('C6 CLOSED → COMPLETED 的 PATCH：期望 409 + 20002 且零写入',
  c6Patch.status === 409 && c6Patch.body?.code === 20002 && c6Before === c6After,
  [`PATCH：${brief(c6Patch)}`, c6Before === c6After ? '指纹相同' : '指纹发生变化']);

// =================================================================== ② 错误码顺序 / 不泄漏终态
say('');
say('## ② 非所有者与匿名 PUT：P2-1 的 20003 不得先于 404/403 命中（否则终态身份泄漏）');
say('');

const leak = await createAs(ctx.demo01, 'QA F3R-C7 非所有者打终态');
await forceTerminal(leak, 'COMPLETED');
const leakBefore = await fingerprint(leak);
const anonPut = await ctx.anon.put(`/api/records/${leak}`, { body: putBody('匿名 PUT') });
const otherPut = await ctx.demo02.put(`/api/records/${leak}`, { body: putBody('非所有者 PUT') });
const leakAfter = await fingerprint(leak);
const orderOk = anonPut.status === 401 && (otherPut.status === 404 || otherPut.status === 403) && otherPut.body?.code !== 20003 && leakBefore === leakAfter;
record('C7 匿名 401 / 非所有者 404|403，且都不得返回 20003；终态行零写入', orderOk, [
  `匿名 PUT：${brief(anonPut)}`,
  `非本人(demo02) PUT：${brief(otherPut)} → 是否泄漏终态(20003)=${otherPut.body?.code === 20003}`,
  leakBefore === leakAfter ? '指纹相同' : '指纹发生变化',
]);

// =================================================================== ③ SQL 层反证：窗口真实存在
say('');
say('## ③ SQL 层反证：同一条 UPDATE 去掉 status 条件能否改写终态行（测试自发的对照 SQL，不经应用）');
say('');

const sg = await createAs(ctx.demo01, 'QA F3R-S 条件更新对照');
await forceTerminal(sg, 'CLOSED');
const guarded = await sql(
  `UPDATE t_record SET title='GUARDED-WRITE', updated_at=NOW() WHERE id=${sg} AND deleted_at IS NULL AND status='PENDING'; SELECT ROW_COUNT();`,
);
const sgRow1 = await rowState(sg);
const unguarded = await sql(
  `UPDATE t_record SET title='UNGUARDED-WRITE', updated_at=NOW() WHERE id=${sg} AND deleted_at IS NULL; SELECT ROW_COUNT();`,
);
const sgRow2 = await rowState(sg);
record('S1 带 status=PENDING 条件：0 行；去掉条件：1 行且真的改写（证明挡住写入的是条件本身）',
  guarded.trim() === '0' && unguarded.trim() === '1' && sgRow1.title.startsWith('QA F3R-S') && sgRow2.title === 'UNGUARDED-WRITE',
  [`guarded ROW_COUNT()=${guarded.trim()}，行仍为 ${JSON.stringify(sgRow1.title)}（status=${sgRow1.status}）`,
    `unguarded ROW_COUNT()=${unguarded.trim()}，行变为 ${JSON.stringify(sgRow2.title)}，updated_at 由 ${SENTINEL} 前进到 ${sgRow2.updated_at}`]);

// =================================================================== ④ 真并发：行锁编排
say('');
say('## ④ 真并发（InnoDB 行锁编排，把交错钉在指定位次上）');
say('');
say('编排：A 会话持该行 X 锁 3s（FOR UPDATE + DO SLEEP，不改任何列）');
say('      → C 会话在 A 之后入队，把 status 改成 CLOSED 并提交（排队等锁）');
say('      → HTTP PUT 最后入队：它的快照读早已读到 PENDING（A 从未改 status），');
say('        所以预检查通过、必定走到 updateContent，然后被锁挡住，等 C 提交 CLOSED 之后才执行。');
say('      此时若返回 20003 且正文未变，就是「预检查与 UPDATE 之间的窗口被条件更新关闭」的直接证据，而不是串行巧合。');
say('');

const race = await createAs(ctx.demo01, 'QA F3R-R1 锁编排竞态原正文');
const holder = mysqlAsync(`START TRANSACTION; SELECT id FROM t_record WHERE id=${race} FOR UPDATE; DO SLEEP(3); COMMIT;`);
await sleep(500);
const closer = mysqlAsync(`UPDATE t_record SET status='CLOSED', completed_at=NULL, updated_at=NOW() WHERE id=${race};`);
await sleep(500);
const t0 = Date.now();
const raceRes = await ctx.demo01.put(`/api/records/${race}`, { body: putBody('QA F3R-R1 PUT 在窗口内写入的正文') });
const putMs = Date.now() - t0;
await holder;
await closer;
const raceRow = await rowState(race);
const raceBlocked = putMs > 1200; // 未阻塞就说明 PUT 根本没走到写阶段，编排不成立
record('R1 锁编排：PUT 应因条件更新返回 409/20003，正文零写入，且 PUT 确实被行锁阻塞过',
  raceRes.status === 409 && raceRes.body?.code === 20003 && raceRow.title.startsWith('QA F3R-R1 锁编排') && raceBlocked,
  [`PUT：${brief(raceRes)} / 耗时 ${putMs} ms（>1200 ms 才说明真在等行锁）`,
    `A 会话：err=${(await holder).err ?? '无'} 耗时 ${(await holder).ms} ms`,
    `C 会话：err=${(await closer).err ?? '无'} 耗时 ${(await closer).ms} ms`,
    `终态行：status=${raceRow.status} title=${JSON.stringify(raceRow.title)} updated_at=${raceRow.updated_at} pair_state=${raceRow.pair_state}`]);

// 反向控制：同样的编排，但不让 C 改 status —— PUT 应当等锁后正常成功，
// 说明上面那次 20003 不是「PUT 被锁拖住所以自己放弃了」。
const race2 = await createAs(ctx.demo01, 'QA F3R-R2 只等锁不改状态');
const holder2 = mysqlAsync(`START TRANSACTION; SELECT id FROM t_record WHERE id=${race2} FOR UPDATE; DO SLEEP(3); COMMIT;`);
await sleep(500);
const t1 = Date.now();
const race2Res = await ctx.demo01.put(`/api/records/${race2}`, { body: putBody('QA F3R-R2 PUT 等锁后应写入') });
const put2Ms = Date.now() - t1;
await holder2;
const race2Row = await rowState(race2);
record('R2 控制组：同编排但无并发终态提交，PUT 应等锁后 200 成功（排除「阻塞导致失败」的解释）',
  isOk(race2Res) && race2Row.title === 'QA F3R-R2 PUT 等锁后应写入' && put2Ms > 1200,
  [`PUT：${brief(race2Res)} / 耗时 ${put2Ms} ms`,
    `行：title=${JSON.stringify(race2Row.title)} status=${race2Row.status} updated_at=${race2Row.updated_at}`]);

// =================================================================== ⑤ 裸并发 N 轮
say('');
say('## ⑤ 裸并发：PUT 与 PATCH(CLOSED) 同时发出，跑 20 轮看结局分布');
say('');

const ROUNDS = 20;
const dist = {};
let raceWriteDetected = 0;
let inconsistent = [];
for (let i = 1; i <= ROUNDS; i++) {
  const id = await createAs(ctx.demo01, `QA F3R-B${i} 裸并发原正文`);
  const [putRes, patchRes] = await Promise.all([
    ctx.demo01.put(`/api/records/${id}`, { body: putBody(`QA F3R-B${i} PUT新正文`) }),
    ctx.demo01.patch(`/api/records/${id}/status`, { body: { status: 'CLOSED' } }),
  ]);
  const row = await rowState(id);
  const key = `PUT=${putRes.status}/${putRes.body?.code} × PATCH=${patchRes.status}/${patchRes.body?.code}`;
  dist[key] = (dist[key] ?? 0) + 1;
  // 竞态签名：记录已 CLOSED，但 PUT 的正文写进去了，且 PUT 自己是 409（说明它在终态之后仍落了笔）
  const putWrote = row.title.includes('PUT新正文');
  if (row.status === 'CLOSED' && putWrote && putRes.body?.code === 20003) raceWriteDetected++;
  if (putRes.body?.code === 20003 && putWrote) inconsistent.push(`B${i}: PUT 报 20003 但正文被写入`);
  if (isOk(putRes) && !isOk(patchRes)) inconsistent.push(`B${i}: PUT 成功但 PATCH 非 200(${patchRes.body?.code})`);
}
say(`- 结局分布：`);
for (const [k, v] of Object.entries(dist)) say(`    ${v} × ${k}`);
record(`B1 裸并发 ${ROUNDS} 轮：终态记录未出现「PUT 被拒却仍写入正文」`,
  raceWriteDetected === 0 && inconsistent.length === 0,
  [`终态行被后写签名次数=${raceWriteDetected}`,
    inconsistent.length ? `不一致明细：${inconsistent.join('；')}` : '无「拒绝但已写入」「成功但流转失败」的不一致轮次',
    '诚实说明：两条请求确实在服务端同时在飞（同一 tick 发出），但「PUT 的快照读发生在 PATCH 提交之前」这一点对裸并发不可外部观测，' +
      '重叠窗口是否命中只能由上面的分布推断；可复现的窗口证明在 R1 锁编排那一条。']);

// =================================================================== 汇总
const failed = results.filter((r) => !r.pass);
say('');
say('## 结论');
say(`- 断言项：${results.length}；失败：${failed.length}`);
say(`- ${failed.length === 0 ? 'P2-1 复验通过：终态 PUT 一律 409/20003（或非所有者 404/403、匿名 401），库里零写入；条件更新在真实锁编排下挡住了窗口，SQL 层反证也说明没有条件时窗口会被打穿。' : `存在未通过项：${failed.map((f) => f.name).join('；')}`}`);

await writeFile(resolve(root, 'qa', 'reports', 'f3r-state-race.json'), JSON.stringify({ generatedAt: new Date().toISOString(), results, dist, raceWriteDetected }, null, 2), 'utf8');
await writeFile(resolve(root, 'qa', 'reports', 'f3r-state-race.txt'), lines.join('\n') + '\n', 'utf8');

// 夹具清理：本轮建了 ROUNDS+10 条记录，回到 seed 初始态，避免污染后续扫描/统计
say('');
say('## 收尾：恢复 seed 初始态');
await resetSeed();
say(`- resetSeed() 已执行（db/schema.sql + db/seed.sql），本轮造的 ${created.length} 条记录与 pair 全部清除`);
await writeFile(resolve(root, 'qa', 'reports', 'f3r-state-race.txt'), lines.join('\n') + '\n', 'utf8');
say('证据：qa/reports/f3r-state-race.txt / .json');
process.exit(failed.length === 0 ? 0 : 1);
