// v2 · F3-R 项 3：P1 八类绕过输入的 HTTP 出口实测（不是单测）。
//
// 判定为什么要「双号分侧」：契约 §4.2/§4.12 规定本人视角的自有内容返回原文
// （RecordDetailResponse 的 ownerView 分支、MyRecordListItemResponse.title=row.getTitle()）。
// 若两侧写同一个号码，「本人读到自己原文」与「越权读到对端原文」在字符串层无法区分，
// 判定会把契约允许的回显误报成泄漏（第一版就踩了这个）。这里 A 侧（demo01）与 B 侧（demo02）
// 各用不同号码：
//   越权/匿名/对端方向 → A 与 B 都必须 0 命中；
//   本人自有内容回显 → 只允许出现自己的号，出现对端的号即为回归。
//
// 两把尺子（缺一不可）：
//   exact   —— 归一后的连续数字串（11 位手机号 / 18 位证件号）出现在出口 ⇒ 泄漏；
//   rawKeep —— 用户原始写法（带分隔符的整串）原样出现在出口 ⇒ 号码仍可整段读出，同样按泄漏计。
// 只用泛化正则 1[3-9]\d{9} 会漏判带分隔符的绕过。
//
// 运行：node qa/f3r/p1-bypass.mjs     前置：后端 18080 已起（每条用例自己 resetSeed）
// 输出：qa/reports/f3r-p1-bypass.{txt,json}
// 退出码：0 = 八类绕过的越权方向 0 命中且对照组不误伤。

import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { contexts } from '../lib/auth.mjs';
import { pairByEnds, sql, upsertPair, waitForScoring, resetSeed } from '../lib/db.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..', '..');
const PHONE_RE = /1[3-9]\d{9}/g;
const ZW = '​';

const ctx = await contexts();
const lines = [];
const say = (s) => {
  lines.push(s);
  console.log(s);
};

/** JSON 里的字符串值是解码后的，直接扫 raw 会把 \n / \t 的转义形式当成原文。 */
const stringsOf = (node, acc = []) => {
  if (typeof node === 'string') acc.push(node);
  else if (Array.isArray(node)) node.forEach((n) => stringsOf(n, acc));
  else if (node && typeof node === 'object') Object.values(node).forEach((n) => stringsOf(n, acc));
  return acc;
};
const textOf = (res) => (res.body ? stringsOf(res.body) : [String(res.raw ?? '')]).join('\n');

/** 对同一个号码串，分别量「归一后连续数字」与「原始写法」两种可读形态。 */
function probeNumber(text, token) {
  const s = String(text ?? '').replace(/"requestId"\s*:\s*"[^"]*"/g, '<noise>');
  return {
    token: token.label,
    exact: (s.match(new RegExp(token.norm, 'g')) ?? []).length,
    rawKeep: s.includes(token.raw) ? 1 : 0,
    sample: s.split('\n').filter((l) => l.includes(token.norm) || l.includes(token.raw)).slice(0, 2),
  };
}

// A 侧=demo01 的号，B 侧=demo02 的号；norm 是归一后的连续数字串。
const A_PHONE = '13812345678';
const B_PHONE = '13923456789';
const A_ID = '110101199001011234';
const B_ID = '110202199002021234';

// 八类绕过串（任务正文逐条）；B8 是 18 位证件号分段。
const BYPASS = [
  { key: 'B1', label: '空格拆', kind: 'phone', a: '138 1234 5678', b: '139 2345 6789' },
  { key: 'B2', label: '换行拆', kind: 'phone', a: '138\n1234\n5678', b: '139\n2345\n6789' },
  { key: 'B3', label: '制表符拆', kind: 'phone', a: '138\t1234\t5678', b: '139\t2345\t6789' },
  { key: 'B4', label: '连字符拆', kind: 'phone', a: '138-1234-5678', b: '139-2345-6789' },
  { key: 'B5', label: '零宽字符拆', kind: 'phone', a: `1${ZW}38${ZW}1234${ZW}5678`, b: `1${ZW}39${ZW}2345${ZW}6789` },
  { key: 'B6', label: '全角空格拆', kind: 'phone', a: '138　1234　5678', b: '139　2345　6789' },
  { key: 'B7', label: '国际前缀', kind: 'phone', a: '+8613812345678', b: '+8613923456789' },
  { key: 'B8', label: '证件号分段', kind: 'id', a: '1101 0119 9001 0112 34', b: '1102 0219 9002 0212 34' },
];

