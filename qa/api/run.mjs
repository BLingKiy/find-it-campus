// v2 · F1 回归资产：后端接口测试（原 5.1 的 64 条 + v2 新增）
//
// 事实源：docs/api-contract-v2.md（断言只写契约里写死的东西，不写实现细节）。
//
// 本轮三件事：
//   1) 登录夹具：匿名 / 本人（demo01）/ 非本人（demo02、demo03）四套上下文；
//      每条写接口用例都覆盖「匿名 401 / 本人 2xx / 非本人 403」三态。
//   2) 既有用例按 v2 的 schema / seed / 出参变化修正断言（CLAIMED→COMPLETED、字段白名单、遮蔽）。
//   3) 新增登录态、越权、遮蔽五出口、三档分档、幂等、降级、多对 pair 独立、三态、TERM-06/PAIR-07。
//
// 运行：node qa/api/run.mjs
// 前置：后端 18080、库为 db/schema.sql + db/seed.sql 初始状态（ENV-01 会自检 total=21）。

import { Api, brief, expectCode, fieldReasons, isOk, sessionCookieFrom } from '../lib/client.mjs';
import { contexts, login, PASSWORD } from '../lib/auth.mjs';
import {
  accessLogCount,
  accessLogResults,
  columnNames,
  forceRecordStatus,
  fullRecordSnapshot,
  pairByEnds,
  pairCount,
  pairLog,
  llmCallCount,
  q,
  record,
  recordSnapshot,
  sql,
  tableNames,
  upsertPair,
  val,
  waitForScoring,
  waitUntil,
} from '../lib/db.mjs';
import { Tester, assert, eq, reportsDir } from '../lib/tester.mjs';

const t = new Tester('api-f1-v2');
let ctx;

// 随机标记用 base36（≤8 位），避免像 Date.now() 那样凑出 1[3-9]\d{9} 的手机号模式，
// 否则「非本人响应 0 命中手机号」的扫描会被测试自己的标题误伤。
const rid = () => Math.random().toString(36).slice(2, 10);

const created = [];
const createdPairs = [];

async function createRecordAs(api, overrides = {}) {
  const body = {
    type: 'LOST',
    title: `QA 测试记录 ${rid()}`,
    description: '这是 v2 接口测试创建的临时记录，用于验证提交、修改、状态流转与删除路径。',
    location: 'QA 测试地点',
    occurredAt: '2026-10-20 10:00:00',
    ...overrides,
  };
  const res = await api.post('/api/records', { body });
  if (isOk(res)) created.push({ id: res.body.data.id, owner: api });
  return { res, body, id: res.body?.data?.id };
}

async function createClosedAs(api, overrides = {}) {
  const made = await createRecordAs(api, overrides);
  expectCode(made.res, 200, 0, '准备：创建记录');
  const res = await api.patch(`/api/records/${made.id}/status`, { body: { status: 'CLOSED' } });
  expectCode(res, 200, 0, '准备：关闭记录');
  return made;
}

/** 造一对 LOST/FOUND 记录并插入指定分数/状态的 pair；返回 {lostId, foundId, pairId}。 */
async function makePair({
  score,
  status = 'NEW',
  lostOwner = ctx.demo01,
  foundOwner = ctx.demo02,
  lostOverrides = {},
  foundOverrides = {},
  ...pairOpts
}) {
  const lost = await createRecordAs(lostOwner, { type: 'LOST', contact: '13800000001', ...lostOverrides });
  expectCode(lost.res, 200, 0, '夹具：创建 LOST');
  const found = await createRecordAs(foundOwner, { type: 'FOUND', contact: '13800000002', ...foundOverrides });
  expectCode(found.res, 200, 0, '夹具：创建 FOUND');
  // 发布后的异步打分先跑完，再覆盖成测试需要的分数/状态；否则打分可能把夹具改回去。
  await waitForScoring([lost.id, found.id]);
  await upsertPair({ lostId: lost.id, foundId: found.id, score, status, ...pairOpts });
  createdPairs.push({ lostId: lost.id, foundId: found.id });
  const pair = await pairByEnds(lost.id, found.id);
  return { lostId: lost.id, foundId: found.id, pairId: Number(pair.id) };
}

/** 造一条已完成记录 + 一对两端确认的 MATCHED pair；返回记录与 pair。 */
async function makeCompleted({ score = 0.95, lostOwner = ctx.demo01, foundOwner = ctx.demo02 } = {}) {
  const marker = `完成夹具${rid()}`;
  const pair = await makePair({
    score,
    status: 'MATCHED',
    lostOwner,
    foundOwner,
    lostOverrides: { title: `${marker}L 深紫色钢笔`, description: `${marker} 在自习室丢失的深紫色钢笔，笔帽有划痕。` },
    foundOverrides: { title: `${marker}F 招领深紫色钢笔`, description: `${marker} 招领一支深紫色钢笔，笔帽有划痕。` },
    lostConfirmedAt: '2026-10-26 10:00:00',
    foundConfirmedAt: '2026-10-26 10:05:00',
  });
  const res = await lostOwner.patch(`/api/records/${pair.lostId}/status`, { body: { status: 'COMPLETED' } });
  expectCode(res, 200, 0, '夹具：标记完成');
  return { ...pair, marker };
}

async function isListed(api, id) {
  const res = await api.get('/api/records?status=ALL&pageSize=50');
  expectCode(res, 200, 0, '列表');
  return res.body.data.list.some((x) => x.id === id);
}

// ---------------------------------------------------------------- 前置自检

async function preconditions() {
  await t.case('ENV-01', 'P0', '后端可达且数据库为 seed 初始状态（total=21）', async () => {
    const health = await ctx.anon.get('/api/health');
    expectCode(health, 200, 0, '健康检查');
    eq(health.body.data.db, 'UP', 'health.data.db');
    const list = await ctx.anon.get('/api/records');
    expectCode(list, 200, 0, '列表基线');
    if (list.body.data.total !== 21) {
      throw new Error(
        `基线不符合 db/seed.sql 初始状态：期望 total=21，实际 ${list.body.data.total}。请先重置库再跑。`,
      );
    }
  });

  await t.case('ENV-02', 'P0', '数据库为 v2 七表；t_user 不存手机号（PHONE 口径）', async () => {
    const tables = await tableNames();
    eq(tables, ['t_access_log', 't_category', 't_match_result', 't_notification', 't_pair_score', 't_record', 't_user'], '七张表');
    const userCols = await columnNames('t_user');
    for (const forbidden of ['phone', 'mobile', 'contact']) {
      assert(!userCols.includes(forbidden), `t_user 不应有 ${forbidden} 列，实际 ${JSON.stringify(userCols)}`);
    }
    const pairCols = await columnNames('t_pair_score');
    for (const required of ['lost_confirmed_at', 'found_confirmed_at', 'lost_unlocked_at', 'found_unlocked_at']) {
      assert(pairCols.includes(required), `t_pair_score 缺少 ${required}`);
    }
  });
}

// ---------------------------------------------------------------- 通用契约

async function contractSuite() {
  await t.case('API-CON-01', 'P1', '外层响应体恒含 code/message/data/requestId 四字段', async () => {
    const ok = await ctx.anon.get('/api/records');
    for (const key of ['code', 'message', 'data', 'requestId']) {
      assert(key in ok.body, `成功响应缺少字段 ${key}：${ok.raw.slice(0, 200)}`);
    }
    assert(typeof ok.body.requestId === 'string' && ok.body.requestId.length > 0, 'requestId 必须为非空字符串');

    const bad = await ctx.anon.get('/api/records/999999');
    for (const key of ['code', 'message', 'data', 'requestId']) {
      assert(key in bad.body, `错误响应缺少字段 ${key}：${bad.raw.slice(0, 200)}`);
    }
    assert(bad.body.requestId && bad.body.requestId !== ok.body.requestId, '每个请求的 requestId 必须不同');
  });

  await t.case('API-CON-02', 'P1', '未知路径返回 404 + 10003，而非框架默认错误页', async () => {
    const res = await ctx.anon.get('/api/not-exists');
    expectCode(res, 404, 10003, '未知路径');
    assert(!/Whitelabel|timestamp/.test(res.raw), `响应体不应是框架默认错误结构：${res.raw.slice(0, 200)}`);
  });

  await t.case('API-CON-03', 'P1', '动词不支持返回 405 + 10004（登录态下越过会话门禁）', async () => {
    const anon = await ctx.anon.del('/api/match');
    expectCode(anon, 401, 10006, '匿名 DELETE /api/match（门禁先拦）');
    const res = await ctx.demo01.del('/api/match');
    expectCode(res, 405, 10004, 'DELETE /api/match');
  });

  await t.case('API-CON-04', 'P2', '请求体不是合法 JSON 时返回 400 + 10001', async () => {
    const anon = await ctx.anon.post('/api/records', { body: '{not-json' });
    expectCode(anon, 401, 10006, '匿名非法 JSON（门禁先拦）');
    const res = await ctx.demo01.post('/api/records', { body: '{not-json' });
    expectCode(res, 400, 10001, '非法 JSON 请求体');
  });

  await t.case('API-CON-05', 'P2', '错误响应不含堆栈信息', async () => {
    const res = await ctx.demo01.post('/api/records', { body: '{"type":"LOST"}' });
    expectCode(res, 400, 10001, '缺字段提交');
    assert(!/\bat com\.findit|\bat java\.|Exception:/.test(res.raw), `错误体泄漏了堆栈：${res.raw.slice(0, 300)}`);
  });
}

// ---------------------------------------------------------------- 登录与三态上下文

async function authSuite() {
  await t.case('AUTH-01', 'P0', '登录成功签发 JSESSIONID（HttpOnly/SameSite=Lax），me 返回本人', async () => {
    const probe = new Api();
    const res = await probe.post('/api/auth/login', { body: { studentNo: 'demo01', password: PASSWORD } });
    expectCode(res, 200, 0, '登录');
    eq(res.body.data.id, 1, 'data.id');
    eq(res.body.data.studentNo, 'demo01', 'data.studentNo');
    const cookie = sessionCookieFrom(res);
    assert(cookie, `应下发 JSESSIONID，实际 Set-Cookie=${JSON.stringify(res.setCookies)}`);
    const raw = res.setCookies.join(';');
    for (const attr of ['Path=/', 'HttpOnly', 'SameSite=Lax']) {
      assert(raw.includes(attr), `Set-Cookie 缺少 ${attr}：${raw}`);
    }
    assert(!/Secure/i.test(raw), `本地 http 不应带 Secure：${raw}`);

    const me = await probe.withCookie(cookie).get('/api/auth/me');
    expectCode(me, 200, 0, '/api/auth/me');
    eq(me.body.data.id, 1, 'me.id');
  });

  await t.case('AUTH-02', 'P0', '错口令与不存在账号同为 401 + 10006 + 同一文案（防枚举）', async () => {
    const probe = new Api();
    const wrong = await probe.post('/api/auth/login', { body: { studentNo: 'demo01', password: 'wrong-password' } });
    const missing = await probe.post('/api/auth/login', { body: { studentNo: 'nobody-999', password: 'whatever' } });
    for (const [label, res] of [['错口令', wrong], ['不存在账号', missing]]) {
      expectCode(res, 401, 10006, label);
      eq(res.body.message, '学号或密码不正确', `${label} 的 message`);
    }
    eq(wrong.body.message, missing.body.message, '两种失败的文案');
  });

  await t.case('AUTH-03', 'P1', '登录参数缺失/超长返回 400 + 10001 + fieldErrors', async () => {
    const probe = new Api();
    const empty = await probe.post('/api/auth/login', { body: {} });
    expectCode(empty, 400, 10001, '空 body');
    const reasons = fieldReasons(empty);
    assert('studentNo' in reasons && 'password' in reasons, `fieldErrors 应指向两个字段：${JSON.stringify(reasons)}`);
    const long = await probe.post('/api/auth/login', { body: { studentNo: 'x'.repeat(33), password: 'y'.repeat(65) } });
    expectCode(long, 400, 10001, '超长参数');
  });

  await t.case('AUTH-04', 'P1', '登出幂等：匿名 200；已登出会话再用 401', async () => {
    const anon = new Api();
    expectCode(await anon.post('/api/auth/logout'), 200, 0, '匿名登出');
    const temp = await login(undefined, 'demo03');
    expectCode(await temp.get('/api/auth/me'), 200, 0, '登出前 me');
    const out = await temp.post('/api/auth/logout');
    expectCode(out, 200, 0, '登出');
    assert(out.setCookies.some((c) => /Max-Age=0|Expires=Thu, 01 Jan 1970/i.test(c)), `登出应清 Cookie：${JSON.stringify(out.setCookies)}`);
    expectCode(await temp.get('/api/auth/me'), 401, 10006, '登出后 me');
  });

  await t.case('AUTH-05', 'P0', '三套登录上下文互不串会话：me 分别返回 1/2/3', async () => {
    for (const [name, api, id] of [['demo01', ctx.demo01, 1], ['demo02', ctx.demo02, 2], ['demo03', ctx.demo03, 3]]) {
      const res = await api.get('/api/auth/me');
      expectCode(res, 200, 0, `${name} me`);
      eq(res.body.data.id, id, `${name} me.id`);
    }
    expectCode(await ctx.anon.get('/api/auth/me'), 401, 10006, '匿名 me');
  });

  await t.case('AUTH-06', 'P1', '会话失效（未登录）后读私有集合与 pair 均 401，未实现路径也先 401', async () => {
    for (const path of ['/api/my/records', '/api/notifications', '/api/pairs/1/contact', '/api/pairs/1']) {
      expectCode(await ctx.anon.get(path), 401, 10006, `匿名 GET ${path}`);
    }
    expectCode(await ctx.anon.patch('/api/pairs/1', { body: { action: 'CONTACT' } }), 401, 10006, '匿名 PATCH pair');
  });
}

