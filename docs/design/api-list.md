# 附录 A · 接口清单

> 本节是独立成表的接口清单，可直接对照代码核对。事实源：`server/src/main/java/com/findit/campus/**/web/*Controller.java`、`**/web/dto/*.java`、`docs/api-contract.md`。
> 核对结果（每条路径与字段的 `file:line`）见附录 B `consistency-checklist.md`。
> 表中「必填」指缺字段即 `10001`；「可空」指允许 `null`。字符串长度上限与 `db/schema.sql` 列长度一致。

## A.1 总览

Base path `/api`，无版本段；所有响应统一为 `{code, message, data, requestId}`；无鉴权。

| # | 方法 | 路径 | 用途 | 请求体 | 成功 | 权限 |
|---|---|---|---|---|---|---|
| 1 | GET | `/api/health` | 健康检查 | 无 | 200 `{status, db, version, engine}` | 公开 |
| 2 | GET | `/api/records` | 列表（筛选 + 分页 + 排序） | 无（Query 参数） | 200 分页对象 | 公开 |
| 3 | GET | `/api/records/{id}` | 详情 | 无 | 200 完整对象 | 公开 |
| 4 | POST | `/api/records` | 提交 | JSON 8 字段 | 200 完整对象 | 公开 |
| 5 | PUT | `/api/records/{id}` | 全量修改内容 | JSON 8 字段 | 200 完整对象 | 公开 |
| 6 | PATCH | `/api/records/{id}/status` | 状态流转 | JSON 1 字段 | 200 `{id,status,allowedStatuses}` | 公开 |
| 7 | DELETE | `/api/records/{id}` | 逻辑删除 | 无 | 200 `data=null` | 公开 |
| 8 | GET | `/api/categories` | 分类字典 | 无（Query 参数） | 200 数组 | 公开 |
| 9 | POST | `/api/match` | 语义匹配 | JSON 6 字段 | 200 匹配结果 | 公开 |

## A.2 逐接口

### A.2.1 `GET /api/health`

| 项 | 内容 |
|---|---|
| 参数 | 无 |
| 成功 | `200`，`data = {status, db, version, engine}` |
| DB 不可用 | `503` + `code 10005`，`data.db = "DOWN"`（必须明确失败，不许 hang） |

```json
{"code":0,"message":"ok","data":{"status":"UP","db":"UP","version":"0.0.1-SNAPSHOT","engine":"local"},"requestId":"…"}
```

### A.2.2 `GET /api/records`

**Query 参数**

