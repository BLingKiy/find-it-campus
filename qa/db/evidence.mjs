// v2 · F1 真实落库取证（MySQL 直查）
//
// 在 E2E 走查之后执行：E2E 会用 demo01 通过 UI 发布一条记录、把 seed 的 10 号标记完成、
// 并通过 pair 卡片查看一次联系方式。这里直接查库核对，不采信接口自述。
//
// 前置：先跑 node qa/e2e/walkthrough.mjs（生成 qa/reports/e2e-created.json）
// 运行：node qa/db/evidence.mjs

import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { columnNames, DB, MYSQL, q, sql, tableNames, val } from '../lib/db.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const created = JSON.parse(await readFile(resolve(here, '..', 'reports', 'e2e-created.json'), 'utf8'));
if (!created.id) {
  console.error('未找到 e2e-created.json 里的记录 id，请先跑 node qa/e2e/walkthrough.mjs');
  process.exit(2);
}

const blocks = [];
const checks = [];
const block = (title, statement, opts = {}) => blocks.push({ title, statement, opts });
const check = (name, ok, detail) => checks.push({ name, ok, detail });

block('① 七张表的行数（v2 数据面）', `
SELECT 't_user' AS table_name, COUNT(*) AS rows_count FROM t_user
UNION ALL SELECT 't_category', COUNT(*) FROM t_category
UNION ALL SELECT 't_record', COUNT(*) FROM t_record
UNION ALL SELECT 't_match_result', COUNT(*) FROM t_match_result
UNION ALL SELECT 't_pair_score', COUNT(*) FROM t_pair_score
UNION ALL SELECT 't_notification', COUNT(*) FROM t_notification
UNION ALL SELECT 't_access_log', COUNT(*) FROM t_access_log;`);

block('② t_record 三态分布与 completed_at 不变量', `
SELECT status, COUNT(*) AS cnt, SUM(completed_at IS NOT NULL) AS with_completed_at
FROM t_record GROUP BY status ORDER BY status;`);

block(`③ E2E 通过 UI 提交的记录（id=${created.id}）在 MySQL 中的整行`, `
SELECT id, type, status, title, LEFT(description, 50) AS description_head, category_id, location,
       occurred_at, contact, publisher_name, publisher_id,
       IFNULL(DATE_FORMAT(deleted_at, '%Y-%m-%d %H:%i:%s'), 'NULL') AS deleted_at,
       created_at, updated_at
FROM t_record WHERE id = ${Number(created.id)};`, { vertical: true });

block('④ 记录 10 的完成状态（E2E 点「标记为已完成」的真实落库）', `
SELECT id, status, completed_at, updated_at FROM t_record WHERE id = 10;`);

block('⑤ 该记录的分类关联（真实 category_id，不是前端硬编码文案）', `
SELECT r.id, r.title, r.category_id, c.code, c.name
FROM t_record r LEFT JOIN t_category c ON c.id = r.category_id
WHERE r.id = ${Number(created.id)};`);

block('⑥ 默认公共列表 SQL 口径（PENDING + 未删除）应能看到该记录', `
SELECT COUNT(*) AS hit FROM t_record
WHERE deleted_at IS NULL AND status = 'PENDING'
  AND (title LIKE ${q('%' + created.title + '%')} OR description LIKE ${q('%' + created.title + '%')});`);

block('⑦ t_pair_score 唯一键与四态：无重复 (lost,found)、无「已解决」态', `
SELECT status, COUNT(*) AS cnt FROM t_pair_score GROUP BY status ORDER BY status;
SELECT COUNT(*) AS rows_count, COUNT(DISTINCT lost_id, found_id) AS distinct_pairs FROM t_pair_score;`);

block('⑧ t_pair_score 的确认/授权不变量（两端均确认 ⇔ MATCHED）', `
SELECT id, lost_id, found_id, score, status,
       IFNULL(DATE_FORMAT(lost_confirmed_at,'%Y-%m-%d %H:%i:%s'),'NULL') AS lost_confirmed_at,
       IFNULL(DATE_FORMAT(found_confirmed_at,'%Y-%m-%d %H:%i:%s'),'NULL') AS found_confirmed_at
FROM t_pair_score WHERE status IN ('MATCHED','IGNORED') ORDER BY id;`);

