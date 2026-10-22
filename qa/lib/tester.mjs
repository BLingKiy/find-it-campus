// 极简测试执行器：AAA 结构由用例自己保证，这里只负责记录 PASS / FAIL 与失败原因。
// 不引第三方断言库 —— 断言要能一眼看出「期望 vs 实际」，JSON 序列化足够。

import { writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { mkdir } from 'node:fs/promises';

const here = dirname(fileURLToPath(import.meta.url));

export const show = (v) => {
  if (v === undefined) return 'undefined';
  if (v === null) return 'null';
  if (typeof v === 'string') return JSON.stringify(v.length > 120 ? v.slice(0, 120) + '…' : v);
  try {
    const s = JSON.stringify(v);
    return s.length > 200 ? s.slice(0, 200) + '…' : s;
  } catch {
    return String(v);
  }
};

export function assert(cond, msg) {
  if (!cond) throw new Error(msg || '断言失败');
}

export function eq(actual, expected, label) {
  const a = show(actual);
  const b = show(expected);
  if (a !== b) throw new Error(`${label || '值'}：期望 ${b}，实际 ${a}`);
}

export function truthy(v, label) {
  if (!v) throw new Error(`${label || '值'}：期望为真，实际 ${show(v)}`);
}

export class Tester {
  constructor(title) {
    this.title = title;
    this.results = [];
    this.startedAt = new Date();
  }

  /**
   * 一条用例。fn 抛异常即 FAIL，异常 message 就是失败原因。
   * meta.knownDefect = 缺陷编号时，该用例的失败会被标成 KNOWN_FAIL：
   * 仍然是失败（不粉饰），但统计上与「原因不明的新失败」分开，避免用已知缺陷把回归信号搅浑。
   */
  async case(id, priority, name, fn, meta = {}) {
    const t0 = Date.now();
    try {
      await fn();
      this.results.push({ id, priority, name, status: 'PASS', durationMs: Date.now() - t0 });
    } catch (err) {
      this.results.push({
        id,
        priority,
        name,
        status: meta.knownDefect ? 'KNOWN_FAIL' : 'FAIL',
        knownDefect: meta.knownDefect ?? null,
        reason: err && err.message ? err.message : String(err),
        durationMs: Date.now() - t0,
      });
    }
  }

  get passed() {
    return this.results.filter((r) => r.status === 'PASS').length;
  }

  /** 失败 = 原因不明的失败 + 已登记缺陷导致的失败（报告里两者分开列，总数不缩水）。 */
  get failed() {
    return this.results.filter((r) => r.status === 'FAIL' || r.status === 'KNOWN_FAIL').length;
  }

  get knownFailures() {
    return this.results.filter((r) => r.status === 'KNOWN_FAIL').length;
  }

  summary() {
    const total = this.results.length;
    const pct = total ? ((this.passed / total) * 100).toFixed(1) : '0.0';
    return {
      title: this.title,
      startedAt: this.startedAt.toISOString(),
      total,
      passed: this.passed,
      failed: this.failed,
      knownFailures: this.knownFailures,
      passRate: `${pct}%`,
      durationMs: Date.now() - this.startedAt.getTime(),
    };
  }

  /** 同时落 JSON（机器读）与 TXT（人读、可直接贴进报告）。 */
  async writeReports(dir) {
    await mkdir(dir, { recursive: true });
    const base = this.title.replace(/[^\w.-]+/g, '-');
    const jsonPath = resolve(dir, `${base}.json`);
    const txtPath = resolve(dir, `${base}.txt`);
    const payload = { summary: this.summary(), cases: this.results };
    await writeFile(jsonPath, JSON.stringify(payload, null, 2), 'utf8');

    const lines = [];
    lines.push(`# ${this.title}`);
    const s = this.summary();
    lines.push(
      `开始时间: ${s.startedAt} | 用例总数: ${s.total} | 通过: ${s.passed} | 失败: ${s.failed}（其中已登记缺陷: ${s.knownFailures}）| 通过率: ${s.passRate} | 耗时: ${s.durationMs}ms`,
    );
    lines.push('');
    for (const r of this.results) {
      const head = `[${r.status}] ${r.id} (${r.priority}) ${r.name}  ${r.durationMs}ms`;
      lines.push(head);
      if (r.status !== 'PASS') {
        lines.push(`      失败原因: ${r.reason}${r.knownDefect ? `  ← 已登记缺陷 ${r.knownDefect}` : ''}`);
      }
    }
    lines.push('');
    lines.push('--- 失败明细 ---');
    const fails = this.results.filter((r) => r.status !== 'PASS');
    if (fails.length === 0) {
      lines.push('（无）');
    } else {
      for (const r of fails) {
        lines.push(
          `${r.id} (${r.priority}) ${r.name}${r.knownDefect ? ` [${r.knownDefect}]` : ''}\n    ${r.reason}`,
        );
      }
    }
    await writeFile(txtPath, lines.join('\n'), 'utf8');
    return { jsonPath, txtPath };
  }

  print() {
    for (const r of this.results) {
      if (r.status === 'PASS') {
        console.log(`  PASS  ${r.id} [${r.priority}] ${r.name} (${r.durationMs}ms)`);
      } else {
        const tag = r.status === 'KNOWN_FAIL' ? `KNOWN_FAIL ${r.knownDefect}` : 'FAIL';
        console.log(`  ${tag}  ${r.id} [${r.priority}] ${r.name} (${r.durationMs}ms)`);
        console.log(`        → ${r.reason}`);
      }
    }
    const s = this.summary();
    console.log('');
    console.log(
      `  ${s.title}: 总数 ${s.total} / 通过 ${s.passed} / 失败 ${s.failed}（已登记缺陷 ${s.knownFailures}） / 通过率 ${s.passRate}`,
    );
    return s;
  }
}

export const reportsDir = (...p) => resolve(here, '..', 'reports', ...p);
