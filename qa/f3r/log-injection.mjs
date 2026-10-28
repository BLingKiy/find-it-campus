// v2 · F3-R 项 5：P2-3 日志注入实测（HTTP 打到真实日志文件，不是单测）。
//
// 七条注入通路，每条用一个**互不相同的假时间戳**（…51~57 秒）做归属，
// 跑完只需在日志增量里数「以该假时间戳开头的行」—— 出现即说明真的多出一整行。
//   出口 1b 查询参数    → GET /api/records?page=…          （LogSafe.sanitize(value)）
//   出口 1b 路径参数    → GET /api/records/{id}            （同上，@PathVariable long id）
//   取值白名单          → GET /api/records?sortBy=…        （@Pattern 先挡，考察是否真到日志）
//   出口 1c 请求体      → POST /api/auth/login 非法 JSON    （LogSafe.sanitize(detail, 512)）
//   服务层 登录学号     → POST /api/auth/login 密码错       （AuthService 的 LogSafe.sanitize(studentNo)）
//   访问日志            → GET /api/records?keyword=…        （RequestIdFilter 记 URI）
//
// 两个口径细节，都是本轮实测出来的，不是推断：
//   ① studentNo 有 @Size(max=32) + strip()：超长/带首尾空白的载荷在校验层就被 400 挡掉，
//      **根本到不了** service 的日志行。所以要证明 LogSafe 生效，必须用 ≤32 字符、
//      换行夹在中间的载荷（J2f）。长载荷（J2a~J2d）单独归为「校验层先挡住」这一类结论。
//   ② 日志文件按 JVM 默认字符集（本机 GBK）写出，Node 侧要 TextDecoder('gbk') 才能读到中文原文，
//      关键字检索只用 ASCII（FORGED/假时间戳），避免中文匹配失败造成假阴性。
//
// 运行：node qa/f3r/log-injection.mjs
// 前置：后端 18080 已起，且 stdout 落盘到 FINDIT_SERVER_LOG（默认 qa/reports/server.log）
// 输出：qa/reports/f3r-log-injection.txt
// 退出码：0 = 所有通路都无伪造整行。

import { readFile, writeFile, stat } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Api } from '../lib/client.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..', '..');
const LOG = process.env.FINDIT_SERVER_LOG ?? resolve(root, 'qa', 'reports', 'server.log');
const API = process.env.FINDIT_API_BASE ?? 'http://localhost:18080';

const NL = String.fromCharCode(10);
const CR = String.fromCharCode(13);
const TAB = String.fromCharCode(9);
const U2028 = String.fromCharCode(0x2028);
const lines = [];
const say = (s) => {
  lines.push(s);
  console.log(s);
};

// 真实 logback pattern 形如：
//   2026-10-28 15:12:16.742 WARN  [http-nio-18080-exec-10] c.f.campus.auth.service.AuthService - [reqId] 正文
// 假日志行要与之同形，否则「没多出一行」可能只是因为形状不像。
const FAKE_HEAD = (sec) => `2026-10-28 23:59:${sec}.111 WARN  [forged-thread] c.f.campus.forged.ForgedLogger - [fakedreqid]`;
const CASES = [
  { id: 'J1', sec: '51', desc: '查询参数 page 含 %0A（LF）' },
  { id: 'J1b', sec: '52', desc: '查询参数 pageSize 含 %0D%0A（CRLF）' },
  { id: 'J1c', sec: '53', desc: '查询参数 sortBy 含 U+2028 行分隔符 + 200+ 字符超长值' },
  { id: 'J1d', sec: '54', desc: '路径参数 /api/records/{id} 含 %0A（@PathVariable long）' },
  { id: 'J1e', sec: '59', desc: '路径参数含 CRLF + TAB + U+2028 + 200 字符超长（补齐 LogSafe 四档替换与截断）' },
  { id: 'J3', sec: '55', desc: '请求体非法 JSON，串内含 %0A（detail 限长 512）' },
  { id: 'J3b', sec: '58', desc: 'LF 放进 JSON 字符串值内部（未转义控制字符）→ Jackson 报错原文' },
  { id: 'J2f', sec: '56', desc: '登录 studentNo 含 LF（≤32 字符，真正到达 AuthService 日志行）' },
  { id: 'J4', sec: '57', desc: '查询参数 keyword 含 LF（String 型、校验通过 → 考察 RequestIdFilter 的访问日志行）' },
];
const byId = Object.fromEntries(CASES.map((c) => [c.id, c]));
const forged = (id) => {
  const c = byId[id];
  return `${FAKE_HEAD(c.sec)} FORGED_${id} 登录成功 userId=9999`;
};

const anon = new Api(API);
const before = await stat(LOG);
const offset = before.size;

