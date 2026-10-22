# API 契约（1.2 交付物 2）

> 适用范围：`server` 全部 REST 接口 + `web` 请求层。3.1 后端与 4.1 前端**并行开发时以本文为准**，出现分歧以本文胜出，改动须先改本文再改代码。
> 记法：`必填` = 缺字段即 `10001`；`可空` = 允许 null；字符串长度上限与 `db/schema.sql` 列长度**必须一致**（改了 DDL 就要改这里）。

---

## 1. 通用约定

| 项 | 基线 | 备注 |
|---|---|---|
| Base path | `/api` | 不带版本段。前端开发期走 Vite 代理，故 `VITE_API_BASE_URL` 默认 `/api` |
| 编码 | UTF-8，`Content-Type: application/json` | 请求体与响应体皆 JSON |
| 字段命名 | camelCase | DB 列 snake_case，由 `mapUnderscoreToCamelCase` 桥接 |
| 时间格式 | `yyyy-MM-dd HH:mm:ss`（本地时间，无时区、无毫秒） | 出入参同一格式；与 `DATETIME` 精度对齐，避开 JS 时区偏移 |
| 布尔 | `true`/`false` | 不用 0/1（DB 里 `is_active` 是 TINYINT，出参转 boolean） |
| 外层响应体 | `{code, message, data, requestId}` | 所有接口（含错误、含 404/405）都返回它，见架构基线 §6.1 |
| 成功判定 | HTTP 2xx **且** `code === 0` | 前端拦截器唯一判据；`code!==0` 抛 `BizError{code,message,fieldErrors,requestId}` |
| 分页请求 | `page`(默认 1，≥1) · `pageSize`(默认 10，1–50) | **不用** offset/limit/pageNum |
| 分页响应 | `page · pageSize · total · totalPages · list · hasNext` | `total`=0 时 `totalPages`=0、`list`=[]、`hasNext`=false |
| 排序 | `sortBy ∈ {createdAt, occurredAt}`（默认 `createdAt`）· `sortOrder ∈ {asc, desc}`（默认 `desc`） | service 白名单映射到列名，非法值 → `10001`；同值追加 `id DESC` 保证稳定 |
| 空结果 | `200` + 空数组 | 不是 404，不是错误码 |
| 枚举值 | `type ∈ {LOST, FOUND}`；`status ∈ {PENDING, CLAIMED, CLOSED}` | 后端只回枚举原值，**中文文案由前端映射**（`RecordStatusTag`/`RecordTypeTag` 一处集中）。不返回 `typeLabel` 之类的后端文案：措辞改动不该要求后端发版，且返回两份同源信息会造成不一致 |
| id 类型 | JSON number（BIGINT 自增，当前量级远小于 2^53） | 若将来改雪花 id 则需改 string，属不可逆项，现在不做 |
| 鉴权 | 无 | 项目范围明确不做权限体系；因此**所有写接口对任何访客开放**，风险见架构基线 §10 |

### 1.1 错误响应形状（前端可依赖，不会变）

参数校验失败（其余错误 `data` 为 `null` 或指定对象）：

```json
{
  "code": 10001,
  "message": "请求参数校验失败",
  "data": {
    "fieldErrors": [
      { "field": "title", "reason": "不得为空" },
      { "field": "description", "reason": "长度需在 2 到 2000 之间" }
    ]
  },
  "requestId": "c0ffee20261021"
}
```

HTTP 状态码 = 该 code 的 `httpStatus`（见架构基线 §6.2 表）。未预期异常恒为 `HTTP 500` + `code 10005`，**响应体绝不含堆栈信息**。

---

## 2. `GET /api/health` — 健康检查

供 2.1 骨架冒烟与 5.1 前置检查。

| 请求 | 无参数 |
|---|---|
| 成功 | `HTTP 200` · `{"code":0,"message":"ok","data":{"status":"UP","db":"UP","version":"0.0.1-SNAPSHOT"},"requestId":"…"}` |
| DB 不可用 | `HTTP 503` · `code 10005`，`data.db = "DOWN"`（**必须明确失败，不许 hang**） |

