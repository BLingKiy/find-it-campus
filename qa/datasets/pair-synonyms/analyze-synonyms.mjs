// 近似同义转换分析（ZHYYKFX-84 追加段）。
//
//   node qa/datasets/pair-synonyms/analyze-synonyms.mjs
//
// 纯计算：读 results/syn-local.json、results/syn-deepseek.json 与 variants.json，
// 输出 results/syn-analysis.json / syn-analysis.md / syn-table.md。
//
// 指标定义：
//   分数保持率 R = 改写对分数 / 原文基线分数；基线 = 同一物品的 O↔O（原文对原文）。
//   过阈比例   = 18 对「原文↔改写」中 score >= 该引擎 unlock 的比例。
//   交叉对     = C↔F / C↔S / F↔S，没有「原文」锚点，只报原始分布，不算保持率。
//   陷阱       = 6 对「不同物但描述高度重叠」的负例，报每对分数与最高分。

import { readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const SINGLE = { unlock: 0.45, direct: 0.65, notify: 0.55 }; // 上一轮交付的 LOCAL 那套
const ENGINE_THRESHOLDS = {
  local: { unlock: 0.45, direct: 0.90, notify: 0.90 },
  deepseek: { unlock: 0.80, direct: 0.95, notify: 0.90 },
};

const fmt = (v, digits = 2) => (v === null || v === undefined ? '-' : Number(v).toFixed(digits));
const pct = (v) => (v === null || v === undefined ? '-' : `${(v * 100).toFixed(1)}%`);

function stats(values) {
  const sorted = [...values].sort((a, b) => a - b);
  if (sorted.length === 0) return { n: 0 };
  const q = (p) => {
    const index = (sorted.length - 1) * p;
    const lo = Math.floor(index);
    const hi = Math.ceil(index);
    return lo === hi ? sorted[lo] : sorted[lo] + (sorted[hi] - sorted[lo]) * (index - lo);
  };
  return {
    n: sorted.length,
    min: sorted[0],
    p25: q(0.25),
    median: q(0.5),
    p75: q(0.75),
    max: sorted[sorted.length - 1],
    mean: sorted.reduce((a, b) => a + b, 0) / sorted.length,
    values: sorted,
  };
}

async function load(label) {
  const run = JSON.parse(await readFile(join(here, 'results', `syn-${label}.json`), 'utf8'));
  const byId = new Map();
  for (const pair of run.pairs) {
    if (typeof pair.score === 'number') byId.set(pair.id, pair);
  }
  return { run, byId };
}

const dataset = JSON.parse(await readFile(join(here, 'variants.json'), 'utf8'));
const engines = {};
for (const label of ['local', 'deepseek']) {
  engines[label] = await load(label);
}

const itemIds = dataset.items.map((item) => item.itemId);
const combos = ['OC', 'OF', 'OS'];
const crossCombos = ['CF', 'CS', 'FS'];

function analyze(label) {
  const { byId } = engines[label];
  const thresholds = ENGINE_THRESHOLDS[label];
  const retention = [];
  const rows = [];
  for (const item of dataset.items) {
    const baseline = byId.get(`${item.itemId}-OO`)?.score ?? null;
    for (const combo of combos) {
      const pair = byId.get(`${item.itemId}-${combo}`);
      if (!pair || typeof pair.score !== 'number' || !baseline) continue;
      const r = pair.score / baseline;
      retention.push(r);
      rows.push({
        id: pair.id,
        itemId: item.itemId,
        object: item.object,
        combo,
        phrasing: pair.phrasing,
        score: pair.score,
        baseline,
        retention: r,
        overUnlock: pair.score >= thresholds.unlock,
        overDirect: pair.score >= thresholds.direct,
        engine: pair.engine,
      });
    }
  }
  const cross = [];
  for (const item of dataset.items) {
    for (const combo of crossCombos) {
      const pair = byId.get(`${item.itemId}-${combo}`);
      if (!pair || typeof pair.score !== 'number') continue;
      cross.push({
        id: pair.id,
        score: pair.score,
        overUnlock: pair.score >= thresholds.unlock,
      });
    }
  }
  const traps = dataset.traps.map((trap) => {
    const pair = byId.get(trap.id);
    const score = pair?.score ?? null;
    return {
      id: trap.id,
      object: trap.object,
      score,
      overUnlock: typeof score === 'number' && score >= thresholds.unlock,
      overDirect: typeof score === 'number' && score >= thresholds.direct,
    };
  });
  const byCombo = {};
  for (const combo of combos) {
    const values = retention.filter((_, index) => rows[index].combo === combo);
    byCombo[combo] = stats(values);
  }
  return {
    thresholds,
    rows,
    retention: stats(retention),
    byCombo,
    overUnlock: rows.filter((r) => r.overUnlock).length / rows.length,
    overDirect: rows.filter((r) => r.overDirect).length / rows.length,
    cross: { ...stats(cross.map((c) => c.score)), overUnlock: cross.filter((c) => c.overUnlock).length / cross.length },
    traps: {
      rows: traps,
      max: Math.max(...traps.map((t) => t.score)),
      min: Math.min(...traps.map((t) => t.score)),
      overUnlock: traps.filter((t) => t.overUnlock).length / traps.length,
      overDirect: traps.filter((t) => t.overDirect).length / traps.length,
    },
  };
}

const result = {};
for (const label of ['local', 'deepseek']) result[label] = analyze(label);

// 单套阈值反证：把 DEEPSEEK 那套（0.80/0.95）套到 LOCAL，以及把 LOCAL 那套（0.45/0.65）套到 DEEPSEEK。
function simulate(label, thresholds) {
  const rows = result[label].rows;
  const traps = result[label].traps.rows;
  return {
    unlock: thresholds.unlock,
    direct: thresholds.direct,
    synonymPass: rows.filter((r) => r.score >= thresholds.unlock).length / rows.length,
    trapPass: traps.filter((t) => t.score >= thresholds.unlock).length / traps.length,
    synonymDirect: rows.filter((r) => r.score >= thresholds.direct).length / rows.length,
    trapDirect: traps.filter((t) => t.score >= thresholds.direct).length / traps.length,
  };
}

const simulation = {
  deepseekThresholdsOnLocal: simulate('local', { unlock: 0.80, direct: 0.95 }),
  oldSharedThresholdsOnLocal: simulate('local', { unlock: 0.75, direct: 0.90 }),
  deliveredLocalOnLocal: simulate('local', { unlock: 0.45, direct: 0.65 }),
  proposedLocalOnLocal: simulate('local', { unlock: 0.45, direct: 0.90 }),
  localThresholdsOnDeepseek: simulate('deepseek', { unlock: 0.45, direct: 0.65 }),
  localHardFloorOnLocal: simulate('local', { unlock: 0.86, direct: 0.90 }),
};

const report = {
  generatedAt: new Date().toISOString(),
  dataset: 'qa/datasets/pair-synonyms/variants.json',
  items: itemIds.length,
  synonymPairs: result.local.rows.length,
  crossPairs: result.local.cross.n,
  traps: dataset.traps.length,
  engines: result,
  simulation,
};

const lines = [];
lines.push('# 近似同义转换分析（pair-synonyms）');
lines.push('');
lines.push(`生成时间：${report.generatedAt}`);
lines.push('');
lines.push(`样本：${report.items} 件物品 × 4 种措辞，原文↔改写 ${report.synonymPairs} 对（O-C/O-F/O-S），交叉改写 ${report.crossPairs} 对，陷阱负例 ${report.traps} 对。`);
lines.push('');
lines.push('分数保持率 = 改写对分数 / 同一物品原文对原文（O↔O）分数。');
lines.push('');
for (const label of ['local', 'deepseek']) {
  const r = result[label];
  lines.push(`## ${label === 'local' ? 'LOCAL_KEYWORD' : 'DEEPSEEK'}`);
  lines.push('');
  lines.push(`- 阈值：unlock ${r.thresholds.unlock} / direct ${r.thresholds.direct}`);
  lines.push(`- 保持率（${r.retention.n} 对）：min ${fmt(r.retention.min)} / p25 ${fmt(r.retention.p25)} / median ${fmt(r.retention.median)} / p75 ${fmt(r.retention.p75)} / max ${fmt(r.retention.max)} / mean ${fmt(r.retention.mean, 3)}`);
  for (const combo of combos) {
    const s = r.byCombo[combo];
    lines.push(`  - ${combo}（n=${s.n}）：median ${fmt(s.median)} / mean ${fmt(s.mean, 3)} / min ${fmt(s.min)}`);
  }
  lines.push(`- 过 unlock 比例：${pct(r.overUnlock)}（${r.rows.filter((x) => x.overUnlock).length}/${r.rows.length}）；过 direct 比例：${pct(r.overDirect)}`);
  lines.push(`- 交叉改写（n=${r.cross.n}）：min ${fmt(r.cross.min)} / median ${fmt(r.cross.median)} / max ${fmt(r.cross.max)}，过 unlock ${pct(r.cross.overUnlock)}`);
  lines.push(`- 陷阱：min ${fmt(r.traps.min)} / max ${fmt(r.traps.max)}；过 unlock ${pct(r.traps.overUnlock)}；过 direct ${pct(r.traps.overDirect)}`);
  lines.push('');
}

lines.push('## 单套阈值反证');
lines.push('');
lines.push('| 场景 | unlock | 同义对过阈 | 陷阱过阈 | 同义对直看 | 陷阱直看 |');
lines.push('|---|---|---|---|---|---|');
for (const [name, s] of Object.entries(simulation)) {
  lines.push(`| ${name} | ${s.unlock} | ${pct(s.synonymPass)} | ${pct(s.trapPass)} | ${pct(s.synonymDirect)} | ${pct(s.trapDirect)} |`);
}
lines.push('');

await writeFile(join(here, 'results', 'syn-analysis.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8');
await writeFile(join(here, 'results', 'syn-analysis.md'), `${lines.join('\n')}\n`, 'utf8');

// 逐对表：两引擎并排，保持率与是否过阈。
const table = [
  '| 样本 | 物品 | 措辞 | LOCAL 分数 | LOCAL 保持率 | LOCAL 过阈 | DEEPSEEK 分数 | DEEPSEEK 保持率 | DEEPSEEK 过阈 |',
  '|---|---|---|---|---|---|---|---|---|',
];
const rowsById = new Map();
for (const label of ['local', 'deepseek']) {
  for (const row of result[label].rows) {
    if (!rowsById.has(row.id)) rowsById.set(row.id, row);
    rowsById.get(row.id)[label] = row;
  }
}
for (const row of rowsById.values()) {
  const l = row.local;
  const d = row.deepseek;
  table.push(`| ${row.id} | ${row.object} | ${row.phrasing.join('→')} | ${fmt(l.score)} | ${fmt(l.retention, 3)} | ${l.overUnlock ? '是' : '否'} | ${fmt(d.score)} | ${fmt(d.retention, 3)} | ${d.overUnlock ? '是' : '否'} |`);
}
table.push('');
table.push('| 陷阱 | 物品 | LOCAL 分数 | LOCAL 过 unlock/direct | DEEPSEEK 分数 | DEEPSEEK 过 unlock/direct |');
table.push('|---|---|---|---|---|---|');
for (const trap of dataset.traps) {
  const l = result.local.traps.rows.find((t) => t.id === trap.id);
  const d = result.deepseek.traps.rows.find((t) => t.id === trap.id);
  table.push(`| ${trap.id} | ${trap.object} | ${fmt(l.score)} | ${l.overUnlock ? '过 unlock' : '未过'} / ${l.overDirect ? '过 direct' : '未过'} | ${fmt(d.score)} | ${d.overUnlock ? '过 unlock' : '未过'} / ${d.overDirect ? '过 direct' : '未过'} |`);
}
await writeFile(join(here, 'results', 'syn-table.md'), `${table.join('\n')}\n`, 'utf8');

for (const label of ['local', 'deepseek']) {
  const r = result[label];
  console.log(`${label}: 保持率 median=${fmt(r.retention.median)} mean=${fmt(r.retention.mean, 3)} min=${fmt(r.retention.min)}；过 unlock ${pct(r.overUnlock)}；陷阱 max=${fmt(r.traps.max)} 过 unlock ${pct(r.traps.overUnlock)} 过 direct ${pct(r.traps.overDirect)}`);
}
console.log('输出：results/syn-analysis.json、syn-analysis.md、syn-table.md');