const tokensFor = (c) => {
  const [na, nb] = c.kind === 'id' ? [A_ID, B_ID] : [A_PHONE, B_PHONE];
  return {
    A: { label: `A(demo01) ${c.a.replace(/\s/g, '␣')}`, raw: c.a, norm: na },
    B: { label: `B(demo02) ${c.b.replace(/\s/g, '␣')}`, raw: c.b, norm: nb },
  };
};

async function publish(api, type, marker, injected, contact) {
  const res = await api.post('/api/records', {
    body: {
      type,
      title: `${marker} 拾到物品 请联系 ${injected}`,
      description: `${marker} F3-R 出口实测。正文描述里的联系方式写作 ${injected}，请据此联系我。`,
      location: '图书馆三楼自习区',
      occurredAt: '2026-10-20 10:00:00',
      contact,
    },
  });
  if (res.status !== 200 || res.body?.code !== 0) {
    throw new Error(`夹具发布失败 ${marker}：HTTP ${res.status} / ${JSON.stringify(res.body)}`);
  }
  return res.body.data.id;
}

/** 一条绕过串跑一整轮：发布 → 配对 → 附言 → 8 个出口读取。 */
async function runCase(c) {
  const T = tokensFor(c);
  await resetSeed();
  const marker = `F3R${c.key}`;
  const lostId = await publish(ctx.demo01, 'LOST', `${marker}L`, c.a, '13800000001');
  const foundId = await publish(ctx.demo02, 'FOUND', `${marker}F`, c.b, '13800000002');
  await waitForScoring([lostId, foundId]);
  await upsertPair({ lostId, foundId, score: 0.95, status: 'NEW' });
  const pair = await pairByEnds(lostId, foundId);
  const pairId = Number(pair.id);
  // 附言走真实 HTTP（PairUnlockService.maskNote 是写入即掩码，落库就应当不含号码）
  const unlockA = await ctx.demo01.post(`/api/pairs/${pairId}/contact-unlock`, {
    body: { action: 'PUBLISH', note: `A 侧附言：我的号码 ${c.a}，随时打给我` },
  });
  const unlockB = await ctx.demo02.post(`/api/pairs/${pairId}/contact-unlock`, {
    body: { action: 'PUBLISH', note: `B 侧附言：我的号码 ${c.b}，随时打给我` },
  });
  for (const [name, r] of [['A', unlockA], ['B', unlockB]]) {
    if (r.status !== 200 || r.body?.code !== 0) throw new Error(`附言解锁失败 ${name}：HTTP ${r.status} / ${JSON.stringify(r.body)}`);
  }
  const matchAsA = await ctx.demo01.post('/api/match', { body: { text: `${marker}F 招领物品 回电联系`, type: 'LOST', topN: 20, minScore: 0 } });
  const matchAsB = await ctx.demo02.post('/api/match', { body: { text: `${marker}L 丢失物品 回电联系`, type: 'FOUND', topN: 20, minScore: 0 } });

  // 出口定义：direction 决定判哪个号。
  //   privacy = 匿名/越权/对端方向，A 与 B 都必须 0；
  //   self    = 本人自有内容回显（契约 §4.2/§4.12 原文），只判「不得出现对端号」。
  const exits = [];
  const add = (name, direction, text, source, note, selfTokens = []) => {
    const judged = direction === 'privacy' ? [T.A, T.B] : selfTokens;
    const checks = judged.map((t) => probeNumber(text, t));
    const leaksSelf = direction === 'self' ? checks.reduce((n, p) => n + p.exact + p.rawKeep, 0) : 0;
    const otherChecks = direction === 'self' ? [T.A, T.B].filter((t) => !selfTokens.includes(t)).map((t) => probeNumber(text, t)) : [];
    const leaksOther = otherChecks.reduce((n, p) => n + p.exact + p.rawKeep, 0);
    const leaks = direction === 'privacy' ? checks.reduce((n, p) => n + p.exact + p.rawKeep, 0) : leaksOther;
    exits.push({ exit: name, direction, source, note, leaks, leaksOther, selfVisible: leaksSelf > 0, checks: [...checks, ...otherChecks] });
  };

  // 出口 1：匿名列表
  let anonListText = '';
  let anonListPage = null;
  for (let page = 1; page <= 6 && !anonListPage; page++) {
    const r = await ctx.anon.get(`/api/records?status=ALL&pageSize=50&page=${page}`);
    if (String(r.raw).includes(marker)) {
      anonListPage = r;
      anonListText = textOf(r);
    }
  }
  add('出口1 匿名列表 GET /api/records?status=ALL', 'privacy', anonListText,
    anonListPage ? `GET ${anonListPage.url} → HTTP ${anonListPage.status}` : '未在任何页找到该记录', 'title 遮蔽视角');
  // 出口 2：匿名详情
  const anonLost = await ctx.anon.get(`/api/records/${lostId}`);
  add('出口2 匿名详情 GET /api/records/{lostId}', 'privacy', textOf(anonLost), `GET ${anonLost.url} → HTTP ${anonLost.status}`, 'title+description 遮蔽视角');
  const anonFound = await ctx.anon.get(`/api/records/${foundId}`);
  add('出口2 匿名详情 GET /api/records/{foundId}', 'privacy', textOf(anonFound), `GET ${anonFound.url} → HTTP ${anonFound.status}`, 'title+description 遮蔽视角');
  // 出口 3：非所有者详情
  const nonOwnerLost = await ctx.demo02.get(`/api/records/${lostId}`);
  add('出口3 非所有者详情 GET /api/records/{lostId}', 'privacy', textOf(nonOwnerLost), `GET ${nonOwnerLost.url} → HTTP ${nonOwnerLost.status}`, 'demo02 读 demo01 记录');
  const nonOwnerFound = await ctx.demo01.get(`/api/records/${foundId}`);
  add('出口3 非所有者详情 GET /api/records/{foundId}', 'privacy', textOf(nonOwnerFound), `GET ${nonOwnerFound.url} → HTTP ${nonOwnerFound.status}`, 'demo01 读 demo02 记录');
  // 出口 4：/api/my/records —— 本人集合原文回显（契约 §4.12），判「不得出现对端号」
  const myA = await ctx.demo01.get('/api/my/records?pageSize=50');
  add('出口4 GET /api/my/records(demo01 本人集合)', 'self', textOf(myA), `GET ${myA.url} → HTTP ${myA.status}`, 'own title 原文允许；pairs[].counterpartTitle 必须遮蔽', [T.A]);
  const myB = await ctx.demo02.get('/api/my/records?pageSize=50');
  add('出口4 GET /api/my/records(demo02 本人集合)', 'self', textOf(myB), `GET ${myB.url} → HTTP ${myB.status}`, 'own title 原文允许；pairs[].counterpartTitle 必须遮蔽', [T.B]);
  // 出口 5：通知 title+body
  const ntfA = await ctx.demo01.get('/api/notifications?page=1&pageSize=50');
  add('出口5 通知 /api/notifications(demo01)', 'privacy', textOf(ntfA), `GET ${ntfA.url} → HTTP ${ntfA.status}`, 'title+body');
  const ntfB = await ctx.demo02.get('/api/notifications?page=1&pageSize=50');
  add('出口5 通知 /api/notifications(demo02)', 'privacy', textOf(ntfB), `GET ${ntfB.url} → HTTP ${ntfB.status}`, 'title+body');
  // 出口 6：match 项（自己的候选原文允许，对端候选必须遮蔽）
  add('出口6 match 项 POST /api/match(demo01 视角)', 'self', textOf(matchAsA), `POST ${matchAsA.url} → HTTP ${matchAsA.status}`, 'items.title/reason；自己发的 FOUND 不存在于此视角', [T.A]);
  add('出口6 match 项 POST /api/match(demo02 视角)', 'self', textOf(matchAsB), `POST ${matchAsB.url} → HTTP ${matchAsB.status}`, 'items.title/reason', [T.B]);
  // 出口 7：pair 卡片
  const ownDetailA = await ctx.demo01.get(`/api/records/${lostId}`);
  add('出口7 pair 卡片 owner 详情 pairs[](A)', 'self', textOf(ownDetailA), `GET ${ownDetailA.url} → HTTP ${ownDetailA.status}`, 'counterpartTitle/otherNote 必须遮蔽；own title 原文允许', [T.A]);
  const ownDetailB = await ctx.demo02.get(`/api/records/${foundId}`);
  add('出口7 pair 卡片 owner 详情 pairs[](B)', 'self', textOf(ownDetailB), `GET ${ownDetailB.url} → HTTP ${ownDetailB.status}`, 'counterpartTitle/myNote 必须遮蔽；own title 原文允许', [T.B]);
  add('出口7 pair 卡片 unlock 响应 note', 'privacy', textOf(unlockA) + '\n' + textOf(unlockB), `POST ${unlockA.url} → HTTP ${unlockA.status} / POST ${unlockB.url} → HTTP ${unlockB.status}`, 'ContactUnlockResponse.note');
  // 出口 8：落库
  const snapSql = `SELECT CONCAT(input_text, ' || ', candidates) FROM t_match_result WHERE input_text LIKE '%${marker}%' ORDER BY id DESC LIMIT 2;`;
  add('出口8 t_match_result 快照', 'privacy', await sql(snapSql), `SQL ${snapSql}`, 'input_text + candidates 写入前脱敏');
  const noteSql = `SELECT CONCAT(IFNULL(lost_unlock_note,'∅'), ' || ', IFNULL(found_unlock_note,'∅')) FROM t_pair_score WHERE id=${pairId};`;
  const noteText = await sql(noteSql);
  add('出口8 t_pair_score 附言落库', 'privacy', noteText, `SQL ${noteSql}`, noteText.trim());
  const descSql = `SELECT CONCAT(title,' ~ ',description) FROM t_record WHERE id IN (${lostId},${foundId});`;
  const descText = await sql(descSql);

  const totalLeak = exits.reduce((n, e) => n + e.leaks, 0);
  return {
    case: c.key,
    label: c.label,
    kind: c.kind,
    injectedA: JSON.stringify(c.a),
    injectedB: JSON.stringify(c.b),
    lostId,
    foundId,
    pairId,
    exits,
    dbRawStored: descText.trim(),
    totalLeak,
    verdict: totalLeak === 0 ? 'PASS（越权/对端/落库方向 0 完整号码）' : `FAIL（${totalLeak} 处可读）`,
  };
}

