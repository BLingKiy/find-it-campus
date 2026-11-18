// 复审工具：统计 db/schema.sql 的物理表 / 列 / CHECK / 外键数量。
// 用法（仓库根目录）：node docs/review/tools/schema-count.mjs
import fs from 'node:fs';

const s = fs.readFileSync('db/schema.sql', 'utf8');
const tableRe = /CREATE TABLE `([^`]+)` \(([\s\S]*?)\) ENGINE=/g;
let m;
let tables = 0;
let cols = 0;
let checks = 0;
let fks = 0;

while ((m = tableRe.exec(s)) !== null) {
  const name = m[1];
  const body = m[2];
  const colMatches = [...body.matchAll(/^\s{2}`([^`]+)`\s+([A-Z][A-Za-z]*)/gm)];
  const c = (body.match(/CONSTRAINT `ck_/g) || []).length;
  const f = (body.match(/CONSTRAINT `fk_/g) || []).length;
  tables++;
  cols += colMatches.length;
  checks += c;
  fks += f;
  console.log(name, colMatches.length, `ck=${c}`, `fk=${f}`);
}

console.log(`TABLES ${tables} COLS ${cols} CHECK ${checks} FK ${fks}`);
