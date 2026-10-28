// v2 · F3-R 组7（任务项 7）：P2-4 过期 COMPLETED 不再被扫描复验（两阶段，中间夹一次后端重启）。
//
// 用途：证明「已过期（completed_at < now-30d）的 COMPLETED 记录会被每日扫描重打分」这个担心已被堵住 ——
//      过期记录既不重算、也不新建 pair / 通知，同时保留期内的记录仍能被正常扫到（正对照）。
//
// P2-4 的原始担忧：过期 COMPLETED（completed_at 早于 now-30d）若停在 PENDING/SCORING/DEGRADED，
// 每日扫描会重新给它打分 → 新建 pair → 发通知，而通知点进去按可见性规则是 404。
// 修复两处：RecordMapper.xml:323-334 目标 SQL 加 `status='PENDING' OR (COMPLETED AND completed_at>cutoff)`；
//          PairScoringService.java:119-122 的 isMatchable 兜底（扫描开始后才发现过期的情况）。
//
// 为什么分两阶段：任务书要求「触发一次扫描」。最真实的触发点有两个 ——
//   ① PairScanOnceRunner 的启动扫描（要带启动参数 --pair-scan-once，光重启不带它不会跑）；
//   ② POST /api/ops/pair-scan（需要 OPS_SCAN_TOKEN，返回 scanned/rescored/expired 计数）。
// 所以：prepare 造夹具 → 带 --pair-scan-once + OPS_SCAN_TOKEN 重启（日志留档）→ verify 跑 ②，
// 并把 ① 的启动扫描一起取证。两个触发口径分别有独立脚本对照：见 expired-scan-startup.mjs。
//
// 判据（全部 EV-SQL + 后端日志，不靠接口返回值自证）：
//   过期夹具 X1(DEGRADED)/X2(PENDING) 在扫描后：
//     · t_pair_score 端点为 X1/X2 的行数不增（不新建 pair）
//     · 预先造好的那行 pair 的 score/status/lost_hash/updated_at 一字不变（不重算）
//     · t_record.pair_state / pair_scored_at 不变（连 markPairScoring 都没进过）
//     · t_notification 不新增（不发通知）
//     · 日志里没有 `pair record scored recordId=X1|X2`
//   同时必须有正对照，否则上面全是「扫描压根没跑」的假 PASS：
//     · 保留期内 COMPLETED 的 X3、PENDING 的 X4 必须被扫到（日志有 record scored 行、pair_state 变 READY）
//   再给一条 SQL 层反证：把修复前的目标 SQL 条件手抄一遍跑一次，X1/X2 会被选上；
//     跑修复后的条件选不上 —— 说明挡住它们的就是那个 cutoff 谓词。
//
// 前置：后端 18080 可被杀掉重启（prepare 阶段要重启它，见 docs/testing/v2/F3R-how-to-rerun.md §8）、
//      MySQL 3306（qa/lib/db.mjs 的 MYSQL_* 环境变量）、jar 已构建、
//      JDK17 的 java 可执行（JAVA_BIN，默认 C:/Project/tools/jdk17/jdk-17.0.20.1+1/bin/java.exe）、
//      env OPS_SCAN_TOKEN 非空（verify 阶段要打 POST /api/ops/pair-scan）。
//
// 运行：node qa/f3r/expired-scan.mjs prepare
//       （重启后端，带 OPS_SCAN_TOKEN）
//       node qa/f3r/expired-scan.mjs verify
// 期望输出：prepare 打印夹具 X1–X4 的 id 与指纹；verify 打印 V1–V8 逐条 PASS/FAIL，
//          末行「P2-4 复验通过：过期 COMPLETED 既不重算也不新建 pair/通知，且对照仍能正常打分。」
//          落盘 qa/reports/f3r-expired-scan.{txt,json}、qa/reports/f3r-expired-prepare.txt、
//          qa/reports/f3r-expired-fixtures.json（供 expired-scan-startup.mjs 复用，属运行时产物不归档）；
//          2026-10-28 那轮的冻结归档见 qa/reports/f3r-2026-10-28-expired-{prepare,scan}.{txt,json}。
// 退出码：0 = 全部判据成立；1 = 有失败项。
// 注意：prepare 与 verify 必须按顺序、同一套夹具跑；单独 verify 会读不到 fixtures.json 而报错。