say(`# F3-R 项3 · P1 八类绕过输入的 HTTP 出口实测 · ${new Date().toISOString()}`);
say(`- A 侧号码归一=${A_PHONE} / B 侧号码归一=${B_PHONE}；证件号 A=${A_ID} B=${B_ID}`);
say(`- 两侧演示账号 contact 固定 13800000001 / 13800000002，与绕过串不同值，避免「本人可见自己 contact」混入判定`);
say(`- 判据：privacy 方向（匿名/非所有者/通知/match 对端/落库）exact 与 rawKeep 双 0；self 方向（本人自有内容）只判「不得出现对端号」`);
say('');

const results = [];
for (const c of BYPASS) {
  const r = await runCase(c);
  results.push(r);
  say(`### ${r.case} ${r.label}｜A 写入=${r.injectedA}｜B 写入=${r.injectedB}｜lostId=${r.lostId} foundId=${r.foundId} pairId=${r.pairId}`);
  for (const e of r.exits) {
    say(`  ${e.leaks === 0 ? 'PASS' : 'FAIL'}  ${e.exit}  [${e.direction}]`);
    say(`         ${e.source}${e.note ? ` ｜ ${e.note}` : ''}`);
    for (const p of e.checks) {
      say(`         ${p.exact + p.rawKeep === 0 ? '-' : '!'} ${p.token}: exact=${p.exact} rawKeep=${p.rawKeep}${p.sample.length ? ` 样本=${JSON.stringify(p.sample[0]).slice(0, 200)}` : ''}`);
    }
    if (e.direction === 'self') say(`         本人自有内容原文回显=${e.selfVisible ? '是（契约 §4.2/§4.12 设计内）' : '否'}`);
  }
  say(`  落库原文（t_record.title/description，明文存储是 C1 口径）：${JSON.stringify(r.dbRawStored).slice(0, 300)}`);
  say(`  → ${r.verdict}`);
  say('');
}

