# 附录 A · 接口清单

> 本节是独立成表的接口清单，可直接对照代码核对。事实源：`server/src/main/java/com/findit/campus/**/web/*Controller.java`、`**/web/dto/*.java`、`docs/api-contract-v2.md`。
> 核对结果（每条路径与字段的 `file:line`）见附录 B `consistency-checklist.md`（B.10 是 v2 抽查）。
> 表中「必填」指缺字段即 `10001`；「可空」指允许 `null`。字符串长度上限与 `db/schema.sql` 列长度一致。
> **v2 的变化集中在三处**：写操作要登录、非所有者读要遮蔽、联系方式按分数分档。v1 的「九接口 / 无鉴权」口径已作废。

## A.1 总览

Base path `/api`，无版本段；所有响应统一为 `{code, message, data, requestId}`。

鉴权由服务端过滤器决定（`common/web/AuthGateFilter` + `AuthPaths` 规则表，先于 `DispatcherServlet`）：**写操作默认拒绝**，未登录一律 `401 / 10006`，客户端传什么身份字段都不作数。会话是 `HttpSession` + `JSESSIONID`（HttpOnly、SameSite=Lax、30 分钟、`tracking-modes: cookie`）。

| # | 方法 | 路径 | 用途 | 请求体 | 成功 | 认证 | v1/v2 |
|---|---|---|---|---|---|---|---|
| 1 | GET | `/api/records` | 列表（筛选 + 分页 + 排序） | 无（Query） | 200 分页对象 | 匿名可读（输出遮蔽） | 输出改遮蔽 + `isMine` |
| 2 | GET | `/api/records/{id}` | 详情（所有者含 pair 卡片） | 无 | 200 完整对象 | 匿名可读（按状态与身份分档） | 扩展 |
| 3 | POST | `/api/records` | 发布 | JSON 8 字段 | 200 完整对象（本人明文） | **需登录** | 行为变更 |
| 4 | PUT | `/api/records/{id}` | 全量修改内容 | JSON 8 字段 | 200 完整对象 | **所有者** | 行为变更 |
| 5 | PATCH | `/api/records/{id}/status` | 三态流转（完成 / 关闭） | JSON 1 字段 | 200 `{id,status,…}` | **所有者** | 行为变更 |
| 6 | DELETE | `/api/records/{id}` | 逻辑删除 | 无 | 200 `data=null` | **所有者** | 行为变更 |
| 7 | GET | `/api/categories` | 分类字典 | 无（Query） | 200 数组 | 公开 | 不变 |
| 8 | POST | `/api/match` | 同步匹配 | JSON 6 字段 | 200 匹配结果（遮蔽） | **需登录** | 行为变更 |
| 9 | POST | `/api/auth/login` | 登录 | JSON 2 字段 | 200 用户对象 + `Set-Cookie` | 公开 | 新增 |
| 10 | POST | `/api/auth/logout` | 登出 | 无 | 200 `data=null` | 公开（幂等） | 新增 |
| 11 | GET | `/api/auth/me` | 当前用户 | 无 | 200 用户对象 | 需登录 | 新增 |
| 12 | GET | `/api/my/records` | 我的启事（含 pair 卡片） | 无（Query） | 200 分页对象 | 需登录 | 新增 |
| 13 | PATCH | `/api/pairs/{id}` | pair 动作：联系 / 确认 / 忽略 | JSON 1 字段 | 200 pair 状态 | 两端任一 | 新增 |
| 14 | GET | `/api/pairs/{id}/contact` | 查看对方联系方式 | 无 | 200 `ContactView` | 两端任一（三档 + 审计） | 新增 |
| 15 | POST | `/api/pairs/{id}/contact-unlock` | 授权公开 / 撤回本端联系方式 | JSON ≤2 字段 | 200 `ContactUnlockResponse` | 两端任一 | 新增 |
| 16 | GET | `/api/notifications` | 通知列表 + 未读数 | 无（Query） | 200 分页 + `unreadCount` | 需登录 | 新增 |
| 17 | PATCH | `/api/notifications/{id}/read` | 标记已读 | 无 | 200 `{id,read,readAt,unreadCount}` | 接收人 | 新增 |
| O1 | GET | `/api/health` | 健康检查 | 无 | 200 `{status,db,version,engine}` | 公开 | 保留，不计入 17 |
| O2 | POST | `/api/ops/pair-scan` | 手动兜底扫描 | 无 | 200 `PairScanResult` | `X-Ops-Token` | 新增，不计入 17 |

「不计入 17」的意思是：正文 §5.3 的权限矩阵按业务接口逐格给状态码，运维接口不进三态矩阵（O1 公开、O2 用静态令牌，与用户身份无关）。

## A.2 逐接口