---

## 3. `GET /api/records` — 列表

### 3.1 Query 参数

| 参数 | 类型 | 必填 | 默认 | 校验 | 含义 |
|---|---|---|---|---|---|
| `type` | string | 否 | 全部 | `LOST`\|`FOUND`，其余 → 10001 | 启事类型筛选 |
| `status` | string | 否 | `PENDING` | `PENDING`\|`CLAIMED`\|`CLOSED` | **默认只看待领**；传 `ALL` 表示不限状态 |
| `categoryId` | number | 否 | 全部 | 存在且 `is_active=1`；不存在 → 40001 | 分类筛选 |
| `keyword` | string | 否 | 无 | 1–64 字，`%`/`_`/`\` 需转义后参与 LIKE | 标题+描述模糊检索 |
| `page` | number | 否 | 1 | ≥1 | 页码 |
| `pageSize` | number | 否 | 10 | 1–50 | 每页条数 |
| `sortBy` | string | 否 | `createdAt` | 白名单 | 排序字段 |
| `sortOrder` | string | 否 | `desc` | `asc`\|`desc` | 排序方向 |

> `status` 默认 `PENDING` 是我定的基线（理由：演示时"已领/关闭"混在首页会削弱可用性叙事）。**若 1.1 要求默认展示全部，改这一行即可，前端不受影响** → Q-05。

### 3.2 响应 `data`

```json
{
  "code": 0, "message": "ok", "requestId": "…",
  "data": {
    "page": 1, "pageSize": 10, "total": 21, "totalPages": 3, "hasNext": true,
    "list": [
      {
        "id": 26,
        "type": "FOUND",
        "status": "PENDING",
        "title": "捡到一张 unknown 的卡片",
        "categoryId": null,
        "categoryName": null,
        "location": "大学生活动中心公告栏",
        "occurredAt": "2026-10-21 13:30:00",
        "createdAt": "2026-10-21 13:50:00"
      },
      {
        "id": 15,
        "type": "FOUND",
        "status": "PENDING",
        "title": "教室捡到卡套内有学生证",
        "categoryId": 4,
        "categoryName": "证件资料",
        "location": "第三教学楼201",
        "occurredAt": "2026-10-21 12:10:00",
        "createdAt": "2026-10-21 12:30:00"
      }
    ]
  }
}
```

> 示例基于 `db/seed.sql`（共 24 行记录）：`status` 默认 `PENDING` ⇒ 排除 23(CLAIMED)、24(CLOSED)、25(已逻辑删除)，故 `total=21`、`totalPages=3`；排序 `createdAt DESC, id DESC` ⇒ 26 在最前。拿它做冒烟对照，数字对不上就是实现写歪了。

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `id` | number | 否 | 详情跳转用 |
| `type` / `status` | string | 否 | 枚举原值，前端自己出文案 |
| `title` | string | 否 | ≤64 |
| `categoryId` | number | **是** | 无分类时 null |
| `categoryName` | string | **是** | 无分类时 null；已停用分类的名称照常返回 |
| `location` | string | 否 | ≤128 |
| `occurredAt` / `createdAt` | string | 否 | §1 时间格式 |

**列表刻意不返回**：`description`（省流量 + 卡片不展示）、`contact`、`publisherName`（最小暴露，只在详情给）。若前端需要 `description` 摘要，由**后端加 `descriptionSummary`（截断 80 字）**而不是放开全文 → 需人类确认才加（Q-12）。

### 3.3 错误

`10001`（分页/枚举/排序非法）、`40001`（categoryId 不存在）。查询本身不因结果为空而报错。

---

## 4. `GET /api/records/{id}` — 详情

| 请求 | path `id` number 必填 |
|---|---|
| 成功 | `HTTP 200`，`data` 为完整对象 |
| `id` 非数字（如 `/api/records/new`） | `HTTP 400` · `code 10001` · `fieldErrors:[{field:"id",reason:"必须为数字"}]` |
| 不存在 / 已逻辑删除 | `HTTP 404` · `code 20001` · `message "记录不存在或已删除"` |

```json
{
  "code": 0, "message": "ok", "requestId": "…",
  "data": {
    "id": 1, "type": "LOST", "status": "PENDING",
    "title": "图书馆丢了黑色钱包",
    "description": "昨天下午在图书馆三楼…",
    "categoryId": 1, "categoryName": "钱包卡证",
    "location": "图书馆三楼自习区",
    "occurredAt": "2026-10-19 17:30:00",
    "contact": "13800000001",
    "publisherName": "李同学",
    "allowedStatuses": ["CLAIMED", "CLOSED"],
    "createdAt": "2026-10-20 09:10:00",
    "updatedAt": "2026-10-20 09:10:00"
  }
}
```

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `description` | string | 否 | 全文 |
| `contact` | string | **是** | 未填/待脱敏时 null（Q-01） |
| `publisherName` | string | **是** | 联系人称呼 |
| `allowedStatuses` | string[] | 否 | **当前状态下可迁往的状态**（由后端 `RecordStatusPolicy` 给出，前端据此渲染按钮）。空数组 ⇒ 不显示状态操作按钮 |

> `allowedStatuses` 是"状态机放后端、前端不自己推"的落点（否则流转规则会散在两处，改一处忘一处）。它让 4.1 的详情页不需要任何流转知识。

---

## 5. `POST /api/records` — 提交（增）

### 5.1 请求体

| 字段 | 类型 | 必填 | 校验 | 说明 |
|---|---|---|---|---|
| `type` | string | **是** | `LOST`\|`FOUND` | 页面用单选，值来自前端 |
| `title` | string | **是** | 2–64 字，去首尾空格后判长 | |
| `description` | string | **是** | 2–2000 字 | 匹配主文本 |
| `categoryId` | number | 否 | 存在且 `is_active=1`，否则 40001/40002 | null=未分类 |
| `location` | string | **是** | 1–128 字 | |
| `occurredAt` | string | **是** | 格式严格；不得晚于 `now + 5min` | 业务时间；允许略超当前以容忍时钟偏差（Q-08 待确认是否允许"未来时间"） |
| `contact` | string | 否（**待 Q-01 拍板**） | 1–64 字，只允许数字/字母/中文/`-_()`，禁换行 | 手机/QQ/微信号，自由格式（无登录故不校验真实性） |
| `publisherName` | string | 否 | 1–32 字 | |

请求示例（寻物）：

```json
{
  "type": "LOST",
  "title": "丢了一个灰色背包",
  "description": "今天下午在第一教学楼 305 的座位上落下了一个灰色的双肩背包，侧面有个水杯袋，里面有一本线性代数课本。",
  "categoryId": 5,
  "location": "第一教学楼305",
  "occurredAt": "2026-10-21 15:20:00",
  "contact": "13800000012",
  "publisherName": "张同学"
}
```

### 5.2 响应

`HTTP 200`，`data` = 与详情接口同构的完整对象（含 `id`、`allowedStatuses`）。

> 为什么不用 `201 + Location`：前端提交成功后需要拿到 `id` 跳详情，两种写法信息等价，但 201 要前端额外解析 header；两者都能工作，故取实现最不容易出错的一种。若人类偏好 REST 正统，改一处注解即可（可逆）。

**幂等提醒（写给 3.1）**：本接口非幂等，重复提交产生两条记录。基线只做前端按钮 pending 禁用，后端不加去重（业务上重复描述本就合法）。更强保证需 `clientRequestId` + 唯一索引 ⇒ Q-11 待拍板。

### 5.3 错误

`10001`（含 fieldErrors）、`40001` 分类不存在、`40002` 分类已停用。

---

## 6. `PUT /api/records/{id}` — 修改内容

- **全量语义**：请求体字段与 §5.1 完全相同（`type` 也必填），未传的可选字段视为 null 覆盖。前端必须先 GET 详情再回填表单，避免误清空。
- 不改 `status`；`status` 走 §6.1 的 PATCH。
- 已 `CLOSED` 的记录 → `20003`（内容不再可改）。

```json
{ "code": 0, "message": "已更新", "data": { …同详情… }, "requestId": "…" }
```

错误：`10001`、`20001`、`20003`、`40001`、`40002`。

### 6.1 `PATCH /api/records/{id}/status` — 状态流转

| 字段 | 类型 | 必填 | 校验 |
|---|---|---|---|
| `status` | string | **是** | ∈ 目标状态；且当前→目标在流转表内 |

请求：`{"status":"CLAIMED"}`
成功：`{"code":0,"message":"状态已更新","data":{"id":1,"status":"CLAIMED","allowedStatuses":["CLOSED"]},"requestId":"…"}`
非法流转：`HTTP 409` · `code 20002` · `data: {"current":"CLOSED","allowed":["(无)"]}`

**为什么单独一条 PATCH 而不塞进 PUT**（可逆决策，但影响契约）：状态流转带不变量校验与不同的错误语义（409 而非 400），与"改内容"是两个用例；混在 PUT 里会让"全量更新"的语义变含糊，前端也无法只发一个字段。

### 6.2 `DELETE /api/records/{id}` — 删除（逻辑）

- 无请求体；`data` 恒为 `null`；`message` = `"已删除"`。
- `HTTP 200`（**不用 204**：204 无响应体会破坏"所有响应同结构"这条规则，前端要为空体写特例）。
- 已删除再删 → `20001`（幂等友好？基线选择明确报错，理由：让调用方知道目标已不存在）。
- 是否限制"仅 `PENDING`/`CLAIMED` 可删" → 依赖 1.1 规则（Q-03），基线先允许任何状态删除。
- 删除后：列表/详情/匹配候选池均不可见，但 `t_match_result` 历史快照仍保留其 recordId（由回表过滤吸收，前端不会看到死链）。

---

## 7. `GET /api/categories` — 分类字典

| 请求参数 | `onlyActive` boolean 默认 `true`（列表页筛选与提交页下拉都用默认） |
|---|---|
| 成功 | `data` = 数组，按 `sortOrder` 升序 |

```json
{"code":0,"message":"ok","requestId":"…","data":[
  {"id":1,"code":"WALLET","name":"钱包卡证","sortOrder":10,"active":true},
  {"id":8,"code":"OTHER","name":"其他","sortOrder":90,"active":true}
]}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | number | 提交/筛选带上它 |
| `code` | string | 前端可用它做图标映射（稳定标识，不因改名而变） |
| `name` | string | 展示文案 |
| `sortOrder` | number | 已排好序，前端不要再排 |
| `active` | boolean | 默认只返 active；`onlyActive=false` 时含停用（详情页要显示历史分类名，用记录自带的 `categoryName`，不必调这个接口） |