// ---------------------------------------------------------------- 对照组
say('## 对照组（11 位连续/全角必须遮蔽；裸 5–9 位、楼栋房间号、日期不得误伤）');
const CONTROL = [
  { key: 'C1', text: A_PHONE, norm: A_PHONE, name: '连续 11 位', expectMasked: true },
  { key: 'C2', text: '１３８１２３４５６７８', norm: A_PHONE, name: '全角 11 位', expectMasked: true },
  { key: 'C3', text: '学号 20230101', norm: A_PHONE, name: '裸 8 位学号', expectMasked: false },
  { key: 'C4', text: '3 号楼 201 房间', norm: A_PHONE, name: '楼栋房间号', expectMasked: false },
  { key: 'C5', text: '2026-10-21 13:30', norm: A_PHONE, name: '日期时间', expectMasked: false },
];
await resetSeed();
const controls = [];
for (const c of CONTROL) {
  const marker = `F3RC${c.key}`;
  const id = await publish(ctx.demo01, 'LOST', marker, c.text, '13800000001');
  const res = await ctx.anon.get(`/api/records/${id}`);
  const text = textOf(res);
  const masked = text.includes('[手机号已隐藏]') || text.includes('[证件号已隐藏]');
  const p = probeNumber(text, { label: 'x', raw: c.text, norm: c.norm });
  const keptVerbatim = text.includes(c.text);
  const ok = c.expectMasked ? masked && p.exact === 0 && p.rawKeep === 0 : !masked && keptVerbatim;
  controls.push({ ...c, id, masked, exact: p.exact, rawKeep: p.rawKeep, keptVerbatim, title: res.body?.data?.title ?? null, description: res.body?.data?.description ?? null, verdict: ok ? 'PASS' : 'FAIL' });
  say(`  ${ok ? 'PASS' : 'FAIL'}  ${c.key} ${c.name}｜写入=${JSON.stringify(c.text)}`);
  say(`         匿名详情 title=${JSON.stringify(res.body?.data?.title)}`);
  say(`         匿名详情 description=${JSON.stringify(res.body?.data?.description)}`);
  say(`         遮蔽标记=${masked}｜exact=${p.exact} rawKeep=${p.rawKeep}｜原文逐字保留=${keptVerbatim}`);
}
say('');