### A.2.1 `GET /api/records`

**Query 参数**（`record/web/dto/RecordQuery.java`）

| 参数 | 类型 | 必填 | 默认 | 校验 | 含义 |
|---|---|---|---|---|---|
| `type` | string | 否 | 全部 | `LOST` 或 `FOUND`，其余 → 10001 | 启事类型 |
| `status` | string | 否 | `PENDING` | `PENDING` / `COMPLETED` / `CLOSED` / `ALL`；**`CLAIMED` 已作废 → 10001** | 状态；`ALL` 表示不限 |
| `categoryId` | number | 否 | 全部 | 必须存在且启用；不存在 → 40001 | 分类 |
| `keyword` | string | 否 | 无 | ≤ 64 字；`%` `_` `\` 转义后参与 LIKE | 标题 + 描述模糊检索 |
| `page` | number | 否 | 1 | ≥ 1 | 页码 |
| `pageSize` | number | 否 | 10 | 1–50 | 每页条数 |
| `sortBy` | string | 否 | `createdAt` | `createdAt` / `occurredAt` | 排序字段（白名单） |
| `sortOrder` | string | 否 | `desc` | `asc` / `desc` | 排序方向 |

**v2 的返回集合口径**：默认只返回 `status='PENDING'` 且未删除的记录；传 `status=COMPLETED` 或 `CLOSED` 也是 `200`，但**恒为空列表**（公共列表不返回终态，0 命中）。这不是过滤失败，是可见性规则——见正文 §5.8。

**响应 `data`**

| 字段 | 类型 | 说明 |
|---|---|---|
| `page` / `pageSize` | number | 回显请求值 |
| `total` / `totalPages` | number | 总条数 / 总页数；`total=0` 时 `totalPages=0` |
| `hasNext` | boolean | 是否还有下一页 |
| `list[]` | array | 列表项，字段见 A.3.1 |

**错误**：`10001`（分页/枚举/排序非法、`CLAIMED`）、`40001`（`categoryId` 不存在）。空结果不是错误。

### A.2.2 `GET /api/records/{id}`

| 项 | 内容 |
|---|---|
| 参数 | path `id`：number，必填 |
| 成功 | `200`，`data` 为完整对象（A.3.2）；**身份不同字段不同**（下表） |
| `id` 非数字 | `400` + `10001`，`fieldErrors: [{field:"id", reason:"必须为数字"}]` |
| 不存在 / 已逻辑删除 | `404` + `20001`，`message="记录不存在或已删除"` |
| `CLOSED`（任何身份，含本人） | `404` + `20001`（与「不存在」同码同文案） |
| `COMPLETED` 且已过保留期（任何身份，含本人） | `404` + `20001` |
| `COMPLETED` 且本人 | `404` + `20001`（管理入口是 #12 `/api/my/records`） |
| `COMPLETED` 且未过期、登录且为合格对端（存在 `pairUsable` 且 `score ≥ 0.90` 的 pair） | `200`，**遮蔽视图**：无 `contact`、无 `pairs` |
| `COMPLETED` 且未过期、登录但不够格 | `404` + `20001` |

**逐身份字段差异**（装配点 `record/web/dto/RecordDetailResponse.java`，本人分支 `:103-115`、他人分支 `:116-130`）：

| 字段 | 本人 | 合格对端 / 匿名 / 非所有者 |
|---|---|---|
| `contact` | 原值 | **`null`**（不是掩码，是整个字段没有值） |
| `description` | 原值 | `MaskingPolicy.maskDescription` |
| `title` | 原值 | 同一遮蔽函数（防御性追加） |
| `location` | 原值 | `maskLocation`（码点截断 + 去精确定位片段） |
| `publisherName` | 原值 | `maskPublisherName`（首字 + `**`） |
| `completedAt` | 原值 | `null` |
| `pairs` | 数组 | 省略 |
| `lowConfidenceCount` | 真实值 | `0` |

### A.2.3 `POST /api/records`

**认证**：需登录（`publisherId` 取 session，**不接受客户端传入**）。未登录 `401 / 10006`。

**请求体**（`record/web/dto/RecordCreateRequest.java`；`PUT` 复用同一 DTO）

| 字段 | 类型 | 必填 | 校验 | 说明 |
|---|---|---|---|---|
| `type` | string | 是 | `LOST` / `FOUND` | 启事类型 |
| `title` | string | 是 | 2–64 字（去首尾空格后判长） | 标题 |
| `description` | string | 是 | 2–2000 字 | 详情正文，匹配主文本 |
| `categoryId` | number | 否 | 存在且 `is_active=1`，否则 40001 / 40002 | `null` = 未分类 |
| `location` | string | 是 | 1–128 字 | 丢失/发现地点 |
| `occurredAt` | string | 是 | `yyyy-MM-dd HH:mm:ss`；日历上必须存在；不得晚于 now + 5 分钟 | 业务时间 |
| `contact` | string | 否 | **`^1[3-9]\d{9}$`（11 位大陆手机号）** | **v2 变更**：不再接受自由格式。只做格式校验，**不做短信验证**，也不写进 `t_user` |
| `publisherName` | string | 否 | ≤ 32 字 | 联系人称呼 |

`contact` 收紧成手机号的原因写在 DTO 注释里（`RecordCreateRequest.java:78-84`）：号码要在详情里原样回显给本人、要在三档授权里作为「联系方式」给出，自由格式会让「这是不是号码」变成前端猜；QQ/微信请写进描述（描述会被打码）。**历史非手机号值仍可读**，只有新写入/更新时必须合法或 `null`。

**成功**：`200`，`message="已提交"`，`data` 与详情同构（本人视图，明文）。

**错误**：`10006`（未登录）、`10001`（含全量 `fieldErrors`）、`40001`、`40002`。

**副作用（不在响应里，但必须知道）**：事务提交后异步发起配对打分（`PairScoringEventListener` → `pairScoringExecutor`）。发布响应**不等 LLM**，实测三次发布 0.167s / 0.054s / 0.061s；进度靠 `pair_state` 轮询（A.3.6）。

**`occurredAt` 的严格口径**：形状不合法或日历上不存在（如 `2026-13-40 25:61:61`）时一律 `400` + `10001`，不落 `500`。此处原为 DEF-01（曾返回 `500/10005`），已于 `3548a27` 修复（`@StrictDateTime` + `ResolverStyle.STRICT`），用例 `API-C-08` 覆盖四种非法格式（`qa/reports/api-5.1.txt:35`），见附录 B C-01。

### A.2.4 `PUT /api/records/{id}`

- 全量语义：请求体字段与 A.2.3 完全相同（`type` 也必填），未传的可选字段视为 `null` 覆盖。
- 不改 `status`（状态走 A.2.5）。
- 认证：所有者。非所有者可见时 `403 / 10007`，不可见时 `404 / 20001`。
- `COMPLETED` / `CLOSED` → `409` + `20003`。
- **并发口径**：`feat/v2-integration @ 64693ec` 给 UPDATE 加了 `AND status='PENDING'`，0 行时用 `SELECT … FOR UPDATE` 回读区分 `20001`（已删/不存在）与 `20003`（并发被改成终态）；更早的 `941fdee` 是「先读 status 再 UPDATE」。见正文 §5.8。
- 错误：`10001`、`10006`、`10007`、`20001`、`20003`、`40001`、`40002`。

### A.2.5 `PATCH /api/records/{id}/status`

| 项 | 内容 |
|---|---|
| 认证 | 所有者（非所有者 `10007`；`CLOSED`/到期 → `20001`） |
| 请求体 | `{"status": "COMPLETED"}`，取值 `COMPLETED` / `CLOSED`；传 `CLAIMED` → `10001` |
| 成功 | `200`，`message="状态已更新"`，`data = {id, status, completedAt, updatedAt, allowedStatuses, hasMatchedPair, matchedPairCount}` |
| 非法流转 | `409` + `20002`，`data = {current, allowed}`；`allowed` 为空时返回 `["(无)"]` |
| **无有效 MATCHED pair 就标完成** | `409` + **`20005`**，`data = {currentStatus, required, matchedPairCount}`，**零写入**（`status` / `completed_at` / `updated_at` 都不动，实测见 F2 终态组） |
| 同值重复提交 | `409` + `20002` |

两个终态的语义区别（正文 §5.8）：`COMPLETED` 要求存在有效 MATCHED pair 且写 `completed_at`（同一条 UPDATE），保留期内仍可被召回；`CLOSED` 无前置条件，其 pair 一律失效（`50005`）。到期**不改写 `status`**，是读时窗口判定。

### A.2.6 `DELETE /api/records/{id}`

- 认证：所有者。三态都可逻辑删除，不改 `status`。
- 无请求体；`data` 恒为 `null`；`message="已删除"`；HTTP `200`（不用 204，因为 204 无响应体，会破坏「所有响应同结构」）。
- 逻辑删除：写 `deleted_at`，物理行保留；所有读接口过滤 `deleted_at IS NULL`。
- 已删除再删 / 不存在 → `404` + `20001`。

### A.2.7 `GET /api/categories`

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

### A.2.8 `POST /api/match`

**认证**：**需登录**（v2 变更，`viewerId` 从 session 取，用于算 `isMine` / `detailAccess`）。响应体仍是遮蔽的——候选是别人的记录。

**请求体**（`match/web/dto/MatchRequest.java`）

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
| `matchId` | number \| null | `t_match_result.id`；快照写失败时为 `null`（只 WARN，不影响响应） |
| `engine` | string | 实际执行的实现标识：`LOCAL_KEYWORD` |
| `inputType` | string \| null | 请求未带 `type` 时为 `null`（不伪造） |
| `topN` / `minScore` | number | 回显 |
| `tookMs` | number | 引擎耗时（毫秒） |
| `total` | number | = `items.length` |
| `items[]` | array | 候选，字段见 A.3.3 |

**`items` 保证**：按 `score` 倒序；`score >= minScore`；长度 ≤ `topN`；`recordId` 不重复；不含 `excludeRecordId`；同分按 `recordId` 升序。**候选池（v2 变更）**：`MATCHABLE` = `PENDING` 恒可 + 保留期内的 `COMPLETED`（带 `detailAccess=RESTRICTED`），`CLOSED` 与已删除恒 0 命中；上限 `findit.match.pool-size=500`。

**不降级**：引擎不可用就是 `503 / 30002`，**不会**偷偷换成本地关键词分（与配对链路的降级策略相反，理由见正文 §6.10）。

**错误**：`10006`（未登录）、`30001`、`10001`、`40001`、`30002` + 503、`30003` + 504、`10005` + 500（响应不含堆栈）。

### A.2.9 `POST /api/auth/login`

| 项 | 内容 |
|---|---|
| 认证 | 公开（**不开放注册**，账号来自 `db/seed.sql` / 运维导入） |
| 请求体 | `{studentNo, password}`；`studentNo` 必填 ≤32，`password` 必填 ≤64（`LoginRequest.java:18-23`） |
| 成功 | `200`，`data = {id, studentNo, displayName}`，`Set-Cookie: JSESSIONID=…; HttpOnly; SameSite=Lax; Path=/` |
| 失败 | `401` + `10006`，`message="学号或密码不正确"` |
| 防枚举 | **凭证错误 / 账号不存在 / 账号停用三种情况的状态码、body、message 完全一致**（实测 `B2-auth-evidence.md`） |
| 失败计数 | `LoginAttemptTracker` 5 次阈值**只记日志、不锁账号**（契约口径）；在线撞库的兜底建议在反代按 IP 限流（正文 §9.2） |
| 口令 | `t_user.password_hash`，bcrypt cost 10；**`t_user` 不存手机号** |

### A.2.10 `POST /api/auth/logout`

无请求体；`data=null`；`message="已退出"`；**幂等**（无会话也返回 200，不给前端一个「退出了个啥」的错误分支）。服务端 `session.invalidate()`，浏览器侧 `JSESSIONID` 失效——登出后再访问私有接口是 `401 / 10006`。

### A.2.11 `GET /api/auth/me`

| 项 | 内容 |
|---|---|
| 成功 | `200`，`data = {id, studentNo, displayName}` |
| 无会话 | `401` + `10006` |

前端刷新后靠它恢复登录态（`web/src/composables/useAuth.ts` 是模块级缓存，不是 store）。

### A.2.12 `GET /api/my/records`

**Query**（`MyRecordsQuery`）：`type`、`status`（`PENDING` / `COMPLETED` / `CLOSED` / `ALL`，默认 `ALL`）、`page`、`pageSize`（≤50）、`sortBy`、`sortOrder`。

**返回**：`PageResult<MyRecordListItemResponse>`（A.3.5），**只返回自己的集合**，三态都在（`COMPLETED` / `CLOSED` 在公共列表 0 命中，这里是唯一管理入口）。本人读自己的 `PENDING` 全部原值；`detailVisible` 告诉前端「点进详情会不会 404」。

**错误**：`10006`、`10001`。

### A.2.13 `PATCH /api/pairs/{id}`

| 项 | 内容 |
|---|---|
| 认证 | 两端任一（非参与方 `403 / 10007`，不区分 pair 状态） |
| 请求体 | `{"action": "CONTACT" \| "CONFIRM" \| "IGNORE"}` |
| 未知字段 | **`400 / 10001`**。DTO 用 `@JsonAnySetter` 收集未知键（`PairActionRequest.java:31-37`），所以 `{"action":"CONFIRM","status":"MATCHED"}` 会被拒——契约说「不能直传状态」，静默忽略等于假装服务端接受了客户端指定的状态 |
| 成功 | `200`，`data` 见 A.3.7；同 action 幂等 |
| 错误 | `score < 0.75` → `403 / 50002`；任一端 `CLOSED` 或到期 → `403 / 50005`；记录已删 / pair 不存在 → `404 / 20004`；`MATCHED` / `IGNORED` 终态再改 → `409 / 50003`；非法 `action` → `400 / 10001` |

`CONFIRM` 写的是**本端**的 `confirmed_at`（`lost_confirmed_at` 或 `found_confirmed_at`，按调用者是哪一端）。两端都非空 ⇔ `status=MATCHED`，这是「标记完成」的前置条件（A.2.5）。

### A.2.14 `GET /api/pairs/{id}/contact`

| 项 | 内容 |
|---|---|
| 认证 | 两端任一；非参与方 `403 / 10007` |
| `id` 形状 | path 参数按 **String** 接（`PairController.java:59`），超 `bigint` 范围、非数字、`0`、`-1` 全部 `404 / 20004`，**不 500**（实测 18 次调用，门禁 2 §6.3） |
| 响应头 | `Cache-Control: no-store` + `Vary: Cookie`（本接口 v1 起就有 `no-store`；其余 `/api/**` 由 `ApiResponseHeadersFilter` 统一施加，已合入 `feat/v2-integration @ 64693ec`） |
| 审计 | **每次调用先写 `t_access_log` 再决定出号**，写失败即 500 且不出号（fail-closed）。审计记「谁、哪一端、看哪一端、当时分数、结果」，**不记号码本身** |

**三档行为**（`PairAccessPolicy.tier`，阈值来自 `findit.pair.*`）：

| 条件 | HTTP | body |
|---|---|---|
| `score ≥ 0.90`（DIRECT） | `200` | `available=true` + `contact` 明文 |
| `0.75 ≤ score < 0.90` 且对方已 `PUBLISH` | `200` | `available=true` + `contact` + `note` |
| 同档、对方未授权 | **`200`** | `available=false`、`reason="NOT_PUBLIC"`、`contact=null` |
| 同档、对方没填号码 | **`200`** | `available=false`、`reason="NOT_PROVIDED"` |
| `score < 0.75` | `403` | `50002`，**不出成功体** |
| 任一端 `CLOSED` / 到期 | `403` | `50005`（失效判定在档位**之前**） |
| 记录已删 / pair 不存在 | `404` | `20004`（pair 行不存在时不写审计，外键会拒） |

「未授权」是 200 而不是 403：它是一个正常的业务状态（对方可以同意，也可以拒绝），前端要渲染成提示而不是 toast 报错。

### A.2.15 `POST /api/pairs/{id}/contact-unlock`

| 项 | 内容 |
|---|---|
| 认证 | 两端任一（**只能操作本端**，不能替对方授权） |
| 请求体 | `{action, note}`，`action ∈ PUBLISH / REVOKE`，**省略 `action` = `PUBLISH`**（兼容 E1 只发 `{note}` 的调用）；`note` ≤ **200 个 Unicode 码点**，`REVOKE` 时被忽略并清空 |
| 成功 | `200`，`data = {pairId, side, published, unlockedAt, note}`；`REVOKE` 时 `published=false`、`unlockedAt=null`、`note=null` |
| 错误 | `PUBLISH` 且 `score < 0.75` → `403 / 50002`；任一端 `CLOSED` / 到期 → `403 / 50005`；已删 / 不存在 → `404 / 20004` |

`note` 落库前过一次 `maskDescription`，**先校验原文长度、再打码**，打码结果再做一次 200 码点防御性截断（打码可能让文本变长：`13812345678` → `[手机号已隐藏]`）。取号时 `note` 只在 `available=true` 的 AUTHORIZABLE 分支给出。

### A.2.16 `GET /api/notifications`

**Query**：`page`（≥1）、`pageSize`（1–50，**默认 10**，取 `NotificationQuery.DEFAULT_PAGE_SIZE`）。

**响应 `data`**：`{unreadCount, page, pageSize, total, list[]}`，`list[]` 见 A.3.9。**只返回自己的**（`user_id` = session）。

正文**不含联系方式**：`PairScoringService.publishNotifications` 组装时用 `MaskingPolicy.maskDescription` 处理描述。去重靠唯一键 `uk_notification_user_pair_type (user_id, pair_id, type)`，`type ∈ PAIR_MATCH / PAIR_UNLOCK / SYSTEM`（DDL `ck_notification_type`）。触发阈值 `notify_threshold = 0.75`。

**错误**：`10006`、`10001`。

### A.2.17 `PATCH /api/notifications/{id}/read`

无请求体；成功 `200`，`data = {id, read, readAt, unreadCount}`（`unreadCount` 是**标记后**的余量，前端不用再拉一次列表）。他人通知 → `403 / 10007`；不存在 → `404 / 50004`；重复标记幂等（`readAt` 保持首次值）。

### A.2.O1 `GET /api/health`

| 项 | 内容 |
|---|---|
| 参数 | 无 |
| 成功 | `200`，`data = {status, db, version, engine}`（`engine` 取 `MatchEngineConfig.engine()`，即当前装配的匹配实现标识） |
| DB 不可用 | `503` + `code 10005`，`data.db = "DOWN"`（必须明确失败，不许 hang） |

```json
{"code":0,"message":"ok","data":{"status":"UP","db":"UP","version":"0.0.1-SNAPSHOT","engine":"local"},"requestId":"…"}
```

### A.2.O2 `POST /api/ops/pair-scan`

| 项 | 内容 |
|---|---|
| 认证 | 请求头 `X-Ops-Token`，常量时间比较；**令牌未配置或都不匹配一律 `404 / 10003`**（不区分「没开」与「token 错」，不给探测者反馈） |
| 成功 | `200`，`data = {scanned, rescored, degraded, skipped, expired, limited, elapsedMs, trigger}` |
| 冲突 | 已有扫描在跑 → `409 / 50006`（进程内 `AtomicBoolean` 单飞） |
| 参数 | 无请求体；`trigger="http"`（与 `scheduled` / `startup` / `--pair-scan-once` 区分，落 `t_pair_score` 无关，只进日志与返回值） |

限额：单次 `scan-limit=500`、`max-llm-calls=500`，`ORDER BY` 保证不被新记录饿死；`expired` 只计数，**不改 `status`**。

## A.3 响应字段

### A.3.1 列表项（`GET /api/records` 的 `list[]`）

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `id` | number | 否 | 详情跳转用 |
| `type` / `status` | string | 否 | 枚举原值，文案由前端映射；v2 三态 |
| `title` | string | 否 | ≤ 64；**非所有者过 `maskDescription`** |
| `categoryId` | number | 是 | 无分类时 `null` |
| `categoryName` | string | 是 | 无分类时 `null`；停用分类的名称照常返回 |
| `location` | string | 否 | ≤ 128；非所有者过 `maskLocation` |
| `occurredAt` / `createdAt` | string | 否 | `yyyy-MM-dd HH:mm:ss` |
| `isMine` | boolean | 否 | **v2 新增**：会话用户是否发布者（匿名恒 `false`） |

**刻意不返回**：`description`（省流量，卡片不展示）、`contact`、`publisherName`（最小暴露，只在详情给）。

### A.3.2 详情（`GET /api/records/{id}`、`POST`、`PUT` 的 `data`）

在 A.3.1 的字段基础上增加：

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `description` | string | 否 | 全文（本人）/ 遮蔽（他人） |
| `contact` | string | 是 | 本人原值；他人 **键不出现**（值为 `null`，类上 `@JsonInclude(NON_NULL)` 把它省略，`RecordDetailResponse.java:71`） |
| `publisherName` | string | 是 | 本人原值；他人 `首字**` |
| `allowedStatuses` | string[] | 否 | 当前状态可迁往的状态；本人 `PENDING` 实测 `["COMPLETED","CLOSED"]`；终态与**他人视图恒为空数组**（不是 `null`，前端可以直接 `.length` 判断） |
| `hasMatchedPair` / `matchedPairCount` | boolean / number | 否 | 「标记完成」按钮的可用性判定，服务端算好；他人视图 `false` / `0` |
| `completedAt` | string | 是 | 仅 `COMPLETED` 且仅本人可见；他人视图键不出现 |
| `pairState` | string | 是 | `PENDING` / `SCORING` / `READY` / `DEGRADED`，**与 `status` 无因果**；**仅所有者返回**，他人视图该键被 `NON_NULL` 省略 |
| `pairs` | PairCard[] | 是 | **仅所有者**；他人是 `null` → 键整个省略（不是空数组，避免前端以为「查过了，没有」） |
| `lowConfidenceCount` | number | 否 | `score < 0.75` 的对数；他人恒 `0` |
| `createdAt` / `updatedAt` | string | 否 | 最后修改时间 |

### A.3.3 匹配项（`items[]`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `recordId` | number | 跳详情用 |
| `score` | number | `0–1`，两位小数 |
| `scorePercent` | integer | `round(score × 100)`，后端算好，前端不二次加工 |
| `reason` | string | 人类可读依据；**前端只展示不解析**（换实现后文案可能变化） |
| `type` / `status` / `title` / `categoryName` / `location` / `occurredAt` / `createdAt` | — | 由 `MatchService` 回表取得；标题/地点过遮蔽 |
| `recordStatus` | string | **v2 新增**：`PENDING` / `COMPLETED`，让前端给保留期内的完成记录挂角标 |
| `detailAccess` | string | **v2 新增**：`OPEN` / `RESTRICTED`。`COMPLETED` 恒为 `RESTRICTED`——命中不等于有详情权限，点进去仍按身份判定，可能 404（`MatchItemResponse.java:73`） |

### A.3.4 分类（`GET /api/categories` 的 `data[]`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | number | 提交/筛选带上它 |
| `code` | string | 稳定标识，可做图标映射 |
| `name` | string | 展示文案 |
| `sortOrder` | number | 已排序 |
| `active` | boolean | 默认只返 `true` |

### A.3.5 我的启事项（`GET /api/my/records` 的 `list[]`）

`record/web/dto/MyRecordListItemResponse.java`，字段：

`id`、`type`、`status`、`title`、`categoryId`、`categoryName`、`location`、`occurredAt`、`completedAt`、`contact`、`publisherName`、`pairState`、`pairDegraded`、`pairCount`、`relatedCount`、`lowConfidenceCount`、`hasMatchedPair`、`matchedPairCount`、`detailVisible`、`pairs[]`、`createdAt`、`updatedAt`。

三点要说清：

- **这里是明文**（`contact`、`publisherName`、`title`、`location` 都是本人数据），遮蔽规则只作用于「他人视角」。
- `pairDegraded` 是 `pairState === "DEGRADED"` 的便捷布尔，前端用它渲染「已降级」提示条。
- `detailVisible=false` 意味着点进详情会 404（`COMPLETED` 本人不可读、`CLOSED` 任何人不可读、到期不可读），列表仍显示是为了让本人能继续操作（改状态/删除）。

### A.3.6 `pair_state` 的读法

| 值 | 含义 | 前端动作 |
|---|---|---|
| `PENDING` | 还没开始打分（事件已发，等线程池） | 显示「分析中」，继续轮询 |
| `SCORING` | 正在打分（`markPairScoring` 已写入） | 同上 |
| `READY` | 全部候选都有分 | 停止轮询，展示 `pairs` |
| `DEGRADED` | 存在降级行（LLM 没参与，本地分顶替） | 展示 `pairs` + **降级提示**，文案不许写「匹配完成」 |

轮询实测：`web/src/views/MyRecords.vue` 只在「进行中」标签激活时按 5000ms 拉一次，且 `document.visibilityState !== 'visible'` 时暂停。

### A.3.7 PairCard（`pairs[]` 元素）

`record/web/dto/PairCard.java`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `pairId` | number | 三个 pair 接口用它 |
| `score` / `scorePercent` | number / integer | `0–1` 四位小数 / 百分比 |
| `tier` | string | `DIRECT` / `AUTHORIZABLE` / `LOW` |
| `status` | string | `NEW` / `CONTACTED` / `MATCHED` / `IGNORED` |
| `matched` | boolean | `status === MATCHED` |
| `myConfirmed` / `otherConfirmed` | boolean | 本端 / 对方是否已确认 |
| `lostConfirmedAt` / `foundConfirmedAt` | string \| null | 两端确认时间 |
| `mySide` | string | `LOST` / `FOUND`：调用者在这一对里是哪一端 |
| `engine` | string | `DEEPSEEK` / `LOCAL_KEYWORD` |
| `degraded` | boolean | 该行是否降级产生 |
| `counterpart` | object | `{recordId, type, title, location, occurredAt, createdAt, status}`，**全部遮蔽后的值** |
| `myUnlocked` / `otherUnlocked` | boolean | 两端是否已 `PUBLISH` |
| `myNote` / `otherNote` | string \| null | 附言（遮蔽后长度 ≤200 码点） |
| `contactAvailable` | boolean | **按钮状态，不是号码**：`(tier===DIRECT \|\| otherUnlocked) && contactPresent`，且排除 `LOW` |
| `contactHint` | string | 四选一的提示文案，判定顺序「先看能不能看，再看有没有号」 |

**`PairCard` 里没有 `contact` 字段，任何身份都没有。** SQL 侧只取 `other.contact IS NOT NULL AND <> ''` 这个布尔（`PairFactsMapper`），Java 拿不到原文——这条由 `PairFactsCardsSqlTest#contactIsNeverSelected` 固定。

### A.3.8 `ContactView`（`GET /api/pairs/{id}/contact` 的 `data`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `pairId` | number | 回显 |
| `score` / `scorePercent` / `tier` | — | **当时**的分数（不缓存、不回填） |
| `viewerSide` / `targetSide` | string | 我是哪一端、我看的是哪一端 |
| `available` | boolean | 本次是否出号 |
| `contact` | string \| null | 仅 `available=true` 时有值 |
| `reason` | string \| null | `NOT_PUBLIC` / `NOT_PROVIDED`（200 不出号的两条路径） |
| `message` | string | 可直接展示的文案 |
| `unlockedAt` | string \| null | 对方授权时间 |
| `note` | string \| null | 对方附言（遮蔽后） |

### A.3.9 通知项（`GET /api/notifications` 的 `list[]`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | number | 标记已读用 |
| `type` | string | `PAIR_MATCH` / `PAIR_UNLOCK` / `SYSTEM` |
| `pairId` | number | 关联 pair（点击直达卡片） |
| `title` / `body` | string | 文案；`body` **不含联系方式**（组装时已遮蔽） |
| `read` | boolean | 是否已读 |
| `createdAt` | string | 时间 |

## A.4 错误码索引

与 `common/api/ErrorCode.java` 逐条对照（枚举是 `code ↔ 文案 ↔ httpStatus` 的唯一映射点）。

| code | HTTP | 出现在 |
|---|---|---|
| 10001 | 400 | 除 `GET /api/categories` 外全部接口（含 `CLAIMED`、非法 `action`、pair 未知字段） |
| 10002 | 413 | 任意带请求体的接口（请求体超限） |
| 10003 | 404 | 任意不存在的路径；**`POST /api/ops/pair-scan` 的令牌不匹配也用它** |
| 10004 | 405 | 任意路径的动词错误 |
| 10005 | 500 / 503 | 全部接口的兜底；`/api/health` 的 DB DOWN 用 503 |
| 10006 | 401 | **v2 新增**：全部写接口 + `/api/auth/me`、`/api/my/**`、`/api/pairs/**`、`/api/notifications/**`、`POST /api/match`；也用于登录失败（文案换成「学号或密码不正确」） |
| 10007 | 403 | **v2**：非所有者改记录（#4/#5/#6）、非参与方动 pair（#13/#14/#15）、改他人通知（#17） |
| 20001 | 404 | `#2/#4/#5/#6`：**也用于 `CLOSED`、到期 `COMPLETED`、非合格对端看 `COMPLETED`** |
| 20002 | 409 | `PATCH /api/records/{id}/status`（同值、终态再流转） |
| 20003 | 409 | `PUT /api/records/{id}`（`COMPLETED` / `CLOSED`） |
| 20004 | 404 | **v2**：`#13/#14/#15`（pair 行不存在、超界 id、或任一端记录已删） |
| 20005 | 409 | **v2**：`#5`（无有效 MATCHED pair 就标完成，零写入） |
| 30001 | 400 | `POST /api/match`（`text` 无效） |
| 30002 | 503 | `POST /api/match`（引擎不可用，**不降级**） |
| 30003 | 504 | `POST /api/match`（引擎超时） |
| 40001 | 404 | `GET /api/records`、`POST /api/records`、`PUT /api/records/{id}`、`POST /api/match` |
| 40002 | 409 | `POST /api/records`、`PUT /api/records/{id}` |
| 50002 | 403 | **v2**：`#13/#14/#15`（`score < 0.75`，档位不足） |
| 50003 | 409 | **v2**：`#13`（`MATCHED` / `IGNORED` 终态再改） |
| 50004 | 404 | **v2**：`#17`（通知不存在） |
| 50005 | 403 | **v2**：`#13/#14/#15`（pair 失效：任一端 `CLOSED` 或 `COMPLETED` 过保留期） |
| 50006 | 409 | **v2**：`O2`（兜底扫描正在运行） |

`30004`（429 匹配过频）在架构基线 §6.2 登记为「当前不实现」，枚举里没有这个值。

## A.5 v1 → v2 的接口面变化

| 变化 | v1 | v2 | 影响哪个客户端 |
|---|---|---|---|
| 写接口鉴权 | 无，任何访客可 POST | `AuthGateFilter` 默认拒绝，401/`10006` | 所有写操作要先带 `JSESSIONID` |
| 身份来源 | 无 | `publisherId` / `viewerId` / pair 参与方判定全部取 session | 请求体里**没有也不需要**身份字段 |
| `contact` 校验 | ≤64 字自由格式 | `^1[3-9]\d{9}$` | 提交页要挂手机号格式提示 |
| 状态枚举 | `PENDING/CLAIMED/CLOSED` | `PENDING/COMPLETED/CLOSED`，`CLAIMED` → 400/`10001` | 老前端传 `CLAIMED` 会拿到校验错误而不是静默成功 |
| 详情可见性 | 一律 200 | `CLOSED` / 到期 / 非合格对端的 `COMPLETED` → 404/`20001` | 「点进去 404」是正常分支，列表要靠 `detailVisible` / `detailAccess` 提前告诉用户 |
| 输出内容 | 明文 | 五个出口统一遮蔽（§5.6） | 前端不许自己拼「`138****5678`」这类二次掩码 |
| 新增资源 | — | auth / my / pairs / notifications 共 8 个接口 + 1 个运维接口 | — |