block('⑨ 匹配快照落库（t_match_result）与候选脱敏', `
SELECT COUNT(*) AS snapshots FROM t_match_result;
SELECT id, impl, IFNULL(input_type,'NULL') AS input_type, top_n, min_score, cost_ms, created_at,
       LEFT(candidates, 160) AS candidates_head
FROM t_match_result ORDER BY id DESC LIMIT 1;`, { vertical: true });

block('⑩ 联系方式查看审计（append-only；无 contact/note 列）', `
SELECT id, pair_id, viewer_id, viewer_side, target_side, score, result, request_id, created_at
FROM t_access_log ORDER BY id DESC LIMIT 10;`, { vertical: true });

block('⑪ t_user 不存手机号；通知正文不含号码', `
SELECT column_name FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 't_user' ORDER BY ordinal_position;
SELECT COUNT(*) AS notifications_with_phone FROM t_notification WHERE title REGEXP '1[3-9][0-9]{9}' OR body REGEXP '1[3-9][0-9]{9}';`);

// ---------------------------------------------------------------- 判定

const row = await sql(
  `SELECT id, title, status, category_id, contact, publisher_name, publisher_id, IFNULL(deleted_at,'NULL') FROM t_record WHERE id = ${Number(created.id)};`,
);
const cols = row.trim().split('\t');
check('E2E 记录存在于 t_record（不是 sessionStorage）', row.trim().length > 0, row.trim());
check('标题与 UI 提交内容一致', cols[1] === created.title, `DB=${cols[1]} / UI=${created.title}`);
check('状态为 PENDING（发布初始态，系统未自动置位）', cols[2] === 'PENDING', `status=${cols[2]}`);
check('分类真实写入 category_id=5', cols[3] === '5', `category_id=${cols[3]}`);
check('联系方式 / 联系人 / 发布者落库', cols[4] === '13800001234' && cols[5] === '端测测' && cols[6] === '1', `contact=${cols[4]} publisher=${cols[5]} publisher_id=${cols[6]}`);
check('未被逻辑删除', cols[7] === 'NULL', `deleted_at=${cols[7]}`);

const desc = await val(`SELECT CHAR_LENGTH(description) FROM t_record WHERE id = ${Number(created.id)};`);
check('描述按 UTF-8 完整落库（长度 > 0）', Number(desc) > 0, `CHAR_LENGTH=${desc}`);

const rec10 = await sql("SELECT CONCAT_WS('|', status, IFNULL(DATE_FORMAT(completed_at,'%Y-%m-%d %H:%i:%s'),'NULL')) FROM t_record WHERE id=10;");
const [status10, completedAt10] = rec10.trim().split('|');
check('E2E 标记完成已落库（10 号 COMPLETED + completed_at 非空）', status10 === 'COMPLETED' && completedAt10 !== 'NULL', rec10.trim());

const tables = await tableNames();
check('数据库恰好 7 张表', tables.length === 7, tables.join(','));

const listTotal = Number(await val("SELECT COUNT(*) FROM t_record WHERE status='PENDING' AND deleted_at IS NULL;"));
check('公共列表（PENDING + 未删除）总数为 21', listTotal === 21, `PENDING=${listTotal}`);

const listHit = Number(await val(
  `SELECT COUNT(*) FROM t_record WHERE status='PENDING' AND deleted_at IS NULL AND title LIKE ${q('%' + created.title + '%')};`,
));
check('E2E 记录在公共列表 SQL 口径内', listHit === 1, `hit=${listHit}`);

const snapshots = Number(await val('SELECT COUNT(*) FROM t_match_result;'));
const snapImpl = await val('SELECT impl FROM t_match_result ORDER BY id DESC LIMIT 1;');
check('匹配快照有行且 impl=LOCAL_KEYWORD', snapshots > 0 && snapImpl === 'LOCAL_KEYWORD', `snapshots=${snapshots} impl=${snapImpl}`);