// ---------------------------------------------------------------- 写接口三态（匿名 / 本人 / 非本人）

async function authzSuite() {
  await t.case('AUTHZ-01', 'P0', 'POST /records 三态：匿名 401；本人 200；另一登录用户 200（发布归自己）', async () => {
    expectCode(await ctx.anon.post('/api/records', { body: { type: 'LOST' } }), 401, 10006, '匿名发布');
    const mine = await createRecordAs(ctx.demo01);
    expectCode(mine.res, 200, 0, '本人发布');
    const other = await createRecordAs(ctx.demo02);
    expectCode(other.res, 200, 0, '另一登录用户发布');
    const detail = await ctx.demo02.get(`/api/records/${other.id}`);
    expectCode(detail, 200, 0, '另一用户读自己记录详情');
    eq(detail.body.data.isMine, true, '发布者本人 isMine');
  });

  await t.case('AUTHZ-02', 'P0', 'PUT /records 三态：匿名 401；本人 200；非本人 403 + 10007', async () => {
    const { id } = await createRecordAs(ctx.demo01);
    const body = {
      type: 'LOST', title: '三态 PUT 用例', description: '验证匿名/本人/非本人三态。', location: 'QA', occurredAt: '2026-10-20 09:00:00',
    };
    expectCode(await ctx.anon.put(`/api/records/${id}`, { body }), 401, 10006, '匿名 PUT');
    expectCode(await ctx.demo02.put(`/api/records/${id}`, { body }), 403, 10007, '非本人 PUT');
    const ok = await ctx.demo01.put(`/api/records/${id}`, { body });
    expectCode(ok, 200, 0, '本人 PUT');
  });

  await t.case('AUTHZ-03', 'P0', 'PATCH status 三态：匿名 401；本人 200；非本人 403 + 10007', async () => {
    const { id } = await createRecordAs(ctx.demo01);
    expectCode(await ctx.anon.patch(`/api/records/${id}/status`, { body: { status: 'CLOSED' } }), 401, 10006, '匿名 PATCH');
    expectCode(await ctx.demo02.patch(`/api/records/${id}/status`, { body: { status: 'CLOSED' } }), 403, 10007, '非本人 PATCH');
    const ok = await ctx.demo01.patch(`/api/records/${id}/status`, { body: { status: 'CLOSED' } });
    expectCode(ok, 200, 0, '本人 PATCH');
    eq(ok.body.data.status, 'CLOSED', '本人关闭后的状态');
  });

  await t.case('AUTHZ-04', 'P0', 'DELETE /records 三态：匿名 401；非本人 403；本人 200', async () => {
    const a = await createRecordAs(ctx.demo01);
    expectCode(await ctx.anon.del(`/api/records/${a.id}`), 401, 10006, '匿名 DELETE');
    expectCode(await ctx.demo02.del(`/api/records/${a.id}`), 403, 10007, '非本人 DELETE');
    expectCode(await ctx.demo01.del(`/api/records/${a.id}`), 200, 0, '本人 DELETE');
  });

  await t.case('AUTHZ-05', 'P0', 'pair 写接口三态：匿名 401；参与方 200；非参与方 403 + 10007', async () => {
    const { lostId, foundId, pairId } = await makePair({ score: 0.96 });
    expectCode(await ctx.anon.patch(`/api/pairs/${pairId}`, { body: { action: 'CONTACT' } }), 401, 10006, '匿名 PATCH pair');
    expectCode(await ctx.anon.get(`/api/pairs/${pairId}/contact`), 401, 10006, '匿名取号');
    expectCode(await ctx.anon.post(`/api/pairs/${pairId}/contact-unlock`, { body: {} }), 401, 10006, '匿名授权');
    expectCode(await ctx.demo03.patch(`/api/pairs/${pairId}`, { body: { action: 'CONTACT' } }), 403, 10007, '非参与方 PATCH');
    expectCode(await ctx.demo03.get(`/api/pairs/${pairId}/contact`), 403, 10007, '非参与方取号');
    expectCode(await ctx.demo03.post(`/api/pairs/${pairId}/contact-unlock`, { body: {} }), 403, 10007, '非参与方授权');
    const ok = await ctx.demo01.patch(`/api/pairs/${pairId}`, { body: { action: 'CONTACT' } });
    expectCode(ok, 200, 0, '参与方 PATCH');
    eq(ok.body.data.status, 'CONTACTED', '联系后的 pair.status');
    const contact = await ctx.demo02.get(`/api/pairs/${pairId}/contact`);
    expectCode(contact, 200, 0, '另一端取号');
    eq(contact.body.data.available, true, 'DIRECT 档应可取号');
    assert(lostId && foundId, '夹具 id');
  });

  await t.case('AUTHZ-06', 'P0', '通知已读三态：匿名 401；接收人 200；他人 403 + 10007', async () => {
    const marker = `QA通知${rid()}`;
    const out = await val(
      `INSERT INTO t_notification (user_id, pair_id, type, title, body, created_at) VALUES (1, NULL, 'SYSTEM', ${q(marker)}, '三态用例', NOW()); SELECT LAST_INSERT_ID();`,
    );
    const id = Number(out);
    expectCode(await ctx.anon.patch(`/api/notifications/${id}/read`), 401, 10006, '匿名已读');
    expectCode(await ctx.demo02.patch(`/api/notifications/${id}/read`), 403, 10007, '他人已读');
    const ok = await ctx.demo01.patch(`/api/notifications/${id}/read`);
    expectCode(ok, 200, 0, '接收人已读');
    eq(ok.body.data.read, true, 'read');
  });

  await t.case('AUTHZ-07', 'P0', 'POST /match 三态：匿名 401；任一登录用户 200（候选对所有人遮蔽）', async () => {
    expectCode(await ctx.anon.post('/api/match', { body: { text: '捡到钥匙' } }), 401, 10006, '匿名匹配');
    expectCode(await ctx.demo01.post('/api/match', { body: { text: '捡到钥匙' } }), 200, 0, 'demo01 匹配');
    expectCode(await ctx.demo02.post('/api/match', { body: { text: '捡到钥匙' } }), 200, 0, 'demo02 匹配');
  });
}

// ---------------------------------------------------------------- 列表

async function listSuite() {
  await t.case('API-L-01', 'P0', '默认参数：status=PENDING、page=1、pageSize=10、createdAt desc', async () => {
    const res = await ctx.anon.get('/api/records');
    expectCode(res, 200, 0, '默认列表');
    const d = res.body.data;
    eq(d.page, 1, 'data.page');
    eq(d.pageSize, 10, 'data.pageSize');
    eq(d.total, 21, 'data.total'); // seed 24 条 − COMPLETED 2 − CLOSED 1
    eq(d.totalPages, 3, 'data.totalPages');
    eq(d.hasNext, true, 'data.hasNext');
    eq(d.list.length, 10, 'data.list.length');
    eq(d.list[0].id, 26, '首条 id（createdAt 最新）');
    eq(d.list[1].id, 22, '次条 id（同 createdAt 排序下的第二新）');
    assert(d.list.every((x) => x.status === 'PENDING'), `默认列表应只含 PENDING，实际 ${JSON.stringify(d.list.map((x) => x.status))}`);
  });

  await t.case('API-L-02', 'P0', '列表项字段白名单：不含 description / contact / publisherName，含 isMine', async () => {
    const res = await ctx.anon.get('/api/records');
    expectCode(res, 200, 0, '列表');
    const expected = ['id', 'type', 'status', 'title', 'categoryId', 'categoryName', 'location', 'occurredAt', 'createdAt', 'isMine'].sort();
    for (const item of res.body.data.list) {
      eq(Object.keys(item).sort(), expected, `记录 ${item.id} 的字段集合`);
    }
  });

  await t.case('API-L-03', 'P0', 'type=FOUND 过滤后全部为 FOUND', async () => {
    const res = await ctx.anon.get('/api/records?type=FOUND&pageSize=50');
    expectCode(res, 200, 0, 'type=FOUND');
    assert(res.body.data.total > 0, 'type=FOUND 不应为空');
    assert(res.body.data.list.every((x) => x.type === 'FOUND'), `混入了非 FOUND：${JSON.stringify(res.body.data.list.map((x) => x.type))}`);
  });

  await t.case('API-L-04', 'P1', '公共列表恒为 PENDING：status=ALL 仍 21；COMPLETED/CLOSED 0 命中', async () => {
    const all = await ctx.anon.get('/api/records?status=ALL&pageSize=50');
    expectCode(all, 200, 0, 'status=ALL');
    eq(all.body.data.total, 21, 'germ status=ALL total');
    const ids = all.body.data.list.map((x) => x.id);
    for (const hidden of [23, 24, 25]) assert(!ids.includes(hidden), `公共列表不得包含 ${hidden}`);

    for (const status of ['COMPLETED', 'CLOSED']) {
      const res = await ctx.anon.get(`/api/records?status=${status}&pageSize=50`);
      expectCode(res, 200, 0, `status=${status}`);
      eq(res.body.data.total, 0, `status=${status} 的 total`);
      eq(res.body.data.list, [], `status=${status} 的 list`);
    }
  });

  await t.case('API-L-05', 'P1', 'categoryId 过滤且停用分类返回 40002', async () => {
    const ok = await ctx.anon.get('/api/records?categoryId=1&pageSize=50');
    expectCode(ok, 200, 0, 'categoryId=1');
    assert(ok.body.data.total > 0, 'categoryId=1 不应为空');
    assert(ok.body.data.list.every((x) => x.categoryId === 1), `混入了其它分类：${JSON.stringify(ok.body.data.list.map((x) => x.categoryId))}`);
    expectCode(await ctx.anon.get('/api/records?categoryId=9'), 409, 40002, '停用分类 9');
    expectCode(await ctx.anon.get('/api/records?categoryId=999999'), 404, 40001, '不存在的分类');
  });

  await t.case('API-L-06', 'P1', 'keyword 命中标题与描述（中文，不区分字段）', async () => {
    const res = await ctx.anon.get('/api/records?keyword=' + encodeURIComponent('钱包'));
    expectCode(res, 200, 0, 'keyword=钱包');
    const ids = res.body.data.list.map((x) => x.id).sort((a, b) => a - b);
    eq(ids, [1, 13], 'keyword=钱包 命中的记录 id');
  });

  await t.case('API-L-07', 'P1', 'keyword 中的 % 与 _ 按字面量处理，不当通配符', async () => {
    for (const kw of ['%', '_']) {
      const res = await ctx.anon.get('/api/records?keyword=' + encodeURIComponent(kw));
      expectCode(res, 200, 0, `keyword=${kw}`);
      eq(res.body.data.total, 0, `keyword=${kw} 的 total（转义生效时应为 0）`);
    }
  });

  await t.case('API-L-08', 'P1', '分页无重复且末页 hasNext=false', async () => {
    const pages = [];
    for (const page of [1, 2, 3]) {
      const res = await ctx.anon.get(`/api/records?page=${page}&pageSize=10&status=ALL`);
      expectCode(res, 200, 0, `第 ${page} 页`);
      pages.push(res.body.data);
    }
    eq(pages[0].total, pages[2].total, '三页的 total 必须一致');
    const ids = pages.flatMap((d) => d.list.map((x) => x.id));
    eq(ids.length, new Set(ids).size, '跨页 id 不得重复');
    eq(pages[2].list.length, 1, '第 3 页条数（21 条 / 每页 10）');
    eq(pages[2].hasNext, false, '末页 hasNext');
  });

  await t.case('API-L-09', 'P1', 'sortBy=occurredAt&asc 按发生时间升序', async () => {
    const res = await ctx.anon.get('/api/records?sortBy=occurredAt&sortOrder=asc&pageSize=50&status=ALL');
    expectCode(res, 200, 0, 'occurredAt asc');
    const times = res.body.data.list.map((x) => x.occurredAt);
    for (let i = 1; i < times.length; i += 1) assert(times[i - 1] <= times[i], `第 ${i} 位排序错误：${times[i - 1]} 应 <= ${times[i]}`);
  });

  await t.case('API-L-10', 'P1', 'sortBy=createdAt&desc 严格降序且两次调用顺序一致', async () => {
    const asc = await ctx.anon.get('/api/records?sortBy=createdAt&sortOrder=asc&pageSize=50');
    expectCode(asc, 200, 0, 'createdAt asc');
    const times = asc.body.data.list.map((x) => x.createdAt);
    for (let i = 1; i < times.length; i += 1) assert(times[i - 1] <= times[i], `createdAt 升序错误：${times[i - 1]} 应 <= ${times[i]}`);

    const desc = await ctx.anon.get('/api/records?sortBy=createdAt&sortOrder=desc&pageSize=50');
    expectCode(desc, 200, 0, 'createdAt desc');
    const descTimes = desc.body.data.list.map((x) => x.createdAt);
    for (let i = 1; i < descTimes.length; i += 1) assert(descTimes[i - 1] >= descTimes[i], `createdAt 降序错误：${descTimes[i - 1]} 应 >= ${descTimes[i]}`);
    eq(desc.body.data.list[0].id, 26, '降序首条（seed 中 createdAt 最新）');

    const again = await ctx.anon.get('/api/records?sortBy=createdAt&sortOrder=desc&pageSize=50');
    eq(desc.body.data.list.map((x) => x.id), again.body.data.list.map((x) => x.id), '两次同条件查询的 id 序列');
  });

  await t.case('API-L-11', 'P2', '分页与枚举参数非法逐项返回 10001 + fieldErrors', async () => {
    const cases = [
      ['/api/records?page=0', 'page'],
      ['/api/records?pageSize=0', 'pageSize'],
      ['/api/records?pageSize=51', 'pageSize'],
      ['/api/records?status=FOO', 'status'],
      ['/api/records?type=SELL', 'type'],
      ['/api/records?sortBy=title', 'sortBy'],
      ['/api/records?sortOrder=up', 'sortOrder'],
      ['/api/records?keyword=' + 'x'.repeat(65), 'keyword'],
    ];
    for (const [path, field] of cases) {
      const res = await ctx.anon.get(path);
      expectCode(res, 400, 10001, path);
      assert(field in fieldReasons(res), `${path} 的 fieldErrors 应指向 ${field}，实际 ${JSON.stringify(fieldReasons(res))}`);
    }
  });

  await t.case('API-L-12', 'P3', '页码越界返回空列表而非报错', async () => {
    const res = await ctx.anon.get('/api/records?page=999');
    expectCode(res, 200, 0, 'page=999');
    eq(res.body.data.list.length, 0, 'list 长度');
    eq(res.body.data.hasNext, false, 'hasNext');
    eq(res.body.data.total, 21, 'total 不因页码越界而变化');
  });
}

