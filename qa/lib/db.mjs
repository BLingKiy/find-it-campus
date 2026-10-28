// 直查 MySQL 的证据工具（EV-SQL）与等待工具。
//
// 为什么测试里允许直连库：
//   ① 有些断言只有库层能证明（t_record.status 零写入、unlocked 列不被重算重置、审计行数）；
//   ② 夹具需要「指定的 score /MATCHED 两端 confirmed_at」，走接口造不出来（契约本来就不让客户端直传）。
// 边界：直查只用于取证与夹具，不用它断言接口实现的 SQL 细节。

import { execFile, spawn } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));

export const MYSQL = process.env.MYSQL_BIN ?? 'C:/Project/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe';
export const DB = process.env.MYSQL_DB ?? 'find_it_campus';
const USER = process.env.MYSQL_USER ?? 'root';
const PASS = process.env.MYSQL_PASSWORD ?? '';

/** 普通 SQL：默认 -N -B（无表头、制表符分隔），便于脚本比对。 */
export function sql(statement, { vertical = false } = {}) {
  return new Promise((resolvePromise, reject) => {
    const args = ['-u', USER, '--default-character-set=utf8mb4'];
    if (vertical) args.push('-E');
    else args.push('-N', '-B');
    if (PASS) args.splice(2, 0, `-p${PASS}`);
    args.push(DB, '-e', statement);
    execFile(MYSQL, args, { encoding: 'buffer', maxBuffer: 8 * 1024 * 1024 }, (err, stdout, stderr) => {
      if (err) {
        reject(new Error(`mysql 执行失败：${statement}\n${stderr.toString('utf8')}`));
        return;
      }
      resolvePromise(stdout.toString('utf8').split(String.fromCharCode(13)).join(''));
    });
  });
}

export const q = (value) => {
  if (value === null || value === undefined) return 'NULL';
  if (typeof value === 'number') return String(value);
  return `'${String(value).replace(/\\/g, '\\\\').replace(/'/g, "''")}'`;
};

/** 单值查询：第一行第一列；没有行返回 null。 */
export async function val(statement) {
  const text = (await sql(statement)).trim();
  if (!text) return null;
  return text.split('\n')[0].split('\t')[0];
}

/** 多行结果 → 对象数组；列名按调用方给的顺序映射。 */
export async function rows(statement, columns) {
  const text = (await sql(statement)).trim();
  if (!text) return [];
  return text.split('\n').map((line) => {
    const cols = line.split('\t');
    return Object.fromEntries(columns.map((c, i) => [c, cols[i] === 'NULL' ? null : cols[i]]));
  });
}

export async function waitUntil(fn, { timeout = 15000, interval = 250, label = '条件' } = {}) {
  const started = Date.now();
  let last;
  while (Date.now() - started < timeout) {
    last = await fn();
    if (last) return last;
    await new Promise((r) => setTimeout(r, interval));
  }
  throw new Error(`${label}：${timeout}ms 内未满足（最后结果 ${JSON.stringify(last)}）`);
}

/** 等异步打分落库：pair_state 从 PENDING/SCORING 变为 READY/DEGRADED。 */
export function waitForScoring(recordIds) {
  const ids = [].concat(recordIds).join(',');
  return waitUntil(
    async () => {
      const text = (await val(`SELECT GROUP_CONCAT(CONCAT(id, ':', pair_state)) FROM t_record WHERE id IN (${ids});`)) ?? '';
      const states = text.split(',').filter(Boolean).map((x) => x.split(':')[1]);
      return states.length > 0 && states.every((s) => s === 'READY' || s === 'DEGRADED') ? states : null;
    },
    { timeout: 20000, label: `记录 ${ids} 的配对打分完成` },
  );
}

/** 记录状态/时间快照，用于「零写入」对照。 */
export async function recordSnapshot(id) {
  const text = (await val(
    `SELECT CONCAT_WS('|', status, IFNULL(DATE_FORMAT(completed_at, '%Y-%m-%d %H:%i:%s'), 'NULL'), DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s'), IFNULL(DATE_FORMAT(deleted_at, '%Y-%m-%d %H:%i:%s'), 'NULL')) FROM t_record WHERE id = ${Number(id)};`,
  )) ?? '';
  const [status, completedAt, updatedAt, deletedAt] = text.split('|');
  return { status, completedAt, updatedAt, deletedAt };
}