import { readFile, writeFile, stat } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Api, isOk, brief } from '../lib/client.mjs';
import { contexts } from '../lib/auth.mjs';
import { sql, val, rows, upsertPair, pairByEnds, resetSeed, waitUntil } from '../lib/db.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..', '..');
const API = process.env.FINDIT_API_BASE ?? 'http://localhost:18080';
const LOG = process.env.FINDIT_SERVER_LOG ?? resolve(root, 'qa', 'reports', 'server.log');
const TOKEN = process.env.OPS_SCAN_TOKEN ?? '';
const FIXTURES = resolve(root, 'qa', 'reports', 'f3r-expired-fixtures.json');
const RETENTION_DAYS = 30;
const STALE_MINUTES = 10;

const phase = process.argv[2];
if (!['prepare', 'verify'].includes(phase)) {
  console.error('用法：node qa/f3r/expired-scan.mjs prepare|verify');
  process.exit(2);
}

const lines = [];
const results = [];
const say = (s) => {
  lines.push(s);
  console.log(s);
};
function record(name, pass, detail) {
  results.push({ name, pass, detail: [].concat(detail) });
  say(`${pass ? 'PASS' : 'FAIL'}  ${name}`);
  for (const d of [].concat(detail)) say(`        ${d}`);
}

async function create(api, type, title, description) {
  const res = await api.post('/api/records', {
    body: { type, title, description, location: '图书馆三楼', occurredAt: '2026-09-20 09:00:00', contact: '13900000001' },
  });
  if (!isOk(res)) throw new Error(`创建失败 ${title}：${brief(res)}`);
  return res.body.data.id;
}

/** 记录行的扫描相关指纹（pair_state/pair_scored_at 是 markPairScoring 的唯一痕迹）。 */
async function scanFingerprint(id) {
  const r = await rows(
    `SELECT status, pair_state,
            IFNULL(DATE_FORMAT(pair_scored_at,'%Y-%m-%d %H:%i:%s.%f'),'NULL') AS scored_at,
            DATE_FORMAT(completed_at,'%Y-%m-%d %H:%i:%s') AS completed_at,
            DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f') AS updated_at
     FROM t_record WHERE id=${Number(id)};`,
    ['status', 'pair_state', 'scored_at', 'completed_at', 'updated_at'],
  );
  return r[0] ?? null;
}
const pairEndsCount = async (ids) =>
  Number(await val(`SELECT COUNT(*) FROM t_pair_score WHERE lost_id IN (${ids}) OR found_id IN (${ids});`));
const notifCount = async (ids) =>
  Number(await val(
    `SELECT COUNT(*) FROM t_notification n JOIN t_pair_score p ON p.id=n.pair_id WHERE p.lost_id IN (${ids}) OR p.found_id IN (${ids});`,
  ));

