// 复审工具：核对 QA 数据里「pair 已降级、记录 pair_state 却仍是 READY」的数量（报告 P1-5）。
// 用法（仓库根目录）：node docs/review/tools/pair-state.mjs
import fs from 'node:fs';

for (const f of [
  'qa/datasets/pair-calibration/results/local.json',
  'qa/datasets/pair-synonyms/results/syn-local.json',
]) {
  const j = JSON.parse(fs.readFileSync(f, 'utf8'));
  const rows = j.pairs ?? j.rows ?? [];
  const total = rows.length;
  const degraded = rows.filter((r) => r.degraded === true).length;
  const degradedReady = rows.filter((r) => r.degraded === true && r.pairState === 'READY').length;
  const states = {};
  for (const r of rows) states[r.pairState] = (states[r.pairState] ?? 0) + 1;
  console.log(f, { total, degraded, degradedReady, states });
}
