/**
 * 复算正文里的六个计数，并与本文声明的基线比对。
 *
 *   node docs/design/tools/verify-counts.mjs
 *
 * 为什么要有这个脚本：设计文档里的「8 张表 / 117 列 / 10 个外键 / 29 个 CHECK /
 * 21 条路径 / 313 次执行」如果只靠人工数，下一次改 schema 就会悄悄过期。这里把
 * 计数规则固化成代码——规则本身就是口径，改口径必须改这个文件，能被 review。
 *
 * 计数规则（写死，别在别处另算一套）：
 *   表       db/schema.sql 里 CREATE TABLE 的个数
 *   列       CREATE TABLE 与收尾 `) ENGINE` 之间、以反引号列名开头的行
 *   外键     出现 FOREIGN KEY 的约束条数
 *   CHECK    以 CONSTRAINT `ck_` 开头的约束条数
 *   路径     *Controller.java 里每个方法级 @XxxMapping 一条；/api/health 与 /api/ops/**
 *            计为运维，其余为业务
 *   执行次数 @Test 个数 + 参数化方法的用例数（@MethodSource 指向的方法里 Arguments.of 的条数）
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../../..');

/** 本文声明的基线：截至 `6934e6e`。改基线时这三组数必须一起改。 */
const EXPECTED = {
  commit: '6934e6e',
  tables: 8,
  columns: 118,
  foreignKeys: 10,
  checks: 29,
  paths: 21,
  businessPaths: 18,
  opsPaths: 3,
  executions: 327,
  testMethods: 321,
  parameterizedCases: 6,
};

const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const walk = (dir, out = []) => {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.java')) out.push(p);
  }
  return out;
};

/* ------------------------------------------------------------ 数据库 */

const schema = read('db/schema.sql');
const tables = [...schema.matchAll(/CREATE TABLE\s+(?:IF NOT EXISTS\s+)?`(\w+)`/g)].map((m) => m[1]);

let columns = 0;
const perTable = {};
{
  let current = null;
  for (const line of schema.split(/\r?\n/)) {
    const open = line.match(/CREATE TABLE\s+(?:IF NOT EXISTS\s+)?`(\w+)`/);
    if (open) { current = open[1]; perTable[current] = 0; continue; }
    if (current && /^\s*\)\s*ENGINE/.test(line)) { current = null; continue; }
    if (current && /^\s*`\w+`\s+\w/.test(line)) { perTable[current] += 1; columns += 1; }
  }
}
const foreignKeys = (schema.match(/FOREIGN KEY/g) ?? []).length;
const checks = (schema.match(/CONSTRAINT\s+`ck_\w+`/g) ?? []).length;

/* ------------------------------------------------------------ 接口 */

const controllers = walk(path.join(ROOT, 'server/src/main/java')).filter((f) => f.endsWith('Controller.java'));
const endpoints = [];
for (const file of controllers) {
  const txt = fs.readFileSync(file, 'utf8');
  const base = txt.match(/@RequestMapping\s*\(\s*(?:value\s*=\s*)?["']([^"']*)["']/)?.[1] ?? '';
  for (const m of txt.matchAll(/@(Get|Post|Put|Delete|Patch)Mapping(?:\s*\(\s*(?:(?:value|path)\s*=\s*)?(?:"([^"]*)")?[^)]*\))?/g)) {
    endpoints.push({ verb: m[1].toUpperCase(), path: base + (m[2] ?? ''), file: path.basename(file) });
  }
}
const opsPaths = endpoints.filter((e) => e.path === '/api/health' || e.path.startsWith('/api/ops/')).length;
const businessPaths = endpoints.length - opsPaths;

/* ------------------------------------------------------------ 测试 */

const tests = walk(path.join(ROOT, 'server/src/test')).filter((f) => f.endsWith('.java'));
let testMethods = 0;
let parameterizedCases = 0;
let skippedGuards = 0;

for (const file of tests) {
  const txt = fs.readFileSync(file, 'utf8');
  testMethods += (txt.match(/^\s*@Test\b/gm) ?? []).length;
  skippedGuards += (txt.match(/Assumptions\.assume\w+/g) ?? []).length;
  // 参数化：一个 @ParameterizedTest 的用例数 = 其 @MethodSource 指向方法里 Arguments.of 的条数
  const blocks = [...txt.matchAll(/@ParameterizedTest[\s\S]{0,400}?@MethodSource\("(\w+)"\)/g)];
  for (const b of blocks) {
    const body = txt.match(new RegExp(`static\\s+[\\w<>,.?\\s]+?\\s${b[1]}\\s*\\([^)]*\\)\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}`));
    parameterizedCases += (body?.[1].match(/Arguments\.of\(/g) ?? []).length;
  }
}
const executions = testMethods + parameterizedCases;

/* ------------------------------------------------------------ 报告 */

const rows = [
  ['表', tables.length, EXPECTED.tables],
  ['列', columns, EXPECTED.columns],
  ['外键', foreignKeys, EXPECTED.foreignKeys],
  ['CHECK', checks, EXPECTED.checks],
  ['路径合计', endpoints.length, EXPECTED.paths],
  ['　业务路径', businessPaths, EXPECTED.businessPaths],
  ['　运维路径', opsPaths, EXPECTED.opsPaths],
  ['测试执行次数', executions, EXPECTED.executions],
  ['　@Test', testMethods, EXPECTED.testMethods],
  ['　参数化用例', parameterizedCases, EXPECTED.parameterizedCases],
];

let bad = 0;
console.log(`基线 ${EXPECTED.commit} · 复算时间 ${new Date().toISOString()}\n`);
console.log('项目'.padEnd(16), '复算'.padStart(6), '声明'.padStart(6), ' 结果');
console.log('-'.repeat(40));
for (const [name, actual, expected] of rows) {
  const ok = actual === expected;
  if (!ok) bad += 1;
  console.log(name.padEnd(16), String(actual).padStart(6), String(expected).padStart(6), ok ? ' OK' : ' MISMATCH');
}

console.log('\n分表列数：', Object.entries(perTable).map(([t, n]) => `${t}=${n}`).join('  '));
console.log('路径清单：', endpoints.map((e) => `${e.verb} ${e.path}`).sort().join('  '));
console.log(`跳过的用例：${skippedGuards} 处 Assumptions 守卫（无真 key 时跳过 1 条）`);
console.log(bad ? `\nFAIL：${bad} 项与声明不一致（改 schema / 接口 / 测试后必须同步正文与本节基线）` : '\n全部一致');
process.exit(bad ? 1 : 0);