// ============================================================================= prepare
if (phase === 'prepare') {
  say('# F3-R 项7 · prepare 阶段（造过期扫描夹具）· ' + new Date().toISOString());
  await resetSeed();
  say('- resetSeed() 已执行，库回到 seed 初始态（计数可复现）');

  const ctx = await contexts(API);
  const ids = {};
  ids.X1 = await create(ctx.demo01, 'LOST', 'QA F3R-X1 过期COMPLETED+DEGRADED 黑色钱包', '在图书馆三楼丢失的黑色钱包，内有卡包与钥匙。');
  ids.X2 = await create(ctx.demo01, 'LOST', 'QA F3R-X2 过期COMPLETED+PENDING 黑色钱包', '在图书馆三楼丢失的黑色钱包，内有银行卡卡包。');
  ids.X3 = await create(ctx.demo01, 'LOST', 'QA F3R-X3 保留期内COMPLETED 控制组', '在图书馆三楼丢失的黑色钱包，控制组应当被扫描。');
  ids.X4 = await create(ctx.demo02, 'LOST', 'QA F3R-X4 PENDING+DEGRADED 控制组', '在图书馆三楼丢失的黑色钱包，PENDING 状态不受保留期约束。');
  ids.C1 = await create(ctx.demo02, 'FOUND', 'QA F3R-C1 拾到黑色钱包 图书馆三楼', '图书馆三楼拾到黑色钱包，内有卡包。');
  ids.C2 = await create(ctx.demo01, 'FOUND', 'QA F3R-C2 拾到黑色钱包与卡包', '图书馆三楼拾到黑色钱包和卡包，可联系领取。');
  say(`- 夹具：X1=${ids.X1} X2=${ids.X2} X3=${ids.X3} X4=${ids.X4}（对照） C1=${ids.C1} C2=${ids.C2}（对手端）`);

  // 创建接口本身会异步触发一轮打分（publishPairScoring）。必须先等它落地，再置夹具状态；
  // 否则这条后台写入会盖在 UPDATE 之后，pair_state/pair_scored_at 就不是我设的值，
  // 「扫描后不变」的判据也会变成假证据。
  await waitUntil(async () => {
    const states = await rows(
      `SELECT id, pair_state FROM t_record WHERE id IN (${Object.values(ids).join(',')});`,
      ['id', 'pair_state'],
    );
    return states.length === 6 && states.every((s) => s.pair_state === 'READY' || s.pair_state === 'DEGRADED') ? states : null;
  }, { timeout: 40000, label: '创建路径的异步打分全部落地' });
  say('- 已等待创建路径的异步打分落地（READY/DEGRADED），之后写入的夹具状态不会被它覆盖');

  const expired = `DATE_SUB(NOW(), INTERVAL ${RETENTION_DAYS + 1} DAY)`;
  await sql(`UPDATE t_record SET status='COMPLETED', completed_at=${expired}, pair_state='DEGRADED', pair_scored_at=${expired}, updated_at=${expired} WHERE id=${ids.X1};`);
  await sql(`UPDATE t_record SET status='COMPLETED', completed_at=${expired}, pair_state='PENDING', pair_scored_at=NULL, updated_at=${expired} WHERE id=${ids.X2};`);
  await sql(`UPDATE t_record SET status='COMPLETED', completed_at=DATE_SUB(NOW(), INTERVAL 1 DAY), pair_state='PENDING', pair_scored_at=NULL, updated_at=DATE_SUB(NOW(), INTERVAL 1 DAY) WHERE id=${ids.X3};`);
  await sql(`UPDATE t_record SET pair_state='DEGRADED', pair_scored_at=DATE_SUB(NOW(), INTERVAL 2 HOUR) WHERE id=${ids.X4};`);
  say(`- X1/X2 置为 COMPLETED 且 completed_at = now-${RETENTION_DAYS + 1}d（超过保留期 ${RETENTION_DAYS}d）；pair_state 分别为 DEGRADED / PENDING`);
  say('- X3 为保留期内 COMPLETED（now-1d, PENDING）、X4 为 PENDING+DEGRADED —— 两者是「扫描确实在跑」的正对照');

  // 预先存在的一行 pair：哈希故意用旧值（upsertPair 按当前内容算哈希，所以这里改完内容再写一次，
  // 然后把 lost_hash 人为改成另一个值，模拟「内容没变但曾被打过」的历史行）。
  await upsertPair({ lostId: ids.X1, foundId: ids.C1, score: 0.8200, status: 'MATCHED', engine: 'LOCAL_KEYWORD', lostConfirmedAt: '2026-09-25 10:00:00', foundConfirmedAt: '2026-09-25 10:05:00' });
  const staleHash = 'f'.repeat(64);
  await sql(`UPDATE t_pair_score SET lost_hash='${staleHash}', updated_at=updated_at WHERE lost_id=${ids.X1} AND found_id=${ids.C1};`);
  const pre = await pairByEnds(ids.X1, ids.C1);
  say(`- 预置 pair 行 id=${pre.id} score=${pre.score} status=${pre.status} lost_hash=${pre.lost_hash.slice(0, 12)}…（故意与当前内容不一致：若扫描真的重算了 X1，哈希与 updated_at 一定会变）`);

  const snap = {
    expiredPairs: await pairEndsCount(`${ids.X1},${ids.X2}`),
    expiredNotifs: await notifCount(`${ids.X1},${ids.X2}`),
    X1: await scanFingerprint(ids.X1),
    X2: await scanFingerprint(ids.X2),
    X3: await scanFingerprint(ids.X3),
    X4: await scanFingerprint(ids.X4),
    prePair: pre,
    totalPairRows: Number(await val('SELECT COUNT(*) FROM t_pair_score;')),
    totalNotifRows: Number(await val('SELECT COUNT(*) FROM t_notification;')),
    logOffset: (await stat(LOG)).size,
  };
  await writeFile(FIXTURES, JSON.stringify({ ids, snap, createdAt: new Date().toISOString() }, null, 2), 'utf8');
  say(`- 快照已写 ${FIXTURES.replace(/\\/g, '/')}`);
  say(`  过期端 pair 行数=${snap.expiredPairs} 通知数=${snap.expiredNotifs}；X1 pair_state=${snap.X1.pair_state} scored_at=${snap.X1.scored_at}`);
  say(`  对照 X3 pair_state=${snap.X3.pair_state}、X4 pair_state=${snap.X4.pair_state}`);
  say(`  日志偏移：${LOG} 当前 ${snap.logOffset} 字节（verify 只读这之后的新增部分）`);
  say('');
  say('下一步：带 OPS_SCAN_TOKEN 重启后端（启动扫描 + 手动扫描两条触发都要），再跑 verify。');
  await writeFile(resolve(root, 'qa', 'reports', 'f3r-expired-prepare.txt'), lines.join('\n') + '\n', 'utf8');
  process.exit(0);
}

