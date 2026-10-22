// 5.1 /api/match 匹配效果评估（ZHYYKFX-10）
//
// 目的：用 db/seed.sql 里 3.2 预置的 10 条样例（S1–S6 / N1–N4）跑一轮，输出「输入 / 期望 / 实际 / 判定」
// 与可复现的指标（top1 命中率、top3 命中率、明显误报数、耗时），不写「效果不错」这种没法反驳的结论。
//
// 两个协议（都跑，分别统计，不混池 —— 契约 §8.4 要求按 impl 分组，这里按调用协议再分一层）：
//   P1「产品路径」：按详情页真实调用方式带 type（失主找招领帖 → FOUND；拾到者找失物帖 → LOST）
//   P2「不限类型」：完全不带 type，观察原始召回
//
// 判定口径（写在表格里，可被复核）：
//   通过 = 期望 top1 出现在实际结果的第 1 位；期望为空 = 实际为空；
//   不通过 = 未命中 / 出现期望排除的 id / 出现「明显误报」。
// 「明显误报」定义：top5 内、score ≥ 0.6、且不在该样例的人工标注相关集内（相关集 = 期望目标 + 同事件另一侧记录）。
//
// 运行：node qa/match/evaluate.mjs

import { Api, brief, isOk } from '../lib/client.mjs';
import { writeFile, mkdir } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const api = new Api();

// 人工标注：related = 该输入在业务上「确实相关」的记录（同事件另一侧 + 期望目标）
const SAMPLES = [
  {
    id: 'S1',
    intent: '失主找招领帖',
    text: '我在图书馆三楼弄丢了一个黑色皮钱包，里面有银行卡',
    type: 'FOUND',
    expectTop1: 13,
    related: [1, 13],
    mustExclude: [],
  },
  {
    id: 'S2',
    intent: '失主找招领帖',
    text: '操场跑步忘拿了白色 iPhone，透明壳带小熊贴纸',
    type: 'FOUND',
    expectTop1: 14,
    related: [2, 14],
    mustExclude: [],
  },
  {
    id: 'S3',
    intent: '失主找招领帖',
    text: '高数课上的学生证校园卡不见了，姓名张三',
    type: 'FOUND',
    expectTop1: 15,
    related: [3, 15],
    mustExclude: [],
  },
  {
    id: 'S4',
    intent: '失主找招领帖',
    text: '食堂丢了把深蓝自动伞，木头伞柄',
    type: 'FOUND',
    expectTop1: 16,
    related: [4, 16],
    mustExclude: [],
  },
  {
    id: 'S5',
    intent: '失主找招领帖',
    text: '白色的无线耳机加充电盒，右耳有划痕',
    type: 'FOUND',
    expectTop1: 17,
    related: [5, 17],
    mustExclude: [],
  },
  {
    id: 'S6',
    intent: '失主找招领帖',
    text: '快递点掉的钥匙串，黄色小熊挂饰',
    type: 'FOUND',
    expectTop1: 18,
    related: [6, 18],
    mustExclude: [],
  },
  {
    id: 'N1',
    intent: '拾到者找失物帖',
    text: '捡到过一个计算器吗？灰色的卡西欧',
    type: 'LOST',
    expectTop1: 7,
    related: [7],
    mustExclude: [19, 20, 21, 22],
  },
  {
    id: 'N2',
    intent: '拾到者找失物帖（纯负例）',
    text: '有人在体育馆捡到泳镜吗',
    type: 'LOST',
    expectTop1: null,
    related: [],
    mustExclude: [],
  },
  {
    id: 'N3',
    intent: '失主找伞（不限类型）',
    text: '丢了一把雨伞',
    type: null,
    expectTop1: 4,
    related: [4, 16],
    mustExclude: [],
    note: 'seed 期望「命中 4/16 且 16 的分数高于 18（钥匙）」；本轮 18 未进入结果集，该条件以「不出现」形式满足',
  },
  {
    id: 'N4',
    intent: '拾到者找钥匙（不限类型）',
    text: '捡到一串钥匙',
    type: null,
    expectTop1: 18,
    related: [6, 18],
    mustExclude: [23, 25],
    note: 'seed 要求 23（CLAIMED）与 25（已删除）都不得出现',
  },
];