export async function fullRecordSnapshot(id) {
  const text = await sql(
    `SELECT status, IFNULL(completed_at,'NULL'), updated_at, IFNULL(deleted_at,'NULL'), title FROM t_record WHERE id = ${Number(id)};`,
  );
  return text.trim();
}

export async function record(id) {
  const list = await rows(
    `SELECT id, type, status, IFNULL(completed_at,'NULL') AS completed_at, title, contact, publisher_id, pair_state, IFNULL(deleted_at,'NULL') AS deleted_at FROM t_record WHERE id = ${Number(id)};`,
    ['id', 'type', 'status', 'completed_at', 'title', 'contact', 'publisher_id', 'pair_state', 'deleted_at'],
  );
  return list[0] ?? null;
}

const HASH_EXPR = (alias) =>
  `SHA2(CONCAT(TRIM(${alias}.title), CHAR(10), TRIM(${alias}.description), CHAR(10), TRIM(${alias}.location), CHAR(10), IFNULL(CAST(${alias}.category_id AS CHAR), ''), CHAR(10), ${alias}.type), 256)`;

/**
 * 造/重置一条 pair 夹具。哈希按 A1 §8.5 现算，和 seed 保持同一公式，
 * 这样后续 PUT 触发重算时「hash 未变/已变」是真实语义，不是人为造出来的。
 */
export async function upsertPair({
  lostId,
  foundId,
  score,
  status = 'NEW',
  engine = 'LOCAL_KEYWORD',
  degradedReason = null,
  lostUnlockedAt = null,
  foundUnlockedAt = null,
  lostNote = null,
  foundNote = null,
  lostConfirmedAt = null,
  foundConfirmedAt = null,
}) {
  await sql(`
INSERT INTO t_pair_score
  (lost_id, found_id, score, engine, degraded_reason, prompt_version,
   lost_hash, found_hash,
   lost_unlocked_at, found_unlocked_at, lost_unlock_note, found_unlock_note,
   lost_confirmed_at, found_confirmed_at, status, created_at, updated_at)
SELECT l.id, f.id, ${Number(score)}, ${q(engine)}, ${q(degradedReason)}, 'pair-score-v1',
       ${HASH_EXPR('l')}, ${HASH_EXPR('f')},
       ${q(lostUnlockedAt)}, ${q(foundUnlockedAt)}, ${q(lostNote)}, ${q(foundNote)},
       ${q(lostConfirmedAt)}, ${q(foundConfirmedAt)}, ${q(status)}, NOW(), NOW()
FROM t_record l JOIN t_record f ON l.id = ${Number(lostId)} AND f.id = ${Number(foundId)}
ON DUPLICATE KEY UPDATE
  score = VALUES(score), engine = VALUES(engine), degraded_reason = VALUES(degraded_reason),
  lost_hash = VALUES(lost_hash), found_hash = VALUES(found_hash),
  lost_unlocked_at = VALUES(lost_unlocked_at), found_unlocked_at = VALUES(found_unlocked_at),
  lost_unlock_note = VALUES(lost_unlock_note), found_unlock_note = VALUES(found_unlock_note),
  lost_confirmed_at = VALUES(lost_confirmed_at), found_confirmed_at = VALUES(found_confirmed_at),
  status = VALUES(status), updated_at = NOW();`);
}

export async function pairByEnds(lostId, foundId) {
  const list = await rows(
    `SELECT id, lost_id, found_id, score, engine, IFNULL(degraded_reason,'NULL') AS degraded_reason,
            status,
            IFNULL(DATE_FORMAT(lost_unlocked_at,'%Y-%m-%d %H:%i:%s'),'NULL') AS lost_unlocked_at,
            IFNULL(DATE_FORMAT(found_unlocked_at,'%Y-%m-%d %H:%i:%s'),'NULL') AS found_unlocked_at,
            IFNULL(lost_unlock_note,'NULL') AS lost_unlock_note,
            IFNULL(found_unlock_note,'NULL') AS found_unlock_note,
            IFNULL(DATE_FORMAT(lost_confirmed_at,'%Y-%m-%d %H:%i:%s'),'NULL') AS lost_confirmed_at,
            IFNULL(DATE_FORMAT(found_confirmed_at,'%Y-%m-%d %H:%i:%s'),'NULL') AS found_confirmed_at,
            lost_hash, found_hash,
            DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s') AS updated_at
     FROM t_pair_score WHERE lost_id=${Number(lostId)} AND found_id=${Number(foundId)};`,
    ['id', 'lost_id', 'found_id', 'score', 'engine', 'degraded_reason', 'status', 'lost_unlocked_at', 'found_unlocked_at',
      'lost_unlock_note', 'found_unlock_note', 'lost_confirmed_at', 'found_confirmed_at', 'lost_hash', 'found_hash', 'updated_at'],
  );
  return list[0] ?? null;
}