> 无 `keywords` 字段：它是后端匹配实现的内部资料，不属于对外契约（避免前端"顺手用上"造成耦合）。

---

## 8. `POST /api/match` — 语义匹配（技术先进性得分点）

### 8.1 请求体

| 字段 | 类型 | 必填 | 默认 | 校验 |
|---|---|---|---|---|
| `text` | string | **是** | — | 去空格后 2–2000 字；否则 `30001` |
| `type` | string | 否 | 不限 | `LOST`\|`FOUND`；含义="我要找的记录类型"（如失主想找招领帖 ⇒ `FOUND`） |
| `categoryId` | number | 否 | — | 存在则作为加权/过滤；不存在 → `40001` |
| `topN` | number | 否 | 5 | 1–20（A6，待确认 Q-07） |
| `minScore` | number | 否 | 0.3 | 0–1，小数 4 位（A5，展示口径待确认 Q-06） |
| `excludeRecordId` | number | 否 | — | 从"某条记录详情页"发起匹配时排除自身 |

```json
{
  "text": "我在图书馆三楼弄丢了一个黑色皮钱包，里面有银行卡",
  "type": "FOUND",
  "categoryId": 1,
  "topN": 5,
  "minScore": 0.3,
  "excludeRecordId": null
}
```

### 8.2 成功响应（`HTTP 200`）

