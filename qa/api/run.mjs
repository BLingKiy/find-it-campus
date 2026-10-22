// 5.1 后端接口测试（ZHYYKFX-10）
//
// 覆盖：健康检查 / 通用响应契约 / 列表 / 详情 / 提交 / 修改 / 状态流转 / 删除 / 分类字典 / 语义匹配。
// 事实源：docs/api-contract.md（断言只写契约里写死的东西，不写实现细节）。
//
// 运行（一条命令，见 docs/testing/5.1-test-report.md §2）：
//   node qa/api/run.mjs                 # 默认 http://localhost:18080
//   FINDIT_API_BASE=http://host:port node qa/api/run.mjs
//
// 前置：后端已启动，且库为 db/schema.sql + db/seed.sql 的初始状态
//（基线断言 total=21 会先自检，不满足时直接给出「请先 reseed」的提示而不是一串莫名失败）。

import { Api, brief, expectCode, fieldReasons, isOk } from '../lib/client.mjs';
import { Tester, assert, eq, reportsDir } from '../lib/tester.mjs';

const api = new Api();
const t = new Tester('api-5.1');

// 本轮创建的记录 id —— 收尾统一逻辑删除，保证可重复执行且不污染种子基线
const created = [];
async function createRecord(overrides = {}) {
  const body = {
    type: 'LOST',
    title: `QA 测试记录 ${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    description: '这是 5.1 接口测试创建的临时记录，用于验证提交、修改、状态流转与删除路径。',
    location: 'QA 测试地点',
    occurredAt: '2026-10-20 10:00:00',
    ...overrides,
  };
  const res = await api.post('/api/records', { body });
  if (isOk(res)) created.push(res.body.data.id);
  return { res, body };
}

const isoDay = (d) => d.toISOString().slice(0, 10);
const apiTime = (d) => {
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
};

// ---------------------------------------------------------------- 前置自检

async function preconditions() {
  await t.case('ENV-01', 'P0', '后端可达且数据库为 seed 初始状态（total=21）', async () => {
    const health = await api.get('/api/health');
    expectCode(health, 200, 0, '健康检查');
    eq(health.body.data.db, 'UP', 'health.data.db');
    const list = await api.get('/api/records');
    expectCode(list, 200, 0, '列表基线');
    if (!list.body.data) {
      throw new Error(`列表响应缺少 data：${brief(list)}`);
    }
    if (list.body.data.total !== 21) {
      throw new Error(
        `基线不符合 db/seed.sql 初始状态：期望 total=21，实际 ${list.body.data.total}。` +
          '请先重置库再跑：mysql -u root < db/schema.sql && mysql -u root find_it_campus < db/seed.sql',
      );
    }
  });
}

// ---------------------------------------------------------------- 通用契约

async function contractSuite() {
  await t.case('API-CON-01', 'P1', '外层响应体恒含 code/message/data/requestId 四字段', async () => {
    const ok = await api.get('/api/records');
    for (const key of ['code', 'message', 'data', 'requestId']) {
      assert(key in ok.body, `成功响应缺少字段 ${key}：${ok.raw.slice(0, 200)}`);
    }
    assert(typeof ok.body.requestId === 'string' && ok.body.requestId.length > 0, 'requestId 必须为非空字符串');

    const bad = await api.get('/api/records/999999');
    for (const key of ['code', 'message', 'data', 'requestId']) {
      assert(key in bad.body, `错误响应缺少字段 ${key}：${bad.raw.slice(0, 200)}`);
    }
    assert(bad.body.requestId && bad.body.requestId !== ok.body.requestId, '每个请求的 requestId 必须不同');
  });

  await t.case('API-CON-02', 'P1', '未知路径返回 404 + 10003，而非框架默认错误页', async () => {
    const res = await api.get('/api/not-exists');
    expectCode(res, 404, 10003, '未知路径');
    assert(!/Whitelabel|timestamp/.test(res.raw), `响应体不应是框架默认错误结构：${res.raw.slice(0, 200)}`);
  });

  await t.case('API-CON-03', 'P1', '动词不支持返回 405 + 10004', async () => {
    const res = await api.del('/api/match');
    expectCode(res, 405, 10004, 'DELETE /api/match');
  });

  await t.case('API-CON-04', 'P2', '请求体不是合法 JSON 时返回 400 + 10001', async () => {
    const res = await api.post('/api/records', { body: '{not-json' });
    expectCode(res, 400, 10001, '非法 JSON 请求体');
  });

  await t.case('API-CON-05', 'P2', '错误响应不含堆栈信息', async () => {
    const res = await api.post('/api/records', { body: '{"type":"LOST"}' });
    expectCode(res, 400, 10001, '缺字段提交');
    assert(!/\bat com\.findit|\bat java\.|Exception:/.test(res.raw), `错误体泄漏了堆栈：${res.raw.slice(0, 300)}`);
  });
}

// ---------------------------------------------------------------- 列表

async function listSuite() {
  await t.case('API-L-01', 'P0', '默认参数：status=PENDING、page=1、pageSize=10、createdAt desc', async () => {
    const res = await api.get('/api/records');
    expectCode(res, 200, 0, '默认列表');
    const d = res.body.data;
    eq(d.page, 1, 'data.page');
    eq(d.pageSize, 10, 'data.pageSize');
    eq(d.total, 21, 'data.total'); // seed 24 条 − CLAIMED 1 − CLOSED 1 − 逻辑删除 1
    eq(d.totalPages, 3, 'data.totalPages');
    eq(d.hasNext, true, 'data.hasNext');
    eq(d.list.length, 10, 'data.list.length');
    eq(d.list[0].id, 26, '首条 id（createdAt 最新）');
    eq(d.list[1].id, 22, '次条 id（同 createdAt 排序下的第二新）');
    assert(
      d.list.every((x) => x.status === 'PENDING'),
      `默认列表应只含 PENDING，实际 ${JSON.stringify(d.list.map((x) => x.status))}`,
    );
  });

  await t.case('API-L-02', 'P0', '列表项字段白名单：不含 description / contact / publisherName', async () => {
    const res = await api.get('/api/records');
    expectCode(res, 200, 0, '列表');
    const expected = [
      'id',
      'type',
      'status',
      'title',
      'categoryId',
      'categoryName',
      'location',
      'occurredAt',
      'createdAt',
    ].sort();
    for (const item of res.body.data.list) {
      const keys = Object.keys(item).sort();
      eq(keys, expected, `记录 ${item.id} 的字段集合`);
    }
  });

  await t.case('API-L-03', 'P0', 'type=FOUND 过滤后全部为 FOUND', async () => {
    const res = await api.get('/api/records?type=FOUND&pageSize=50');
    expectCode(res, 200, 0, 'type=FOUND');
    assert(res.body.data.total > 0, 'type=FOUND 不应为空');
    assert(
      res.body.data.list.every((x) => x.type === 'FOUND'),
      `混入了非 FOUND：${JSON.stringify(res.body.data.list.map((x) => x.type))}`,
    );
  });

  await t.case('API-L-04', 'P1', 'status=ALL 含已认领/已关闭但排除逻辑删除', async () => {
    const res = await api.get('/api/records?status=ALL&pageSize=50');
    expectCode(res, 200, 0, 'status=ALL');
    const d = res.body.data;
    eq(d.total, 23, 'status=ALL total（24 条 − 1 条逻辑删除）');
    const ids = d.list.map((x) => x.id);
    assert(ids.includes(23) && ids.includes(24), 'status=ALL 应包含 23(CLAIMED) 与 24(CLOSED)');
    assert(!ids.includes(25), 'status=ALL 不得包含逻辑删除的 25');
  });

  await t.case('API-L-05', 'P1', 'categoryId 过滤且停用分类返回 40002', async () => {
    const ok = await api.get('/api/records?categoryId=1&pageSize=50');
    expectCode(ok, 200, 0, 'categoryId=1');
    assert(ok.body.data.total > 0, 'categoryId=1 不应为空');
    assert(
      ok.body.data.list.every((x) => x.categoryId === 1),
      `混入了其它分类：${JSON.stringify(ok.body.data.list.map((x) => x.categoryId))}`,
    );

    const inactive = await api.get('/api/records?categoryId=9');
    expectCode(inactive, 409, 40002, '停用分类 9');

    const missing = await api.get('/api/records?categoryId=999999');
    expectCode(missing, 404, 40001, '不存在的分类');
  });

  await t.case('API-L-06', 'P1', 'keyword 命中标题与描述（中文，不区分字段）', async () => {
    const res = await api.get('/api/records?keyword=' + encodeURIComponent('钱包'));
    expectCode(res, 200, 0, 'keyword=钱包');
    const ids = res.body.data.list.map((x) => x.id).sort((a, b) => a - b);
    eq(ids, [1, 13], 'keyword=钱包 命中的记录 id');
  });

  await t.case('API-L-07', 'P1', 'keyword 中的 % 与 _ 按字面量处理，不当通配符', async () => {
    for (const kw of ['%', '_']) {
      const res = await api.get('/api/records?keyword=' + encodeURIComponent(kw));
      expectCode(res, 200, 0, `keyword=${kw}`);
      eq(res.body.data.total, 0, `keyword=${kw} 的 total（转义生效时应为 0）`);
    }
  });

  await t.case('API-L-08', 'P1', '分页无重复且末页 hasNext=false', async () => {
    const p1 = await api.get('/api/records?page=1&pageSize=10&status=ALL');
    const p2 = await api.get('/api/records?page=2&pageSize=10&status=ALL');
    const p3 = await api.get('/api/records?page=3&pageSize=10&status=ALL');
    expectCode(p1, 200, 0, '第 1 页');
    expectCode(p2, 200, 0, '第 2 页');
    expectCode(p3, 200, 0, '第 3 页');
    eq(p1.body.data.total, p3.body.data.total, '三页的 total 必须一致');

    const ids = [...p1.body.data.list, ...p2.body.data.list, ...p3.body.data.list].map((x) => x.id);
    eq(ids.length, new Set(ids).size, '跨页 id 不得重复');
    eq(p3.body.data.list.length, 3, '第 3 页条数（23 条 / 每页 10）');
    eq(p3.body.data.hasNext, false, '末页 hasNext');
  });

  await t.case('API-L-09', 'P1', 'sortBy=occurredAt&asc 按发生时间升序', async () => {
    const res = await api.get('/api/records?sortBy=occurredAt&sortOrder=asc&pageSize=50&status=ALL');
    expectCode(res, 200, 0, 'occurredAt asc');
    const times = res.body.data.list.map((x) => x.occurredAt);
    for (let i = 1; i < times.length; i += 1) {
      assert(times[i - 1] <= times[i], `第 ${i} 位排序错误：${times[i - 1]} 应 <= ${times[i]}`);
    }
  });

  await t.case('API-L-10', 'P1', 'sortBy=createdAt&desc 严格降序且两次调用顺序一致', async () => {
    const asc = await api.get('/api/records?sortBy=createdAt&sortOrder=asc&pageSize=50');
    expectCode(asc, 200, 0, 'createdAt asc');
    const times = asc.body.data.list.map((x) => x.createdAt);
    for (let i = 1; i < times.length; i += 1) {
      assert(times[i - 1] <= times[i], `createdAt 升序错误：${times[i - 1]} 应 <= ${times[i]}`);
    }

    const desc = await api.get('/api/records?sortBy=createdAt&sortOrder=desc&pageSize=50');
    expectCode(desc, 200, 0, 'createdAt desc');
    const descTimes = desc.body.data.list.map((x) => x.createdAt);
    for (let i = 1; i < descTimes.length; i += 1) {
      assert(descTimes[i - 1] >= descTimes[i], `createdAt 降序错误：${descTimes[i - 1]} 应 >= ${descTimes[i]}`);
    }
    eq(desc.body.data.list[0].id, 26, '降序首条（seed 中 createdAt 最新）');

    // 排序稳定性：契约要求「同值追加 id DESC」，可观测的等价证据是同条件两次请求顺序完全一致
    const again = await api.get('/api/records?sortBy=createdAt&sortOrder=desc&pageSize=50');
    eq(
      desc.body.data.list.map((x) => x.id),
      again.body.data.list.map((x) => x.id),
      '两次同条件查询的 id 序列',
    );
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
      const res = await api.get(path);
      expectCode(res, 400, 10001, path);
      const reasons = fieldReasons(res);
      assert(field in reasons, `${path} 的 fieldErrors 应指向 ${field}，实际 ${JSON.stringify(reasons)}`);
    }
  });

  await t.case('API-L-12', 'P3', '页码越界返回空列表而非报错', async () => {
    const res = await api.get('/api/records?page=999');
    expectCode(res, 200, 0, 'page=999');
    eq(res.body.data.list.length, 0, 'list 长度');
    eq(res.body.data.hasNext, false, 'hasNext');
    eq(res.body.data.total, 21, 'total 不因页码越界而变化');
  });
}

// ---------------------------------------------------------------- 详情

async function detailSuite() {
  await t.case('API-D-01', 'P0', '详情返回完整字段与 allowedStatuses', async () => {
    const res = await api.get('/api/records/13');
    expectCode(res, 200, 0, '详情 13');
    const d = res.body.data;
    for (const key of [
      'id',
      'type',
      'status',
      'title',
      'description',
      'categoryId',
      'categoryName',
      'location',
      'occurredAt',
      'contact',
      'publisherName',
      'allowedStatuses',
      'createdAt',
      'updatedAt',
    ]) {
      assert(key in d, `详情缺少字段 ${key}`);
    }
    eq(d.id, 13, 'id');
    eq(d.status, 'PENDING', 'status');
    eq(d.categoryName, '钱包卡证', 'categoryName');
    eq(d.allowedStatuses, ['CLAIMED', 'CLOSED'], 'PENDING 的 allowedStatuses');
    assert(typeof d.description === 'string' && d.description.length > 0, 'description 不应为空');
  });

  await t.case('API-D-02', 'P1', '逻辑删除记录 404 + 20001', async () => {
    const res = await api.get('/api/records/25');
    expectCode(res, 404, 20001, '已删除记录 25');
  });

  await t.case('API-D-03', 'P1', '不存在的 id 404 + 20001', async () => {
    const res = await api.get('/api/records/999999');
    expectCode(res, 404, 20001, '不存在记录');
  });

  await t.case('API-D-04', 'P1', '非数字 id 400 + 10001（/records/new 路由不被 id 抢走）', async () => {
    for (const path of ['/api/records/new', '/api/records/1abc']) {
      const res = await api.get(path);
      expectCode(res, 400, 10001, path);
      assert('id' in fieldReasons(res), `${path} 的 fieldErrors 应指向 id，实际 ${JSON.stringify(fieldReasons(res))}`);
    }
  });

  await t.case('API-D-05', 'P1', 'CLOSED 记录 allowedStatuses 为空、CLAIMED 只可到 CLOSED', async () => {
    const closed = await api.get('/api/records/24');
    expectCode(closed, 200, 0, 'CLOSED 记录');
    eq(closed.body.data.status, 'CLOSED', '24 的 status');
    eq(closed.body.data.allowedStatuses, [], 'CLOSED 的 allowedStatuses');

    const claimed = await api.get('/api/records/23');
    expectCode(claimed, 200, 0, 'CLAIMED 记录');
    eq(claimed.body.data.allowedStatuses, ['CLOSED'], 'CLAIMED 的 allowedStatuses');
  });

  await t.case('API-D-06', 'P2', '无分类记录的 categoryId / categoryName 为 null', async () => {
    const res = await api.get('/api/records/26');
    expectCode(res, 200, 0, '详情 26');
    eq(res.body.data.categoryId, null, 'categoryId');
    eq(res.body.data.categoryName, null, 'categoryName');
  });
}

// ---------------------------------------------------------------- 提交

async function createSuite() {
  await t.case('API-C-01', 'P0', '合法提交返回与详情同构的对象，初始状态 PENDING', async () => {
    const { res } = await createRecord({ contact: '13800009999', publisherName: 'QA' });
    expectCode(res, 200, 0, '提交记录');
    const d = res.body.data;
    assert(typeof d.id === 'number' && d.id > 0, `新记录应有 id，实际 ${JSON.stringify(d.id)}`);
    eq(d.status, 'PENDING', '新记录 status');
    eq(d.allowedStatuses, ['CLAIMED', 'CLOSED'], '新记录 allowedStatuses');
    eq(d.createdAt, d.updatedAt, '新记录 createdAt 与 updatedAt 应相同');
    eq(d.contact, '13800009999', 'contact 回显');

    const detail = await api.get(`/api/records/${d.id}`);
    expectCode(detail, 200, 0, '提交后取详情');
    eq(detail.body.data.title, d.title, '提交后详情标题');
    eq(detail.body.data.description, d.description, '提交后详情描述');
  });

  await t.case('API-C-02', 'P1', '可选字段缺省时落为 null', async () => {
    const { res } = await createRecord();
    expectCode(res, 200, 0, '最小合法提交');
    eq(res.body.data.categoryId, null, 'categoryId');
    eq(res.body.data.contact, null, 'contact');
    eq(res.body.data.publisherName, null, 'publisherName');
  });

  await t.case('API-C-03', 'P1', '分类不存在 404/40001，分类停用 409/40002', async () => {
    const missing = await createRecord({ categoryId: 999999 });
    expectCode(missing.res, 404, 40001, '分类不存在');
    const inactive = await createRecord({ categoryId: 9 });
    expectCode(inactive.res, 409, 40002, '分类停用');
  });

  await t.case('API-C-04', 'P1', '标题长度边界：1 字与 65 字均 10001', async () => {
    const short = await createRecord({ title: '丢' });
    expectCode(short.res, 400, 10001, '标题 1 字');
    assert('title' in fieldReasons(short.res), 'fieldErrors 应指向 title');

    const long = await createRecord({ title: '长'.repeat(65) });
    expectCode(long.res, 400, 10001, '标题 65 字');
    assert('title' in fieldReasons(long.res), 'fieldErrors 应指向 title');
  });

  await t.case('API-C-05', 'P1', '描述长度边界：1 字拒绝、2000 字通过', async () => {
    const short = await createRecord({ description: '短' });
    expectCode(short.res, 400, 10001, '描述 1 字');
    assert('description' in fieldReasons(short.res), 'fieldErrors 应指向 description');

    const edge = await createRecord({ description: '描'.repeat(2000) });
    expectCode(edge.res, 200, 0, '描述 2000 字（上限内）');
    eq(edge.res.body.data.description.length, 2000, '落库描述长度');

    const tooLong = await createRecord({ description: '描'.repeat(2001) });
    expectCode(tooLong.res, 400, 10001, '描述 2001 字');
  });

  await t.case('API-C-06', 'P1', '地点 129 字拒绝、1 字通过', async () => {
    const tooLong = await createRecord({ location: '地'.repeat(129) });
    expectCode(tooLong.res, 400, 10001, '地点 129 字');
    const edge = await createRecord({ location: '楼' });
    expectCode(edge.res, 200, 0, '地点 1 字');
  });

  await t.case('API-C-07', 'P1', 'occurredAt 未来 10 分钟拒绝、3 分钟放行（容差 5 分钟）', async () => {
    const future10 = new Date(Date.now() + 10 * 60 * 1000);
    const r1 = await createRecord({ occurredAt: apiTime(future10) });
    expectCode(r1.res, 400, 10001, '未来 10 分钟');
    assert('occurredAt' in fieldReasons(r1.res), 'fieldErrors 应指向 occurredAt');

    const future3 = new Date(Date.now() + 3 * 60 * 1000);
    const r2 = await createRecord({ occurredAt: apiTime(future3) });
    expectCode(r2.res, 200, 0, '未来 3 分钟（容差内）');
  });

  // 已登记缺陷 DEF-01：occurredAt 只按正则校验格式，日历上不存在的值（13 月/40 日）漏到
  // LocalDateTime.parse 才炸，返回 500/10005；契约 §5.3 要求 10001。判定维持失败，标 KNOWN_FAIL。
  await t.case(
    'API-C-08',
    'P1',
    'occurredAt 格式严格（分隔符、时间必须合法）',
    async () => {
      for (const value of ['2026/10/20 10:00:00', '2026-10-20T10:00:00', '2026-13-40 25:61:61', '20261020 100000']) {
        const { res } = await createRecord({ occurredAt: value });
        expectCode(res, 400, 10001, `occurredAt=${value}`);
      }
    },
    { knownDefect: 'DEF-01' },
  );

  await t.case('API-C-09', 'P1', 'type 非法 10001，必填项缺失一并返回 fieldErrors', async () => {
    const bad = await createRecord({ type: 'SELL' });
    expectCode(bad.res, 400, 10001, 'type=SELL');
    assert('type' in fieldReasons(bad.res), 'fieldErrors 应指向 type');

    const empty = await api.post('/api/records', { body: { type: 'LOST', title: '只有标题' } });
    expectCode(empty, 400, 10001, '缺 description/location/occurredAt');
    const reasons = fieldReasons(empty);
    for (const f of ['description', 'location', 'occurredAt']) {
      assert(f in reasons, `fieldErrors 应包含 ${f}，实际 ${JSON.stringify(reasons)}`);
    }
  });

  await t.case('API-C-10', 'P1', 'contact 只允许数字/字母/中文/-_()，空格与符号被拒', async () => {
    for (const value of ['138 0000 0001', 'abc<script>', 'a\nb', 'tel:+86']) {
      const { res } = await createRecord({ contact: value });
      expectCode(res, 400, 10001, `contact=${JSON.stringify(value)}`);
      assert('contact' in fieldReasons(res), `contact=${JSON.stringify(value)} 的 fieldErrors`);
    }
    const ok = await createRecord({ contact: 'QQ_123-456(工作)' });
    expectCode(ok.res, 200, 0, 'contact 合法字符集');
  });

  await t.case('API-C-11', 'P2', '中文与 emoji 往返无损（UTF-8 全链路）', async () => {
    const title = '捡到一只🐱猫（蓝眼，很乖）';
    const description = '在教学楼③号门附近捡到一只蓝眼睛的小猫，脖子上有铃铛🔔，已暂养。';
    const { res } = await createRecord({ title, description, publisherName: '同学🙂' });
    expectCode(res, 200, 0, 'emoji 提交');
    eq(res.body.data.title, title, '标题往返');
    eq(res.body.data.description, description, '描述往返');
  });

  await t.case('API-C-12', 'P2', '纯空格标题被 strip 后判空', async () => {
    const { res } = await createRecord({ title: '   ' });
    expectCode(res, 400, 10001, '纯空格标题');
    assert('title' in fieldReasons(res), 'fieldErrors 应指向 title');
  });

  await t.case('API-C-13', 'P3', '重复提交不幂等（契约 §5.2 已知行为，产生两条记录）', async () => {
    const payload = {
      type: 'LOST',
      title: `QA 重复提交 ${Date.now()}`,
      description: '验证契约 §5.2 的已知行为：本接口不去重，重复提交产生两条记录。',
      location: 'QA 测试地点',
      occurredAt: '2026-10-20 09:00:00',
    };
    const a = await api.post('/api/records', { body: payload });
    const b = await api.post('/api/records', { body: payload });
    expectCode(a, 200, 0, '第一次提交');
    expectCode(b, 200, 0, '第二次提交');
    if (isOk(a)) created.push(a.body.data.id);
    if (isOk(b)) created.push(b.body.data.id);
    assert(a.body.data.id !== b.body.data.id, '契约声明非幂等：两次提交应得到不同 id');
  });
}

// ---------------------------------------------------------------- 修改

async function updateSuite() {
  await t.case('API-U-01', 'P0', '全量更新成功且不改 status', async () => {
    const { res: createdRes } = await createRecord();
    expectCode(createdRes, 200, 0, '准备数据');
    const id = createdRes.body.data.id;
    const before = createdRes.body.data;

    const res = await api.put(`/api/records/${id}`, {
      body: {
        type: 'FOUND',
        title: 'QA 修改后的标题',
        description: '修改后的描述内容，用于验证全量更新语义。',
        location: 'QA 修改后的地点',
        occurredAt: '2026-10-19 08:30:00',
        contact: '123456',
        publisherName: '修改人',
      },
    });
    expectCode(res, 200, 0, '全量更新');
    const after = res.body.data;
    eq(after.title, 'QA 修改后的标题', 'title');
    eq(after.type, 'FOUND', 'type');
    eq(after.description, '修改后的描述内容，用于验证全量更新语义。', 'description');
    eq(after.status, before.status, 'status 不应被 PUT 改动');
    eq(after.contact, '123456', 'contact');
    assert(after.updatedAt >= before.updatedAt, `updatedAt 不应回退：${before.updatedAt} → ${after.updatedAt}`);
  });

  await t.case('API-U-02', 'P1', '未传的可选字段按全量语义清空为 null', async () => {
    const { res: createdRes } = await createRecord({ contact: '13800001111', publisherName: '原联系人' });
    const id = createdRes.body.data.id;
    const res = await api.put(`/api/records/${id}`, {
      body: {
        type: 'LOST',
        title: 'QA 清空可选字段',
        description: '本次请求故意不带 contact 与 publisherName，验证全量更新语义。',
        location: 'QA 地点',
        occurredAt: '2026-10-18 08:00:00',
      },
    });
    expectCode(res, 200, 0, '全量更新（省略可选字段）');
    eq(res.body.data.contact, null, 'contact 应被清空');
    eq(res.body.data.publisherName, null, 'publisherName 应被清空');
  });

  await t.case('API-U-03', 'P1', '已关闭记录不可修改 409 + 20003', async () => {
    const res = await api.put('/api/records/24', {
      body: {
        type: 'LOST',
        title: '试图修改已关闭记录',
        description: '已关闭记录的内容不允许再修改，期望 409 20003。',
        location: 'QA',
        occurredAt: '2026-10-18 08:00:00',
      },
    });
    expectCode(res, 409, 20003, 'PUT 已关闭记录');
  });

  await t.case('API-U-04', 'P1', 'PUT 不存在的记录 404 + 20001', async () => {
    const res = await api.put('/api/records/999999', {
      body: {
        type: 'LOST',
        title: '不存在的记录',
        description: '用于校验 20001 的请求体。',
        location: 'QA',
        occurredAt: '2026-10-18 08:00:00',
      },
    });
    expectCode(res, 404, 20001, 'PUT 不存在记录');
  });

  await t.case('API-U-05', 'P1', 'PUT 缺 type 或字段非法时 10001', async () => {
    const { res: createdRes } = await createRecord();
    const id = createdRes.body.data.id;
    const missingType = await api.put(`/api/records/${id}`, {
      body: {
        title: '缺 type',
        description: '契约 §6 声明 PUT 请求体与新增完全同构，type 必填。',
        location: 'QA',
        occurredAt: '2026-10-18 08:00:00',
      },
    });
    expectCode(missingType, 400, 10001, 'PUT 缺 type');
    assert('type' in fieldReasons(missingType), 'fieldErrors 应指向 type');

    const badTitle = await api.put(`/api/records/${id}`, {
      body: {
        type: 'LOST',
        title: 'x',
        description: '标题只给一个字，应被拒绝。',
        location: 'QA',
        occurredAt: '2026-10-18 08:00:00',
      },
    });
    expectCode(badTitle, 400, 10001, 'PUT 标题过短');
    assert('title' in fieldReasons(badTitle), 'fieldErrors 应指向 title');
  });
}

// ---------------------------------------------------------------- 状态流转

async function statusSuite() {
  await t.case('API-S-01', 'P0', 'PENDING → CLAIMED → CLOSED 全链路，allowedStatuses 同步收缩', async () => {
    const { res: createdRes } = await createRecord();
    const id = createdRes.body.data.id;

    const claimed = await api.patch(`/api/records/${id}/status`, { body: { status: 'CLAIMED' } });
    expectCode(claimed, 200, 0, 'PENDING→CLAIMED');
    eq(claimed.body.data.status, 'CLAIMED', '流转后 status');
    eq(claimed.body.data.allowedStatuses, ['CLOSED'], 'CLAIMED 的 allowedStatuses');

    const detail = await api.get(`/api/records/${id}`);
    eq(detail.body.data.status, 'CLAIMED', '详情中的 status');

    const closed = await api.patch(`/api/records/${id}/status`, { body: { status: 'CLOSED' } });
    expectCode(closed, 200, 0, 'CLAIMED→CLOSED');
    eq(closed.body.data.status, 'CLOSED', '终态 status');
    eq(closed.body.data.allowedStatuses, [], '终态 allowedStatuses');
  });

  await t.case('API-S-02', 'P1', '终态不可再流转 409 + 20002，且带 current / allowed', async () => {
    const res = await api.patch('/api/records/24/status', { body: { status: 'CLAIMED' } });
    expectCode(res, 409, 20002, 'CLOSED→CLAIMED');
    eq(res.body.data.current, 'CLOSED', 'data.current');
    eq(res.body.data.allowed, ['(无)'], 'data.allowed');
  });

  await t.case('API-S-03', 'P1', '流转到当前相同状态 409 + 20002', async () => {
    const res = await api.patch('/api/records/13/status', { body: { status: 'PENDING' } });
    expectCode(res, 409, 20002, 'PENDING→PENDING');
    eq(res.body.data.current, 'PENDING', 'data.current');
  });

  await t.case('API-S-04', 'P1', '目标状态非法 400 + 10001（field=status）', async () => {
    const res = await api.patch('/api/records/13/status', { body: { status: 'DONE' } });
    expectCode(res, 400, 10001, '非法目标状态');
    assert('status' in fieldReasons(res), 'fieldErrors 应指向 status');
  });

  await t.case('API-S-05', 'P1', '对不存在的记录流转 404 + 20001', async () => {
    const res = await api.patch('/api/records/999999/status', { body: { status: 'CLAIMED' } });
    expectCode(res, 404, 20001, '不存在记录的状态流转');
  });
}

// ---------------------------------------------------------------- 删除

async function deleteSuite() {
  await t.case('API-X-01', 'P0', '逻辑删除后详情 404、列表不可见、匹配候选池不含', async () => {
    const title = `QA 待删除记录 ${Date.now()}`;
    const { res: createdRes } = await createRecord({ title, description: '这条记录将被删除，用于验证三条读取路径都过滤逻辑删除。' });
    const id = createdRes.body.data.id;

    const del = await api.del(`/api/records/${id}`);
    expectCode(del, 200, 0, '删除');
    eq(del.body.data, null, '删除响应的 data');
    eq(del.body.message, '已删除', '删除响应的 message');

    const detail = await api.get(`/api/records/${id}`);
    expectCode(detail, 404, 20001, '删除后详情');

    const list = await api.get('/api/records?keyword=' + encodeURIComponent(title) + '&status=ALL&pageSize=50');
    expectCode(list, 200, 0, '删除后按标题检索');
    eq(list.body.data.total, 0, '删除后列表不可见');

    const match = await api.post('/api/match', { body: { text: title, topN: 20, minScore: 0 } });
    expectCode(match, 200, 0, '删除后匹配');
    assert(
      !match.body.data.items.some((i) => i.recordId === id),
      `匹配候选池不应包含已删除记录 ${id}，实际 ${JSON.stringify(match.body.data.items.map((i) => i.recordId))}`,
    );
  });

  await t.case('API-X-02', 'P1', '重复删除 404 + 20001', async () => {
    const { res: createdRes } = await createRecord();
    const id = createdRes.body.data.id;
    const first = await api.del(`/api/records/${id}`);
    expectCode(first, 200, 0, '首次删除');
    const second = await api.del(`/api/records/${id}`);
    expectCode(second, 404, 20001, '重复删除');
  });

  await t.case('API-X-03', 'P1', '删除不存在的记录 404 + 20001', async () => {
    const res = await api.del('/api/records/999999');
    expectCode(res, 404, 20001, '删除不存在记录');
  });
}

// ---------------------------------------------------------------- 分类字典

async function categorySuite() {
  await t.case('API-CAT-01', 'P0', '默认只返启用分类，按 sortOrder 升序，字段与契约一致', async () => {
    const res = await api.get('/api/categories');
    expectCode(res, 200, 0, '分类字典');
    const list = res.body.data;
    eq(list.length, 8, '启用分类数量');
    const orders = list.map((c) => c.sortOrder);
    for (let i = 1; i < orders.length; i += 1) {
      assert(orders[i - 1] <= orders[i], `sortOrder 未升序：${orders[i - 1]} > ${orders[i]}`);
    }
    for (const c of list) {
      eq(Object.keys(c).sort(), ['active', 'code', 'id', 'name', 'sortOrder'], `分类 ${c.id} 的字段集合`);
      eq(c.active, true, `分类 ${c.id} 的 active`);
      assert(!('keywords' in c), '分类字典不得返回 keywords（内部资料）');
    }
  });

  await t.case('API-CAT-02', 'P1', 'onlyActive=false 含停用分类', async () => {
    const res = await api.get('/api/categories?onlyActive=false');
    expectCode(res, 200, 0, '含停用分类');
    eq(res.body.data.length, 9, '全部分类数量');
    const inactive = res.body.data.filter((c) => !c.active).map((c) => c.code);
    eq(inactive, ['SPORTS'], '停用分类 code');
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
      const res = await api.post('/api/match', {
        body: { text: s.text, type: s.type, topN: 5, minScore: 0.3 },
      });
      expectCode(res, 200, 0, `${s.id} 匹配`);
      const items = res.body.data.items;
      assert(items.length > 0, `${s.id} 应有命中结果，实际为空`);
      eq(items[0].recordId, s.top1, `${s.id} 的 top1`);
    }
  });

  await t.case('API-M-02', 'P0', '结果结构与排序：倒序、去重、score 口径、total=items.length', async () => {
    const res = await api.post('/api/match', {
      body: { text: SAMPLE[0].text, type: 'FOUND', topN: 20, minScore: 0.3 },
    });
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
      eq(item.status, 'PENDING', `record ${item.recordId} 的 status（只应返回待领）`);
    }
  });

  await t.case('API-M-03', 'P1', 'excludeRecordId 生效（详情页发起匹配时排除自身）', async () => {
    const res = await api.post('/api/match', {
      body: { text: SAMPLE[0].text, type: 'FOUND', topN: 20, minScore: 0.3, excludeRecordId: 13 },
    });
    expectCode(res, 200, 0, 'S1 + exclude 13');
    assert(!res.body.data.items.some((i) => i.recordId === 13), '结果仍包含被排除的 13');
  });

  await t.case('API-M-04', 'P1', 'N1 计算器问句命中 7（拾到者找失物帖 ⇒ type=LOST）', async () => {
    const res = await api.post('/api/match', {
      body: { text: '捡到过一个计算器吗？灰色的卡西欧', type: 'LOST', topN: 5, minScore: 0.3 },
    });
    expectCode(res, 200, 0, 'N1 匹配');
    assert(res.body.data.items.length >= 1, 'N1 应有命中');
    eq(res.body.data.items[0].recordId, 7, 'N1 的 top1');
  });

  await t.case('API-M-05', 'P1', 'N2 负例返回 200 + 空结果（不是错误、不是高分误报）', async () => {
    const res = await api.post('/api/match', {
      body: { text: '有人在体育馆捡到泳镜吗', type: 'LOST', topN: 5, minScore: 0.3 },
    });
    expectCode(res, 200, 0, 'N2 匹配');
    eq(res.body.data.total, 0, 'N2 total');
    eq(res.body.data.items, [], 'N2 items');
    assert(typeof res.body.data.matchId === 'number', '空结果也应写快照（matchId 非空）');
  });

  await t.case('API-M-06', 'P1', 'N4 不返回已认领(23)与已删除(25)', async () => {
    const res = await api.post('/api/match', { body: { text: '捡到一串钥匙', topN: 20, minScore: 0.3 } });
    expectCode(res, 200, 0, 'N4 匹配');
    const ids = res.body.data.items.map((i) => i.recordId);
    assert(ids.includes(18), `N4 应命中 18，实际 ${JSON.stringify(ids)}`);
    assert(!ids.includes(23), 'N4 不得返回已认领的 23');
    assert(!ids.includes(25), 'N4 不得返回已删除的 25');
  });

  await t.case('API-M-07', 'P1', 'N3 同时命中 4 与 16（雨伞语义两侧都能召回）', async () => {
    const res = await api.post('/api/match', { body: { text: '丢了一把雨伞', topN: 20, minScore: 0.3 } });
    expectCode(res, 200, 0, 'N3 匹配');
    const ids = res.body.data.items.map((i) => i.recordId);
    assert(ids.includes(4) && ids.includes(16), `N3 应同时命中 4/16，实际 ${JSON.stringify(ids)}`);
  });

  await t.case('API-M-08', 'P1', '同一输入两次调用结果完全一致（本地实现必须确定）', async () => {
    const body = { text: SAMPLE[2].text, type: 'FOUND', topN: 10, minScore: 0.2 };
    const a = await api.post('/api/match', { body });
    const b = await api.post('/api/match', { body });
    expectCode(a, 200, 0, '第一次');
    expectCode(b, 200, 0, '第二次');
    eq(
      a.body.data.items.map((i) => [i.recordId, i.score]),
      b.body.data.items.map((i) => [i.recordId, i.score]),
      '两次调用的 (recordId, score) 序列',
    );
    assert(a.body.data.matchId !== b.body.data.matchId, '两次调用应各自产生一条快照');
  });

  await t.case('API-M-09', 'P1', 'topN / minScore / type / categoryId 约束生效', async () => {
    const one = await api.post('/api/match', {
      body: { text: SAMPLE[0].text, type: 'FOUND', topN: 1, minScore: 0.1 },
    });
    expectCode(one, 200, 0, 'topN=1');
    eq(one.body.data.items.length, 1, 'topN=1 的条数');

    const high = await api.post('/api/match', { body: { text: '捡到一串钥匙', topN: 20, minScore: 0.95 } });
    expectCode(high, 200, 0, 'minScore=0.95');
    assert(high.body.data.items.every((i) => i.score >= 0.95), 'minScore 未生效');

    const typed = await api.post('/api/match', { body: { text: '捡到一串钥匙', type: 'LOST', topN: 20, minScore: 0 } });
    expectCode(typed, 200, 0, 'type=LOST');
    assert(typed.body.data.items.every((i) => i.type === 'LOST'), 'type 过滤未生效');

    const cat = await api.post('/api/match', {
      body: { text: SAMPLE[0].text, type: 'FOUND', categoryId: 1, topN: 20, minScore: 0 },
    });
    expectCode(cat, 200, 0, 'categoryId=1');
    assert(cat.body.data.items.every((i) => i.categoryName === '钱包卡证'), 'categoryId 过滤未生效');
  });

  await t.case('API-M-10', 'P1', 'text 非法统一回 400 + 30001（空/1 字/超长）', async () => {
    for (const text of ['', '   ', 'a', 'x'.repeat(2001)]) {
      const res = await api.post('/api/match', { body: { text } });
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
      const res = await api.post('/api/match', { body });
      expectCode(res, status, code, JSON.stringify(body));
      assert(field in fieldReasons(res), `fieldErrors 应指向 ${field}，实际 ${JSON.stringify(fieldReasons(res))}`);
    }
    const missingCat = await api.post('/api/match', { body: { text: '捡到钥匙', categoryId: 999999 } });
    expectCode(missingCat, 404, 40001, 'categoryId 不存在');
  });

  await t.case('API-M-12', 'P2', '并发 10 次匹配全部 200 且快照 id 唯一', async () => {
    const results = await Promise.all(
      Array.from({ length: 10 }, () =>
        api.post('/api/match', { body: { text: '图书馆 钱包 银行卡', type: 'FOUND', topN: 5, minScore: 0.2 } }),
      ),
    );
    for (const res of results) expectCode(res, 200, 0, '并发匹配');
    const ids = results.map((r) => r.body.data.matchId);
    eq(ids.length, new Set(ids).size, '并发调用的 matchId 必须各不相同');
  });
}

// ---------------------------------------------------------------- 收尾

async function cleanup() {
  let removed = 0;
  for (const id of created) {
    for (let attempt = 0; attempt < 2; attempt += 1) {
      const res = await api.del(`/api/records/${id}`);
      if (res.status === 200 || res.status === 404) break;
    }
    removed += 1;
  }
  console.log(`\n  清理：逻辑删除本轮创建的 ${removed} 条记录（种子数据未改动）`);
  const check = await api.get('/api/records');
  console.log(`  清理后基线自检：默认列表 total=${check.body?.data?.total}（应为 21）`);
}

// ---------------------------------------------------------------- main

const only = process.argv.includes('--list-only') ? 'list' : null;

await preconditions();
if (!only) {
  await contractSuite();
  await listSuite();
  await detailSuite();
  await createSuite();
  await updateSuite();
  await statusSuite();
  await deleteSuite();
  await categorySuite();
  await matchSuite();
}

await cleanup();
const summary = t.print();
const paths = await t.writeReports(reportsDir());
console.log(`\n  报告：${paths.txtPath}`);
console.log(`  报告：${paths.jsonPath}`);
process.exit(summary.failed > 0 ? 1 : 0);