// ---------------------------------------------------------------- 详情

async function detailSuite() {
  await t.case('API-D-01', 'P0', '所有者详情返回完整字段、allowedStatuses 与 pairs', async () => {
    const res = await ctx.demo02.get('/api/records/13');
    expectCode(res, 200, 0, '详情 13（demo02 为所有者）');
    const d = res.body.data;
    for (const key of [
      'id', 'type', 'status', 'title', 'description', 'categoryId', 'categoryName', 'location', 'occurredAt',
      'completedAt', 'contact', 'publisherName', 'allowedStatuses', 'hasMatchedPair', 'matchedPairCount',
      'isMine', 'pairState', 'lowConfidenceCount', 'pairs', 'createdAt', 'updatedAt',
    ]) assert(key in d, `详情缺少字段 ${key}`);
    eq(d.id, 13, 'id');
    eq(d.status, 'PENDING', 'status');
    eq(d.isMine, true, 'isMine');
    eq(d.allowedStatuses, ['CLOSED'], 'PENDING 无有效 MATCHED 时的 allowedStatuses');
    assert(typeof d.description === 'string' && d.description.length > 0, 'description 不应为空');
  });

  await t.case('API-D-02', 'P1', '逻辑删除记录 404 + 20001', async () => {
    const made = await createRecordAs(ctx.demo01);
    expectCode(await ctx.demo01.del(`/api/records/${made.id}`), 200, 0, '准备：删除');
    expectCode(await ctx.anon.get(`/api/records/${made.id}`), 404, 20001, '已删除记录');
  });

  await t.case('API-D-03', 'P1', '不存在的 id 404 + 20001', async () => {
    expectCode(await ctx.anon.get('/api/records/999999'), 404, 20001, '不存在记录');
  });

  await t.case('API-D-04', 'P1', '非数字 id 400 + 10001（/records/new 路由不被 id 抢走）', async () => {
    for (const path of ['/api/records/new', '/api/records/1abc']) {
      const res = await ctx.anon.get(path);
      expectCode(res, 400, 10001, path);
      assert('id' in fieldReasons(res), `${path} 的 fieldErrors 应指向 id，实际 ${JSON.stringify(fieldReasons(res))}`);
    }
  });

  await t.case('API-D-05', 'P1', 'CLOSED / 到期 COMPLETED 任何身份 404；保留期 COMPLETED 仅合格对端可见', async () => {
    for (const [label, api] of [['匿名', ctx.anon], ['所有者 demo02', ctx.demo02], ['他人 demo03', ctx.demo03]]) {
      expectCode(await api.get('/api/records/24'), 404, 20001, `CLOSED 24（${label}）`);
      expectCode(await api.get('/api/records/25'), 404, 20001, `到期 COMPLETED 25（${label}）`);
    }
    // 23：COMPLETED（3 天前）。demo01 是 (10,23) MATCHED 0.95 的合格对端；demo02 的 pair 是 IGNORED。
    expectCode(await ctx.demo01.get('/api/records/23'), 200, 0, '合格对端看 23');
    expectCode(await ctx.demo02.get('/api/records/23'), 404, 20001, 'IGNORED pair 不算通道');
    expectCode(await ctx.demo03.get('/api/records/23'), 404, 20001, '所有者看 COMPLETED 也 404');
  });

  await t.case('API-D-06', 'P2', '无分类记录的 categoryId / categoryName 为 null', async () => {
    const res = await ctx.anon.get('/api/records/26');
    expectCode(res, 200, 0, '详情 26');
    eq(res.body.data.categoryId, null, 'categoryId');
    eq(res.body.data.categoryName, null, 'categoryName');
  });
}

// ---------------------------------------------------------------- 提交

async function createSuite() {
  await t.case('API-C-01', 'P0', '合法提交返回与详情同构的对象，初始状态 PENDING', async () => {
    const { res } = await createRecordAs(ctx.demo01, { contact: '13800009999', publisherName: 'QA' });
    expectCode(res, 200, 0, '提交记录');
    const d = res.body.data;
    assert(typeof d.id === 'number' && d.id > 0, `新记录应有 id，实际 ${JSON.stringify(d.id)}`);
    eq(d.status, 'PENDING', '新记录 status');
    eq(d.completedAt, null, '新记录 completedAt');
    eq(d.allowedStatuses, ['CLOSED'], '新记录 allowedStatuses');
    eq(d.hasMatchedPair, false, '新记录 hasMatchedPair');
    eq(d.pairs, [], '新记录 pairs');
    eq(d.contact, '13800009999', 'contact 回显');
    const detail = await ctx.demo01.get(`/api/records/${d.id}`);
    expectCode(detail, 200, 0, '提交后取详情');
    eq(detail.body.data.title, d.title, '提交后详情标题');
  });

  await t.case('API-C-02', 'P1', '可选字段缺省时落为 null', async () => {
    const { res } = await createRecordAs(ctx.demo01);
    expectCode(res, 200, 0, '最小合法提交');
    eq(res.body.data.categoryId, null, 'categoryId');
    eq(res.body.data.contact, null, 'contact');
    eq(res.body.data.publisherName, '演示甲', 'publisherName 缺省时回落到会话 displayName（契约 §4.3）');
  });

  await t.case('API-C-03', 'P1', '分类不存在 404/40001，分类停用 409/40002', async () => {
    expectCode((await createRecordAs(ctx.demo01, { categoryId: 999999 })).res, 404, 40001, '分类不存在');
    expectCode((await createRecordAs(ctx.demo01, { categoryId: 9 })).res, 409, 40002, '分类停用');
  });

  await t.case('API-C-04', 'P1', '标题长度边界：1 字与 65 字均 10001', async () => {
    const short = await createRecordAs(ctx.demo01, { title: '丢' });
    expectCode(short.res, 400, 10001, '标题 1 字');
    assert('title' in fieldReasons(short.res), 'fieldErrors 应指向 title');
    const long = await createRecordAs(ctx.demo01, { title: '长'.repeat(65) });
    expectCode(long.res, 400, 10001, '标题 65 字');
    assert('title' in fieldReasons(long.res), 'fieldErrors 应指向 title');
  });

  await t.case('API-C-05', 'P1', '描述长度边界：1 字拒绝、2000 字通过', async () => {
    const short = await createRecordAs(ctx.demo01, { description: '短' });
    expectCode(short.res, 400, 10001, '描述 1 字');
    assert('description' in fieldReasons(short.res), 'fieldErrors 应指向 description');
    const edge = await createRecordAs(ctx.demo01, { description: '描'.repeat(2000) });
    expectCode(edge.res, 200, 0, '描述 2000 字（上限内）');
    eq(edge.res.body.data.description.length, 2000, '落库描述长度');
    expectCode((await createRecordAs(ctx.demo01, { description: '描'.repeat(2001) })).res, 400, 10001, '描述 2001 字');
  });

  await t.case('API-C-06', 'P1', '地点 129 字拒绝、1 字通过', async () => {
    expectCode((await createRecordAs(ctx.demo01, { location: '地'.repeat(129) })).res, 400, 10001, '地点 129 字');
    expectCode((await createRecordAs(ctx.demo01, { location: '楼' })).res, 200, 0, '地点 1 字');
  });

  await t.case('API-C-07', 'P1', 'occurredAt 未来 10 分钟拒绝、3 分钟放行（容差 5 分钟）', async () => {
    const apiTime = (d) => {
      const p = (n) => String(n).padStart(2, '0');
      return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
    };
    const r1 = await createRecordAs(ctx.demo01, { occurredAt: apiTime(new Date(Date.now() + 10 * 60 * 1000)) });
    expectCode(r1.res, 400, 10001, '未来 10 分钟');
    assert('occurredAt' in fieldReasons(r1.res), 'fieldErrors 应指向 occurredAt');
    const r2 = await createRecordAs(ctx.demo01, { occurredAt: apiTime(new Date(Date.now() + 3 * 60 * 1000)) });
    expectCode(r2.res, 200, 0, '未来 3 分钟（容差内）');
  });

  await t.case('API-C-08', 'P1', 'occurredAt 格式严格（分隔符、时间必须合法）', async () => {
    for (const value of ['2026/10/20 10:00:00', '2026-10-20T10:00:00', '2026-13-40 25:61:61', '20261020 100000']) {
      expectCode((await createRecordAs(ctx.demo01, { occurredAt: value })).res, 400, 10001, `occurredAt=${value}`);
    }
  });

  await t.case('API-C-09', 'P1', 'type 非法 10001，必填项缺失一并返回 fieldErrors', async () => {
    const bad = await createRecordAs(ctx.demo01, { type: 'SELL' });
    expectCode(bad.res, 400, 10001, 'type=SELL');
    assert('type' in fieldReasons(bad.res), 'fieldErrors 应指向 type');
    const empty = await ctx.demo01.post('/api/records', { body: { type: 'LOST', title: '只有标题' } });
    expectCode(empty, 400, 10001, '缺 description/location/occurredAt');
    const reasons = fieldReasons(empty);
    for (const f of ['description', 'location', 'occurredAt']) assert(f in reasons, `fieldErrors 应包含 ${f}，实际 ${JSON.stringify(reasons)}`);
  });

  await t.case('API-C-10', 'P1', 'contact 只做手机号格式校验：^1[3-9]\\d{9}$，无短信验证', async () => {
    for (const value of ['138 0000 0001', 'abc<script>', 'tel:+86', '12345678901', '1380000000']) {
      const { res } = await createRecordAs(ctx.demo01, { contact: value });
      expectCode(res, 400, 10001, `contact=${JSON.stringify(value)}`);
      assert('contact' in fieldReasons(res), `contact=${JSON.stringify(value)} 的 fieldErrors`);
    }
    const ok = await createRecordAs(ctx.demo01, { contact: '13912345678' });
    expectCode(ok.res, 200, 0, 'contact 合法手机号');
    eq(ok.res.body.data.contact, '13912345678', 'contact 原样回显（未走短信验证）');
    // 不存在短信验证/发送验证码接口：门禁先拦匿名（401），登录后确认路由确实不存在（404）
    expectCode(await ctx.anon.post('/api/auth/sms', { body: {} }), 401, 10006, '匿名短信接口（门禁）');
    expectCode(await ctx.demo01.post('/api/auth/sms', { body: {} }), 404, 10003, '短信验证接口不存在');
  });

  await t.case('API-C-11', 'P2', '中文与 emoji 往返无损（UTF-8 全链路）', async () => {
    const title = '捡到一只🐱猫（蓝眼，很乖）';
    const description = '在教学楼③号门附近捡到一只蓝眼睛的小猫，脖子上有铃铛🔔，已暂养。';
    const { res } = await createRecordAs(ctx.demo01, { title, description, publisherName: '同学🙂' });
    expectCode(res, 200, 0, 'emoji 提交');
    eq(res.body.data.title, title, '标题往返');
    eq(res.body.data.description, description, '描述往返');
  });

  await t.case('API-C-12', 'P2', '纯空格标题被 strip 后判空', async () => {
    const { res } = await createRecordAs(ctx.demo01, { title: '   ' });
    expectCode(res, 400, 10001, '纯空格标题');
    assert('title' in fieldReasons(res), 'fieldErrors 应指向 title');
  });

  await t.case('API-C-13', 'P3', '重复提交不幂等（契约 §4.3 已知行为，产生两条记录）', async () => {
    const payload = {
      type: 'LOST',
      title: `QA 重复提交 ${rid()}`,
      description: '验证契约 §4.3 的已知行为：本接口不去重，重复提交产生两条记录。',
      location: 'QA 测试地点',
      occurredAt: '2026-10-20 09:00:00',
    };
    const a = await ctx.demo01.post('/api/records', { body: payload });
    const b = await ctx.demo01.post('/api/records', { body: payload });
    expectCode(a, 200, 0, '第一次提交');
    expectCode(b, 200, 0, '第二次提交');
    if (isOk(a)) created.push({ id: a.body.data.id, owner: ctx.demo01 });
    if (isOk(b)) created.push({ id: b.body.data.id, owner: ctx.demo01 });
    assert(a.body.data.id !== b.body.data.id, '契约声明非幂等：两次提交应得到不同 id');
  });
}

// ---------------------------------------------------------------- 修改