// ============================================================================= verify
say('# F3-R 项7 · verify 阶段（触发扫描并取证）· ' + new Date().toISOString());
const { ids, snap } = JSON.parse(await readFile(FIXTURES, 'utf8'));
const EXP = `${ids.X1},${ids.X2}`;
const CTRL = `${ids.X3},${ids.X4}`;
say(`- 夹具：X1=${ids.X1}(过期+DEGRADED) X2=${ids.X2}(过期+PENDING) ｜ 对照 X3=${ids.X3} X4=${ids.X4} ｜ 对手端 C1=${ids.C1} C2=${ids.C2}`);

// ① 启动扫描（PairScanOnceRunner）取证：读重启后新增的日志
const logStat = await stat(LOG);
const logText = new TextDecoder('gbk').decode((await readFile(LOG)).subarray(snap.logOffset, logStat.size));
const startupLine = logText.split('\n').filter((l) => /pair scan once done|pair scan done trigger=startup/.test(l));
say('');
say('## ① 启动扫描（PairScanOnceRunner，需启动参数 --pair-scan-once；未带参数时该触发点不会跑）');
for (const l of startupLine) say(`  ${l.trim()}`);
if (startupLine.length === 0) say('  （未捕获到 trigger=startup 的汇总行 —— 见下方日志原文）');

// ② 手动扫描：ops 接口
say('');
say('## ② POST /api/ops/pair-scan（X-Ops-Token 认证）');
const opsApi = new Api(API);
const noToken = await opsApi.post('/api/ops/pair-scan');
say(`- 不带 token：HTTP ${noToken.status} / code=${noToken.body?.code}（未配置或无 token 都应是 404，不暴露入口存在）`);
if (!TOKEN) {
  say('');
  say('## 中止：OPS_SCAN_TOKEN 未设置，无法触发手动扫描');
  say('- 启动扫描（①）的取证仍然有效，但本轮不把它当完整结论。');
  await writeFile(resolve(root, 'qa', 'reports', 'f3r-expired-scan.txt'), lines.join('\n') + '\n', 'utf8');
  process.exit(2);
}
const scan1 = await opsApi.post('/api/ops/pair-scan', { headers: { 'X-Ops-Token': TOKEN }, body: '' });
say(`- 第 1 次：${brief(scan1)}`);
say(`- 结果：${JSON.stringify(scan1.body?.data)}`);
const scan2 = await opsApi.post('/api/ops/pair-scan', { headers: { 'X-Ops-Token': TOKEN }, body: '' });
say(`- 第 2 次（幂等对照）：${JSON.stringify(scan2.body?.data)}`);