```json
{
  "code": 0,
  "message": "ok",
  "requestId": "9f3c1a7e",
  "data": {
    "matchId": 41,
    "engine": "LOCAL_KEYWORD",
    "inputType": "FOUND",
    "topN": 5,
    "minScore": 0.3,
    "tookMs": 18,
    "total": 2,
    "items": [
      {
        "recordId": 13,
        "score": 0.83,
        "scorePercent": 83,
        "reason": "标题与描述命中「图书馆三楼」「黑色」「钱包」，分类一致",
        "type": "FOUND",
        "status": "PENDING",
        "title": "图书馆拾到黑色钱包",
        "categoryName": "钱包卡证",
        "location": "图书馆三楼自习区",
        "occurredAt": "2026-10-20 15:00:00",
        "createdAt": "2026-10-20 15:30:00"
      },
      {
        "recordId": 26,
        "score": 0.31,
        "scorePercent": 31,
        "reason": "地点不同，仅「卡」字重合，弱相关",
        "type": "FOUND",
        "status": "PENDING",
        "title": "捡到一张 unknown 的卡片",
        "categoryName": null,
        "location": "大学生活动中心公告栏",
        "occurredAt": "2026-10-21 13:30:00",
        "createdAt": "2026-10-21 13:50:00"
      }
    ]
  }
}
```

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `matchId` | number | 否 | `t_match_result.id`，快照主键。**不可逆点②：前端一旦使用就去不掉** |
| `engine` | string | 否 | 实际执行的实现标识；答辩与排障看它，前端可用它显示"由本地关键词匹配给出" |
| `total` | number | 否 | = `items.length`（冗余但省得前端算长度，且与分页的 `total` 同词同义） |
| `items[].score` | number | 否 | **0–1，保留 2 位小数**；口径见 §8.4 |
| `items[].scorePercent` | integer | 否 | = round(score×100)，为前端展示准备的（避免每处各算各的） |
| `items[].reason` | string | 否 | 人类可读的依据说明；外部实现可能返回较笼统的文本，**前端不得解析它**，只展示 |
| `items[].recordId` | number | 否 | 跳详情用 |
| 其余展示字段 | — | — | **由 service 回表 `t_record` 取**，故与详情接口完全同值，且已过滤逻辑删除/非法状态 |