async function updateSuite() {
  await t.case('API-U-01', 'P0', '全量更新成功且不改 status', async () => {
    const made = await createRecordAs(ctx.demo01);
    const before = made.res.body.data;
    const res = await ctx.demo01.put(`/api/records/${made.id}`, {
      body: {
        type: 'FOUND', title: 'QA 修改后的标题', description: '修改后的描述内容，用于验证全量更新语义。',
        location: 'QA 修改后的地点', occurredAt: '2026-10-19 08:30:00', contact: '13812345678', publisherName: '修改人',
      },
    });
    expectCode(res, 200, 0, '全量更新');
    const after = res.body.data;
    eq(after.title, 'QA 修改后的标题', 'title');
    eq(after.type, 'FOUND', 'type');
    eq(after.description, '修改后的描述内容，用于验证全量更新语义。', 'description');
    eq(after.status, before.status, 'status 不应被 PUT 改动');
    eq(after.contact, '13812345678', 'contact');
    assert(after.updatedAt >= before.updatedAt, `updatedAt 不应回退：${before.updatedAt} → ${after.updatedAt}`);
  });

  await t.case('API-U-02', 'P1', '未传的可选字段按全量语义清空为 null', async () => {
    const made = await createRecordAs(ctx.demo01, { contact: '13800001111', publisherName: '原联系人' });
    const res = await ctx.demo01.put(`/api/records/${made.id}`, {
      body: {
        type: 'LOST', title: 'QA 清空可选字段', description: '本次请求故意不带 contact 与 publisherName，验证全量更新语义。',
        location: 'QA 地点', occurredAt: '2026-10-18 08:00:00',
      },
    });
    expectCode(res, 200, 0, '全量更新（省略可选字段）');
    eq(res.body.data.contact, null, 'contact 应被清空');
    eq(res.body.data.publisherName, null, 'publisherName 应被清空');
  });

  await t.case('API-U-03', 'P1', '已关闭记录不可修改 409 + 20003；非本人 403', async () => {
    const made = await createClosedAs(ctx.demo01);
    const body = { type: 'LOST', title: '试图修改已关闭记录', description: '已关闭记录的内容不允许再修改，期望 409 20003。', location: 'QA', occurredAt: '2026-10-18 08:00:00' };
    expectCode(await ctx.demo01.put(`/api/records/${made.id}`, { body }), 409, 20003, 'PUT 已关闭记录');
    expectCode(await ctx.demo02.put(`/api/records/${made.id}`, { body }), 404, 20001, '非本人 PUT 已关闭记录（不可见）');
  });

  await t.case('API-U-04', 'P1', 'PUT 不存在的记录 404 + 20001', async () => {
    const res = await ctx.demo01.put('/api/records/999999', {
      body: { type: 'LOST', title: '不存在的记录', description: '用于校验 20001 的请求体。', location: 'QA', occurredAt: '2026-10-18 08:00:00' },
    });
    expectCode(res, 404, 20001, 'PUT 不存在记录');
  });

  await t.case('API-U-05', 'P1', 'PUT 缺 type 或字段非法时 10001', async () => {
    const made = await createRecordAs(ctx.demo01);
    const missingType = await ctx.demo01.put(`/api/records/${made.id}`, {
      body: { title: '缺 type', description: '契约 §4.4 声明 PUT 请求体与新增完全同构，type 必填。', location: 'QA', occurredAt: '2026-10-18 08:00:00' },
    });
    expectCode(missingType, 400, 10001, 'PUT 缺 type');
    assert('type' in fieldReasons(missingType), 'fieldErrors 应指向 type');
    const badTitle = await ctx.demo01.put(`/api/records/${made.id}`, {
      body: { type: 'LOST', title: 'x', description: '标题只给一个字，应被拒绝。', location: 'QA', occurredAt: '2026-10-18 08:00:00' },
    });
    expectCode(badTitle, 400, 10001, 'PUT 标题过短');
    assert('title' in fieldReasons(badTitle), 'fieldErrors 应指向 title');
  });
}

// ---------------------------------------------------------------- 状态流转

async function statusSuite() {
  await t.case('API-S-01', 'P0', 'PENDING → CLOSED 成功；终态再流转 409 + 20002', async () => {
    const made = await createRecordAs(ctx.demo01);
    const closed = await ctx.demo01.patch(`/api/records/${made.id}/status`, { body: { status: 'CLOSED' } });
    expectCode(closed, 200, 0, 'PENDING→CLOSED');
    eq(closed.body.data.status, 'CLOSED', '流转后 status');
    eq(closed.body.data.completedAt, null, 'CLOSED 的 completedAt');
    eq(closed.body.data.allowedStatuses, [], '终态 allowedStatuses');
    const again = await ctx.demo01.patch(`/api/records/${made.id}/status`, { body: { status: 'COMPLETED' } });
    expectCode(again, 409, 20002, 'CLOSED→COMPLETED');
  });

  await t.case('API-S-02', 'P1', '终态不可再流转 409 + 20002，且带 current / allowed', async () => {
    const made = await createClosedAs(ctx.demo01);
    const res = await ctx.demo01.patch(`/api/records/${made.id}/status`, { body: { status: 'COMPLETED' } });
    expectCode(res, 409, 20002, 'CLOSED→COMPLETED');
    eq(res.body.data.current, 'CLOSED', 'data.current');
    eq(res.body.data.allowed, ['(无)'], 'data.allowed');
  });

  await t.case('API-S-03', 'P1', '流转到当前相同状态 409 + 20002（PENDING 不是合法目标值 → 400）', async () => {
    // 非本人 + 合法目标：先验证越权出口
    expectCode(await ctx.demo01.patch('/api/records/13/status', { body: { status: 'COMPLETED' } }), 403, 10007, '非本人 PENDING→COMPLETED');
    // 契约 §4.5：目标值只允许 COMPLETED/CLOSED，PENDING 是 400 而不是 409
    const draft = await createRecordAs(ctx.demo01);
    const pending = await ctx.demo01.patch(`/api/records/${draft.id}/status`, { body: { status: 'PENDING' } });
    expectCode(pending, 400, 10001, 'PENDING→PENDING（非法目标值）');
    // 同值 409 用终态 CLOSED→CLOSED 验证（目标值合法）
    const closed = await createClosedAs(ctx.demo02);
    const same = await ctx.demo02.patch(`/api/records/${closed.id}/status`, { body: { status: 'CLOSED' } });
    expectCode(same, 409, 20002, 'CLOSED→CLOSED');
    eq(same.body.data.current, 'CLOSED', 'data.current');
  });

  await t.case('API-S-04', 'P1', '目标状态非法 400 + 10001（field=status，CLAIMED 亦非法）', async () => {
    const made = await createRecordAs(ctx.demo01);
    for (const status of ['DONE', 'CLAIMED', 'RESOLVED']) {
      const res = await ctx.demo01.patch(`/api/records/${made.id}/status`, { body: { status } });
      expectCode(res, 400, 10001, `status=${status}`);
      assert('status' in fieldReasons(res), `fieldErrors 应指向 status（${status}）`);
    }
  });

  await t.case('API-S-05', 'P1', '对不存在的记录流转 404 + 20001', async () => {
    expectCode(await ctx.demo01.patch('/api/records/999999/status', { body: { status: 'CLOSED' } }), 404, 20001, '不存在记录的状态流转');
  });
}

// ---------------------------------------------------------------- 删除

async function deleteSuite() {
  await t.case('API-X-01', 'P0', '逻辑删除后详情 404、列表不可见、匹配候选池不含', async () => {
    const title = `QA 待删除记录 ${rid()}`;
    const made = await createRecordAs(ctx.demo01, { title, description: '这条记录将被删除，用于验证三条读取路径都过滤逻辑删除。' });
    expectCode(await ctx.demo01.del(`/api/records/${made.id}`), 200, 0, '删除');
    expectCode(await ctx.anon.get(`/api/records/${made.id}`), 404, 20001, '删除后详情');
    const list = await ctx.anon.get('/api/records?keyword=' + encodeURIComponent(title) + '&status=ALL&pageSize=50');
    expectCode(list, 200, 0, '删除后按标题检索');
    eq(list.body.data.total, 0, '删除后列表不可见');
    const match = await ctx.demo01.post('/api/match', { body: { text: title, topN: 20, minScore: 0 } });
    expectCode(match, 200, 0, '删除后匹配');
    assert(!match.body.data.items.some((i) => i.recordId === made.id), `匹配候选池不应包含已删除记录 ${made.id}`);
  });

  await t.case('API-X-02', 'P1', '重复删除 404 + 20001', async () => {
    const made = await createRecordAs(ctx.demo01);
    expectCode(await ctx.demo01.del(`/api/records/${made.id}`), 200, 0, '首次删除');
    expectCode(await ctx.demo01.del(`/api/records/${made.id}`), 404, 20001, '重复删除');
  });

  await t.case('API-X-03', 'P1', '删除不存在的记录 404 + 20001', async () => {
    expectCode(await ctx.demo01.del('/api/records/999999'), 404, 20001, '删除不存在记录');
  });
}

// ---------------------------------------------------------------- 分类字典

async function categorySuite() {
  await t.case('API-CAT-01', 'P0', '默认只返启用分类，按 sortOrder 升序，字段与契约一致', async () => {
    const res = await ctx.anon.get('/api/categories');
    expectCode(res, 200, 0, '分类字典');
    const list = res.body.data;
    eq(list.length, 8, '启用分类数量');
    const orders = list.map((c) => c.sortOrder);
    for (let i = 1; i < orders.length; i += 1) assert(orders[i - 1] <= orders[i], `sortOrder 未升序：${orders[i - 1]} > ${orders[i]}`);
    for (const c of list) {
      eq(Object.keys(c).sort(), ['active', 'code', 'id', 'name', 'sortOrder'], `分类 ${c.id} 的字段集合`);
      eq(c.active, true, `分类 ${c.id} 的 active`);
      assert(!('keywords' in c), '分类字典不得返回 keywords（内部资料）');
    }
  });

  await t.case('API-CAT-02', 'P1', 'onlyActive=false 含停用分类', async () => {
    const res = await ctx.anon.get('/api/categories?onlyActive=false');
    expectCode(res, 200, 0, '含停用分类');
    eq(res.body.data.length, 9, '全部分类数量');
    eq(res.body.data.filter((c) => !c.active).map((c) => c.code), ['SPORTS'], '停用分类 code');
  });
}

// ---------------------------------------------------------------- 语义匹配

const SAMPLE = [
  { id: 'S1', text: '我在图书馆三楼弄丢了一个黑色皮钱包，里面有银行卡', type: 'FOUND', top1: 13 },
  { id: 'S2', text: '操场跑步忘拿了白色 iPhone，透明壳带小熊贴纸', type: 'FOUND', top1: 14 },
  { id: 'S3', text: '高数课上的学生证校园卡不见了，姓名张三', type: 'FOUND', top1: 15 },
  { id: 'S4', text: '食堂丢了把深蓝自动伞，木头伞柄', type: 'FOUND', top1: 16 },
  { id: 'S5', text: '白色的无线耳机加充电盒，右耳有划痕', type: 'FOUND', top1: 17 },
  { id: 'S6', text: '快递点掉的钥匙串，黄色小熊挂饰', type: 'FOUND', top1: 18 },
];