await waitUntil(async () => {
  const s = await scanFingerprint(ids.X3);
  return s.pair_state === 'READY' || s.pair_state === 'DEGRADED' ? s : null;
}, { timeout: 30000, label: '对照 X3 打分落库' }).catch(() => null);

const after = {
  X1: await scanFingerprint(ids.X1),
  X2: await scanFingerprint(ids.X2),
  X3: await scanFingerprint(ids.X3),
  X4: await scanFingerprint(ids.X4),
  expiredPairs: await pairEndsCount(EXP),
  expiredNotifs: await notifCount(EXP),
  ctrlPairs: await pairEndsCount(CTRL),
  ctrlNotifs: await notifCount(CTRL),
  prePair: await pairByEnds(ids.X1, ids.C1),
  totalPairRows: Number(await val('SELECT COUNT(*) FROM t_pair_score;')),
  totalNotifRows: Number(await val('SELECT COUNT(*) FROM t_notification;')),
};
const logAfter = new TextDecoder('gbk').decode((await readFile(LOG)).subarray(snap.logOffset, (await stat(LOG)).size));
const logLinesAll = logAfter.split('\n').map((l) => l.trim()).filter(Boolean);
const scoredOf = (id) => logLinesAll.filter((l) => new RegExp(`pair record scored recordId=${id}\\b`).test(l));
const writtenOf = (id) => logLinesAll.filter((l) => /pair score written/.test(l) && (l.includes(`lostId=${id}`) || l.includes(`foundId=${id}`)));

say('');
say('## ③ EV-SQL 对照（扫描前 → 扫描后）');
const showFp = (tag, a, b) => `${tag}：pair_state ${a.pair_state}→${b.pair_state}，pair_scored_at ${a.scored_at}→${b.scored_at}，updated_at ${a.updated_at}→${b.updated_at}`;
say(`- ${showFp('X1（过期+DEGRADED）', snap.X1, after.X1)}`);
say(`- ${showFp('X2（过期+PENDING）', snap.X2, after.X2)}`);
say(`- ${showFp('X3（保留期内，对照）', snap.X3, after.X3)}`);
say(`- ${showFp('X4（PENDING+DEGRADED，对照）', snap.X4, after.X4)}`);
say(`- 端点为过期记录的 pair 行数：${snap.expiredPairs} → ${after.expiredPairs}`);
say(`- 端点为过期记录的通知数：${snap.expiredNotifs} → ${after.expiredNotifs}`);
say(`- 端点为对照记录的 pair 行数：0 → ${after.ctrlPairs}；通知 0 → ${after.ctrlNotifs}（对照必须会动，否则扫描等于没跑）`);
say(`- 全表 pair 行数：${snap.totalPairRows} → ${after.totalPairRows}；通知总数：${snap.totalNotifRows} → ${after.totalNotifRows}`);
say(`- 预置 pair 行：score ${snap.prePair.score}→${after.prePair.score}，status ${snap.prePair.status}→${after.prePair.status}，` +
  `lost_hash ${snap.prePair.lost_hash.slice(0, 12)}…→${after.prePair.lost_hash.slice(0, 12)}…，updated_at ${snap.prePair.updated_at}→${after.prePair.updated_at}`);

