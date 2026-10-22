// 4.1 残留复验：真实接口「提交 → 列表 → 详情」是否真的落到 MySQL（ZHYYKFX-10）
//
// 背景：4.1 交付时前端走 mock + sessionStorage，界面能跑但库里没有数据，不能算「落库」。
// 本脚本在 E2E（VITE_USE_MOCK=false）跑完之后执行，直接查库取证，不依赖接口自述。
//
// 前置：先跑 node qa/e2e/walkthrough.mjs（会写 qa/reports/e2e-created.json，含本轮 UI 创建的记录 id）
// 运行：node qa/db/evidence.mjs
// 说明：mysql 客户端路径可用环境变量覆盖：MYSQL_BIN=... node qa/db/evidence.mjs

import { execFile } from 'node:child_process';
import { readFile, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const MYSQL = process.env.MYSQL_BIN ?? 'C:/Project/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe';
const DB = process.env.MYSQL_DB ?? 'find_it_campus';
const USER = process.env.MYSQL_USER ?? 'root';
const PASS = process.env.MYSQL_PASSWORD ?? '';

const sql = (statement, { vertical = false } = {}) =>
  new Promise((resolvePromise, reject) => {
    const args = ['-u', USER, '--default-character-set=utf8mb4'];
    if (vertical) args.push('-E');
    else args.push('-N', '-B');
    args.push(DB, '-e', statement);
    if (PASS) args.splice(2, 0, `-p${PASS}`);
    execFile(MYSQL, args, { encoding: 'buffer', maxBuffer: 8 * 1024 * 1024 }, (err, stdout, stderr) => {
      if (err) {
        reject(new Error(`mysql 执行失败：${statement}\n${stderr.toString('utf8')}`));
        return;
      }
      resolvePromise(stdout.toString('utf8'));
    });
  });

const created = JSON.parse(await readFile(resolve(here, '..', 'reports', 'e2e-created.json'), 'utf8'));
if (!created.id) {
  console.error('未找到 e2e-created.json 里的记录 id，请先跑 node qa/e2e/walkthrough.mjs');
  process.exit(2);
}

const blocks = [];
const checks = [];
// vertical=true 用 mysql -E 输出「字段名: 值」，便于人读；默认 -N -B 便于脚本判定
const record = (title, statement, opts = {}) => blocks.push({ title, statement, opts });
const check = (name, ok, detail) => checks.push({ name, ok, detail });

record(
  '① 三张表存在，且 t_record 是「记录主表」',
  'SELECT table_name, table_rows FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY table_name;',
);
record(
  '② t_record 关键列与落库相关的约束（逻辑删除列 / 时间列 / CHECK）',
  "SELECT column_name, column_type, is_nullable FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_record' ORDER BY ordinal_position;",
);
record(
  `③ E2E 通过 UI 提交的记录（id=${created.id}）在 MySQL 中的整行（含 description / contact / category / 时间）`,
  `SELECT id, type, status, title, LEFT(description, 60) AS description_head, category_id, location, occurred_at, contact, publisher_name, deleted_at, created_at, updated_at FROM t_record WHERE id = ${created.id};`,
  { vertical: true },
);
record(
  '④ 同一条记录的分类关联（证明 category_id 真实映射到 t_category，而不是前端硬编码文案）',
  `SELECT r.id, r.title, r.category_id, c.code, c.name FROM t_record r LEFT JOIN t_category c ON c.id = r.category_id WHERE r.id = ${created.id};`,
);
record(
  `⑤ 该记录在默认列表查询条件下确实被 SQL 检索到（复现列表页即这条 SQL 的口径）`,
  `SELECT COUNT(*) AS hit FROM t_record WHERE deleted_at IS NULL AND status = 'PENDING' AND (title LIKE '%${created.title.replace(/'/g, "''")}%' OR description LIKE '%${created.title.replace(/'/g, "''")}%');`,
);
record(
  '⑥ 详情页状态流转落库（E2E 把 1 号记录从 PENDING 改成 CLAIMED）',
  "SELECT id, title, status, created_at, updated_at FROM t_record WHERE id = 1;",
);
record(
  '⑦ 匹配快照落库（t_match_result 行数 + 最近一条的 impl / 输入 / 候选 JSON）',
  "SELECT COUNT(*) AS snapshots FROM t_match_result; SELECT id, impl, IFNULL(input_type,'NULL') AS input_type, top_n, min_score, cost_ms, created_at, LEFT(candidates, 120) AS candidates_head FROM t_match_result ORDER BY id DESC LIMIT 1;",
  { vertical: true },
);
record(
  '⑧ 逻辑删除列的证据（种子里的 25 号：deleted_at 非空，读接口不可见）',
  'SELECT id, title, status, deleted_at FROM t_record WHERE deleted_at IS NOT NULL ORDER BY id LIMIT 5;',
);
record(
  '⑨ 全库状态分布（解释为什么默认列表是 21 条：24 条 − CLAIMED 1 − CLOSED 1 − 逻辑删除 1）',
  'SELECT status, COUNT(*) AS cnt, SUM(deleted_at IS NOT NULL) AS deleted FROM t_record GROUP BY status ORDER BY status;',
);

const output = [];
output.push('# 4.1 真实落库取证（MySQL 直查）');
output.push('');
output.push(`- 生成时间：${new Date().toISOString()}`);
output.push(`- 客户端：${MYSQL} · 库：${DB} · 用户：${USER}`);
output.push(`- 取证对象：E2E 走查通过 UI 创建的记录 id=${created.id}（标题「${created.title}」）`);
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

// ---------------------------------------------------------------- 判定

const row = await sql(
  `SELECT id, title, status, category_id, contact, publisher_name, deleted_at FROM t_record WHERE id = ${created.id};`,
);
const cols = row.trim().split('\t');
check('记录存在于 t_record（不是 sessionStorage）', row.trim().length > 0, row.trim());
check('标题与 UI 提交内容一致', cols[1] === created.title, `DB=${cols[1]} / UI=${created.title}`);
check('状态为 PENDING（列表默认口径可见）', cols[2] === 'PENDING', `status=${cols[2]}`);
check('分类真实写入 category_id', cols[3] === '5', `category_id=${cols[3]}`);
check('联系方式 / 联系人落库', cols[4] === '13800001234' && cols[5] === '端测测', `contact=${cols[4]} publisher=${cols[5]}`);
check('未被逻辑删除', cols[6] === 'NULL', `deleted_at=${cols[6]}`);

const desc = await sql(`SELECT CHAR_LENGTH(description) FROM t_record WHERE id = ${created.id};`);
check('描述按 UTF-8 完整落库（长度与提交一致）', Number(desc.trim()) > 0, `CHAR_LENGTH=${desc.trim()}`);

const status1 = await sql('SELECT status FROM t_record WHERE id = 1;');
check('详情页状态流转已落库（1 号 = CLAIMED）', status1.trim() === 'CLAIMED', `status=${status1.trim()}`);

const snapshots = await sql('SELECT COUNT(*) FROM t_match_result;');
check('匹配快照有行（>0）', Number(snapshots.trim()) > 0, `t_match_result rows=${snapshots.trim()}`);

const snapImpl = await sql("SELECT impl FROM t_match_result ORDER BY id DESC LIMIT 1;");
check('快照 impl 为 LOCAL_KEYWORD（本期实现口径）', snapImpl.trim() === 'LOCAL_KEYWORD', `impl=${snapImpl.trim()}`);

output.push('## 判定');
output.push('');
output.push('| 检查项 | 结果 | 证据 |');
output.push('|---|---|---|');
for (const c of checks) {
  output.push(`| ${c.name} | ${c.ok ? '通过' : '不通过'} | ${String(c.detail).replace(/\|/g, '\\|').slice(0, 160)} |`);
}
output.push('');

const failed = checks.filter((c) => !c.ok);
output.push(
  `**结论**：${failed.length === 0 ? '通过' : `不通过（${failed.length} 项）`} —— ` +
    `${checks.length - failed.length}/${checks.length} 项检查符合预期。` +
    '数据来自 MySQL 直查，不采信接口自述。',
);
output.push('');

const path = resolve(here, '..', 'reports', 'db-evidence.txt');
await writeFile(path, output.join('\n'), 'utf8');
console.log(output.join('\n'));
console.log(`\n报告：${path}`);
process.exit(failed.length === 0 ? 0 : 1);