async function matchSuite() {
  await t.case('API-M-01', 'P0', 'S1–S6 正例 top1 命中（失主找招领帖 ⇒ type=FOUND）', async () => {
    for (const s of SAMPLE) {
      const res = await ctx.demo01.post('/api/match', { body: { text: s.text, type: s.type, topN: 5, minScore: 0.3 } });
      expectCode(res, 200, 0, `${s.id} 匹配`);
      assert(res.body.data.items.length > 0, `${s.id} 应有命中结果，实际为空`);
      eq(res.body.data.items[0].recordId, s.top1, `${s.id} 的 top1`);
    }
  });

  await t.case('API-M-02', 'P0', '结果结构与排序：倒序、去重、score 口径、total=items.length', async () => {
    const res = await ctx.demo01.post('/api/match', { body: { text: SAMPLE[0].text, type: 'FOUND', topN: 20, minScore: 0.3 } });
    expectCode(res, 200, 0, 'S1 匹配');
    const d = res.body.data;
    eq(d.engine, 'LOCAL_KEYWORD', 'engine');
    eq(d.inputType, 'FOUND', 'inputType');
    eq(d.topN, 20, 'topN 回显');
    eq(d.minScore, 0.3, 'minScore 回显');
    eq(d.total, d.items.length, 'total 应等于 items.length');
    assert(typeof d.matchId === 'number' && d.matchId > 0, `matchId 应为快照主键，实际 ${JSON.stringify(d.matchId)}`);
    const ids = d.items.map((i) => i.recordId);
    eq(ids.length, new Set(ids).size, 'recordId 不得重复');
    const scores = d.items.map((i) => i.score);
    for (let i = 0; i < scores.length; i += 1) {
      assert(scores[i] >= 0 && scores[i] <= 1, `score 越界：${scores[i]}`);
      assert(scores[i] >= 0.3, `score 低于 minScore：${scores[i]}`);
      if (i > 0) assert(scores[i - 1] >= scores[i], `未按分数倒序：${scores[i - 1]} < ${scores[i]}`);
    }
    for (const item of d.items) {
      eq(item.scorePercent, Math.round(item.score * 100), `record ${item.recordId} 的 scorePercent`);
      assert(typeof item.reason === 'string' && item.reason.length > 0, `record ${item.recordId} 缺 reason`);
      assert(['PENDING', 'COMPLETED'].includes(item.recordStatus), `recordStatus 非法：${item.recordStatus}`);
      eq(item.detailAccess, item.recordStatus === 'PENDING' ? 'OPEN' : 'RESTRICTED', `record ${item.recordId} 的 detailAccess`);
    }
  });

  await t.case('API-M-03', 'P1', 'excludeRecordId 生效（详情页发起匹配时排除自身）', async () => {
    const res = await ctx.demo01.post('/api/match', { body: { text: SAMPLE[0].text, type: 'FOUND', topN: 20, minScore: 0.3, excludeRecordId: 13 } });
    expectCode(res, 200, 0, 'S1 + exclude 13');
    assert(!res.body.data.items.some((i) => i.recordId === 13), '结果仍包含被排除的 13');
  });

  await t.case('API-M-04', 'P1', 'N1 计算器问句命中 7（拾到者找失物帖 ⇒ type=LOST）', async () => {
    const res = await ctx.demo01.post('/api/match', { body: { text: '捡到过一个计算器吗？灰色的卡西欧', type: 'LOST', topN: 5, minScore: 0.3 } });
    expectCode(res, 200, 0, 'N1 匹配');
    assert(res.body.data.items.length >= 1, 'N1 应有命中');
    eq(res.body.data.items[0].recordId, 7, 'N1 的 top1');
  });

  await t.case('API-M-05', 'P1', 'N2 负例返回 200 + 空结果（不是错误、不是高分误报）', async () => {
    const res = await ctx.demo01.post('/api/match', { body: { text: '有人在体育馆捡到泳镜吗', type: 'LOST', topN: 5, minScore: 0.3 } });
    expectCode(res, 200, 0, 'N2 匹配');
    eq(res.body.data.total, 0, 'N2 total');
    eq(res.body.data.items, [], 'N2 items');
    assert(typeof res.body.data.matchId === 'number', '空结果也应写快照（matchId 非空）');
  });

  await t.case('API-M-06', 'P1', 'N4：COMPLETED 保留期内进候选池并标 RESTRICTED；CLOSED 与到期记录 0 命中', async () => {
    const res = await ctx.demo01.post('/api/match', { body: { text: '捡到一串钥匙', topN: 20, minScore: 0.3 } });
    expectCode(res, 200, 0, 'N4 匹配');
    const items = res.body.data.items;
    const ids = items.map((i) => i.recordId);
    assert(ids.includes(18), `N4 应命中 18，实际 ${JSON.stringify(ids)}`);
    const c23 = items.find((i) => i.recordId === 23);
    assert(c23, `23（COMPLETED 3 天前）应进候选池，实际 ${JSON.stringify(ids)}`);
    eq(c23.recordStatus, 'COMPLETED', '23 的 recordStatus');
    eq(c23.detailAccess, 'RESTRICTED', '23 的 detailAccess');
    assert(!ids.includes(24), 'N4 不得返回 CLOSED 的 24');
    assert(!ids.includes(25), 'N4 不得返回到期 COMPLETED 的 25');
  });

  await t.case('API-M-07', 'P1', 'N3 同时命中 4 与 16（雨伞语义两侧都能召回）', async () => {
    const res = await ctx.demo01.post('/api/match', { body: { text: '丢了一把雨伞', topN: 20, minScore: 0.3 } });
    expectCode(res, 200, 0, 'N3 匹配');
    const ids = res.body.data.items.map((i) => i.recordId);
    assert(ids.includes(4) && ids.includes(16), `N3 应同时命中 4/16，实际 ${JSON.stringify(ids)}`);
  });

  await t.case('API-M-08', 'P1', '同一输入两次调用结果完全一致（本地实现必须确定）', async () => {
    const body = { text: SAMPLE[2].text, type: 'FOUND', topN: 10, minScore: 0.2 };
    const a = await ctx.demo01.post('/api/match', { body });
    const b = await ctx.demo01.post('/api/match', { body });
    expectCode(a, 200, 0, '第一次');
    expectCode(b, 200, 0, '第二次');
    eq(a.body.data.items.map((i) => [i.recordId, i.score]), b.body.data.items.map((i) => [i.recordId, i.score]), '两次调用的 (recordId, score) 序列');
    assert(a.body.data.matchId !== b.body.data.matchId, '两次调用应各自产生一条快照');
  });

  await t.case('API-M-09', 'P1', 'topN / minScore / type / categoryId 约束生效', async () => {
    const one = await ctx.demo01.post('/api/match', { body: { text: SAMPLE[0].text, type: 'FOUND', topN: 1, minScore: 0.1 } });
    expectCode(one, 200, 0, 'topN=1');
    eq(one.body.data.items.length, 1, 'topN=1 的条数');
    const high = await ctx.demo01.post('/api/match', { body: { text: '捡到一串钥匙', topN: 20, minScore: 0.95 } });
    expectCode(high, 200, 0, 'minScore=0.95');
    assert(high.body.data.items.every((i) => i.score >= 0.95), 'minScore 未生效');
    const typed = await ctx.demo01.post('/api/match', { body: { text: '捡到一串钥匙', type: 'LOST', topN: 20, minScore: 0 } });
    expectCode(typed, 200, 0, 'type=LOST');
    assert(typed.body.data.items.every((i) => i.type === 'LOST'), 'type 过滤未生效');
    const cat = await ctx.demo01.post('/api/match', { body: { text: SAMPLE[0].text, type: 'FOUND', categoryId: 1, topN: 20, minScore: 0 } });
    expectCode(cat, 200, 0, 'categoryId=1');
    assert(cat.body.data.items.every((i) => i.categoryName === '钱包卡证'), 'categoryId 过滤未生效');
  });

  await t.case('API-M-10', 'P1', 'text 非法统一回 400 + 30001（空/1 字/超长）', async () => {
    for (const text of ['', '   ', 'a', 'x'.repeat(2001)]) {
      const res = await ctx.demo01.post('/api/match', { body: { text } });
      expectCode(res, 400, 30001, `text=${JSON.stringify(text.slice(0, 12))}…`);
      assert('text' in fieldReasons(res), 'fieldErrors 应指向 text');
    }
  });

  await t.case('API-M-11', 'P2', 'topN / minScore / type / categoryId 非法各自 10001 或 40001', async () => {
    const cases = [
      [{ text: '捡到钥匙', topN: 0 }, 400, 10001, 'topN'],
      [{ text: '捡到钥匙', topN: 21 }, 400, 10001, 'topN'],
      [{ text: '捡到钥匙', minScore: 1.5 }, 400, 10001, 'minScore'],
      [{ text: '捡到钥匙', minScore: -0.1 }, 400, 10001, 'minScore'],
      [{ text: '捡到钥匙', type: 'ANY' }, 400, 10001, 'type'],
    ];
    for (const [body, status, code, field] of cases) {
      const res = await ctx.demo01.post('/api/match', { body });
      expectCode(res, status, code, JSON.stringify(body));
      assert(field in fieldReasons(res), `fieldErrors 应指向 ${field}，实际 ${JSON.stringify(fieldReasons(res))}`);
    }
    expectCode(await ctx.demo01.post('/api/match', { body: { text: '捡到钥匙', categoryId: 999999 } }), 404, 40001, 'categoryId 不存在');
  });

  await t.case('API-M-12', 'P2', '并发 10 次匹配全部 200 且快照 id 唯一', async () => {
    const results = await Promise.all(
      Array.from({ length: 10 }, () => ctx.demo01.post('/api/match', { body: { text: '图书馆 钱包 银行卡', type: 'FOUND', topN: 5, minScore: 0.2 } })),
    );
    for (const res of results) expectCode(res, 200, 0, '并发匹配');
    const ids = results.map((r) => r.body.data.matchId);
    eq(ids.length, new Set(ids).size, '并发调用的 matchId 必须各不相同');
  });
}

// ---------------------------------------------------------------- 遮蔽五出口

async function maskingSuite() {
  let sensitive;
  const marker = `遮蔽${rid()}`;
  const phone = '13812345678';

  await t.case('MASK-01', 'P0', '列表出口：标题号码打码、地点截断、无 contact/publisherName/description', async () => {
    const made = await createRecordAs(ctx.demo01, {
      type: 'FOUND',
      title: `${marker} 蓝色保温杯 电话${phone}`,
      description: `${marker} 在第三教学楼201教室捡到蓝色保温杯，我的手机 ${phone}，QQ 123456789。`,
      location: '第三教学楼201教室',
      contact: '13987654321',
      publisherName: '端测测',
    });
    expectCode(made.res, 200, 0, '夹具：敏感记录');
    sensitive = made;
    const list = await ctx.demo02.get('/api/records?keyword=' + encodeURIComponent(marker) + '&pageSize=50');
    expectCode(list, 200, 0, '非本人列表');
    const item = list.body.data.list.find((x) => x.id === made.id);
    assert(item, '非本人列表应能看到该 PENDING 记录');
    assert(!new RegExp(`1[3-9]\\d{9}`).test(JSON.stringify(item)), `列表项出现手机号：${JSON.stringify(item)}`);
    assert(item.location.endsWith('…') && !item.location.includes('201'), `地点应截断：${item.location}`);
    assert(!('contact' in item) && !('publisherName' in item) && !('description' in item), `列表项不应含 contact/publisherName/description：${Object.keys(item)}`);
    eq(item.isMine, false, '非本人 isMine');
  });

  await t.case('MASK-02', 'P0', '详情出口：非本人 contact=null、描述打码、地点截断；本人原值', async () => {
    assert(sensitive, '依赖 MASK-01 的夹具');
    const other = await ctx.demo02.get(`/api/records/${sensitive.id}`);
    expectCode(other, 200, 0, '非本人详情');
    const d = other.body.data;
    eq(d.contact, null, '非本人 contact');
    eq(d.isMine, false, '非本人 isMine');
    assert(!new RegExp(`1[3-9]\\d{9}`).test(JSON.stringify(d)), `非本人详情出现手机号：${JSON.stringify(d).slice(0, 300)}`);
    assert(d.description.includes('[手机号已隐藏]'), `描述应打码：${d.description}`);
    eq(d.location, '第三教学楼…', '地点截断口径');
    assert(d.publisherName !== '端测测' && d.publisherName.includes('*'), `publisherName 应打码：${d.publisherName}`);

    const mine = await ctx.demo01.get(`/api/records/${sensitive.id}`);
    expectCode(mine, 200, 0, '本人详情');
    eq(mine.body.data.contact, '13987654321', '本人 contact 原值');
    eq(mine.body.data.publisherName, '端测测', '本人 publisherName 原值');
    assert(mine.body.data.description.includes(phone), '本人描述应保留原文');
  });

  await t.case('MASK-03', 'P0', '匹配出口：候选 title/location/reason 走同一打码器，不返回 contact/publisherName', async () => {
    assert(sensitive, '依赖 MASK-01 的夹具');
    const res = await ctx.demo02.post('/api/match', { body: { text: `${marker} 蓝色保温杯`, type: 'FOUND', topN: 20, minScore: 0 } });
    expectCode(res, 200, 0, '非本人匹配');
    const item = res.body.data.items.find((x) => x.recordId === sensitive.id);
    assert(item, `匹配结果应包含 ${sensitive.id}，实际 ${JSON.stringify(res.body.data.items.map((x) => x.recordId))}`);
    assert(!new RegExp(`1[3-9]\\d{9}`).test(JSON.stringify(item)), `匹配项出现手机号：${JSON.stringify(item)}`);
    assert(!('contact' in item) && !('publisherName' in item), '匹配项不应返回 contact/publisherName');
    eq(item.location, '第三教学楼…', '匹配项地点截断');
  });

  await t.case('MASK-04', 'P0', '通知出口：title/body 不含手机号（写入前已打码）', async () => {
    const res = await ctx.demo02.get('/api/notifications?pageSize=50');
    expectCode(res, 200, 0, '通知列表');
    assert(!new RegExp(`1[3-9]\\d{9}`).test(JSON.stringify(res.body.data)), `通知出现手机号：${JSON.stringify(res.body.data).slice(0, 400)}`);
  });

  await t.case('MASK-05', 'P0', '我的启事出口：本人数据不遮蔽（与公共出口口径不同，但只对本人）', async () => {
    const res = await ctx.demo01.get('/api/my/records?pageSize=50');
    expectCode(res, 200, 0, '我的启事');
    const item = res.body.data.list.find((x) => x.id === sensitive.id);
    assert(item, '我的启事应包含本人记录');
    eq(item.contact, '13987654321', '本人 my.contact');
    assert(item.title.includes(phone), '本人 my.title 原值');
    const other = await ctx.demo02.get('/api/my/records?pageSize=50');
    expectCode(other, 200, 0, '他人我的启事');
    assert(!other.body.data.list.some((x) => x.id === sensitive.id), '他人的我的启事不应包含这条记录');
  });

  await t.case('MASK-06', 'P0', '非本人响应全量扫描 1[3-9]\\d{9}：列表/详情/匹配/通知 0 命中', async () => {
    const responses = [
      ['列表', await ctx.demo02.get('/api/records?status=ALL&pageSize=50')],
      ['详情 3', await ctx.demo02.get('/api/records/3')],
      ['详情 15', await ctx.demo02.get('/api/records/15')],
      ['详情 23', await ctx.demo02.get('/api/records/23')],
      ['匹配', await ctx.demo02.post('/api/match', { body: { text: '钱包 银行卡 钥匙 手机', topN: 20, minScore: 0 } })],
      ['通知', await ctx.demo02.get('/api/notifications?pageSize=50')],
    ];
    const hits = [];
    for (const [label, res] of responses) {
      const matches = (res.raw.match(/1[3-9]\d{9}/g) ?? []);
      if (matches.length > 0) hits.push(`${label}: ${matches.join(',')}`);
    }
    eq(hits, [], '非本人响应中的手机号');
  });
}

// ---------------------------------------------------------------- 三档与阈值边界

