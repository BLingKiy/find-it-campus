// 复审工具：用仓库内的 20 对校准集重算本地引擎在 unlock=0.40 / 0.45 下的
// 正例召回与负例 specificity，核对 docs/testing/v2/threshold-calibration.md 的「代价为零」。
// 用法（仓库根目录）：node docs/review/tools/calibrate.mjs
import fs from 'node:fs';

const j = JSON.parse(fs.readFileSync('qa/datasets/pair-calibration/results/local.json', 'utf8'));
const rows = j.pairs.map((p) => ({ id: p.id, expect: p.expect, score: Number(p.score) }));
const pos = rows.filter((r) => r.expect === 'MATCH');
const neg = rows.filter((r) => r.expect !== 'MATCH');

console.log('rows', rows.length, 'pos', pos.length, 'neg', neg.length);
for (const t of [0.4, 0.45]) {
  const posPass = pos.filter((r) => r.score >= t).length;
  const negPass = neg.filter((r) => r.score >= t);
  console.log(
    'unlock',
    t,
    'posRecall',
    `${posPass}/${pos.length}`,
    'negSpecificity',
    `${neg.length - negPass.length}/${neg.length}`,
    'negPass',
    negPass.map((r) => r.id).join(',') || '(none)',
  );
}
console.log('neg scores', neg.map((r) => `${r.id}=${r.score}`).join(', '));