say('');
say('## ④ 后端日志原文（本轮重启后新增，含启动扫描与两次手动扫描）');
for (const l of logLinesAll.filter((x) => /pair scan|pair record scored|pair score (written|skipped)|PairScan/i.test(x)).slice(0, 40)) say(`  ${l}`);

say('');
say('## ⑤ SQL 层反证：同一个 WHERE，修复前 / 修复后分别选中谁');
const cutoffExpr = `DATE_SUB(NOW(), INTERVAL ${RETENTION_DAYS} DAY)`;
const staleExpr = `DATE_SUB(NOW(), INTERVAL ${STALE_MINUTES} MINUTE)`;
const oldSql = `SELECT GROUP_CONCAT(id ORDER BY id) FROM t_record r
  WHERE r.deleted_at IS NULL AND ( (r.pair_state IN ('PENDING','SCORING') AND (r.pair_scored_at IS NULL OR r.pair_scored_at < ${staleExpr})) OR r.pair_state='DEGRADED' )`;
const newSql = `SELECT GROUP_CONCAT(id ORDER BY id) FROM t_record r
  WHERE r.deleted_at IS NULL
    AND (r.status='PENDING' OR (r.status='COMPLETED' AND r.completed_at > ${cutoffExpr}))
    AND ( (r.pair_state IN ('PENDING','SCORING') AND (r.pair_scored_at IS NULL OR r.pair_scored_at < ${staleExpr})) OR r.pair_state='DEGRADED' )`;
const oldTargets = (await val(oldSql)) ?? '';
const newTargets = (await val(newSql)) ?? '';
const oldHasX = [ids.X1, ids.X2].filter((i) => oldTargets.split(',').includes(String(i)));
const newHasX = [ids.X1, ids.X2].filter((i) => newTargets.split(',').includes(String(i)));
const newHasCtrl = [ids.X3, ids.X4].filter((i) => newTargets.split(',').includes(String(i)));
say(`- 修复前条件选中的目标（含本轮全部夹具）：${oldTargets || '（空）'}`);
say(`- 修复后条件选中的目标：${newTargets || '（空）'}`);
say(`- 过期夹具在「修复前」被选中：${oldHasX.length} / 2；在「修复后」被选中：${newHasX.length} / 2`);
say(`- 对照夹具在「修复后」仍被选中：${newHasCtrl.length} / 2（${CTRL} = ${CTRL ? 'X3,X4' : ''}）`);

// ------------------------------------------------------------------ 判据
say('');
say('## 判据');
record('V1 过期夹具 X1/X2 未被重算：pair_state 与 pair_scored_at 一字不变（连 markPairScoring 都没进）',
  after.X1.pair_state === snap.X1.pair_state && after.X1.scored_at === snap.X1.scored_at && after.X1.updated_at === snap.X1.updated_at &&
  after.X2.pair_state === snap.X2.pair_state && after.X2.scored_at === snap.X2.scored_at && after.X2.updated_at === snap.X2.updated_at,
  [showFp('X1', snap.X1, after.X1), showFp('X2', snap.X2, after.X2)]);

record('V2 未新建 pair：端点为 X1/X2 的 t_pair_score 行数不增',
  after.expiredPairs === snap.expiredPairs,
  [`${snap.expiredPairs} → ${after.expiredPairs}（预置那一行仍为 ${after.expiredPairs} 行，无新增）`]);

record('V3 预置 pair 行未被重算覆盖：score/status/lost_hash/updated_at 全部不变',
  after.prePair.score === snap.prePair.score && after.prePair.status === snap.prePair.status &&
  after.prePair.lost_hash === snap.prePair.lost_hash && after.prePair.updated_at === snap.prePair.updated_at,
  [`旧：${JSON.stringify({ score: snap.prePair.score, status: snap.prePair.status, hash: snap.prePair.lost_hash.slice(0, 16), at: snap.prePair.updated_at })}`,
    `新：${JSON.stringify({ score: after.prePair.score, status: after.prePair.status, hash: after.prePair.lost_hash.slice(0, 16), at: after.prePair.updated_at })}`]);