async function tierSuite() {
  await t.case('TIER-01', 'P0', 'DIRECT（≥0.90）：两端各自拿到对方号码，且写 GRANTED 审计', async () => {
    const { pairId } = await makePair({ score: 0.96 });
    const a = await ctx.demo01.get(`/api/pairs/${pairId}/contact`);
    const b = await ctx.demo02.get(`/api/pairs/${pairId}/contact`);
    expectCode(a, 200, 0, 'LOST 端取号');
    expectCode(b, 200, 0, 'FOUND 端取号');
    eq(a.body.data.tier, 'DIRECT', 'tier');
    eq(a.body.data.available, true, 'available');
    assert(a.body.data.contact && a.body.data.contact !== b.body.data.contact, '两端应拿到对方的号码（不同值）');
    eq(await accessLogResults(pairId), ['GRANTED', 'GRANTED'], '审计结果序列');
  });

  await t.case('TIER-02', 'P0', 'AUTHORIZABLE（0.75–0.90）：未公开 available=false → 公开有号带打码附言 → 撤回回退', async () => {
    const { pairId } = await makePair({ score: 0.86 });
    const before = await ctx.demo01.get(`/api/pairs/${pairId}/contact`);
    expectCode(before, 200, 0, '未公开取号');
    eq(before.body.data.tier, 'AUTHORIZABLE', 'tier');
    eq(before.body.data.available, false, 'available');
    eq(before.body.data.reason, 'NOT_PUBLIC', 'reason');

    const publish = await ctx.demo02.post(`/api/pairs/${pairId}/contact-unlock`, { body: { action: 'PUBLISH', note: '周六下午图书馆门口等，电话13812345678' } });
    expectCode(publish, 200, 0, '公开');
    eq(publish.body.data.public, true, 'public');
    assert(publish.body.data.note.includes('[手机号已隐藏]') && !publish.body.data.note.includes('13812345678'), `附言应打码：${publish.body.data.note}`);

    const after = await ctx.demo01.get(`/api/pairs/${pairId}/contact`);
    expectCode(after, 200, 0, '公开后取号');
    eq(after.body.data.available, true, '公开后 available');
    assert(after.body.data.contact, '公开后应有号码');
    assert(after.body.data.note.includes('[手机号已隐藏]'), '附言打码后返回');

    expectCode(await ctx.demo02.post(`/api/pairs/${pairId}/contact-unlock`, { body: { action: 'REVOKE' } }), 200, 0, '撤回');
    const reverted = await ctx.demo01.get(`/api/pairs/${pairId}/contact`);
    eq(reverted.body.data.available, false, '撤回后 available');
    eq(reverted.body.data.note, null, '撤回后附言清空');
    const dbRow = await sql(`SELECT IFNULL(found_unlock_note,'NULL') FROM t_pair_score WHERE id=${Number(pairId)};`);
    eq(dbRow.trim(), 'NULL', '库层撤回后附言置空');
  });

  await t.case('TIER-03', 'P0', '<0.75 LOW：取号/授权/改 pair 全部 403 + 50002，并留 DENIED_LOW_SCORE', async () => {
    const { pairId } = await makePair({ score: 0.42 });
    expectCode(await ctx.demo01.get(`/api/pairs/${pairId}/contact`), 403, 50002, '低分取号');
    expectCode(await ctx.demo01.post(`/api/pairs/${pairId}/contact-unlock`, { body: { action: 'PUBLISH' } }), 403, 50002, '低分公开');
    expectCode(await ctx.demo01.patch(`/api/pairs/${pairId}`, { body: { action: 'CONTACT' } }), 403, 50002, '低分联系');
    const results = await accessLogResults(pairId);
    eq(results, ['DENIED_LOW_SCORE'], '审计结果');
  });

  await t.case('TIER-04', 'P1', '阈值边界：0.90=DIRECT、0.75=AUTHORIZABLE、0.7499=LOW', async () => {
    const cases = [
      [0.9, 'DIRECT', 200],
      [0.75, 'AUTHORIZABLE', 200],
      [0.7499, 'LOW', 403],
    ];
    for (const [score, tier, status] of cases) {
      const { pairId } = await makePair({ score });
      const res = await ctx.demo01.get(`/api/pairs/${pairId}/contact`);
      if (status === 403) {
        expectCode(res, 403, 50002, `score=${score}`);
      } else {
        expectCode(res, 200, 0, `score=${score}`);
        eq(res.body.data.tier, tier, `score=${score} 的 tier`);
      }
    }
  });
}

// ---------------------------------------------------------------- 幂等

async function idempotencySuite() {
  await t.case('IDEM-01', 'P0', '同一 pair 重复 CONTACT：200 且 updatedAt 不变（不写库）', async () => {
    const { pairId } = await makePair({ score: 0.96 });
    const first = await ctx.demo01.patch(`/api/pairs/${pairId}`, { body: { action: 'CONTACT' } });
    expectCode(first, 200, 0, '第一次 CONTACT');
    const before = (await sql(`SELECT DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s') FROM t_pair_score WHERE id=${Number(pairId)};`)).trim();
    const second = await ctx.demo01.patch(`/api/pairs/${pairId}`, { body: { action: 'CONTACT' } });
    expectCode(second, 200, 0, '第二次 CONTACT');
    eq(second.body.data.updatedAt, first.body.data.updatedAt, '两次响应 updatedAt');
    const after = (await sql(`SELECT DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s') FROM t_pair_score WHERE id=${Number(pairId)};`)).trim();
    eq(after, before, '库层 updated_at');
  });

  await t.case('IDEM-02', 'P0', '重复 CONFIRM：单端不刷新 confirmed_at；两端齐后 MATCHED，重复不再改', async () => {
    const { lostId, foundId, pairId } = await makePair({ score: 0.96 });
    const first = await ctx.demo01.patch(`/api/pairs/${pairId}`, { body: { action: 'CONFIRM' } });
    expectCode(first, 200, 0, 'demo01 CONFIRM');
    eq(first.body.data.status, 'CONTACTED', '单端确认后 status');
    const lostConfirmed = (await pairByEnds(lostId, foundId)).lost_confirmed_at;
    const second = await ctx.demo01.patch(`/api/pairs/${pairId}`, { body: { action: 'CONFIRM' } });
    expectCode(second, 200, 0, 'demo01 重复 CONFIRM');
    eq((await pairByEnds(lostId, foundId)).lost_confirmed_at, lostConfirmed, '重复 CONFIRM 不应刷新 lost_confirmed_at');
    const mate = await ctx.demo02.patch(`/api/pairs/${pairId}`, { body: { action: 'CONFIRM' } });
    expectCode(mate, 200, 0, 'demo02 CONFIRM');
    eq(mate.body.data.status, 'MATCHED', '两端确认后 status');
    const again = await ctx.demo02.patch(`/api/pairs/${pairId}`, { body: { action: 'CONFIRM' } });
    expectCode(again, 200, 0, 'MATCHED 重复 CONFIRM');
    eq(again.body.data.updatedAt, mate.body.data.updatedAt, 'MATCHED 重复 CONFIRM 的 updatedAt');
  });

  await t.case('IDEM-03', 'P1', '重复 PUBLISH：unlockedAt 不变且不重复写 PAIR_UNLOCK 通知', async () => {
    const { pairId } = await makePair({ score: 0.86 });
    const first = await ctx.demo02.post(`/api/pairs/${pairId}/contact-unlock`, { body: { action: 'PUBLISH', note: '第一次' } });
    expectCode(first, 200, 0, '第一次 PUBLISH');
    const count = Number(await val(`SELECT COUNT(*) FROM t_notification WHERE pair_id=${Number(pairId)} AND type='PAIR_UNLOCK';`));
    const second = await ctx.demo02.post(`/api/pairs/${pairId}/contact-unlock`, { body: { action: 'PUBLISH', note: '第二次' } });
    expectCode(second, 200, 0, '第二次 PUBLISH');
    eq(second.body.data.unlockedAt, first.body.data.unlockedAt, 'unlockedAt');
    eq(Number(await val(`SELECT COUNT(*) FROM t_notification WHERE pair_id=${Number(pairId)} AND type='PAIR_UNLOCK';`)), count, '通知条数');
    eq(count, 1, '公开应只写一条通知');
  });

  await t.case('IDEM-04', 'P1', '重复 REVOKE：200 且保持 null（自然幂等）', async () => {
    const { pairId } = await makePair({ score: 0.86 });
    expectCode(await ctx.demo02.post(`/api/pairs/${pairId}/contact-unlock`, { body: { action: 'PUBLISH' } }), 200, 0, '先公开');
    for (let i = 0; i < 2; i += 1) {
      const res = await ctx.demo02.post(`/api/pairs/${pairId}/contact-unlock`, { body: { action: 'REVOKE' } });
      expectCode(res, 200, 0, `第 ${i + 1} 次 REVOKE`);
      eq(res.body.data.public, false, 'public');
      eq(res.body.data.unlockedAt, null, 'unlockedAt');
    }
  });
}

// ---------------------------------------------------------------- 降级

async function degradationSuite() {
  await t.case('DEG-01', 'P0', '空 Key 发布：记录照常 200，pair 落 LOCAL_KEYWORD + EMPTY_KEY，记录 pairState=DEGRADED', async () => {
    const made = await createRecordAs(ctx.demo01, {
      type: 'LOST',
      title: `降级测试 图书馆黑色钱包 ${rid()}`,
      description: '在图书馆三楼自习区丢了黑色皮质钱包，里面有两张银行卡和现金。',
      location: '图书馆三楼自习区',
    });
    expectCode(made.res, 200, 0, '空 Key 发布');
    await waitForScoring(made.id);
    const rec = await record(made.id);
    eq(rec.pair_state, 'DEGRADED', '记录 pairState');
    const pair = await val(`SELECT CONCAT(engine, '|', IFNULL(degraded_reason,'NULL')) FROM t_pair_score WHERE lost_id=${Number(made.id)} LIMIT 1;`);
    assert(pair, '应生成至少一条 pair');
    const [engine, reason] = pair.split('|');
    eq(engine, 'LOCAL_KEYWORD', 'engine');
    eq(reason, 'EMPTY_KEY', 'degraded_reason');
  });

  await t.case('DEG-02', 'P1', '降级不影响 /api/match：仍返回 200 + LOCAL_KEYWORD（不是 503）', async () => {
    const res = await ctx.demo01.post('/api/match', { body: { text: '图书馆 黑色钱包 银行卡', type: 'FOUND', topN: 5, minScore: 0.2 } });
    expectCode(res, 200, 0, '匹配');
    eq(res.body.data.engine, 'LOCAL_KEYWORD', 'engine');
  });

  await t.case('DEG-03', 'P0', 'hash 变化触发重算：仍为 LOCAL_KEYWORD + EMPTY_KEY，分数写新值（不静默成功）', async () => {
    const marker = `降级重算${rid()}`;
    const made = await createRecordAs(ctx.demo01, {
      type: 'LOST', title: `降级重算 图书馆钱包 ${marker}`,
      description: '在图书馆三楼自习区丢了黑色钱包，里面有银行卡。', location: '图书馆三楼自习区',
    });
    expectCode(made.res, 200, 0, '发布');
    await waitForScoring(made.id);
    const f = await val(`SELECT f.id FROM t_pair_score p JOIN t_record f ON f.id=p.found_id WHERE p.lost_id=${Number(made.id)} LIMIT 1;`);
    assert(f, '应召回 seed 的招领帖');
    const before = await pairByEnds(made.id, Number(f));
    expectCode(
      await ctx.demo01.put(`/api/records/${made.id}`, {
        body: { type: 'LOST', title: `降级重算改标题 ${marker}`, description: '标题已变化，触发该记录相关 pair 重算。', location: '图书馆三楼自习区', occurredAt: '2026-10-20 10:00:00' },
      }),
      200, 0, '改标题触发重算',
    );
    await waitForScoring(made.id);
    await waitUntil(async () => (await pairByEnds(made.id, Number(f))).lost_hash !== before.lost_hash, { timeout: 15000, label: 'DEG-03 hash 重算' });
    const after = await pairByEnds(made.id, Number(f));
    eq(after.engine, 'LOCAL_KEYWORD', '重算后 engine');
    eq(after.degraded_reason, 'EMPTY_KEY', '重算后 degraded_reason');
    assert(after.lost_hash !== before.lost_hash, 'lost_hash 应已变化');
    assert(Number(after.score) >= 0 && Number(after.score) <= 1, `重算后分数越界：${after.score}`);
  });
}

// ---------------------------------------------------------------- 多对 pair 独立（1:1 ×N）

let pair07 = null;