const TOP_N = 5;
const MIN_SCORE = 0.3;
const FALSE_POSITIVE_SCORE = 0.6;

async function callMatch(sample, withType) {
  const body = { text: sample.text, topN: TOP_N, minScore: MIN_SCORE };
  if (withType && sample.type) body.type = sample.type;
  const started = performance.now();
  const res = await api.post('/api/match', { body });
  const clientMs = Number((performance.now() - started).toFixed(1));
  if (!isOk(res)) throw new Error(`${sample.id} 调用失败：${brief(res)}`);
  // tookMs 是后端只包围引擎调用的耗时（整数毫秒），不含 HTTP 与序列化；clientMs 是端到端观感值。
  return { data: res.body.data, clientMs };
}

function judge(sample, data) {
  const items = data.items;
  const ids = items.map((i) => i.recordId);
  const reasons = [];
  let pass = true;

  if (sample.expectTop1 === null) {
    if (items.length !== 0) {
      pass = false;
      reasons.push(`期望空结果，实际返回 ${JSON.stringify(ids)}`);
    }
  } else if (ids[0] !== sample.expectTop1) {
    pass = false;
    reasons.push(`top1 期望 ${sample.expectTop1}，实际 ${ids[0] ?? '无结果'}`);
  }

  const leaked = sample.mustExclude.filter((id) => ids.includes(id));
  if (leaked.length > 0) {
    pass = false;
    reasons.push(`出现应排除的记录 ${JSON.stringify(leaked)}`);
  }

  const fps = items.filter((i) => i.score >= FALSE_POSITIVE_SCORE && !sample.related.includes(i.recordId));
  if (fps.length > 0) {
    pass = false;
    reasons.push(
      `明显误报（score>=${FALSE_POSITIVE_SCORE} 且不在相关集）：${fps.map((i) => `${i.recordId}@${i.score}`).join(', ')}`,
    );
  }

  return { pass, reasons, ids, top1: ids[0] ?? null };
}

const fmtItems = (items) =>
  items.length === 0 ? '（空）' : items.map((i) => `${i.recordId}@${i.score.toFixed(2)}`).join(' → ');

async function runProtocol(name, withType) {
  const rows = [];
  const latencies = [];
  const clientLatencies = [];
  for (const sample of SAMPLES) {
    const first = await callMatch(sample, withType);
    const second = await callMatch(sample, withType);
    const verdict = judge(sample, first.data);
    const deterministic =
      JSON.stringify(first.data.items.map((i) => [i.recordId, i.score])) ===
      JSON.stringify(second.data.items.map((i) => [i.recordId, i.score]));
    latencies.push(first.data.tookMs);
    clientLatencies.push(first.clientMs);
    rows.push({
      sample,
      expect: sample.expectTop1 === null ? '空结果' : `top1 = ${sample.expectTop1}`,
      actual: fmtItems(first.data.items),
      verdict,
      deterministic,
      tookMs: first.data.tookMs,
      clientMs: first.clientMs,
      matchId: first.data.matchId,
      engine: first.data.engine,
      inputType: first.data.inputType,
    });
  }

  const passed = rows.filter((r) => r.verdict.pass).length;
  const top3 = rows.filter((r) => (r.sample.expectTop1 === null ? r.verdict.pass : r.verdict.ids.slice(0, 3).includes(r.sample.expectTop1))).length;
  const deterministic = rows.filter((r) => r.deterministic).length;
  const avg = latencies.reduce((a, b) => a + b, 0) / latencies.length;
  const sorted = [...latencies].sort((a, b) => a - b);
  const cAvg = clientLatencies.reduce((a, b) => a + b, 0) / clientLatencies.length;
  const cSorted = [...clientLatencies].sort((a, b) => a - b);

  return {
    name,
    withType,
    rows,
    metrics: {
      samples: rows.length,
      top1Pass: passed,
      top1Rate: `${((passed / rows.length) * 100).toFixed(0)}%`,
      top3Hit: top3,
      top3Rate: `${((top3 / rows.length) * 100).toFixed(0)}%`,
      deterministic,
      avgMs: Number(avg.toFixed(1)),
      p95Ms: sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * 0.95) - 1)],
      maxMs: sorted[sorted.length - 1],
      clientAvgMs: Number(cAvg.toFixed(1)),
      clientP95Ms: cSorted[Math.min(cSorted.length - 1, Math.ceil(cSorted.length * 0.95) - 1)],
      clientMaxMs: cSorted[cSorted.length - 1],
    },
  };
}