record('V4 未发通知：端点为 X1/X2 的通知数扫描前后都为 0',
  snap.expiredNotifs === 0 && after.expiredNotifs === 0,
  [`过期端通知：${snap.expiredNotifs} → ${after.expiredNotifs}`,
    `全表通知：${snap.totalNotifRows} → ${after.totalNotifRows}`,
    `对照端（X3/X4）通知：${after.ctrlNotifs} —— 保留期内允许新增，作为「通知通路本身还活着」的旁证`]);

record('V5 日志里没有 X1/X2 的打分痕迹（没有 record scored、也没有 written）',
  scoredOf(ids.X1).length === 0 && scoredOf(ids.X2).length === 0 && writtenOf(ids.X1).length === 0 && writtenOf(ids.X2).length === 0,
  [`record scored X1=${scoredOf(ids.X1).length} X2=${scoredOf(ids.X2).length}；pair score written X1=${writtenOf(ids.X1).length} X2=${writtenOf(ids.X2).length}`]);

record('V6 正对照成立：X3（保留期内 COMPLETED）与 X4（PENDING+DEGRADED）确实被扫到',
  (after.X3.pair_state === 'READY' || after.X3.pair_state === 'DEGRADED') && (after.X4.pair_state === 'READY' || after.X4.pair_state === 'DEGRADED'),
  [`X3 pair_state ${snap.X3.pair_state} → ${after.X3.pair_state}，scored_at ${snap.X3.scored_at} → ${after.X3.scored_at}`,
    `X4 pair_state ${snap.X4.pair_state} → ${after.X4.pair_state}，scored_at ${snap.X4.scored_at} → ${after.X4.scored_at}`,
    `日志：record scored X3=${scoredOf(ids.X3).length} 行、X4=${scoredOf(ids.X4).length} 行`,
    scoredOf(ids.X3)[0] ?? '', scoredOf(ids.X4)[0] ?? ''].filter(Boolean));

record('V7 SQL 层反证：修复前的 WHERE 会选中 X1/X2，修复后的不会，而对照仍在',
  oldHasX.length === 2 && newHasX.length === 0 && newHasCtrl.length === 2,
  [`修复前选中：${oldTargets || '（空）'}`, `修复后选中：${newTargets || '（空）'}`,
    `X1/X2 命中修复后条件：${newHasX.length}；X3/X4 命中：${newHasCtrl.length}`]);

const expiredInDb = Number(await val(
  `SELECT COUNT(*) FROM t_record WHERE deleted_at IS NULL AND status='COMPLETED' AND completed_at <= ${cutoffExpr};`,
));
const reportedExpired = scan1.body?.data?.expired;
record('V8 ops 返回的 expired 计数与库内过期 COMPLETED 行数一致（只计数、不改写任何列）',
  Number(reportedExpired) === expiredInDb && expiredInDb >= 2,
  [`ops 响应 expired=${reportedExpired}，EV-SQL 实数=${expiredInDb}（含 X1/X2）`,
    `扫描后 X1/X2 仍为 status=COMPLETED、completed_at=${after.X1.completed_at}，未被任何清扫改写`]);

const failed = results.filter((r) => !r.pass);
say('');
say('## 结论');
say(`- 断言项 ${results.length}，失败 ${failed.length}`);
say(`- ${failed.length === 0 ? 'P2-4 复验通过：过期 COMPLETED 既不重算也不新建 pair/通知，且对照仍能正常打分。' : `未通过：${failed.map((f) => f.name).join('；')}`}`);

await writeFile(resolve(root, 'qa', 'reports', 'f3r-expired-scan.json'), JSON.stringify({ ids, snap, after, verdicts: results, scan1: scan1.body?.data, scan2: scan2.body?.data, oldTargets, newTargets }, null, 2), 'utf8');
await writeFile(resolve(root, 'qa', 'reports', 'f3r-expired-scan.txt'), lines.join('\n') + '\n', 'utf8');
say('证据：qa/reports/f3r-expired-scan.txt / .json');
process.exit(failed.length === 0 ? 0 : 1);