async function pairIndependenceSuite() {
  await t.case('PAIR-01', 'P0', '一条招领与两条寻物各成 pair：A 标已联系后 B 仍 NEW，招领仍在候选池，记录全 PENDING', async () => {
    const marker = `多对独立${rid()}`;
    const foundTitle = `${marker} 紫色雨伞招领`;
    const found = await createRecordAs(ctx.demo03, {
      type: 'FOUND', title: foundTitle, description: `${marker} 在食堂捡到一把紫色自动雨伞，伞柄有熊图案。`, location: '第二食堂二楼',
    });
    expectCode(found.res, 200, 0, '招领');
    const lostA = await createRecordAs(ctx.demo01, { type: 'LOST', title: `${marker} 寻紫色雨伞 A`, description: `${marker} 丢了一把紫色自动雨伞，伞柄有熊图案。`, location: '第二食堂二楼' });
    const lostB = await createRecordAs(ctx.demo02, { type: 'LOST', title: `${marker} 寻紫色雨伞 B`, description: `${marker} 丢了一把紫色自动雨伞，伞柄有熊图案。`, location: '第二食堂二楼' });
    expectCode(lostA.res, 200, 0, '寻物 A');
    expectCode(lostB.res, 200, 0, '寻物 B');
    await upsertPair({ lostId: lostA.id, foundId: found.id, score: 0.97 });
    await upsertPair({ lostId: lostB.id, foundId: found.id, score: 0.94 });
    createdPairs.push({ lostId: lostA.id, foundId: found.id }, { lostId: lostB.id, foundId: found.id });
    const pairA = Number((await pairByEnds(lostA.id, found.id)).id);
    const pairB = Number((await pairByEnds(lostB.id, found.id)).id);

    expectCode(await ctx.demo01.patch(`/api/pairs/${pairA}`, { body: { action: 'CONTACT' } }), 200, 0, 'A 标为已联系');
    eq((await pairByEnds(lostA.id, found.id)).status, 'CONTACTED', 'A 的状态');
    eq((await pairByEnds(lostB.id, found.id)).status, 'NEW', 'B 仍应为 NEW');
    expectCode(await ctx.demo02.patch(`/api/pairs/${pairB}`, { body: { action: 'CONTACT' } }), 200, 0, 'B 仍可联系');

    const match = await ctx.demo01.post('/api/match', { body: { text: foundTitle, type: 'FOUND', topN: 20, minScore: 0 } });
    expectCode(match, 200, 0, '招领仍在候选池');
    assert(match.body.data.items.some((i) => i.recordId === found.id), '招领应仍在候选池（无占用语义）');

    for (const id of [lostA.id, lostB.id, found.id]) eq((await record(id)).status, 'PENDING', `记录 ${id} 的 status`);
    const statuses = await val(`SELECT GROUP_CONCAT(DISTINCT status ORDER BY status) FROM t_pair_score WHERE found_id=${Number(found.id)};`);
    assert(!/RESOLVED|SOLVED/.test(statuses ?? ''), `pair 不得出现已解决态：${statuses}`);
  });

  await t.case('PAIR-02', 'P0', '唯一键 (lost_id, found_id)：重算/重复触发仍只有一行', async () => {
    const { lostId, foundId } = await makePair({ score: 0.93 });
    eq(await pairCount(lostId, foundId), 1, '初始行数');
    await upsertPair({ lostId, foundId, score: 0.93 });
    eq(await pairCount(lostId, foundId), 1, '重复 upsert 后行数');
    expectCode(
      await ctx.demo01.put(`/api/records/${lostId}`, {
        body: { type: 'LOST', title: '唯一键重算用例', description: '改动标题触发重算，行数仍应为 1。', location: 'QA', occurredAt: '2026-10-20 10:00:00' },
      }),
      200, 0, '触发重算',
    );
    await waitForScoring(lostId);
    eq(await pairCount(lostId, foundId), 1, '重算后行数');
  });

  await t.case('PAIR-03', 'P0', '无占用/锁定：A pair MATCHED 后 B pair 仍可联系，招领仍可被匹配', async () => {
    const marker = `无占用${rid()}`;
    const found = await createRecordAs(ctx.demo03, { type: 'FOUND', title: `${marker} 银色保温杯招领`, description: `${marker} 捡到银色保温杯。`, location: '实验楼402' });
    const lostA = await createRecordAs(ctx.demo01, { type: 'LOST', title: `${marker} 寻银色保温杯 A`, description: `${marker} 丢了银色保温杯。`, location: '实验楼402' });
    const lostB = await createRecordAs(ctx.demo02, { type: 'LOST', title: `${marker} 寻银色保温杯 B`, description: `${marker} 丢了银色保温杯。`, location: '实验楼402' });
    for (const r of [lostA, lostB]) expectCode(r.res, 200, 0, '寻物');
    await upsertPair({ lostId: lostA.id, foundId: found.id, score: 0.96, status: 'MATCHED', lostConfirmedAt: '2026-10-26 10:00:00', foundConfirmedAt: '2026-10-26 10:01:00' });
    await upsertPair({ lostId: lostB.id, foundId: found.id, score: 0.91 });
    createdPairs.push({ lostId: lostA.id, foundId: found.id }, { lostId: lostB.id, foundId: found.id });
    const pairB = Number((await pairByEnds(lostB.id, found.id)).id);
    expectCode(await ctx.demo02.patch(`/api/pairs/${pairB}`, { body: { action: 'CONTACT' } }), 200, 0, 'B 在 A MATCHED 后仍可联系');
    const match = await ctx.demo01.post('/api/match', { body: { text: `${marker} 银色保温杯`, type: 'FOUND', topN: 20, minScore: 0 } });
    assert(match.body.data.items.some((i) => i.recordId === found.id), 'A MATCHED 不应把招领移出候选池');
  });

  await t.case('PAIR-04', 'P0', 'pair 进展不触发记录状态变更：status / updated_at 前后一致', async () => {
    const { lostId, foundId, pairId } = await makePair({ score: 0.96 });
    await new Promise((r) => setTimeout(r, 1100));
    const lostBefore = await recordSnapshot(lostId);
    const foundBefore = await recordSnapshot(foundId);
    expectCode(await ctx.demo01.patch(`/api/pairs/${pairId}`, { body: { action: 'CONTACT' } }), 200, 0, 'CONTACT');
    expectCode(await ctx.demo01.patch(`/api/pairs/${pairId}`, { body: { action: 'CONFIRM' } }), 200, 0, 'CONFIRM 单端');
    expectCode(await ctx.demo02.patch(`/api/pairs/${pairId}`, { body: { action: 'CONFIRM' } }), 200, 0, 'CONFIRM 两端');
    eq(await recordSnapshot(lostId), lostBefore, 'LOST 记录快照');
    eq(await recordSnapshot(foundId), foundBefore, 'FOUND 记录快照');
  });

  await t.case('PAIR-05', 'P1', 'pair 不接受 status 直传 / 已解决 action；状态只允许四态', async () => {
    const { pairId } = await makePair({ score: 0.96 });
    for (const body of [{ status: 'MATCHED' }, { action: 'RESOLVE' }, { action: 'MATCHED' }]) {
      const res = await ctx.demo01.patch(`/api/pairs/${pairId}`, { body });
      expectCode(res, 400, 10001, JSON.stringify(body));
    }
    const values = (await val('SELECT GROUP_CONCAT(DISTINCT status ORDER BY status) FROM t_pair_score;')) ?? '';
    for (const s of values.split(',')) assert(['NEW', 'CONTACTED', 'MATCHED', 'IGNORED'].includes(s), `非法 pair 状态：${s}`);
  });

  await t.case('PAIR-06', 'P0', '1:1 ×N 可同时 MATCHED：两条 pair 各自两端确认，互不影响', async () => {
    const marker = `双匹配${rid()}`;
    const found = await createRecordAs(ctx.demo03, { type: 'FOUND', title: `${marker} 黑色双肩包招领`, description: `${marker} 捡到黑色双肩包。`, location: '体育馆看台' });
    const lostA = await createRecordAs(ctx.demo01, { type: 'LOST', title: `${marker} 寻黑色双肩包 A`, description: `${marker} 丢了黑色双肩包。`, location: '体育馆看台' });
    const lostB = await createRecordAs(ctx.demo02, { type: 'LOST', title: `${marker} 寻黑色双肩包 B`, description: `${marker} 丢了黑色双肩包。`, location: '体育馆看台' });
    for (const r of [found, lostA, lostB]) expectCode(r.res, 200, 0, '夹具记录');
    await upsertPair({ lostId: lostA.id, foundId: found.id, score: 0.96 });
    await upsertPair({ lostId: lostB.id, foundId: found.id, score: 0.94 });
    createdPairs.push({ lostId: lostA.id, foundId: found.id }, { lostId: lostB.id, foundId: found.id });
    for (const [lost, owner] of [[lostA.id, ctx.demo01], [lostB.id, ctx.demo02]]) {
      const pairId = Number((await pairByEnds(lost, found.id)).id);
      expectCode(await owner.patch(`/api/pairs/${pairId}`, { body: { action: 'CONFIRM' } }), 200, 0, 'LOST 端确认');
      const both = await ctx.demo03.patch(`/api/pairs/${pairId}`, { body: { action: 'CONFIRM' } });
      expectCode(both, 200, 0, 'FOUND 端确认');
      eq(both.body.data.status, 'MATCHED', '该 pair 状态');
    }
    eq((await pairByEnds(lostA.id, found.id)).status, 'MATCHED', 'A pair');
    eq((await pairByEnds(lostB.id, found.id)).status, 'MATCHED', 'B pair');
  });

  await t.case('PAIR-07', 'P0', 'hash 变化重算不重置 IGNORED：unlocked 列/附言原样；系统无恢复路径', async () => {
    const marker = `重算忽略${rid()}`;
    const found = await createRecordAs(ctx.demo03, { type: 'FOUND', title: `${marker} 灰色围巾 洗衣房`, description: `${marker} 一条灰色针织围巾，在 3 号宿舍楼洗衣房。`, location: '3号宿舍楼洗衣房' });
    const lost = await createRecordAs(ctx.demo02, { type: 'LOST', title: `${marker} 灰色围巾 洗衣房`, description: `${marker} 一条灰色针织围巾，在 3 号宿舍楼洗衣房。`, location: '3号宿舍楼洗衣房' });
    pair07 = { lostId: lost.id, foundId: found.id };
    expectCode(found.res, 200, 0, '招领');
    expectCode(lost.res, 200, 0, '寻物');
    await upsertPair({
      lostId: lost.id, foundId: found.id, score: 0.93, status: 'IGNORED',
      lostUnlockedAt: '2026-10-26 10:00:00', foundUnlockedAt: '2026-10-26 10:05:00',
      lostNote: '失主端附言', foundNote: '拾获端附言',
    });
    createdPairs.push({ lostId: lost.id, foundId: found.id });
    const before = await pairByEnds(lost.id, found.id);
    const logBefore = await pairLog(lost.id, found.id);

    expectCode(
      await ctx.demo02.put(`/api/records/${lost.id}`, {
        body: { type: 'LOST', title: `${marker} 灰色围巾 洗衣房（改）`, description: `${marker} 一条灰色针织围巾，在 3 号宿舍楼洗衣房，已更新描述。`, location: '3号宿舍楼洗衣房', occurredAt: '2026-10-20 10:00:00' },
      }),
      200, 0, '改标题触发重算',
    );
    await waitForScoring(lost.id);
    await waitUntil(async () => (await pairByEnds(lost.id, found.id)).lost_hash !== before.lost_hash, { timeout: 15000, label: 'PAIR-07 hash 重算' });
    const after = await pairByEnds(lost.id, found.id);
    const logAfter = await pairLog(lost.id, found.id);

    eq(after.status, 'IGNORED', '重算后 pair.status');
    eq(after.lost_unlocked_at, before.lost_unlocked_at, 'lost_unlocked_at');
    eq(after.found_unlocked_at, before.found_unlocked_at, 'found_unlocked_at');
    eq(after.lost_unlock_note, before.lost_unlock_note, 'lost_unlock_note');
    eq(after.found_unlock_note, before.found_unlock_note, 'found_unlock_note');
    eq(after.lost_confirmed_at, before.lost_confirmed_at, 'lost_confirmed_at');
    eq(after.found_confirmed_at, before.found_confirmed_at, 'found_confirmed_at');
    assert(after.lost_hash !== before.lost_hash, 'lost_hash 应变化（证明真的走了重算）');
    eq(await pairCount(lost.id, found.id), 1, '仍是一行');

    // 系统没有恢复入口：IGNORED 上 CONTACT/CONFIRM 都不可能成功；若分数被重算到 <0.75，判定顺序会先命中 50002
    const terminal = Number(after.score) >= 0.75 ? [409, 50003] : [403, 50002];
    expectCode(await ctx.demo02.patch(`/api/pairs/${after.id}`, { body: { action: 'CONTACT' } }), terminal[0], terminal[1], 'IGNORED→CONTACT');
    expectCode(await ctx.demo02.patch(`/api/pairs/${after.id}`, { body: { action: 'CONFIRM' } }), terminal[0], terminal[1], 'IGNORED→CONFIRM');
    const idle = await ctx.demo02.patch(`/api/pairs/${after.id}`, { body: { action: 'IGNORE' } });
    expectCode(idle, 200, 0, 'IGNORED 重复 IGNORE');
    eq((await pairByEnds(lost.id, found.id)).status, 'IGNORED', '重复 IGNORE 后 status');

    // EV-LOG：该 pair 前后日志行与 LLM 调用计数
    assert(logAfter.available, `EV-LOG 不可用：未找到后端日志（路径候选见 qa/lib/db.mjs）`);
    const excerpt = logAfter.lines.filter((line) => line.includes('written') || line.includes('skipped reason=UNCHANGED'));
    console.log(`\n  [EV-SQL] PAIR-07 pairId=${after.id} 重算前 status=${before.status} unlocked=${before.lost_unlocked_at}/${before.found_unlocked_at} notes=${before.lost_unlock_note}/${before.found_unlock_note} hashes=${before.lost_hash.slice(0, 8)}…`);
    console.log(`  [EV-SQL] PAIR-07 pairId=${after.id} 重算后 status=${after.status} unlocked=${after.lost_unlocked_at}/${after.found_unlocked_at} notes=${after.lost_unlock_note}/${after.found_unlock_note} hashes=${after.lost_hash.slice(0, 8)}…`);
    console.log(`  [EV-LOG] PAIR-07 pairId=${after.id} 日志 ${logAfter.path}：新增 ${logAfter.lines.length - logBefore.lines.length} 行，llmCalls=${llmCallCount(logAfter.lines)}`);
    console.log(`  [EV-LOG] 摘录：${excerpt.slice(-2).join(' | ') || '(无相关行)'}`);
  });

  // 手动恢复是唯一翻转路径：直接 SQL 置回 NEW（模拟运维人工恢复），再跑一次重算验证不会被系统改回 IGNORED
  await t.case('PAIR-07b', 'P1', '只有手动恢复才翻转：SQL 置回 NEW 后重算保持 NEW', async () => {
    assert(pair07, '找不到 PAIR-07 夹具');
    const { lostId, foundId } = pair07;
    const pair = await pairByEnds(lostId, foundId);
    eq(pair.status, 'IGNORED', '恢复前状态');
    await sql(`UPDATE t_pair_score SET status='NEW' WHERE id=${Number(pair.id)};`);
    expectCode(
      await ctx.demo02.put(`/api/records/${lostId}`, {
        body: { type: 'LOST', title: `重算忽略手动恢复${rid()}`, description: '手动恢复后再改标题，验证系统不会把 NEW 改回 IGNORED。', location: '3号宿舍楼洗衣房', occurredAt: '2026-10-20 10:00:00' },
      }),
      200, 0, '恢复后再触发重算',
    );
    await waitForScoring(lostId);
    eq((await pairByEnds(lostId, foundId)).status, 'NEW', '重算后应保持 NEW');
  });
}