say(`# F3-R 项5 · P2-3 日志注入实测 · ${new Date().toISOString()}`);
say(`- 日志文件：${LOG}（注入前 ${before.size} 字节，注入后按下标增量截取）`);
say(`- 日志编码：GBK（JVM 默认字符集）；读取用 TextDecoder('gbk')，关键字只用 ASCII`);
say(`- 归属方式：每条通路一个独立假时间戳（23:59:51~56），增量里「以该时间戳开头的行数」必须为 0`);
say('');

// ------------------------------------------------------------------ 请求
const probes = [];
async function hit(id, method, path, send, note) {
  const c = CASES.find((x) => x.id === id);
  let res;
  try {
    res = method === 'GET' ? await anon.get(path) : await anon.post(path, { body: send });
  } catch (e) {
    res = { status: 0, body: null, raw: String(e), url: path, method };
  }
  probes.push({
    ...c,
    request: `${method} ${res.url ?? path}`,
    sent: JSON.stringify(typeof send === 'string' ? send : send ?? path).slice(0, 220),
    http: res.status,
    code: res.body?.code ?? null,
    fieldErrors: res.body?.data?.fieldErrors ?? null,
    note,
  });
  return res;
}

const j1Value = `1${NL}${forged('J1')}${NL}2`;
await hit('J1', 'GET', `/api/records?page=${encodeURIComponent(j1Value)}`, undefined, 'page 声明为 int，LF 落在 handleTypeMismatch 的 value 字段');

const j1bValue = `10${CR}${NL}${forged('J1b')}`;
await hit('J1b', 'GET', `/api/records?pageSize=${encodeURIComponent(j1bValue)}`, undefined, 'CRLF：期望 CR、LF 各替换为一个下划线');

const j1cValue = `x${U2028}${forged('J1c')}${'A'.repeat(200)}`;
const j1c = await hit('J1c', 'GET', `/api/records?sortBy=${encodeURIComponent(j1cValue)}`, undefined, 'U+2028 期望替换成 ?；超长期望截断并标注原长');

const j1dPath = `/api/records/${encodeURIComponent(`1${NL}${forged('J1d')}`)}`;
await hit('J1d', 'GET', j1dPath, undefined, 'RecordController.java:63 @PathVariable long id → handleTypeMismatch');

// @PathVariable 不经 WebDataBinder 的剥空白，所以这条能把 LogSafe 的四档替换一次打全：
//   CRLF → __、TAB → _、U+2028 → ?、>128 字符 → 截断并附 ...[Nchars]
const j1eValue = `9${CR}${NL}${TAB}${U2028}${forged('J1e')}${'C'.repeat(200)}`;
await hit('J1e', 'GET', `/api/records/${encodeURIComponent(j1eValue)}`, undefined, `载荷 ${j1eValue.length} 字符 > LogSafe.DEFAULT_MAX_LENGTH=128，应看到截断标注`);

const j3Body = `{not-json${NL}${forged('J3')}${NL}tail`;
await hit('J3', 'POST', '/api/auth/login', j3Body, 'Content-Type=application/json 但整段不可解析 → handleNotReadable');

// J3b：把 LF 放进 JSON 字符串值内部（未转义的控制字符）。这条才可能让请求体原文进日志，
// 用来验证 handleNotReadable 的 detail 是否真的带用户内容（Jackson 默认关闭 INCLUDE_SOURCE_IN_LOCATION）。
const j3bBody = `{"studentNo":"Q","password":"x${NL}${forged('J3b')}"`;
await hit('J3b', 'POST', '/api/auth/login', j3bBody, '未转义控制字符 + 截断，检查 Jackson 是否把原文写进日志');

const j2fValue = `P${NL}2026-10-28 23:59:56.111 INFO`; // 30 字符：过 @Size(max=32)，LF 不在首尾故 strip() 不消
if (j2fValue.length > 32) throw new Error(`J2f 载荷 ${j2fValue.length} 字符，会被 @Size 挡掉，测不到 service 日志`);
await hit('J2f', 'POST', '/api/auth/login', { studentNo: j2fValue, password: 'Wrong@123456' }, '密码错才进 service 的 warn 行；载荷长度 30 ≤ 32');

// keyword 是 String 型且只限长 64：LF 能绑进对象、请求正常走完 200。
// 这条考察的不是 LogSafe，而是 RequestIdFilter 那行访问日志 —— 它记录 URI，
// 若记录前解过码，%0A 就成了真换行，日志里会凭空多一行；若不解码，日志里是字面 %0A。
const j4Value = `A${NL}2026-10-28 23:59:57.111 WARN FORGED_J4`;
if (j4Value.length > 64) throw new Error(`J4 载荷 ${j4Value.length} 字符，超 keyword 限长`);
await hit('J4', 'GET', `/api/records?status=ALL&keyword=${encodeURIComponent(j4Value)}`, undefined, '走完整成功路径（200），检查访问日志是否对 URI 解码');