`items` 保证：按 `score` **倒序**；`score >= minScore`；长度 ≤ `topN`；`recordId` 不重复；不含 `excludeRecordId`；**只含 `status='PENDING'` 且未逻辑删除的记录**（已领 `CLAIMED` 与已关闭 `CLOSED` 一律不返回——捡到的东西已被认领，再推给失主是噪声）。同分按 `recordId ASC`。

### 8.3 空结果与错误

| 场景 | HTTP | code | data |
|---|---|---|---|
| 无命中（含候选池为空） | 200 | 0 | `{…, "total":0, "items":[]}` —— **不是错误**，前端显示"暂无匹配"空态 |
| `text` 空/过短/超长 | 400 | 30001 | `fieldErrors:[{field:"text",…}]` |
| 枚举/数值越界（topN、minScore、type） | 400 | 10001 | `fieldErrors` |
| `categoryId` 不存在 | 404 | 40001 | null |
| 外部引擎不可用（未配 Key / 网络失败 / 配额耗尽） | 503 | 30002 | `{"engine":"external"}` |
| 外部引擎超时 | 504 | 30003 | `{"engine":"external"}` |
| 引擎内部未预期异常 | 500 | 10005 | null（**不许把堆栈带出去，不许让 /api/match 抛未捕获异常** —— 3.2 的验收点） |

### 8.4 分数口径（跨实现一致性，写给 3.2 / 5.1 / 4.1）

- 所有实现必须把分数归一到 `[0,1]`，**且只承诺"同一实现内的相对排序可比"**。本地关键词分与余弦相似度分**不可跨实现比较**，因此：
  - 前端展示文案统一为"相似度 83%"，不得出现"语义置信度""匹配概率"这类暗示绝对含义的词；
  - 5.1 评估必须分组按 `impl` 统计，不混池；
  - `minScore` 阈值换实现后需要重新标定（写给 3.2：**阈值属于实现参数，不属于接口**）。