const phoneInCandidates = Number(await val(
  `SELECT COUNT(*) FROM t_match_result WHERE candidates REGEXP '1[3-9][0-9]{9}' AND input_text NOT REGEXP '1[3-9][0-9]{9}';`,
));
check('快照候选 JSON 未出现手机号（输入本身含号码的除外）', phoneInCandidates === 0, `hits=${phoneInCandidates}`);

const granted = Number(await val("SELECT COUNT(*) FROM t_access_log WHERE pair_id=1 AND result='GRANTED';"));
check('DIRECT 档取号写入 GRANTED 审计（pair 1）', granted >= 1, `GRANTED=${granted}`);

const accessCols = await columnNames('t_access_log');
check('t_access_log 无 contact/note 列（审计不复制敏感值）', !accessCols.includes('contact') && !accessCols.includes('note'), accessCols.join(','));

const pairRows = Number(await val('SELECT COUNT(*) FROM t_pair_score;'));
const pairDistinct = Number(await val('SELECT COUNT(DISTINCT lost_id, found_id) FROM t_pair_score;'));
check('t_pair_score 行数 = 唯一 (lost,found) 对数', pairRows === pairDistinct && pairRows > 0, `rows=${pairRows} distinct=${pairDistinct}`);

const statuses = (await sql('SELECT GROUP_CONCAT(DISTINCT status ORDER BY status) FROM t_pair_score;')).trim().split(',');
check('pair 状态只有 NEW/CONTACTED/MATCHED/IGNORED（无已解决）', statuses.every((s) => ['NEW', 'CONTACTED', 'MATCHED', 'IGNORED'].includes(s)), statuses.join(','));

const completedBad = Number(await val(
  "SELECT COUNT(*) FROM t_record WHERE (status='COMPLETED' AND completed_at IS NULL) OR (status<>'COMPLETED' AND completed_at IS NOT NULL);",
));
check('t_record 的 status/completed_at 双向绑定', completedBad === 0, `violations=${completedBad}`);

const userCols = await columnNames('t_user');
check('t_user 无 phone/mobile/contact 列', !['phone', 'mobile', 'contact'].some((c) => userCols.includes(c)), userCols.join(','));

// ---------------------------------------------------------------- 输出

const output = [];
output.push('# v2 · F1 真实落库取证（MySQL 直查）');
output.push('');
output.push(`- 生成时间：${new Date().toISOString()}`);
output.push(`- 客户端：${MYSQL} · 库：${DB}`);
output.push(`- 取证对象：E2E 通过 UI 创建的记录 id=${created.id}（标题「${created.title}」）`);
output.push('');

for (const b of blocks) {
  const text = await sql(b.statement, b.opts);
  output.push(`## ${b.title}`);
  output.push('');
  output.push('```sql');
  output.push(b.statement);
  output.push('```');
  output.push('```');
  output.push(text.trim() || '(空)');
  output.push('```');
  output.push('');
}

output.push('## 判定');
output.push('');
output.push('| 检查项 | 结果 | 证据 |');
output.push('|---|---|---|');
for (const c of checks) output.push(`| ${c.name} | ${c.ok ? '通过' : '不通过'} | ${String(c.detail).replace(/\|/g, '\\|').slice(0, 160)} |`);
output.push('');

const failed = checks.filter((c) => !c.ok);
output.push(
  `**结论**：${failed.length === 0 ? '通过' : `不通过（${failed.length} 项）`} —— ` +
    `${checks.length - failed.length}/${checks.length} 项检查符合预期。数据来自 MySQL 直查，不采信接口自述。`,
);
output.push('');

await mkdir(resolve(here, '..', 'reports'), { recursive: true });
const path = resolve(here, '..', 'reports', 'db-evidence.txt');
await writeFile(path, output.join('\n'), 'utf8');
console.log(output.join('\n'));
console.log(`\n报告：${path}`);
process.exit(failed.length === 0 ? 0 : 1);
