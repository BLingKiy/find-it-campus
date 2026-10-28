// v2 · F3-R 组7 补充（任务项 7 第二口径）：以「重启后端」为触发点复验 P2-4（PairScanOnceRunner）。
//
// 用途：把过期扫描的另一个触发口径也测上 —— 进程启动时的 once 扫描（不是 ops 接口）。
//
// 为什么单独一个脚本：PairScanOnceRunner 不是无条件跑的 —— 它要 `--pair-scan-once` 这个启动参数
// （server/src/main/java/com/findit/campus/pair/config/PairScanOnceRunner.java:38-41）。
// 第一次重启没带该参数，所以启动扫描压根没触发，日志里没有 trigger=startup 汇总行。
// 这条脚本把那个缺口补上：自己重启（带参数）、从启动日志里读 once 汇总，再做一次前后对照。
//
// 判据与 verify 阶段一致，但触发点是「进程启动」而不是 ops 接口：
//   X1/X2（过期 COMPLETED）pair_state / pair_scored_at / updated_at 一字不变；
//   端点为 X1/X2 的 pair 行数与通知数不增；
//   日志里没有 record scored recordId=X1|X2，而 once 汇总行确实扫到了东西（scanned>0）。
//
// 前置：
//   · 先跑过 node qa/f3r/expired-scan.mjs prepare，qa/reports/f3r-expired-fixtures.json 里有 X1/X2 的 id；
//   · 18080 上确实有后端在跑（脚本要 taskkill 掉它再用 --pair-scan-once 重启，重启后的进程是脱离的，
//     收尾时可自行 taskkill）；
//   · MySQL 3306、jar 已构建（server/target/find-it-campus-0.0.1-SNAPSHOT.jar）、
//     JDK17 的 java（JAVA_BIN，默认 C:/Project/tools/jdk17/jdk-17.0.20.1+1/bin/java.exe）；
//   · qa/reports/server.log 存在（脚本用「字节偏移之后的新增部分」取证，日志必须是追加写的同一个文件）。
// 运行：node qa/f3r/expired-scan-startup.mjs
// 期望输出：留档 netstat/taskkill 命令与 PID、健康检查探测次数、once 汇总行，
//          末行「## 结论：以「重启」为触发点同样复验通过（P2-4 的两个触发口径一致）」；
//          落盘 qa/reports/f3r-expired-scan-startup.txt（本轮覆盖写），
//          2026-10-28 那轮的冻结归档见 qa/reports/f3r-2026-10-28-expired-scan-startup.txt。
// 退出码：0 = 判据全部成立；1 = 有失败项；2 = 环境不满足而中止（找不到 18080 监听进程 / 90 秒内没探活成功）。

import { execFile, spawn } from 'node:child_process';
import { readFile, stat, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { val, rows } from '../lib/db.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..', '..');
const LOG = resolve(root, 'qa', 'reports', 'server.log');
const JAVA = process.env.JAVA_BIN ?? 'C:/Project/tools/jdk17/jdk-17.0.20.1+1/bin/java.exe';
const JAR = 'server/target/find-it-campus-0.0.1-SNAPSHOT.jar';
const OPS_TOKEN = process.env.OPS_SCAN_TOKEN ?? '';
const FLAG = '--pair-scan-once';

const lines = [];
const say = (s) => {
  lines.push(s);
  console.log(s);
};
const run = (cmd, args) =>
  new Promise((res) => execFile(cmd, args, { encoding: 'utf8' }, (e, so, se) => res({ code: e ? 1 : 0, out: `${so}${se}` })));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const ids = JSON.parse(await readFile(resolve(root, 'qa', 'reports', 'f3r-expired-fixtures.json'), 'utf8')).ids;
const EXP = `${ids.X1},${ids.X2}`;

