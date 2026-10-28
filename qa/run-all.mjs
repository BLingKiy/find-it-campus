// v2 · F1 全量回归入口：一条命令跑完接口 / 匹配评估 / 前端走查 / 落库取证 / 跨页一致性。
//
// 前置（不由本脚本启动，避免把「测试」和「怎么起服务」混在一起）：
//   1) MySQL 8 + db/schema.sql + db/seed.sql 的库（脚本会自己重置一次，结束再重置一次）
//   2) 后端 18080 已起
//   3) 前端 dev 5173 已起（web/.env.local: VITE_USE_MOCK=false）
//
// 运行：node qa/run-all.mjs
// 可选环境变量：MYSQL_BIN（默认取 db/README.md 里的本机路径）、FINDIT_API_BASE、WEB_BASE
//
// 顺序有依赖：E2E 会通过 UI 创建一条记录，db/evidence.mjs 要查这一条，所以取证必须排在 E2E 之后。

import { spawn } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..');
const MYSQL = process.env.MYSQL_BIN ?? 'C:/Project/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe';
const DB = process.env.MYSQL_DB ?? 'find_it_campus';
const USER = process.env.MYSQL_USER ?? 'root';
const API = process.env.FINDIT_API_BASE ?? 'http://localhost:18080';
const WEB = process.env.WEB_BASE ?? 'http://localhost:5173';

const log = [];
const out = (s) => {
  log.push(s);
  console.log(s);
};

const runSqlFile = (db, file) =>
  new Promise((res, rej) => {
    const child = spawn(MYSQL, db ? ['-u', USER, db] : ['-u', USER], { stdio: ['pipe', 'ignore', 'pipe'] });
    let err = '';
    child.stderr.on('data', (d) => (err += d));
    child.on('close', (code) => (code === 0 ? res() : rej(new Error(err || `mysql exit ${code}`))));
    child.stdin.end(readFileSync(file));
  });

const reseed = async (label) => {
  out(`\n=== ${label}：重置库（schema.sql + seed.sql） ===`);
  await runSqlFile(null, resolve(root, 'db', 'schema.sql'));
  await runSqlFile(DB, resolve(root, 'db', 'seed.sql'));
  out('  已重置为种子状态');
};

const probe = async (url, name) => {
  try {
    const res = await fetch(url);
    out(`  前置检查 ${name}: HTTP ${res.status}`);
    return res.ok;
  } catch (e) {
    out(`  前置检查 ${name}: 不可达（${e.message}）`);
    return false;
  }
};

const stage = (name, script, { required = true } = {}) =>
  new Promise((res) => {
    out(`\n=== ${name} ===`);
    const child = spawn(process.execPath, [script], { cwd: here, stdio: 'pipe', env: process.env });
    const sink = (buf) => {
      const text = buf.toString('utf8').trimEnd();
      if (text) {
        out(text);
      }
    };
    child.stdout.on('data', sink);
    child.stderr.on('data', sink);
    child.on('close', (code) => {
      out(`--- ${name} 退出码 ${code}`);
      res({ name, code, required });
    });
  });

// PAIR-07 / TERM-06 的 EV-LOG 需要后端日志：默认找 reports/server.log，可用 FINDIT_SERVER_LOG 覆盖
process.env.FINDIT_SERVER_LOG ??= resolve(here, 'reports', 'server.log');

const results = [];

out(`# v2 · F1 全量回归 · ${new Date().toISOString()}`);
out(`- 后端：${API}`);
out(`- 前端：${WEB}`);
out(`- MySQL 客户端：${MYSQL}`);

const apiUp = await probe(`${API}/api/health`, '后端 /api/health');
const webUp = await probe(WEB, '前端 dev server');
if (!apiUp) {
  out('\n后端不可达，后续用例没有意义。请先启动 server（18080）再重跑。');
  process.exit(2);
}

await mkdir(resolve(here, 'reports'), { recursive: true });
await reseed('开始前');

results.push(await stage('接口测试（node qa/api/run.mjs）', resolve(here, 'api', 'run.mjs')));
results.push(await stage('匹配效果评估（node qa/match/evaluate.mjs）', resolve(here, 'match', 'evaluate.mjs')));

if (!webUp) {
  out('\n前端 dev server 不可达：跳过 E2E / 落库取证 / 一致性三组用例（这几组必须走真实页面）。');
  results.push({ name: 'E2E 走查', code: 'skipped', required: true });
  results.push({ name: '落库取证', code: 'skipped', required: true });
  results.push({ name: '跨页一致性', code: 'skipped', required: true });
} else {
  results.push(await stage('E2E 走查（node qa/e2e/walkthrough.mjs）', resolve(here, 'e2e', 'walkthrough.mjs')));
  results.push(await stage('落库取证（node qa/db/evidence.mjs）', resolve(here, 'db', 'evidence.mjs')));
  results.push(await stage('跨页一致性（node qa/e2e/consistency.mjs）', resolve(here, 'e2e', 'consistency.mjs')));
}

await reseed('结束后');

out('\n=== 汇总 ===');
out('| 阶段 | 退出码 | 结论 |');
out('|---|---|---|');
for (const r of results) {
  const verdict = r.code === 'skipped' ? '跳过（前端未起）' : r.code === 0 ? '通过' : '有失败（见上文明细）';
  out(`| ${r.name} | ${r.code} | ${verdict} |`);
}

for (const [label, file] of [['接口', 'api-f1-v2.json'], ['E2E', 'e2e-f1-v2.json'], ['一致性', 'consistency-5.1.json']]) {
  try {
    const payload = JSON.parse(readFileSync(resolve(here, 'reports', file), 'utf8'));
    out(`\n- ${label}：${payload.summary.total} 条，通过 ${payload.summary.passed}，失败 ${payload.summary.failed}`);
  } catch {
    /* 报告缺失时由阶段退出码兜底 */
  }
}
out('\n说明：本入口对应 docs/testing/v2/F1-regression-assets.md；接口用例 111 条（原 64 + 新增 47），全部按 v2 契约断言；');
out('      「有失败」即为新回归，先看 reports/api-f1-v2.txt 与 reports/e2e-f1-v2.txt 的失败原因。');

const logPath = resolve(here, 'reports', 'run-all-output.txt');
await writeFile(logPath, log.join('\n') + '\n', 'utf8');
console.log(`\n完整输出：${logPath}`);

const hardFail = results.some((r) => r.required !== false && r.code !== 0 && r.code !== 'skipped');
process.exit(hardFail ? 1 : 0);