// ---------------------------------------------------------------- R-1 现状
say('## R-1（逗号/冒号/分号）现状实测 —— 只记录命中与否，不改规则、不加正则');
const R1 = [
  { key: 'R1a', label: '逗号拆', text: '138,1234,5678' },
  { key: 'R1b', label: '冒号拆', text: '138:1234:5678' },
  { key: 'R1c', label: '分号拆', text: '138;1234;5678' },
];
const r1 = [];
for (const c of R1) {
  await resetSeed();
  const marker = `F3R${c.key}`;
  const lostId = await publish(ctx.demo01, 'LOST', `${marker}L`, c.text, '13800000001');
  const foundId = await publish(ctx.demo02, 'FOUND', `${marker}F`, c.text.replace('138', '139'), '13800000002');
  await waitForScoring([lostId, foundId]);
  await upsertPair({ lostId, foundId, score: 0.95, status: 'NEW' });
  const pair = await pairByEnds(lostId, foundId);
  const unlock = await ctx.demo01.post(`/api/pairs/${pair.id}/contact-unlock`, { body: { action: 'PUBLISH', note: `号码 ${c.text}` } });
  const token = { label: c.label, raw: c.text, norm: A_PHONE };
  const anon = await ctx.anon.get(`/api/records/${lostId}`);
  const nonOwner = await ctx.demo02.get(`/api/records/${lostId}`);
  const pAnon = probeNumber(textOf(anon), token);
  const pNon = probeNumber(textOf(nonOwner), token);
  const noteSql = `SELECT IFNULL(lost_unlock_note,'∅') FROM t_pair_score WHERE id=${pair.id};`;
  const noteText = await sql(noteSql);
  const pNote = probeNumber(noteText, token);
  const match = await ctx.demo02.post('/api/match', { body: { text: `${marker} 回电 ${c.text}`, type: 'FOUND', topN: 20, minScore: 0 } });
  const pMatch = probeNumber(textOf(match), token);
  const snapSql = `SELECT CONCAT(input_text,' || ',candidates) FROM t_match_result WHERE input_text LIKE '%${marker}%' ORDER BY id DESC LIMIT 1;`;
  const pSnap = probeNumber(await sql(snapSql), token);
  const hit = [pAnon, pNon, pNote, pMatch, pSnap].some((p) => p.exact + p.rawKeep > 0);
  r1.push({ ...c, lostId, foundId, pairId: Number(pair.id), unlockHttp: `HTTP ${unlock.status}/code=${unlock.body?.code}`, anon: pAnon, nonOwner: pNon, note: pNote, noteDb: noteText.trim(), match: pMatch, snapshot: pSnap, hit });
  say(`  ${hit ? 'HIT（现状仍可整段读出）' : 'NO-HIT'}  ${c.key} ${c.label}｜写入=${JSON.stringify(c.text)}｜lostId=${lostId} foundId=${foundId} pairId=${pair.id}`);
  say(`         匿名详情 description=${JSON.stringify(anon.body?.data?.description)} exact=${pAnon.exact} rawKeep=${pAnon.rawKeep}`);
  say(`         非所有者详情 exact=${pNon.exact} rawKeep=${pNon.rawKeep}｜match items exact=${pMatch.exact} rawKeep=${pMatch.rawKeep}｜快照 exact=${pSnap.exact} rawKeep=${pSnap.rawKeep}`);
  say(`         附言落库=${JSON.stringify(noteText.trim())} exact=${pNote.exact} rawKeep=${pNote.rawKeep}`);
}
say('');