async function snapshot() {
  const recs = await rows(
    `SELECT id, status, pair_state,
            IFNULL(DATE_FORMAT(pair_scored_at,'%Y-%m-%d %H:%i:%s'),'NULL') AS scored_at,
            DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s.%f') AS updated_at
     FROM t_record WHERE id IN (${EXP}) ORDER BY id;`,
    ['id', 'status', 'pair_state', 'scored_at', 'updated_at'],
  );
  return {
    recs,
    pairs: Number(await val(`SELECT COUNT(*) FROM t_pair_score WHERE lost_id IN (${EXP}) OR found_id IN (${EXP});`)),
    notifs: Number(await val(
      `SELECT COUNT(*) FROM t_notification n JOIN t_pair_score p ON p.id=n.pair_id WHERE p.lost_id IN (${EXP}) OR p.found_id IN (${EXP});`,
    )),
    totalPairs: Number(await val('SELECT COUNT(*) FROM t_pair_score;')),
  };
}

say('# F3-R 项7 补充 · 以「重启后端 + --pair-scan-once」为触发点 · ' + new Date().toISOString());
const logOffset = (await stat(LOG)).size;
const before = await snapshot();
say(`- 重启前：X1/X2 = ${JSON.stringify(before.recs)}`);
say(`- 重启前：过期端 pair 行数=${before.pairs}，通知=${before.notifs}，全表 pair=${before.totalPairs}`);
say(`- 日志偏移：${logOffset} 字节（只读之后新增部分）`);
say('');

// 找到并结束当前 18080 监听进程
const net = await run('netstat', ['-ano', '-p', 'TCP']);
const pid = (net.out.split('\n').map((l) => l.trim()).filter((l) => l.includes(':18080') && /LISTEN/.test(l))[0] ?? '').split(/\s+/).pop();
if (!pid || !/^\d+$/.test(pid)) {
  say(`## 中止：没找到 18080 的监听进程（netstat 输出：${net.out.slice(0, 200)}）`);
  await writeFile(resolve(root, 'qa', 'reports', 'f3r-expired-scan-startup.txt'), lines.join('\n') + '\n', 'utf8');
  process.exit(2);
}
say(`## 重启命令留档`);
say(`- netstat -ano -p TCP | grep :18080 → PID ${pid}`);
say(`- taskkill /PID ${pid} /F`);
const kill = await run('taskkill', ['/PID', pid, '/F']);
say(`    ${kill.out.trim()}`);
await sleep(2000);

// 带 --pair-scan-once 重新启动，stdout/stderr 追加进同一个日志文件（保持 GBK 与原偏移口径）
const startCmd = `${JAVA} -jar ${JAR} ${FLAG}`;
say(`- ${startCmd}   （OPS_SCAN_TOKEN 已设为一次性本地值，此处不落档）>> qa/reports/server.log 2>&1`);
const logFh = await import('node:fs/promises').then((m) => m.open(LOG, 'a'));
const child = spawn(JAVA, ['-jar', JAR, FLAG], { cwd: root, env: { ...process.env, OPS_SCAN_TOKEN: OPS_TOKEN }, stdio: ['ignore', logFh.fd, logFh.fd], detached: true });
child.unref();

let up = false;
let lastProbe = '';
for (let i = 0; i < 60; i++) {
  // 用 Node 自己的 fetch 探活：execFile('curl') 在本机取不到 -w 的输出（探测确实打到了后端，
  // 但读数一直为空），换成进程内探活既稳定又不依赖 curl 的路径解析。
  try {
    const r = await fetch('http://localhost:18080/api/records?pageSize=1', { signal: AbortSignal.timeout(4000) });
    lastProbe = `HTTP ${r.status}`;
    if (r.status === 200) {
      up = true;
      say(`- 健康检查：第 ${i + 1} 次探测 ${lastProbe}`);
      break;
    }
  } catch (e) {
    lastProbe = String(e.message ?? e).slice(0, 80);
  }
  await sleep(1500);
}
if (!up) {
  say(`## 中止：60 次探测内后端没有回到 HTTP 200（最后一次：${lastProbe}）`);
  await writeFile(resolve(root, 'qa', 'reports', 'f3r-expired-scan-startup.txt'), lines.join('\n') + '\n', 'utf8');
  process.exit(2);
}