// ---------------------------------------------------------------- 终态可见性（STAT / TERM-06）

async function terminalSuite() {
  await t.case('STAT-01', 'P0', '未达 MATCHED 标完成 → 409 + 20005，status/completed_at/updated_at 零变化', async () => {
    const made = await createRecordAs(ctx.demo01);
    await new Promise((r) => setTimeout(r, 1100));
    const before = await recordSnapshot(made.id);
    const res = await ctx.demo01.patch(`/api/records/${made.id}/status`, { body: { status: 'COMPLETED' } });
    expectCode(res, 409, 20005, '未达 MATCHED 标完成');
    eq(res.body.data.currentStatus, 'PENDING', 'data.currentStatus');
    eq(res.body.data.required, 'MATCHED_PAIR', 'data.required');
    eq(res.body.data.matchedPairCount, 0, 'data.matchedPairCount');
    eq(await recordSnapshot(made.id), before, '记录三列快照');
  });

  await t.case('STAT-02', 'P0', 'COMPLETED：列表/搜索 0 命中、匹配可命中、owner 404、合格对端 200、非合格 404', async () => {
    const fixture = await makeCompleted({ score: 0.95, lostOwner: ctx.demo01, foundOwner: ctx.demo02 });
    const completedId = fixture.lostId;
    eq((await record(completedId)).status, 'COMPLETED', 'COMPLETED 落库');
    assert((await record(completedId)).completed_at !== 'NULL', 'completed_at 应写入');

    eq(await isListed(ctx.anon, completedId), false, '公共列表 0 命中');
    const search = await ctx.anon.get('/api/records?keyword=' + encodeURIComponent(`${fixture.marker}L`));
    expectCode(search, 200, 0, '搜索');
    eq(search.body.data.total, 0, '搜索 0 命中');

    const match = await ctx.demo01.post('/api/match', { body: { text: `${fixture.marker} 深紫色钢笔`, type: 'LOST', topN: 20, minScore: 0 } });
    expectCode(match, 200, 0, '匹配');
    const hit = match.body.data.items.find((i) => i.recordId === completedId);
    assert(hit, '保留期内 COMPLETED 应进匹配候选池');
    eq(hit.recordStatus, 'COMPLETED', 'recordStatus');
    eq(hit.detailAccess, 'RESTRICTED', 'detailAccess');

    expectCode(await ctx.demo01.get(`/api/records/${completedId}`), 404, 20001, '所有者看 COMPLETED 详情');
    expectCode(await ctx.demo02.get(`/api/records/${completedId}`), 200, 0, '合格对端看 COMPLETED 详情');
    expectCode(await ctx.demo03.get(`/api/records/${completedId}`), 404, 20001, '非合格对端看 COMPLETED 详情');
  });

  await t.case('STAT-03', 'P0', 'COMPLETED 分数 <0.90 的非合格对端：详情 404（score 是硬门槛）', async () => {
    const fixture = await makeCompleted({ score: 0.80, lostOwner: ctx.demo01, foundOwner: ctx.demo02 });
    expectCode(await ctx.demo02.get(`/api/records/${fixture.lostId}`), 404, 20001, 'score 0.80 的合格对端');
    // 同一条记录改回 PENDING 后对所有人可见，证明 404 来自 COMPLETED+分数门槛而不是别的原因
    await sql(`UPDATE t_record SET status='PENDING', completed_at=NULL WHERE id=${Number(fixture.lostId)};`);
    expectCode(await ctx.demo02.get(`/api/records/${fixture.lostId}`), 200, 0, '恢复 PENDING 后可见');
  });

  await t.case('STAT-04', 'P0', 'CLOSED：列表与匹配双 0 命中，详情任何身份 404，pair 取号 403 + 50005', async () => {
    const { lostId, foundId, pairId } = await makePair({ score: 0.96, status: 'CONTACTED' });
    expectCode(await ctx.demo01.patch(`/api/records/${lostId}/status`, { body: { status: 'CLOSED' } }), 200, 0, '关闭');
    eq(await isListed(ctx.anon, lostId), false, '列表 0 命中');
    const match = await ctx.demo01.post('/api/match', { body: { text: 'QA 测试记录', topN: 20, minScore: 0 } });
    assert(!match.body.data.items.some((i) => i.recordId === lostId), 'CLOSED 不得进候选池');
    for (const [label, api] of [['匿名', ctx.anon], ['所有者', ctx.demo01], ['他人', ctx.demo03]]) {
      expectCode(await api.get(`/api/records/${lostId}`), 404, 20001, `CLOSED 详情（${label}）`);
    }
    expectCode(await ctx.demo01.get(`/api/pairs/${pairId}/contact`), 403, 50005, 'CLOSED 的 pair 取号');
    expectCode(await ctx.demo02.patch(`/api/pairs/${pairId}`, { body: { action: 'CONTACT' } }), 403, 50005, 'CLOSED 的 pair 改状态');
    eq((await record(foundId)).status, 'PENDING', '对端记录不应被关闭');
  });

  await t.case('STAT-05', 'P0', '到期 COMPLETED：completed_at 推出保留期后从匹配消失、详情 404，status 不被自动改写', async () => {
    const fixture = await makeCompleted({ score: 0.95 });
    const completedId = fixture.lostId;
    const matchBefore = await ctx.demo01.post('/api/match', { body: { text: `${fixture.marker} 深紫色钢笔`, type: 'LOST', topN: 20, minScore: 0 } });
    assert(matchBefore.body.data.items.some((i) => i.recordId === completedId), '到期前应可命中');
    await sql(`UPDATE t_record SET completed_at = DATE_SUB(NOW(), INTERVAL 31 DAY) WHERE id=${Number(completedId)};`);
    const matchAfter = await ctx.demo01.post('/api/match', { body: { text: `${fixture.marker} 深紫色钢笔`, type: 'LOST', topN: 20, minScore: 0 } });
    assert(!matchAfter.body.data.items.some((i) => i.recordId === completedId), '到期后应从候选池消失');
    expectCode(await ctx.demo02.get(`/api/records/${completedId}`), 404, 20001, '到期后合格对端详情');
    const rec = await record(completedId);
    eq(rec.status, 'COMPLETED', '到期不改写 status');
    eq(rec.completed_at === 'NULL', false, '到期不改写 completed_at');
  });

  await t.case('TERM-06', 'P0', 'IGNORED pair 不作 COMPLETED 详情通道：非所有者与所有者均 404、t_access_log 不新增；改回 NEW 后 200', async () => {
    // C = COMPLETED（demo03）；A/C = MATCHED .95（demo01 合格对端）；B/C = IGNORED .93（demo02 不合格）
    const marker = `TERM06${rid()}`;
    const c = await createRecordAs(ctx.demo03, { type: 'FOUND', title: `${marker} 招领银色钥匙`, description: `${marker} 捡到一串银色钥匙。`, location: '第二教学楼楼梯口' });
    const a = await createRecordAs(ctx.demo01, { type: 'LOST', title: `${marker} 寻银色钥匙 A`, description: `${marker} 丢了银色钥匙。`, location: '第二教学楼楼梯口' });
    const b = await createRecordAs(ctx.demo02, { type: 'LOST', title: `${marker} 寻银色钥匙 B`, description: `${marker} 丢了银色钥匙。`, location: '第二教学楼楼梯口' });
    for (const r of [c, a, b]) expectCode(r.res, 200, 0, '夹具记录');
    await upsertPair({ lostId: a.id, foundId: c.id, score: 0.95, status: 'MATCHED', lostConfirmedAt: '2026-10-26 10:00:00', foundConfirmedAt: '2026-10-26 10:01:00' });
    await upsertPair({ lostId: b.id, foundId: c.id, score: 0.93, status: 'IGNORED' });
    createdPairs.push({ lostId: a.id, foundId: c.id }, { lostId: b.id, foundId: c.id });
    const ignoredPair = await pairByEnds(b.id, c.id);
    await forceRecordStatus(c.id, 'COMPLETED', '2026-10-26 10:10:00');

    const before = await accessLogCount(Number(ignoredPair.id));
    const logBefore = await pairLog(b.id, c.id);
    expectCode(await ctx.demo02.get(`/api/records/${c.id}`), 404, 20001, 'IGNORED 端看 COMPLETED');
    expectCode(await ctx.demo03.get(`/api/records/${c.id}`), 404, 20001, 'COMPLETED 所有者看详情');
    expectCode(await ctx.demo01.get(`/api/records/${c.id}`), 200, 0, 'MATCHED 合格对端看详情');
    const after = await accessLogCount(Number(ignoredPair.id));
    eq(after, before, 't_access_log 行数');
    eq(after, 0, '详情读取不写审计');

    // 对照：把同 pair 改回 NEW，IGNORED 通道消失 → demo02 变成 score≥0.90 的合格对端
    await sql(`UPDATE t_pair_score SET status='NEW' WHERE id=${Number(ignoredPair.id)};`);
    expectCode(await ctx.demo02.get(`/api/records/${c.id}`), 200, 0, '改回 NEW 后看详情');
    await sql(`UPDATE t_pair_score SET status='IGNORED' WHERE id=${Number(ignoredPair.id)};`);
    const logAfter = await pairLog(b.id, c.id);
    assert(logAfter.available, 'EV-LOG 不可用：未找到后端日志');
    console.log(`\n  [EV-SQL] TERM-06 pairId=${ignoredPair.id} 通道 IGNORED=${ignoredPair.status}；访问审计 before=${before} after=${after}；改 NEW→200，改回 IGNORED→404`);
    console.log(`  [EV-LOG] TERM-06 pairId=${ignoredPair.id} 日志新增 ${logAfter.lines.length - logBefore.lines.length} 行，llmCalls=${llmCallCount(logAfter.lines)}（详情读取不应触发打分/LLM）`);
  });
}

// ---------------------------------------------------------------- 不自动下架

async function lifecycleSuite() {
  await t.case('NOUNPUB-01', 'P0', '「不自动下架」限定 PENDING：打分跑完仍 PENDING、deleted_at 为空、列表可见、updated_at 不变', async () => {
    const marker = `不下架${rid()}`;
    const made = await createRecordAs(ctx.demo01, {
      type: 'LOST', title: `${marker} 图书馆丢了黑色钱包`,
      description: '在图书馆三楼自习区丢了黑色钱包，有银行卡。', location: '图书馆三楼自习区',
    });
    expectCode(made.res, 200, 0, '发布');
    const before = await recordSnapshot(made.id);
    await waitForScoring(made.id);
    await new Promise((r) => setTimeout(r, 800));
    const after = await recordSnapshot(made.id);
    eq(after.status, 'PENDING', 'status');
    eq(after.deletedAt, 'NULL', 'deleted_at');
    eq(after.updatedAt, before.updatedAt, 'updated_at（系统任务不写本人写路径的列）');
    assert(await isListed(ctx.anon, made.id), 'PENDING 记录应仍在公共列表');
  });

  await t.case('PHONE-01', 'P1', '手机号只做格式校验：不落 t_user、接口无短信验证、contact 明文入库仅输出层遮蔽', async () => {
    const made = await createRecordAs(ctx.demo01, { contact: '13711112222' });
    expectCode(made.res, 200, 0, '带手机号发布');
    const rec = await record(made.id);
    eq(rec.contact, '13711112222', 'contact 明文落库');
    const userCols = await columnNames('t_user');
    for (const forbidden of ['phone', 'mobile', 'contact']) {
      assert(!userCols.includes(forbidden), `t_user 不应有 ${forbidden} 列`);
    }
    expectCode(await ctx.anon.post('/api/auth/sms', { body: {} }), 401, 10006, '匿名短信接口（门禁）');
    expectCode(await ctx.demo01.post('/api/auth/sms', { body: {} }), 404, 10003, '无短信验证接口');
    // 非本人读不到 contact；号码只在本人视图与 pair 取号出口出现
    const other = await ctx.demo02.get(`/api/records/${made.id}`);
    eq(other.body.data.contact, null, '非本人 contact');
  });
}

// ---------------------------------------------------------------- 收尾

async function cleanup() {
  let removed = 0;
  for (const item of created) {
    for (let attempt = 0; attempt < 2; attempt += 1) {
      const res = await item.owner.del(`/api/records/${item.id}`);
      if (res.status === 200 || res.status === 404) break;
    }
    removed += 1;
  }
  console.log(`\n  清理：逻辑删除本轮创建的 ${removed} 条记录（种子数据未改动）`);
  const check = await ctx.anon.get('/api/records');
  console.log(`  清理后基线自检：默认列表 total=${check.body?.data?.total}（应为 21）`);
}

// ---------------------------------------------------------------- main

ctx = await contexts();
await preconditions();
await contractSuite();
// 列表基线用例先跑：后面的越权/新增用例会写入临时记录，不能拿「total=21」去断言它们
await listSuite();
await authSuite();
await authzSuite();
await detailSuite();
await createSuite();
await updateSuite();
await statusSuite();
await deleteSuite();
await categorySuite();
await matchSuite();
await maskingSuite();
await tierSuite();
await idempotencySuite();
await degradationSuite();
await pairIndependenceSuite();
await terminalSuite();
await lifecycleSuite();

await cleanup();
const summary = t.print();
const paths = await t.writeReports(reportsDir());
console.log(`\n  报告：${paths.txtPath}`);
console.log(`  报告：${paths.jsonPath}`);
process.exit(summary.failed > 0 ? 1 : 0);
