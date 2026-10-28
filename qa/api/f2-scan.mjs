// v2 · F2 手机号泄漏全局扫描（响应体 + 通知正文）。
//
// 口径（任务书）：非本人与匿名响应体、通知正文，正则 1[3-9]\d{9} 0 命中。
// 本人 `/api/my/records` 与本人详情按契约返回原值，属于设计内可见，不在扫描范围。
//
// 输出：docs/testing/v2/F2-phone-scan.txt（人读）、qa/reports/f2-phone-scan.{json,txt}
// 运行：node qa/api/f2-scan.mjs
// 前置：后端 18080、库为 seed 初始状态。

import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { contexts } from '../lib/auth.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..', '..');
const PHONE = /1[3-9]\d{9}/g;

const ctx = await contexts();
const probes = [];
const log = [];

const mask = (text) => text.replace(PHONE, (m) => `${m.slice(0, 3)}****${m.slice(7)}`);

// requestId 是 16 位随机十六进制，偶尔会拼出 1[3-9]\d{9} 的形状（本轮已实际撞到一次）。
// 它不是业务数据，先剔除再扫描，避免把随机串当泄漏；其余字段原样扫描。
const stripNoise = (text) => String(text ?? '').replace(/"requestId"\s*:\s*"[^"]*"/g, '"requestId":"<noise>"');

async function scan(label, res, { body = true } = {}) {
  const text = body ? stripNoise(res.raw ?? '') : '';
  const hits = text.match(PHONE) ?? [];
  probes.push({ label, path: res.url, http: res.status, code: res.body?.code ?? null, hits: hits.map(mask) });
  log.push(
    `${hits.length === 0 ? 'PASS' : 'FAIL'}  ${label}\n      ${res.method} ${res.url} → HTTP ${res.status} / code=${res.body?.code ?? '-'} / 命中 ${hits.length}${hits.length ? `：${hits.map(mask).join(', ')}` : ''}`,
  );
}

// ---------------------------------------------------------------- 匿名
const list = await ctx.anon.get('/api/records?status=ALL&pageSize=50&page=1');
await scan('匿名-列表 PENDING/ALL 第 1 页', list);
const list2 = await ctx.anon.get('/api/records?status=ALL&pageSize=50&page=2');
await scan('匿名-列表 PENDING/ALL 第 2 页', list2);

for (const id of [1, 2, 3, 5, 6, 13, 14, 15, 16, 17, 18]) {
  const res = await ctx.anon.get(`/api/records/${id}`);
  await scan(`匿名-详情 #${id}`, res);
}

const anonMatch = await ctx.anon.post('/api/match', { body: { text: '图书馆 黑色钱包', type: 'FOUND', topN: 10, minScore: 0 } });
await scan('匿名-POST /api/match（应 401）', anonMatch);

// ---------------------------------------------------------------- 非本人
for (const id of [1, 2, 3, 5]) {
  const res = await ctx.demo02.get(`/api/records/${id}`);
  await scan(`非本人(demo02)-详情 #${id}（owner=demo01）`, res);
}
for (const id of [13, 16]) {
  const res = await ctx.demo01.get(`/api/records/${id}`);
  await scan(`非本人(demo01)-详情 #${id}（owner=demo02）`, res);
}

const m1 = await ctx.demo01.post('/api/match', { body: { text: '图书馆 黑色钱包 银行卡', type: 'FOUND', topN: 20, minScore: 0 } });
await scan('非本人(demo01)-/api/match 候选', m1);
const m2 = await ctx.demo02.post('/api/match', { body: { text: '图书馆 黑色钱包 银行卡', type: 'LOST', topN: 20, minScore: 0 } });
await scan('非本人(demo02)-/api/match 候选', m2);

// ---------------------------------------------------------------- 通知正文
for (const [name, api] of [['demo01', ctx.demo01], ['demo02', ctx.demo02], ['demo03', ctx.demo03]]) {
  const res = await api.get('/api/notifications?page=1&pageSize=50');
  await scan(`通知正文-${name}`, res);
  const bodies = (res.body?.data?.list ?? []).map((n) => n.body).join('\n');
  const titleText = (res.body?.data?.list ?? []).map((n) => n.title).join('\n');
  const hits = (bodies + '\n' + titleText).match(PHONE) ?? [];
  probes.push({ label: `通知 title/body 字段-${name}`, path: res.url, http: res.status, code: res.body?.code ?? null, hits: hits.map(mask) });
  log.push(`${hits.length === 0 ? 'PASS' : 'FAIL'}  通知 title/body 字段-${name}：${res.body?.data?.list?.length ?? 0} 条，命中 ${hits.length}`);
}

// ---------------------------------------------------------------- 汇总
const totalHits = probes.reduce((n, p) => n + p.hits.length, 0);
const failed = probes.filter((p) => p.hits.length > 0);
const summary = {
  generatedAt: new Date().toISOString(),
  regex: '1[3-9]\\d{9}',
  probes: probes.length,
  hits: totalHits,
  failedProbes: failed,
  cases: probes,
  verdict: totalHits === 0 ? 'PASS（0 命中）' : `FAIL（${totalHits} 命中）`,
};

log.unshift(
  `# F2 手机号泄漏全局扫描 · ${summary.generatedAt}`,
  `- 正则：1[3-9]\\d{9}`,
  `- 载体：匿名响应体 / 非本人响应体 / 通知 title+body`,
  `- 去噪：扫描前剔除 requestId（16 位随机十六进制，会偶发撞出号码形状，本轮实际撞到 1 次）`,
  `- 探针：${probes.length} 个；命中：${totalHits}`,
  `- 结论：${summary.verdict}`,
  '',
);

const shotsDir = resolve(root, 'docs', 'testing', 'v2');
await mkdir(shotsDir, { recursive: true });
await mkdir(resolve(here, '..', 'reports'), { recursive: true });
await writeFile(resolve(root, 'docs', 'testing', 'v2', 'F2-phone-scan.txt'), log.join('\n') + '\n', 'utf8');
await writeFile(resolve(here, '..', 'reports', 'f2-phone-scan.json'), JSON.stringify(summary, null, 2), 'utf8');
await writeFile(resolve(here, '..', 'reports', 'f2-phone-scan.txt'), log.join('\n') + '\n', 'utf8');

console.log(log.join('\n'));
console.log(`\n扫描输出：docs/testing/v2/F2-phone-scan.txt`);
process.exit(totalHits === 0 ? 0 : 1);