// once 扫描在 ApplicationRunner 里同步执行，日志行出现即代表跑完
let onceLine = [];
for (let i = 0; i < 40; i++) {
  await sleep(1000);
  const text = new TextDecoder('gbk').decode((await readFile(LOG)).subarray(logOffset, (await stat(LOG)).size));
  onceLine = text.split('\n').map((l) => l.trim()).filter((l) => /pair scan once|trigger=startup/.test(l));
  if (onceLine.some((l) => /pair scan once done/.test(l))) break;
}

const text = new TextDecoder('gbk').decode((await readFile(LOG)).subarray(logOffset, (await stat(LOG)).size));
const allNew = text.split('\n').map((l) => l.trim()).filter(Boolean);
const after = await snapshot();

say('');
say('## 启动扫描日志（本轮新增，GBK 解码）');
for (const l of allNew.filter((x) => /Started FindItCampusApplication|pair scan once|trigger=startup|record scored/.test(x)).slice(0, 20)) say(`  ${l}`);
const scoredX = allNew.filter((l) => /pair record scored/.test(l) && new RegExp(`recordId=(${ids.X1}|${ids.X2})\\b`).test(l));
const writtenX = allNew.filter((l) => /pair score written/.test(l) && (l.includes(`lostId=${ids.X1}`) || l.includes(`foundId=${ids.X2}`) || l.includes(`lostId=${ids.X2}`) || l.includes(`foundId=${ids.X1}`)));
const onceDone = allNew.filter((l) => /pair scan once/.test(l));
const scannedAny = allNew.filter((l) => /pair record scored/.test(l));

say('');
say('## 前后对照');
say(`- X1/X2 重启前：${JSON.stringify(before.recs)}`);
say(`- X1/X2 重启后：${JSON.stringify(after.recs)}`);
say(`- 过期端 pair：${before.pairs} → ${after.pairs}；通知：${before.notifs} → ${after.notifs}；全表 pair：${before.totalPairs} → ${after.totalPairs}`);
say(`- 新增日志里 record scored 总行数=${scannedAny.length}，其中命中 X1/X2 的=${scoredX.length}；pair score written 命中 X1/X2 端点的=${writtenX.length}`);

const sameRows = JSON.stringify(before.recs) === JSON.stringify(after.recs);
const checks = [
  ['U1 启动扫描（--pair-scan-once）确实跑了：日志有 pair scan once 汇总行', onceDone.length > 0, onceDone.join(' | ') || '无汇总行'],
  ['U2 扫描真的在做的事：新增日志里存在 record scored 行（非零工作量）', scannedAny.length > 0, `record scored ${scannedAny.length} 行`],
  ['U3 过期夹具未被打分：X1/X2 的 record scored / written 次数为 0', scoredX.length === 0 && writtenX.length === 0, `scored=${scoredX.length} written=${writtenX.length}`],
  ['U4 库层零变化：X1/X2 全行指纹不变，过期端 pair 与通知数不增', sameRows && after.pairs === before.pairs && after.notifs === before.notifs, `pairs ${before.pairs}→${after.pairs}，notifs ${before.notifs}→${after.notifs}`],
];
say('');
say('## 判据');
let failed = 0;
for (const [name, pass, detail] of checks) {
  say(`${pass ? 'PASS' : 'FAIL'}  ${name}`);
  say(`        ${detail}`);
  if (!pass) failed++;
}
say('');
say(`## 结论：${failed === 0 ? '以「重启」为触发点同样复验通过（P2-4 的两个触发口径一致）' : `${failed} 项未通过`}`);
await writeFile(resolve(root, 'qa', 'reports', 'f3r-expired-scan-startup.txt'), lines.join('\n') + '\n', 'utf8');
say('证据：qa/reports/f3r-expired-scan-startup.txt');
process.exit(failed === 0 ? 0 : 1);