export async function pairCount(lostId, foundId) {
  return Number(await val(`SELECT COUNT(*) FROM t_pair_score WHERE lost_id=${Number(lostId)} AND found_id=${Number(foundId)};`));
}

export async function accessLogCount(pairId) {
  return Number(await val(`SELECT COUNT(*) FROM t_access_log WHERE pair_id=${Number(pairId)};`));
}

export async function accessLogResults(pairId) {
  return (await sql(`SELECT result FROM t_access_log WHERE pair_id=${Number(pairId)} ORDER BY id;`))
    .trim()
    .split('\n')
    .filter(Boolean);
}

export async function notificationCount(where) {
  return Number(await val(`SELECT COUNT(*) FROM t_notification WHERE ${where};`));
}

/** 把记录直接置为终态（仅用于夹具；接口路径的置位由 API 用例自己验证）。 */
export async function forceRecordStatus(id, status, completedAt = null) {
  await sql(
    `UPDATE t_record SET status=${q(status)}, completed_at=${q(completedAt)}, updated_at=updated_at WHERE id=${Number(id)};`,
  );
}

export async function columnNames(tableName) {
  return (await sql(
    `SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=${q(tableName)} ORDER BY ordinal_position;`,
  ))
    .trim()
    .split('\n')
    .filter(Boolean);
}

export async function tableNames() {
  return (await sql(
    "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() ORDER BY table_name;",
  ))
    .trim()
    .split('\n')
    .filter(Boolean);
}

export async function resetSeed() {
  const root = resolve(here, '..', '..');
  const run = (db, file) =>
    new Promise((res, rej) => {
      const child = spawn(MYSQL, db ? ['-u', USER, db, ...(PASS ? [`-p${PASS}`] : [])] : ['-u', USER, ...(PASS ? [`-p${PASS}`] : [])], {
        stdio: ['pipe', 'ignore', 'pipe'],
      });
      let err = '';
      child.stderr.on('data', (d) => (err += d));
      child.on('close', (code) => (code === 0 ? res() : rej(new Error(err || `mysql exit ${code}`))));
      child.stdin.end(file);
    });
  await run(null, await readFile(resolve(root, 'db', 'schema.sql')));
  await run(DB, await readFile(resolve(root, 'db', 'seed.sql')));
}

// ---------------------------------------------------------------- EV-LOG

const LOG_CANDIDATES = [
  process.env.FINDIT_SERVER_LOG,
  resolve(here, '..', 'reports', 'server.log'),
  resolve(here, '..', '..', 'server', 'target', 'server.log'),
].filter(Boolean);

/** 读取后端日志里某个 pair 的打分行；找不到日志时 available=false（由用例显式记录，不冒充有证据）。 */
export async function pairLog(lostId, foundId) {
  for (const path of LOG_CANDIDATES) {
    let text;
    try {
      text = await readFile(path, 'utf8');
    } catch {
      continue;
    }
    const lines = text
      .split('\n')
      .filter((line) => line.includes('pair score') && line.includes(`lostId=${lostId}`) && line.includes(`foundId=${foundId}`))
      .map((line) => line.trim());
    return { available: true, path, lines };
  }
  return { available: false, path: null, lines: [] };
}

/** EV-LOG 口径：日志里该 pair 的实际引擎写入次数；DEEPSEEK=真实 LLM 调用，LOCAL_KEYWORD=降级/本地（0 次调用）。 */
export function llmCallCount(lines) {
  return lines.filter((line) => line.includes('pair score written') && /engine=DEEPSEEK\b/.test(line)).length;
}