| 参数 | 类型 | 必填 | 默认 | 校验 | 含义 |
|---|---|---|---|---|---|
| `type` | string | 否 | 全部 | `LOST` 或 `FOUND`，其余 → 10001 | 启事类型 |
| `status` | string | 否 | `PENDING` | `PENDING` / `CLAIMED` / `CLOSED` / `ALL` | 状态；`ALL` 表示不限 |
| `categoryId` | number | 否 | 全部 | 必须存在且启用；不存在 → 40001 | 分类 |
| `keyword` | string | 否 | 无 | ≤ 64 字；`%` `_` `\` 转义后参与 LIKE | 标题 + 描述模糊检索 |
| `page` | number | 否 | 1 | ≥ 1 | 页码 |
| `pageSize` | number | 否 | 10 | 1–50 | 每页条数 |
| `sortBy` | string | 否 | `createdAt` | `createdAt` / `occurredAt` | 排序字段（白名单） |
| `sortOrder` | string | 否 | `desc` | `asc` / `desc` | 排序方向 |

**响应 `data`**

| 字段 | 类型 | 说明 |
|---|---|---|
| `page` / `pageSize` | number | 回显请求值 |
| `total` / `totalPages` | number | 总条数 / 总页数；`total=0` 时 `totalPages=0` |
| `hasNext` | boolean | 是否还有下一页 |
| `list[]` | array | 列表项，字段见 A.3.1 |

**错误**：`10001`（分页/枚举/排序非法）、`40001`（`categoryId` 不存在）。空结果不是错误。

### A.2.3 `GET /api/records/{id}`

| 项 | 内容 |
|---|---|
| 参数 | path `id`：number，必填 |
| 成功 | `200`，`data` 为完整对象（A.3.2） |
| `id` 非数字 | `400` + `10001`，`fieldErrors: [{field:"id", reason:"必须为数字"}]` |
| 不存在 / 已逻辑删除 | `404` + `20001`，`message="记录不存在或已删除"` |

### A.2.4 `POST /api/records`

**请求体**

| 字段 | 类型 | 必填 | 校验 | 说明 |
|---|---|---|---|---|
| `type` | string | 是 | `LOST` / `FOUND` | 启事类型 |
| `title` | string | 是 | 2–64 字（去首尾空格后判长） | 标题 |
| `description` | string | 是 | 2–2000 字 | 详情正文，匹配主文本 |
| `categoryId` | number | 否 | 存在且 `is_active=1`，否则 40001 / 40002 | `null` = 未分类 |
| `location` | string | 是 | 1–128 字 | 丢失/发现地点 |
| `occurredAt` | string | 是 | `yyyy-MM-dd HH:mm:ss`；不得晚于 now + 5 分钟 | 业务时间 |
| `contact` | string | 否 | ≤ 64 字；只允许数字/字母/中文与 `-_()` | 联系方式 |
| `publisherName` | string | 否 | ≤ 32 字 | 联系人称呼 |

**成功**：`200`，`message="已提交"`，`data` 与详情同构（含 `id` 与 `allowedStatuses`）。

**错误**：`10001`（含全量 `fieldErrors`）、`40001`、`40002`。

**`occurredAt` 的严格口径**：分隔符与字段形状都不合法、或日期在日历上不存在（如 `2026-13-40 25:61:61`）时，一律 `400` + `10001`，不落 `500`。此处原为实现偏差（曾返回 `500/10005`，缺陷 DEF-01），已于 `3548a27` 修复（合入 `d21fe78`），并由用例 `API-C-08` 在 REG-01 回归中覆盖四种非法格式（`qa/reports/api-5.1.txt:35`），见附录 B C-01。

### A.2.5 `PUT /api/records/{id}`

- 全量语义：请求体字段与 A.2.4 完全相同（`type` 也必填），未传的可选字段视为 `null` 覆盖。
- 不改 `status`（状态走 A.2.6）。
- 已 `CLOSED` 的记录 → `409` + `20003`。
- 错误：`10001`、`20001`、`20003`、`40001`、`40002`。

### A.2.6 `PATCH /api/records/{id}/status`

| 项 | 内容 |
|---|---|
| 请求体 | `{"status": "CLAIMED"}`（必填，取值见 §5.5 流转表） |
| 成功 | `200`，`message="状态已更新"`，`data = {id, status, allowedStatuses}` |
| 非法流转 | `409` + `20002`，`data = {current, allowed}`；`allowed` 为空时返回 `["(无)"]` |

### A.2.7 `DELETE /api/records/{id}`

- 无请求体；`data` 恒为 `null`；`message="已删除"`；HTTP `200`（不用 204，因为 204 无响应体，会破坏「所有响应同结构」）。
- 逻辑删除：写 `deleted_at`，物理行保留。
- 已删除再删 / 不存在 → `404` + `20001`。

### A.2.8 `GET /api/categories`

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `onlyActive` | boolean | `true` | `false` 时含停用分类（排查/演示用） |

```json
{"code":0,"message":"ok","requestId":"…","data":[
  {"id":1,"code":"WALLET","name":"钱包卡证","sortOrder":10,"active":true},
  {"id":8,"code":"OTHER","name":"其他","sortOrder":90,"active":true}
]}
```

按 `sortOrder` 升序返回，前端不要再排。**无 `keywords` 字段**：它是后端匹配的内部资料，不属于对外契约。

### A.2.9 `POST /api/match`

**请求体**

| 字段 | 类型 | 必填 | 默认 | 校验 |
|---|---|---|---|---|
| `text` | string | 是 | — | 去空格后 2–2000 字；否则 `30001` |
| `type` | string | 否 | 不限 | `LOST` / `FOUND`：我要找的记录类型 |
| `categoryId` | number | 否 | — | 存在；不存在 → `40001` |
| `topN` | number | 否 | 5 | 1–20，越界 → `10001` |
| `minScore` | number | 否 | 0.3 | 0–1（含端点），越界 → `10001` |
| `excludeRecordId` | number | 否 | — | 从详情页发起时排除自身 |

**响应 `data`**

| 字段 | 类型 | 说明 |
|---|---|---|
| `matchId` | number \| null | `t_match_result.id`；快照写失败时为 `null` |
| `engine` | string | 实际执行的实现标识：`LOCAL_KEYWORD` |
| `inputType` | string \| null | 请求未带 `type` 时为 `null`（不伪造） |
| `topN` / `minScore` | number | 回显 |
| `tookMs` | number | 引擎耗时（毫秒） |
| `total` | number | = `items.length` |
| `items[]` | array | 候选，字段见 A.3.3 |

**`items` 保证**：按 `score` 倒序；`score >= minScore`；长度 ≤ `topN`；`recordId` 不重复；不含 `excludeRecordId`；只含 `status='PENDING'` 且未逻辑删除的记录；同分按 `recordId` 升序。

**错误**：`30001`（text 空/过短/超长）、`10001`（`topN`/`minScore`/`type` 越界）、`40001`（分类不存在）、`30002` + 503（引擎不可用）、`30003` + 504（引擎超时）、`10005` + 500（未预期异常，响应不含堆栈）。

## A.3 响应字段

### A.3.1 列表项（`GET /api/records` 的 `list[]`）

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `id` | number | 否 | 详情跳转用 |
| `type` / `status` | string | 否 | 枚举原值，文案由前端映射 |
| `title` | string | 否 | ≤ 64 |
| `categoryId` | number | 是 | 无分类时 `null` |
| `categoryName` | string | 是 | 无分类时 `null`；停用分类的名称照常返回 |
| `location` | string | 否 | ≤ 128 |
| `occurredAt` / `createdAt` | string | 否 | `yyyy-MM-dd HH:mm:ss` |

**刻意不返回**：`description`（省流量，卡片不展示）、`contact`、`publisherName`（最小暴露，只在详情给）。

### A.3.2 详情（`GET /api/records/{id}`、`POST`、`PUT` 的 `data`）

在 A.3.1 的字段基础上增加：

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `description` | string | 否 | 全文 |
| `contact` | string | 是 | 未填时为 `null` |
| `publisherName` | string | 是 | 联系人称呼 |
| `allowedStatuses` | string[] | 否 | 当前状态可迁往的状态；空数组表示终态，前端不显示状态按钮 |
| `updatedAt` | string | 否 | 最后修改时间 |

### A.3.3 匹配项（`items[]`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `recordId` | number | 跳详情用 |
| `score` | number | `0–1`，两位小数 |
| `scorePercent` | integer | `round(score × 100)`，由后端算好，前端不二次加工 |
| `reason` | string | 人类可读依据；**前端只展示不解析**（换实现后文案可能变化） |
| `type` / `status` / `title` / `categoryName` / `location` / `occurredAt` / `createdAt` | — | 由 `MatchService` 回表取得，与详情接口同值 |

### A.3.4 分类（`GET /api/categories` 的 `data[]`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | number | 提交/筛选带上它 |
| `code` | string | 稳定标识，可做图标映射 |
| `name` | string | 展示文案 |
| `sortOrder` | number | 已排序 |
| `active` | boolean | 默认只返 `true` |

## A.4 错误码索引

| code | HTTP | 出现在 |
|---|---|---|
| 10001 | 400 | 除 `GET /api/categories` 外全部接口 |
| 10002 | 413 | 任意带请求体的接口（请求体超限） |
| 10003 | 404 | 任意不存在的路径 |
| 10004 | 405 | 任意路径的动词错误 |
| 10005 | 500 / 503 | 全部接口的兜底；`/api/health` 的 DB DOWN 用 503 |
| 20001 | 404 | `/api/records/{id}` 的 GET / PUT / PATCH / DELETE |
| 20002 | 409 | `PATCH /api/records/{id}/status` |
| 20003 | 409 | `PUT /api/records/{id}`（`CLOSED` 记录） |
| 30001 | 400 | `POST /api/match` |
| 30002 | 503 | `POST /api/match` |
| 30003 | 504 | `POST /api/match` |
| 40001 | 404 | `GET /api/records`、`POST /api/records`、`PUT /api/records/{id}`、`POST /api/match` |
| 40002 | 409 | `POST /api/records`、`PUT /api/records/{id}` |