const failCases = results.filter((r) => r.totalLeak > 0);
const controlFail = controls.filter((c) => c.verdict === 'FAIL');
const r1Hit = r1.filter((x) => x.hit);
say('## 汇总');
say(`- 八类绕过：${results.length - failCases.length}/${results.length} 越权/对端/落库方向 0 可读${failCases.length ? `；未通过：${failCases.map((f) => `${f.case}(${f.totalLeak})`).join(', ')}` : ''}`);
say(`- 对照组：${controls.length - controlFail.length}/${controls.length} 符合预期${controlFail.length ? `；异常：${controlFail.map((f) => f.key).join(', ')}` : ''}`);
say(`- R-1 现状：${r1.map((x) => `${x.key}${x.label}=${x.hit ? 'HIT' : 'NO-HIT'}`).join(' ｜ ')}（不改规则；是否升 P1 追加修复由人类在门禁 3 裁决）`);

const outDir = resolve(root, 'qa', 'reports');
await mkdir(outDir, { recursive: true });
await writeFile(resolve(outDir, 'f3r-p1-bypass.json'), JSON.stringify({ results, controls, r1 }, null, 2), 'utf8');
await writeFile(resolve(outDir, 'f3r-p1-bypass.txt'), lines.join('\n') + '\n', 'utf8');
say('\n证据：qa/reports/f3r-p1-bypass.txt / .json');
process.exit(failCases.length === 0 && controlFail.length === 0 ? 0 : 1);