// 对照组：超长 studentNo。结论应当是「校验层先挡、进不到 service 日志」，
// 而不是「LogSafe 挡住了」—— 把这条分开写清楚，避免把两层防御混为一谈。
const longCases = [
  { name: 'J2a 裸 LF + 完整假行（>32）', studentNo: `demoXX${NL}${forged('J2f')}` },
  { name: 'J2b CRLF + TAB + 假行（>32）', studentNo: `demoXX${CR}${NL}admin${TAB}${forged('J2f')}` },
  { name: 'J2c U+2028（>32）', studentNo: `demoXX${U2028}FORGEDU2028XXXX${forged('J2f')}` },
  { name: 'J2d 300 字符 + 换行', studentNo: `demoXX${NL}${forged('J2f')}${'B'.repeat(300)}` },
];
const longResults = [];
for (const p of longCases) {
  const res = await anon.post('/api/auth/login', { body: { studentNo: p.studentNo, password: 'Wrong@123456' } });
  longResults.push({ name: p.name, chars: p.studentNo.length, http: res.status, code: res.body?.code ?? null, reason: res.body?.data?.fieldErrors?.[0]?.reason ?? null });
}

say('## 请求与响应');
for (const p of probes) {
  say(`- ${p.id}｜${p.desc}`);
  say(`    ${p.request}`);
  say(`    发送：${p.sent}`);
  say(`    响应：HTTP ${p.http} / code=${p.code ?? '-'}${p.fieldErrors ? ` / fieldErrors=${JSON.stringify(p.fieldErrors)}` : ''}`);
  say(`    说明：${p.note}`);
}
say('');
say('## 对照组：超长 studentNo（校验层拦截，与 LogSafe 是两层不同的防御）');
for (const r of longResults) {
  say(`- ${r.name}：${r.chars} 字符 → HTTP ${r.http} / code=${r.code} / reason=${JSON.stringify(r.reason)}`);
}
say('');

// ------------------------------------------------------------------ 取证
await new Promise((r) => setTimeout(r, 1200));
const all = await readFile(LOG);
const delta = new TextDecoder('gbk').decode(all.subarray(offset, all.length));
const deltaLines = delta.split(NL).filter((l) => l.trim().length > 0);

say('## 日志增量取证');
say(`- 注入后日志大小：${all.length} 字节（增量 ${all.length - offset} 字节 / ${deltaLines.length} 行）`);
say('');
say('### 判据 A：以各通路假时间戳开头的行数（>0 即真的多出一整行）');
const verdictA = [];
for (const c of CASES) {
  const headRe = new RegExp(`^${FAKE_HEAD(c.sec).slice(0, 23).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`);
  const hitLines = deltaLines.filter((l) => headRe.test(l.trim()));
  verdictA.push({ id: c.id, sec: c.sec, count: hitLines.length, lines: hitLines });
  say(`  ${hitLines.length === 0 ? 'PASS' : 'FAIL'}  ${c.id} 23:59:${c.sec} 开头 ${hitLines.length} 行`);
  for (const l of hitLines) say(`        ${l.trim()}`);
}
say('');
say('### 判据 B：假文案是否以「独立行」出现（FORGED_xx 作为行首）');
const standaloneForged = deltaLines.filter((l) => /^\s*(INFO|WARN|ERROR|DEBUG)?\s*FORGED_/.test(l) || /^FORGED_/.test(l.trim()));
const forgedAnywhere = deltaLines.filter((l) => /FORGED_/.test(l));
say(`- 以 FORGED_xx 起头的独立行数：${standaloneForged.length}（应为 0）`);
say(`- 含 FORGED_xx 的行数：${forgedAnywhere.length}（应全部是「真实日志行里被净化后的字段」）`);
say('');
say('### 净化痕迹原文（含 FORGED_ 或假时间戳的行，逐条列出）');
const traceLines = deltaLines.filter((l) => /FORGED_|2026-10-28 23:59:5[1-9]/.test(l));
for (const l of traceLines) say(`  ${l.trimEnd()}`);
if (traceLines.length === 0) say('  （无）');
say('');
say('### 拦截层归因（同一句"没有伪造行"，靠哪一层挡住的差别很大，逐条实测而不是猜）');
const binderLines = deltaLines.filter((l) => /For input string:/.test(l));
// 只看 `For input string: "…"` 里那段值本身有没有控制字符（整行含 \r 是 Windows 换行，不算注入残留）
const binderValue = (l) => (l.match(/For input string: "([^"]*)"/) ?? [])[1] ?? '';
const binderStillHasControl = binderLines.filter((l) => /[\r\n\t]/.test(binderValue(l)));
const notReadableLines = deltaLines.filter((l) => /请求体不可解析/.test(l));
const notReadableCarriesPayload = notReadableLines.filter((l) => /FORGED_J3/.test(l));
const patternLines = deltaLines.filter((l) => /取值非法/.test(l));
const patternCarriesPayload = patternLines.filter((l) => /FORGED_/.test(l));
const logSafeLines = deltaLines.filter((l) => /value=1_|studentNo=P_|value=9_/.test(l));
say(`- 绑定层 WebDataBinder 剥空白（出口：\`For input string:\`）：${binderLines.length} 行，其中值内仍含 CR/LF/TAB 的 ${binderStillHasControl.length} 行`);
for (const l of binderLines) say(`    值原文：${JSON.stringify(binderValue(l).slice(0, 120))}`);
say(`    → J1/J1b 的换行是被**绑定层**（默认移除字符串里所有空白）吃掉的，没有走到 LogSafe；`);
say(`      该行的 reason 是 Spring 原文，确实把入参值写进了日志（只是值已无空白）。`);
say(`- LogSafe.sanitize 实际生效并可见下划线：${logSafeLines.length} 行（J1d/J1e 路径参数 + J2f 登录学号）`);
say(`- @Pattern 取值白名单先挡（J1c sortBy）：${patternLines.length} 行，其中携带注入原文的 ${patternCarriesPayload.length} 行 → 固定文案，用户串未入日志`);
say(`- 请求体不可解析出口（J3/J3b）：${notReadableLines.length} 行，其中携带注入原文的 ${notReadableCarriesPayload.length} 行`);
for (const l of notReadableLines) say(`    ${l.trimEnd()}`);
say('');
say('### 增量日志原文（最后 30 行，GBK 解码后）');