function renderMarkdown(product, any) {
  const lines = [];
  lines.push('# /api/match 匹配效果评估（ZHYYKFX-10 · 5.1）');
  lines.push('');
  lines.push(`- 引擎：${product.rows[0].engine}（impl 分组：本轮只有这一种实现，契约 §8.4 要求不与其他实现混池）`);
  lines.push(`- 参数：topN=${TOP_N} · minScore=${MIN_SCORE}`);
  lines.push(`- 样例来源：db/seed.sql 文末 S1–S6 / N1–N4（3.2 预置）`);
  lines.push(`- 生成时间：${new Date().toISOString()}`);
  lines.push('');
  lines.push('## 协议 P1：产品路径（按详情页真实调用方式带 type）');
  lines.push('');
  lines.push('| # | 意图 | 输入 | 期望 | 实际（top5，recordId@score） | 判定 | 耗时 |');
  lines.push('|---|---|---|---|---|---|---|');
  for (const r of product.rows) {
    const verdict = r.verdict.pass ? '通过' : `不通过：${r.verdict.reasons.join('；')}`;
    lines.push(
      `| ${r.sample.id} | ${r.sample.intent} | ${r.sample.text} | ${r.expect} | ${r.actual} | ${verdict} | ${r.tookMs}ms |`,
    );
  }
  lines.push('');
  lines.push('## 协议 P2：不限类型（对照，观察原始召回）');
  lines.push('');
  lines.push('| # | 输入 | 期望 | 实际（top5） | 判定 |');
  lines.push('|---|---|---|---|---|');
  for (const r of any.rows) {
    const verdict = r.verdict.pass ? '通过' : `不通过：${r.verdict.reasons.join('；')}`;
    lines.push(`| ${r.sample.id} | ${r.sample.text} | ${r.expect} | ${r.actual} | ${verdict} |`);
  }
  lines.push('');
  lines.push('## 指标');
  lines.push('');
  lines.push('| 指标 | P1 产品路径 | P2 不限类型 |');
  lines.push('|---|---|---|');
  lines.push(`| top1 命中率 | ${product.metrics.top1Pass}/${product.metrics.samples}（${product.metrics.top1Rate}） | ${any.metrics.top1Pass}/${any.metrics.samples}（${any.metrics.top1Rate}） |`);
  lines.push(`| top3 命中率 | ${product.metrics.top3Hit}/${product.metrics.samples}（${product.metrics.top3Rate}） | ${any.metrics.top3Hit}/${any.metrics.samples}（${any.metrics.top3Rate}） |`);
  lines.push(`| 两次调用结果一致 | ${product.metrics.deterministic}/${product.metrics.samples} | ${any.metrics.deterministic}/${any.metrics.samples} |`);
  lines.push(`| 耗时（后端 tookMs，仅引擎）avg / p95 / max | ${product.metrics.avgMs} / ${product.metrics.p95Ms} / ${product.metrics.maxMs} ms | ${any.metrics.avgMs} / ${any.metrics.p95Ms} / ${any.metrics.maxMs} ms |`);
  lines.push(`| 耗时（客户端端到端）avg / p95 / max | ${product.metrics.clientAvgMs} / ${product.metrics.clientP95Ms} / ${product.metrics.clientMaxMs} ms | ${any.metrics.clientAvgMs} / ${any.metrics.clientP95Ms} / ${any.metrics.clientMaxMs} ms |`);
  lines.push('');
  return lines.join('\n');
}

const product = await runProtocol('P1 产品路径', true);
const any = await runProtocol('P2 不限类型', false);

const md = renderMarkdown(product, any);
const payload = { generatedAt: new Date().toISOString(), params: { topN: TOP_N, minScore: MIN_SCORE }, product, any };

await mkdir(resolve(here, '..', 'reports'), { recursive: true });
await writeFile(resolve(here, '..', 'reports', 'match-eval.md'), md, 'utf8');
await writeFile(resolve(here, '..', 'reports', 'match-eval.json'), JSON.stringify(payload, null, 2), 'utf8');

console.log(md);
console.log('\n报告：qa/reports/match-eval.md（同内容 JSON：match-eval.json）');