- 排序稳定：`score` 相同时按 `recordId ASC`（保证同一输入两次调用可复现，正/负例断言才不会飘）。
- `scorePercent` 与 `score` 必须由同一次计算得出，禁止前端二次加工。

### 8.5 快照写入语义（写给 3.1 / 3.2）

成功返回后写 `t_match_result`（含空结果）。写失败**只记 warn 日志，不影响响应**——快照是评估证据不是业务数据，不该让用户体验承担它的一致性（依据 A9，若你要求"必须落库才算成功"，改为前置写 + 整体回滚，架构基线 §7.4 已标出该位置）。

---

## 9. 接口 × 错误码 总览（前端拦截器映射表）

| code | 建议前端行为 |
|---|---|
| 0 | 正常渲染 |
| 10001 / 30001 | 表单：把 `fieldErrors` 落到对应输入框下方；非表单：toast `message` |
| 20001 | 详情页 → 显示"记录不存在或已删除"空态 + 返回列表按钮，**不做重试** |
| 20002 | toast `message` + 重新 GET 详情刷新 `allowedStatuses`（状态可能被别人改过） |
| 20003 / 40002 | toast `message` |
| 30002 / 30003 | 匹配区显示"匹配服务暂不可用，请稍后再试"，列表区不受影响（**降级 UI，不许整页报错**） |
| 10003 / 10004 | 视为前端调用了不存在的接口 ⇒ 属于开发期缺陷，控制台 error + toast |
| 10005 / 网络失败 | toast `"请求失败，请稍后再试（{requestId}）"`，把 requestId 带出来便于排查 |

---

## 10. curl 冒烟集（2.1 之后 5.1 直接扩展成接口测试）

```bash
BASE=http://localhost:18080

curl -s $BASE/api/health
curl -s "$BASE/api/records?type=FOUND&status=PENDING&page=1&pageSize=5&sortBy=createdAt&sortOrder=desc"
curl -s "$BASE/api/records/13"
curl -s "$BASE/api/records?status=ALL&keyword=钱包"
curl -s $BASE/api/categories
curl -s -X POST $BASE/api/records -H 'Content-Type: application/json' -d @create.json
curl -s -X PATCH $BASE/api/records/13/status -H 'Content-Type: application/json' -d '{"status":"CLAIMED"}'
curl -s -X DELETE $BASE/api/records/24
curl -s -X POST $BASE/api/match -H 'Content-Type: application/json' \
     -d '{"text":"我在图书馆三楼弄丢了一个黑色皮钱包，里面有银行卡","type":"FOUND","topN":5,"minScore":0.3}'

# 错误路径必须逐条验（3.1 验收点）
curl -si -X POST $BASE/api/records -H 'Content-Type: application/json' -d '{"type":"LOST","title":"x"}'      # → 400 10001
curl -si $BASE/api/records/999999                                                                            # → 404 20001
curl -si -X PATCH $BASE/api/records/24/status -H 'Content-Type: application/json' -d '{"status":"PENDING"}'  # → 409 20002
curl -si $BASE/api/records/25                                                                                # → 404（逻辑删除不可见）
curl -si -X POST $BASE/api/match -H 'Content-Type: application/json' -d '{"text":" "}'                       # → 400 30001
```

---

## 11. 契约冻结与变更流程

1. 本文与 `db/schema.sql` 是 3.1 / 3.2 / 4.1 / 4.2 的共同上游。**任一方需要改字段，先改本文，再改 DDL/DTO/TS 类型，三处同一 commit。**
2. 字段"只增不改不删"；删字段/改语义属不可逆变更，须走 `open-questions.md` 排队拍板。
3. 后端建议导出 OpenAPI（`springdoc` 可选依赖）供前端对照，但**本文是事实源**；若引入代码生成（`openapi-typescript`），生成物落 `web/src/types/api.generated.ts`，`api.ts` 只 re-export——这是 4.1/4.2 的可选优化，不写进基线（Q-13）。