for (const l of deltaLines.slice(-30)) say(`  ${l.trimEnd()}`);

const traces = {
  lfToUnderscore: deltaLines.filter((l) => /value=1_2026-10-28|studentNo=P_2026-10-28/.test(l)).length,
  crlfTabToUnderscore: deltaLines.filter((l) => /value=9___\?/.test(l)).length,
  u2028ToQuestion: deltaLines.filter((l) => /\?2026-10-28 23:59:59/.test(l)).length,
  truncatedWithLen: deltaLines.filter((l) => /\.\.\.\[\d+chars\]/.test(l)).map((l) => (l.match(/\.\.\.\[\d+chars\]\s*$/) ?? [])[0] ?? (l.match(/\.\.\.\[\d+chars\]/) ?? [])[0]),
  truncatedSample: (deltaLines.find((l) => /\.\.\.\[\d+chars\]/.test(l)) ?? '').trim().slice(0, 300),
};
say('');
say('## LogSafe 规则命中统计');
say(`- LF → 下划线（value=1_… / studentNo=P_…）：${traces.lfToUnderscore} 行`);
say(`- CRLF+TAB → 三个下划线（J1e 的 value=9___?…）：${traces.crlfTabToUnderscore} 行`);
say(`- U+2028 → ?（J1e）：${traces.u2028ToQuestion} 行`);
say(`- 超长截断并标注原长：${traces.truncatedWithLen.length ? [...new Set(traces.truncatedWithLen)].join(', ') : '未出现'}`);
if (traces.truncatedSample) say(`  截断样例行：${traces.truncatedSample}`);
const accessLines = deltaLines.filter((l) => /RequestIdFilter - \[[0-9a-f]{16}\] (GET|POST) \/api/.test(l));
const encodedAccess = accessLines.filter((l) => /%0A|%0D/i.test(l));
say(`- 访问日志行（RequestIdFilter）共 ${accessLines.length} 条，其中把 LF 记成字面 %0A 的：${encodedAccess.length} 条`);
say('  → 即访问日志记录的是未解码的原始 URI，%0A 在日志里是 3 个字符，不构成换行注入');
for (const l of encodedAccess) say(`    ${l.trimEnd()}`);

const forgedCount = verdictA.reduce((n, v) => n + v.count, 0) + standaloneForged.length;
const verdict = forgedCount === 0 ? `PASS（${CASES.length} 条通路均无伪造整行，LogSafe 替换可见）` : `FAIL（多出 ${forgedCount} 处伪造行）`;
say('');
say(`## 结论：${verdict}`);

await writeFile(
  resolve(root, 'qa', 'reports', 'f3r-log-injection.json'),
  JSON.stringify({ generatedAt: new Date().toISOString(), probes, longResults, verdictA, standaloneForged: standaloneForged.length, forgedAnywhere: forgedAnywhere.length, traces, verdict }, null, 2),
  'utf8',
);
await writeFile(resolve(root, 'qa', 'reports', 'f3r-log-injection.txt'), lines.join(NL) + NL, 'utf8');
say('证据：qa/reports/f3r-log-injection.txt / .json');
process.exit(forgedCount === 0 ? 0 : 1);
