// 阈值校准分析（ZHYYKFX-84）：读 results/<engine>.json，出分档分布、正负分离与候选阈值。
//
//   node qa/datasets/pair-calibration/analyze.mjs
//
// 纯计算，不发请求、不连库。输出 results/analysis.md 与 results/analysis.json。
// 判据（任务书验收）：
//   正例 recall(t) = 应匹配样本中 score >= t 的比例，要求 >= 0.80
//   负例 spec(t)   = 不应匹配样本中 score <  t 的比例，要求 >= 0.90

import { readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const POS_TARGET = 0.8;
const NEG_TARGET = 0.9;

// 校准后的建议值（与 server/src/main/resources/application.yml 保持一致）。
const RECOMMENDED = {
  local: { unlock: 0.45, direct: 0.65, notify: 0.55 },
  deepseek: { unlock: 0.8, direct: 0.95, notify: 0.9 },
};

function tierAt(score, { unlock, direct }) {
  if (score >= direct) return 'DIRECT';
  if (score >= unlock) return 'AUTHORIZABLE';
  return 'LOW';
}

function quantile(sorted, p) {
  if (sorted.length === 0) return null;
  const index = (sorted.length - 1) * p;
  const lower = Math.floor(index);
  const upper = Math.ceil(index);
  if (lower === upper) return sorted[lower];
  return sorted[lower] + (sorted[upper] - sorted[lower]) * (index - lower);
}

function stats(scores) {
  const sorted = [...scores].sort((a, b) => a - b);
  return {
    n: sorted.length,
    min: sorted[0] ?? null,
    p25: quantile(sorted, 0.25),
    median: quantile(sorted, 0.5),
    p75: quantile(sorted, 0.75),
    max: sorted[sorted.length - 1] ?? null,
    values: sorted,
  };
}

const fmt = (v) => (v === null || v === undefined ? '-' : v.toFixed(2));

function sweep(pairs) {
  const positives = pairs.filter((p) => p.expect === 'MATCH').map((p) => p.score);
  const negatives = pairs.filter((p) => p.expect === 'NO_MATCH').map((p) => p.score);
  const rows = [];
  for (let i = 0; i <= 100; i += 1) {
    const t = i / 100;
    const recall = positives.filter((s) => s >= t).length / positives.length;
    const spec = negatives.filter((s) => s < t).length / negatives.length;
    rows.push({ t, recall, spec, pass: recall >= POS_TARGET && spec >= NEG_TARGET });
  }
  const passing = rows.filter((r) => r.pass).map((r) => r.t);
  return {
    positives: stats(positives),
    negatives: stats(negatives),
    sweep: rows,
    passing: passing.length === 0 ? null : { min: Math.min(...passing), max: Math.max(...passing) },
    best: rows.reduce((best, row) => {
      const score = Math.min(row.recall / POS_TARGET, row.spec / NEG_TARGET);
      return !best || score > best.score ? { ...row, score } : best;
    }, null),
  };
}

function markdownTable(run) {
  const head = '| 样本 | 类别 | 人工判定 | 分数 | 引擎 | 是否出卡片（按建议阈值） | 理由 |';
  const sep = '|---|---|---|---|---|---|---|';
  return [head, sep, ...run.rows].join('\n');
}

async function load(label) {
  try {
    const run = JSON.parse(await readFile(join(here, 'results', `${label}.json`), 'utf8'));
    return run;
  } catch {
    return null;
  }
}

const report = { generatedAt: new Date().toISOString(), criteria: { posTarget: POS_TARGET, negTarget: NEG_TARGET }, engines: {} };
const sections = [];
for (const label of ['local', 'deepseek']) {
  const run = await load(label);
  if (!run) continue;
  const pairs = run.pairs.filter((p) => typeof p.score === 'number');
  const result = sweep(pairs);
  report.engines[label] = { label, pairs: pairs.length, ...result, raw: run };
  sections.push({ label, result, run });
}

const lines = [];
lines.push('# 阈值校准分析（pair-calibration）');
lines.push('');
lines.push(`生成时间：${report.generatedAt}`);
lines.push('');
lines.push('判据：正例 `score >= unlock` 比例 ≥ 80%；负例 `score < unlock` 比例 ≥ 90%。');
lines.push('');
for (const { label, result } of sections) {
  lines.push(`## ${label === 'local' ? 'LOCAL_KEYWORD' : 'DEEPSEEK'}`);
  lines.push('');
  lines.push(`- 正例（${result.positives.n}）：min ${fmt(result.positives.min)} / p25 ${fmt(result.positives.p25)} / median ${fmt(result.positives.median)} / p75 ${fmt(result.positives.p75)} / max ${fmt(result.positives.max)}`);
  lines.push(`- 负例（${result.negatives.n}）：min ${fmt(result.negatives.min)} / p25 ${fmt(result.negatives.p25)} / median ${fmt(result.negatives.median)} / p75 ${fmt(result.negatives.p75)} / max ${fmt(result.negatives.max)}`);
  lines.push(`- 同时满足两条判据的 unlock 区间：${result.passing ? `[${fmt(result.passing.min)}, ${fmt(result.passing.max)}]` : '无'}`);
  lines.push('');
  lines.push('| unlock | 正例 recall | 负例 specificity | 通过 |');
  lines.push('|---|---|---|---|');
  for (const row of result.sweep.filter((r) => r.t % 0.05 === 0 || r.pass)) {
    lines.push(`| ${row.t.toFixed(2)} | ${(row.recall * 100).toFixed(0)}% | ${(row.spec * 100).toFixed(0)}% | ${row.pass ? '✅' : ''} |`);
  }
  lines.push('');
}

await writeFile(join(here, 'results', 'analysis.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8');
await writeFile(join(here, 'results', 'analysis.md'), `${lines.join('\n')}\n`, 'utf8');

// 逐对标注表：两个引擎的分数并排，附建议阈值下的档位与是否出卡片。
const byId = new Map();
for (const { label } of sections) {
  const run = report.engines[label].raw;
  for (const row of run.pairs) {
    if (!byId.has(row.id)) byId.set(row.id, { id: row.id, class: row.class, expect: row.expect, reason: row.reason });
    byId.get(row.id)[label] = row.score;
  }
}
const table = [
  '| 样本 | 类别 | 人工判定 | LOCAL_KEYWORD 分数 | LOCAL 档位/出卡片 | DEEPSEEK 分数 | DEEPSEEK 档位/出卡片 | 理由 |',
  '|---|---|---|---|---|---|---|---|',
];
const verdict = (score, name) => {
  if (typeof score !== 'number') return '未测';
  const rec = RECOMMENDED[name];
  const tier = tierAt(score, rec);
  return `${tier}/${tier === 'LOW' ? '不出' : '出'}`;
};
for (const row of byId.values()) {
  table.push(`| ${row.id} | ${row.class} | ${row.expect === 'MATCH' ? '应匹配' : '不应匹配'} | ${fmt(row.local)} | ${verdict(row.local, 'local')} | ${fmt(row.deepseek)} | ${verdict(row.deepseek, 'deepseek')} | ${row.reason} |`);
}
await writeFile(join(here, 'results', 'table.md'), `${table.join('\n')}\n`, 'utf8');

for (const { label, result } of sections) {
  console.log(`${label}: 正例[${result.positives.values.map(fmt).join(', ')}] 负例[${result.negatives.values.map(fmt).join(', ')}]`);
  console.log(`  同时通过判据的 unlock 区间：${result.passing ? `[${fmt(result.passing.min)}, ${fmt(result.passing.max)}]` : '无'}`);
  const pos = report.engines[label].raw.pairs.filter((p) => p.expect === 'MATCH' && typeof p.score === 'number');
  const neg = report.engines[label].raw.pairs.filter((p) => p.expect === 'NO_MATCH' && typeof p.score === 'number');
  const rec = RECOMMENDED[label];
  console.log(`  建议 unlock=${rec.unlock}：正例 recall=${(pos.filter((p) => p.score >= rec.unlock).length / pos.length * 100).toFixed(0)}% 负例 specificity=${(neg.filter((p) => p.score < rec.unlock).length / neg.length * 100).toFixed(0)}%`);
}
console.log('输出：results/analysis.md、results/analysis.json、results/table.md');
