# find-it-campus v2 · API 契约（A1 冻结稿）

> 状态：**待门禁 0 冻结** · 版本 `v2.1`（2026-10-27 第二轮返工，按 vF 终态口径重写） · 日期 `2026-10-27`
> 任务：ZHYYKFX-45（A1 架构与契约 v2）
> 适用范围：v2 全部 REST 接口 + `web` 请求层。
> 关系：v1 契约见 `docs/api-contract.md`；**本文是 v2 的唯一契约**。v1 的 8 个业务接口（另有 `GET /api/health`）在 v2 全部保留，其中 7 个的鉴权/可见性/校验有变更（见 §0.2）。v1 与本文冲突时，以本文为准。
> 上游：`docs/design/v2-architecture.md`（数据模型、遮蔽算法、分档、LLM、三态/四态状态机）。本文只定义 HTTP 契约；实现细节回架构文档。
> v2.1 变量：`status ∈ {PENDING, COMPLETED, CLOSED}`（`CLAIMED` 作废）；`pairStatus ∈ {NEW, CONTACTED, MATCHED, IGNORED}`；配置项 `contact_direct_threshold` / `contact_unlock_threshold` / `completed_retention_days` / `completed_visible_threshold`。

---

## 0. 与 v1 的关系

### 0.1 接口数量

- **17 个业务接口**（v1 8 个业务接口 + v2 新增 9 个），逐接口见 §4，权限矩阵见 §3。
- **2 个运维接口**（`GET /api/health`、`POST /api/ops/pair-scan`）不属于 17 个业务接口，不参与三态矩阵，见 §4.18。
- 不开放注册接口；不存在“短信验证码”“找回密码”“自动下架”“pair 已解决”接口。

### 0.2 v2 对 v1 的破坏性/行为变更（下游必须知道）

| # | v1 行为 | v2 行为 | 影响 |
|---|---|---|---|
| 1 | 写接口匿名开放 | 发布/编辑/改状态/删除/匹配/我的启事/通知/pair 全部要求登录；列表、详情、分类保持匿名可读 | 所有写请求无 session → `401 + 10006` |
| 2 | 详情对所有人返回 contact/publisherName/description 原文 | 非所有者 `contact=null`、`publisherName` 打码、`description` 打码、`location` 截断；所有者原样 | 前端必须按 `isMine` 渲染遮蔽态 |
| 3 | `contact` 自由格式 | 新写入只接受 `^1[3-9]\d{9}$`；历史非手机号值仍可读 | 旧客户端提交字母/短号会 400 |
| 4 | `GET /api/match` 匿名可用 | 要求登录；输出候选的 title/location/reason 打码；外部引擎失败不自动降级 | 无 session → 401；引擎失败 503/504 |
| 5 | CORS `allowCredentials=false` | `allowCredentials=true` + 显式 origin；前端 `fetch` 必须带 `credentials:'include'` | 不带 credentials 时 session 不生效 |
| 6 | `t_record.updated_at` 由 DB `ON UPDATE` 自动改 | 只有本人写路径显式更新；系统配对任务不碰它 | A2 可用 `updated_at` 验证“系统不改记录” |
| 7 | 无 `publisher_id` | 新建记录必须绑定 session 用户；`t_user` 无 phone 列 | 非所有者越权可判定 |
| 8 | 无 pair/通知 | 新增 pair 状态、三档联系方式、通知 | 见 §4.13–§4.17 |
| 9 | 状态集 `PENDING/CLAIMED/CLOSED` | `PENDING/COMPLETED/CLOSED`；`COMPLETED` 需本记录已有一对 MATCHED，`PENDING→COMPLETED` 写 `completed_at` | 旧客户端传 `CLAIMED` → 400/10001；未达 MATCHED 标完成 → 409/20005 且不改 `status`/`updated_at` |
| 10 | pair 三态、`PATCH` 直传 `status` | pair 四态；`PATCH` 用 `action=CONTACT/CONFIRM/IGNORE`；`MATCHED` 由两端 `confirmed_at` 派生，客户端不能直传 | 前端要显示「已确认/等待对方确认」；`MATCHED` 后才能点「标记完成」 |
| 11 | 无 COMPLETED 可见性规则 | COMPLETED：公共列表/搜索 0 命中；保留期内进召回与 `/api/match`；详情仅 `pairUsable` 且 `score ≥ completed_visible_threshold` 的合格对端；到期后不召回、详情 404 | 列表/匹配/详情三套渲染；`/my` 用 `detailVisible` 决定是否可点 |
| 12 | 配置键 `direct-view-threshold` / `contact-auth-threshold` | 冻结为 `contact_direct_threshold` / `contact_unlock_threshold`，新增 `completed_retention_days` / `completed_visible_threshold`；同一配置类 | 老 yml 启动失败；旧键不读、不设别名 |

---

## 1. 通用约定

| 项 | v2 基线 | 备注 |
|---|---|---|
| Base path | `/api` | 不带版本段；v2 与 v1 同前缀，靠字段/行为区分 |
| 编码 | UTF-8，`Content-Type: application/json` | 请求/响应皆 JSON |
| 字段命名 | camelCase | DB `snake_case` 由 `mapUnderscoreToCamelCase` 桥接 |
| 时间格式 | `yyyy-MM-dd HH:mm:ss`（本地时间，无时区、无毫秒） | 与 `DATETIME` 对齐 |
| 布尔 | `true` / `false` | 不用 0/1 |
| 外层响应体 | `{code, message, data, requestId}` | 所有接口（含 401/403/404/500）统一 |
| 成功判定 | HTTP 2xx **且** `code === 0` | 前端拦截器唯一判据 |
| 分页请求 | `page`（默认 1，≥1）、`pageSize`（默认 10，1–50） | 不用 offset/limit |
| 分页响应 | `page · pageSize · total · totalPages · list · hasNext` | `total=0` 时 `totalPages=0`、`list=[]`、`hasNext=false` |
| 排序 | `sortBy ∈ {createdAt, occurredAt}`（默认 `createdAt`）、`sortOrder ∈ {asc, desc}`（默认 `desc`） | service 白名单；同值追加 `id DESC` |
| 空结果 | 200 + 空数组/空列表 | 不是 404 |
| 枚举 | `type ∈ {LOST,FOUND}`；`status ∈ {PENDING,COMPLETED,CLOSED}`；`pairStatus ∈ {NEW,CONTACTED,MATCHED,IGNORED}`；`pairState ∈ {PENDING,SCORING,READY,DEGRADED}`；`pairAction ∈ {CONTACT,CONFIRM,IGNORE}` | 后端只回枚举原值；中文文案前端集中映射；`CLAIMED` 已作废，收到即 400/10001 |
| id 类型 | JSON number（BIGINT 自增） | 当前量级 < 2^53 |
| 鉴权 | 服务端 session（`JSESSIONID` Cookie） | 见 §1.1 |
| 遮蔽 | 服务端单点 `MaskingPolicy`，规则见架构 §6 | 前端不得自行“还原”或拼接号码 |
| 联系方式 | **只从 `GET /api/pairs/{id}/contact` 返回对方号码** | 列表/详情/match/通知/pair 卡片都不带对方号码；**本人自己的 `contact` 仍在本人可见的详情/我的启事中返回**（C1 验收要求），非所有者一律 `null` |

### 1.1 认证、Cookie 与 CORS

- 登录成功：`Set-Cookie: JSESSIONID=<opaque>; Path=/; HttpOnly; SameSite=Lax`；生产环境（HTTPS）追加 `Secure`。
- 前端所有请求：`fetch(url, { credentials: 'include' })`；否则跨端口/代理场景不带 Cookie，表现为“登录成功但下一个请求 401”。
- CORS：`allowedOrigins = ["http://localhost:5173","http://127.0.0.1:5173"]`（显式，不能用 `*`）；`allowCredentials=true`；`allowedMethods=GET,POST,PUT,PATCH,DELETE,OPTIONS`；`allowedHeaders=Content-Type,X-Requested-With`。
- Session 超时 30 分钟；服务重启即失效（内存 session），属已知限制。
- CSRF：`SameSite=Lax` + 写操作只用 POST/PUT/PATCH/DELETE；v2 不引入 CSRF token（残余风险见架构 §11.2）。
- 401 统一出口：`code=10006`，`message="请先登录"`；登录失败也是 401，但 `message="学号或密码不正确"`（不区分账号/密码）。

### 1.2 遮蔽与身份

- 匿名 = 无有效 session。
- 登录非所有者 = 有 session，但 `session.userId != resource.publisherId`（pair 资源则是“不是两端任一 publisher”）。
- 所有者 = `session.userId == resource.publisherId`；pair 资源允许两端任一 publisher。
- 字段级规则见架构 §6.3；本契约只声明“哪些接口返回哪些字段、对谁遮蔽”。

### 1.3 权限三态定义（矩阵用；区别于记录三态）

| 态 | 定义 | 判定 |
|---|---|---|
| 匿名 | 无有效 session | `SecurityContext` 为空 |
| 登录非所有者 | 有 session，且不是目标资源的所有者/参与方/接收人 | 有用户、无所有权 |
| 合格对端 | 登录非所有者的子集：本人某条**未删除**启事与目标记录存在 `pairUsable` 的 pair，且该 pair `score ≥ completed_visible_threshold` | 只在 COMPLETED 详情用；不满足按 404，不区分“不存在”与“不够格” |
| 所有者 | 目标资源的 `publisher_id` = 当前用户；pair 为两端任一；通知为 `user_id`；`/my` 为本人集合 | service 层校验，不只看前端 |

> 对**集合接口**（`GET /api/records`、`GET /api/my/records`、`GET /api/notifications`），“登录非所有者”拿到的是**他自己作用域内的集合**，服务端按 `publisher_id`/`user_id` 过滤，拿不到别人的数据；因此矩阵为 200，而不是 403。矩阵备注会写清“自己的集合”。

---

## 2. 接口总览

| # | 方法 | 路径 | 用途 | 认证 | v1/v2 |
|---|---|---|---|---|---|
| 1 | GET | `/api/records` | 列表（筛选 + 分页 + 排序） | 匿名可读 | v1 保留，输出遮蔽 |
| 2 | GET | `/api/records/{id}` | 详情（所有者含 pairs） | 匿名可读 | v1 扩展 |
| 3 | POST | `/api/records` | 发布 | 需登录 | v1 行为变更 |
| 4 | PUT | `/api/records/{id}` | 全量修改 | 所有者 | v1 行为变更 |
| 5 | PATCH | `/api/records/{id}/status` | 状态流转 | 所有者 | v1 行为变更 |
| 6 | DELETE | `/api/records/{id}` | 逻辑删除 | 所有者 | v1 行为变更 |
| 7 | GET | `/api/categories` | 分类字典 | 公开 | v1 不变 |
| 8 | POST | `/api/match` | 同步匹配 | 需登录 | v1 行为变更 |
| 9 | POST | `/api/auth/login` | 登录 | 公开 | 新增 |
| 10 | POST | `/api/auth/logout` | 登出 | 公开（幂等） | 新增 |
| 11 | GET | `/api/auth/me` | 当前用户 | 需登录 | 新增 |
| 12 | GET | `/api/my/records` | 我的启事（含 pair 卡片） | 需登录 | 新增 |
| 13 | PATCH | `/api/pairs/{id}` | pair 状态 NEW→CONTACTED/IGNORED | 两端任一 | 新增 |
| 14 | GET | `/api/pairs/{id}/contact` | 查看对方联系方式（三档 + 审计） | 两端任一 | 新增 |
| 15 | POST | `/api/pairs/{id}/contact-unlock` | 授权公开 / 撤回本端联系方式 | 两端任一 | 新增 |
| 16 | GET | `/api/notifications` | 通知列表 + 未读数 | 需登录 | 新增 |
| 17 | PATCH | `/api/notifications/{id}/read` | 标记已读 | 接收人 | 新增 |
| O1 | GET | `/api/health` | 健康检查（运维） | 公开 | v1 保留，不计入 17 |
| O2 | POST | `/api/ops/pair-scan` | 手动兜底扫描（运维） | `X-Ops-Token` | 新增，不计入 17 |

---

## 3. 权限矩阵（17 × 3，逐格有状态码）

> 读法：`✅ 200` 允许；`❌ 401` 未登录；`❌ 403` 越权；`❌ 404` 资源不存在；`⚠️` 行内注明多结果规则。**没有空格，没有“视情况”。**
> “所有者”对集合接口 = 该集合中属于他的那些资源；他拿不到别人的记录/通知。

| # | 接口 | 匿名 | 登录非所有者 | 所有者 |
|---|---|---|---|---|
| 1 | `GET /api/records` | ✅ 200（只返回 `PENDING`，逐条遮蔽，无 contact/publisherName/description；COMPLETED/CLOSED 恒 0 命中） | ✅ 200（同一 `PENDING` 可见集合，`isMine=false`；传 `status=COMPLETED/CLOSED` 也是 200 + 空列表） | ✅ 200（本人 `PENDING` 明文、`isMine=true`；本人 COMPLETED/CLOSED **同样不在公共列表**，0 命中；管理视图走 #12） |
| 2 | `GET /api/records/{id}` | ✅ 200（`PENDING`，遮蔽）；COMPLETED/CLOSED/到期 → ❌ 404（20001） | ⚠️ `PENDING` → ✅ 200（遮蔽）；COMPLETED 且本人为**合格对端**（`pairUsable` 且 `score ≥ completed_visible_threshold`）→ ✅ 200（遮蔽，无 contact、无 pairs）；其余 COMPLETED / CLOSED / 到期 → ❌ 404（20001） | ⚠️ `PENDING` → ✅ 200（明文 + `pairs` + `allowedStatuses`）；COMPLETED → ❌ 404（20001，按架构 A9 字面）；CLOSED → ❌ 404（20001） |
| 3 | `POST /api/records` | ❌ 401 | ✅ 200（可发布；`publisherId` 取 session） | ✅ 200（同左） |
| 4 | `PUT /api/records/{id}` | ❌ 401 | ❌ 403（`PENDING`，或 COMPLETED 合格对端：可见但非所有者）；其余 COMPLETED/CLOSED → ❌ 404（20001） | ⚠️ `PENDING` → ✅ 200；COMPLETED → ❌ 409（20003）；CLOSED → ❌ 409（20003） |
| 5 | `PATCH /api/records/{id}/status` | ❌ 401 | ❌ 403（`PENDING`，或 COMPLETED 合格对端：可见但非所有者）；其余 COMPLETED/CLOSED → ❌ 404（20001） | ⚠️ `PENDING→COMPLETED` 已有有效 MATCHED → ✅ 200；无 MATCHED → ❌ 409（20005，零写入）；`PENDING→CLOSED` → ✅ 200；同值 / COMPLETED 再流转 / CLOSED 再流转 → ❌ 409（20002）；非法目标值 → ❌ 400（10001） |
| 6 | `DELETE /api/records/{id}` | ❌ 401 | ❌ 403（`PENDING`，或 COMPLETED 合格对端：可见但非所有者）；其余 COMPLETED/CLOSED → ❌ 404（20001） | ✅ 200（三态都可逻辑删除，不写 `status`）；已删除 → ❌ 404（20001） |
| 7 | `GET /api/categories` | ✅ 200 | ✅ 200 | ✅ 200 |
| 8 | `POST /api/match` | ❌ 401 | ✅ 200（候选 = `PENDING` 遮蔽 + 保留期内 COMPLETED 遮蔽并标 `detailAccess=RESTRICTED`；CLOSED 恒 0 命中；引擎失败 → ❌ 503/504） | ✅ 200（同左；候选仍遮蔽；引擎失败 → ❌ 503/504） |
| 9 | `POST /api/auth/login` | ✅ 200（成功）/ ❌ 401（凭证错） | ✅ 200（成功）/ ❌ 401（凭证错） | ✅ 200（成功）/ ❌ 401（凭证错） |
| 10 | `POST /api/auth/logout` | ✅ 200（幂等） | ✅ 200 | ✅ 200 |
| 11 | `GET /api/auth/me` | ❌ 401 | ✅ 200（返回本人） | ✅ 200（返回本人） |
| 12 | `GET /api/my/records` | ❌ 401 | ✅ 200（只返回自己的集合；三态都返回，带 `detailVisible`） | ✅ 200（只返回自己的集合；三态都返回，带 `detailVisible`） |
| 13 | `PATCH /api/pairs/{id}` | ❌ 401 | ❌ 403（10007，非参与方；不区分 pair 状态） | ⚠️ `action=CONTACT/CONFIRM/IGNORE` 且 pair 可用 → ✅ 200（同 action 幂等；CONFIRM 单端→CONTACTED、两端→MATCHED）；`score<contact_unlock_threshold` → ❌ 403（50002）；任一端 CLOSED 或 COMPLETED 已到期 → ❌ 403（50005）；记录已删除 → ❌ 404（20004）；MATCHED/IGNORED 再改 → ❌ 409（50003）；非法 action → ❌ 400（10001） |
| 14 | `GET /api/pairs/{id}/contact` | ❌ 401 | ❌ 403（10007，非参与方） | ⚠️ pair 可用且 `score ≥ contact_direct_threshold` → ✅ 200；`contact_unlock_threshold ≤ score < contact_direct_threshold` 且对方已授权 → ✅ 200；同档对方未授权 → ✅ 200（`available:false`）；`score < contact_unlock_threshold` → ❌ 403（50002）；任一端 CLOSED 或 COMPLETED 已到期 → ❌ 403（50005）；记录已删除 → ❌ 404（20004） |
| 15 | `POST /api/pairs/{id}/contact-unlock` | ❌ 401 | ❌ 403（10007，非参与方） | ⚠️ `PUBLISH` 且 `score ≥ contact_unlock_threshold` → ✅ 200；`PUBLISH` 且 `score < contact_unlock_threshold` → ❌ 403（50002）；`REVOKE` 任意分档 → ✅ 200；任一端 CLOSED 或 COMPLETED 已到期 → ❌ 403（50005）；记录已删除 → ❌ 404（20004） |
| 16 | `GET /api/notifications` | ❌ 401 | ✅ 200（只返回自己的集合） | ✅ 200（只返回自己的集合） |
| 17 | `PATCH /api/notifications/{id}/read` | ❌ 401 | ❌ 403（10007，他人通知） | ✅ 200；不存在 → ❌ 404（50004） |

**矩阵自检：** 17 行 × 3 格 = 51 格，每格都有状态码；`⚠️` 只出现在“同一请求内按记录状态/分数/action 分支”的行（#2/#4/#5/#13/#14/#15），分支条件与状态码都在格内写死，不是“视情况”。`GET /api/health` 与 `POST /api/ops/pair-scan` 不在此表。

**两处容易读错的点：** ①#2 的“合格对端”是**登录非所有者**的子集，不是第四种身份；不满足时统一 404，避免泄露 COMPLETED 是否存在。②#1 的所有者格是 200，但 `PENDING` 以外的记录**0 命中**——公共列表从不返回 COMPLETED/CLOSED，所有者要看自己的完成/关闭记录请走 #12。

---

## 4. 逐接口契约

> 所有成功响应都省略外层 `code/message/requestId` 的重复说明，只给 `data` 形状与关键状态码。错误响应统一为 `{code,message,data,requestId}`。

### 4.1 `GET /api/records` — 列表

**权限：** 匿名可读；输出按身份遮蔽；**可见集合恒为 `PENDING`**（COMPLETED/CLOSED 在公共列表 0 命中）。

**Query：**

| 参数 | 类型 | 必填 | 默认 | 校验 | 含义 |
|---|---|---|---|---|---|
| `type` | string | 否 | 全部 | `LOST`/`FOUND`，否则 10001 | 类型 |
| `status` | string | 否 | `PENDING` | `PENDING`/`COMPLETED`/`CLOSED`/`ALL`，否则 10001 | 过滤值合法即可；**`COMPLETED`/`CLOSED` 返回 200 + 空列表**（不是 400，也不是 403） |
| `categoryId` | number | 否 | 全部 | 存在且 `is_active=1`，否则 40001 | 分类 |
| `keyword` | string | 否 | 无 | 1–64 字；LIKE 转义 | 标题+描述检索 |
| `page` | number | 否 | 1 | ≥1 | 页码 |
| `pageSize` | number | 否 | 10 | 1–50 | 每页 |
| `sortBy` | string | 否 | `createdAt` | 白名单 | 排序 |
| `sortOrder` | string | 否 | `desc` | `asc`/`desc` | 方向 |

**`list[]` 字段：** `id,type,status,title,location,occurredAt,createdAt,categoryId,categoryName,isMine`。
匿名/非所有者：`title`/`location` 已打码；`isMine=false`；不返回 `description/contact/publisherName`。所有者行：`location` 原文、`isMine=true`；仍不返回 `contact`（列表最小暴露）。

**可见性硬约束：** SQL 恒加 `status='PENDING' AND deleted_at IS NULL`；`status=COMPLETED/CLOSED` 只是让调用方确认“它们确实不在公共列表”，不改变结果集。COMPLETED 的召回走 `/api/match` 与背景配对（§4.8），所有者管理走 `/api/my/records`（§4.12）。

**成功示例：**

```json
{
  "code": 0, "message": "ok", "requestId": "r-1001",
  "data": {
    "page": 1, "pageSize": 10, "total": 21, "totalPages": 3, "hasNext": true,
    "list": [
      {
        "id": 26, "type": "FOUND", "status": "PENDING",
        "title": "捡到一张校园卡", "location": "大学生活动中心…",
        "occurredAt": "2026-10-21 13:30:00", "createdAt": "2026-10-21 13:50:00",
        "categoryId": 4, "categoryName": "证件资料", "isMine": false
      }
    ]
  }
}
```

**错误：** `10001`（分页/枚举/排序/长度非法）、`40001`（分类不存在）。空结果 200 + `list=[]`。

**幂等：** 安全 GET，可缓存/重试；但响应随身份与遮蔽变化，`Cache-Control: private, no-store`。

---

### 4.2 `GET /api/records/{id}` — 详情（所有者含 pair 卡片）

**权限：** 匿名可读（仅 `PENDING`）；`PENDING` 对非所有者遮蔽；所有者明文 + `pairs`；`COMPLETED` 只对**合格对端**开放（遮蔽视图）；`CLOSED` 与到期 `COMPLETED` 对任何身份 404。

**Path：** `id` number，必填；非数字 → 400/10001；不存在/已删 → 404/20001。

**可见性判定（按顺序，先命中先返回）：**

```text
1. 记录不存在 / deleted_at 非空            → 404 + 20001
2. status = 'CLOSED'                       → 404 + 20001（任何身份，含所有者）
3. status = 'COMPLETED' 且已过保留期        → 404 + 20001（任何身份）
4. status = 'COMPLETED' 且未到期：
      viewer 是所有者                       → 404 + 20001（按架构 A9 字面；所有者走 /my）
      viewer 是合格对端（pairUsable 且 score ≥ 阈值） → 200（非所有者遮蔽视图：无 contact、无 pairs）
      其余                                  → 404 + 20001
5. status = 'PENDING'：
      viewer 是所有者                       → 200（明文 + pairs + allowedStatuses）
      其余（匿名/登录非所有者）              → 200（遮蔽；pairs 字段省略）
```

**`data` 字段：**

| 字段 | 类型 | 可空 | 匿名/非所有者 | 所有者 |
|---|---|---|---|---|
| `id` | number | 否 | 原值 | 原值 |
| `type`/`status` | string | 否 | 原值（`PENDING` 或 COMPLETED 的 200 视图） | 原值 |
| `title` | string | 否 | 打码 | 原值 |
| `description` | string | 否 | 打码 | 原值 |
| `categoryId`/`categoryName` | number/string | 是 | 原值 | 原值 |
| `location` | string | 否 | 截断 | 原值 |
| `occurredAt` | string | 否 | 原值 | 原值 |
| `completedAt` | string | 是 | `null`（非所有者看不到完成时间） | `COMPLETED` 时为标记时间；否则 `null` |
| `contact` | string | 是 | `null` | 本人原值 |
| `publisherName` | string | 是 | 打码（空→“匿名同学”） | 原值 |
| `allowedStatuses` | string[] | 否 | `[]` | `PENDING` 且有 MATCHED → `["COMPLETED","CLOSED"]`；`PENDING` 无 MATCHED → `["CLOSED"]`；`COMPLETED`/`CLOSED` → `[]` |
| `hasMatchedPair` | boolean | 否 | `false` | 是否已有一对有效 MATCHED（决定「标记完成」是否可点） |
| `matchedPairCount` | number | 否 | `0` | 本记录有效 MATCHED pair 数 |
| `isMine` | boolean | 否 | `false` | `true` |
| `pairState` | string | 是 | `null` | `PENDING/SCORING/READY/DEGRADED` |
| `lowConfidenceCount` | number | 否 | `0` | `<contact_unlock_threshold` 的 pair 数 |
| `pairs` | array | 否 | **字段省略** | `PairCard[]`，仅 `score≥contact_unlock_threshold` 且 `pairUsable` |
| `createdAt`/`updatedAt` | string | 否 | 原值 | 原值 |

> 合格对端看 COMPLETED 时，返回的是**非所有者遮蔽视图**：`description` 打码、`location` 截断、`contact=null`、`pairs` 省略、`lowConfidenceCount=0`。能看详情 ≠ 能看号码；号码仍只走 §4.14。

**所有者成功示例（节选）：**

```json
{
  "code": 0, "message": "ok", "requestId": "r-1002",
  "data": {
    "id": 1, "type": "LOST", "status": "PENDING",
    "title": "图书馆丢了黑色钱包", "description": "昨天下午在图书馆三楼…",
    "categoryId": 1, "categoryName": "钱包卡证",
    "location": "图书馆三楼自习区", "occurredAt": "2026-10-19 17:30:00",
    "completedAt": null,
    "contact": "13800000001", "publisherName": "李同学",
    "allowedStatuses": ["COMPLETED", "CLOSED"], "hasMatchedPair": true, "matchedPairCount": 1,
    "isMine": true,
    "pairState": "READY", "lowConfidenceCount": 1,
    "pairs": [
      {
        "pairId": 101, "score": 0.92, "scorePercent": 92, "tier": "DIRECT",
        "status": "MATCHED", "engine": "DEEPSEEK", "degraded": false,
        "lostConfirmedAt": "2026-10-27 09:00:00", "foundConfirmedAt": "2026-10-27 09:10:00",
        "counterpart": {
          "recordId": 13, "type": "FOUND", "title": "图书馆拾到黑色钱包",
          "location": "图书馆三楼自习区", "occurredAt": "2026-10-20 15:00:00",
          "createdAt": "2026-10-20 15:30:00", "status": "PENDING"
        },
        "mySide": "LOST", "myUnlocked": false, "otherUnlocked": false,
        "myNote": null, "otherNote": null,
        "contactAvailable": true, "contactHint": "匹配度较高，可直接查看"
      }
    ],
    "createdAt": "2026-10-20 09:10:00", "updatedAt": "2026-10-20 09:10:00"
  }
}
```

**错误：** `10001`（id 非数字）、`20001`（不存在/已删/CLOSED/到期 COMPLETED/非合格对端看 COMPLETED）。匿名不返回 401。

**幂等：** 安全 GET；无副作用（不写审计、不改 pair 状态）。

---

### 4.3 `POST /api/records` — 发布

**权限：** 登录；`publisher_id` 取 session，客户端传值被忽略。

**请求体：**

| 字段 | 类型 | 必填 | 校验 |
|---|---|---|---|
| `type` | string | 是 | `LOST`/`FOUND` |
| `title` | string | 是 | 2–64 字（去首尾空格） |
| `description` | string | 是 | 2–2000 字 |
| `categoryId` | number | 否 | 存在且启用；否则 40001/40002 |
| `location` | string | 是 | 1–128 字 |
| `occurredAt` | string | 是 | 严格格式；不得晚于 now+5min |
| `contact` | string | 否 | `^1[3-9]\d{9}$`；不合法 → 400/10001；不做短信验证 |
| `publisherName` | string | 否 | 1–32 字；为空时用 `displayName` 或 null |

> 不接受 `publisherId`、`status`、`completedAt`、`pairState`；服务端忽略这些字段（`status` 固定 `PENDING`，`completedAt` 固定 `null`，`pairState` 固定 `PENDING`）。`contact` 只做格式校验，UI 必须标注「自行填写，请自行核实」。

**成功：** 200，`data` 与详情同构（所有者视角），`status="PENDING"`、`completedAt=null`、`hasMatchedPair=false`、`matchedPairCount=0`、`allowedStatuses=["CLOSED"]`、`pairs=[]`、`pairState="PENDING"`、`lowConfidenceCount=0`；`message="已提交"`。

**错误：** `401/10006`、`10001`（含 `fieldErrors`）、`40001`、`40002`。

**幂等：** **非幂等**。重复提交产生两条记录；前端按钮 pending 禁用；后端不去重（业务上重复描述合法）。

---

### 4.4 `PUT /api/records/{id}` — 全量修改

**权限：** 仅所有者且记录为 `PENDING`；匿名 401；非所有者 `PENDING` → 403，COMPLETED 合格对端 → 403（可见但非所有者），其余 COMPLETED/CLOSED → 404；所有者 COMPLETED/CLOSED → 409/20003。

- 全量语义：字段与 §4.3 相同（`type` 也必填），未传的可选字段视为 null 覆盖。
- 不接受 `status`（走 §4.5）、`completedAt`、`publisherId`、`pairState`。
- 只有 `PENDING` 可编辑：`COMPLETED`/`CLOSED` → 409/20003（终态不可改内容，避免完成/关闭后内容与配对证据不一致）；不存在/已删 → 404/20001。
- 修改成功后：`updated_at=NOW()`，`pair_state=PENDING`，提交后触发该记录相关 pair 重算；**不清空**已有 pair 的 `status`/授权列/附言/`confirmed_at`。
- 响应：与详情同构（所有者视角）；此时 `pairs` 可能仍是旧分数，`pairState="PENDING"`（前端显示“分析中”）。

**错误：** `401/10006`、`403/10007`、`404/20001`、`10001`、`409/20003`、`40001`、`40002`。

**幂等：** 同一请求体重复提交内容等价；但每次都会更新 `updated_at` 并可能触发重算，**不是严格幂等**；前端不要自动重试写请求。

---

### 4.5 `PATCH /api/records/{id}/status` — 三态流转（标记完成 / 关闭）

**权限：** 所有者；匿名 401；非所有者 `PENDING` → 403/10007，COMPLETED 合格对端 → 403/10007（可见但非所有者），其余 COMPLETED/CLOSED → 404/20001（不可见记录不暴露存在性）。

**请求：** `{"status":"COMPLETED"}`；目标值**只允许** `COMPLETED` / `CLOSED`。`PENDING`、`CLAIMED`、`RESOLVED` 及其他值 → 400/10001。

| 当前 | 目标 | 前置 | HTTP | code | 落库 |
|---|---|---|---|---|---|
| `PENDING` | `COMPLETED` | 本记录已有**一对有效 MATCHED**（两端 `confirmed_at` 非空、两端未删除且非 CLOSED） | 200 | 0 | `status='COMPLETED'`, `completed_at=NOW()`, `updated_at=NOW()`（同一条 UPDATE） |
| `PENDING` | `COMPLETED` | 无上述 MATCHED pair | **409** | **20005** | **不执行 UPDATE**：`status`、`completed_at`、`updated_at` 全不变 |
| `PENDING` | `CLOSED` | 无 | 200 | 0 | `status='CLOSED'`, `updated_at=NOW()`；`completed_at` 保持 `NULL` |
| `PENDING` | `PENDING` | — | 409 | 20002 | 不写 |
| `COMPLETED` | `COMPLETED` / `CLOSED` | — | 409 | 20002 | 不写（终态） |
| `CLOSED` | `COMPLETED` / `CLOSED` | — | 409 | 20002 | 不写（终态） |

**成功 `data`：** `{id,status,completedAt,updatedAt,allowedStatuses,hasMatchedPair,matchedPairCount}`；`COMPLETED` 时 `message="已标记完成"`，`CLOSED` 时 `message="已关闭"`。

**409/20005 响应示例（未达 MATCHED 就点完成）：**

```json
{
  "code": 20005,
  "message": "需双方确认匹配后才能标记完成",
  "data": {"currentStatus": "PENDING", "required": "MATCHED_PAIR", "matchedPairCount": 0},
  "requestId": "r-2005"
}
```

**零写入的验收口径：** 调用前后各执行一次

```sql
SELECT status, completed_at, updated_at FROM t_record WHERE id = ?;
```

两行结果必须**完全一致**（`status` 仍 `PENDING`、`completed_at` 仍 `NULL`、`updated_at` 不变）。实现上前置检查在 `UPDATE` 之前，409 分支的事务里没有任何 DML。

**错误：** `401/10006`、`403/10007`、`404/20001`、`400/10001`（目标值非法）、`409/20005`（无 MATCHED）、`409/20002`（同值/终态再流转）。

**关键约束：**

- 这是 `t_record.status` 的**唯一写入路径**；`POST` 只初始化 `PENDING`，`PUT` 不接收 `status`，`DELETE` 只写 `deleted_at`。
- `PENDING→COMPLETED` 必须在同一条 UPDATE 里写 `completed_at`，不允许先写 status 再补时间（否则 `ck_record_completed_at` 会拒绝，且中途崩溃会留下 `COMPLETED` + `NULL` 的脏行）。
- **不触发任何 pair 变更**：不把任何 pair 改成 `IGNORED`/`MATCHED`，不写 `t_pair_score`；也不发通知（完成是本人动作）。
- `CLOSED` 的 pair 失效是读时谓词（架构 §9.3），本接口不批量改 pair 行。
- 系统任务（配对、扫描、到期清扫、通知）没有写 `status`/`completed_at` 的代码路径。

**幂等：** **不幂等**。同值再提交返回 409/20002（沿用 v1 口径），前端需先 GET 刷新；双击「标记完成」第二次会拿到 409，不是 200。

---

### 4.6 `DELETE /api/records/{id}` — 逻辑删除

**权限：** 所有者；匿名 401；非所有者 `PENDING` → 403/10007，COMPLETED 合格对端 → 403/10007（可见但非所有者），其余 COMPLETED/CLOSED → 404/20001。

- 无请求体；写 `deleted_at=NOW(), updated_at=NOW()`；**不写 `status`、不写 `completed_at`**（删除与三态正交：`PENDING`/`COMPLETED`/`CLOSED` 都可删，删了也不会变成 `CLOSED`）。
- 成功：200，`data=null`，`message="已删除"`（不用 204）。
- 已删除再删/不存在 → 404/20001。
- 删除后：列表/详情/匹配/我的启事不可见；该记录的 pair 不物理删，但 pair 卡片、`PATCH pair`、contact/unlock 均按 **404/20004**（记录已删）处理。
- 无“自动下架”：没有定时任务改 `deleted_at`；到期 COMPLETED 也不写 `deleted_at`。
- **为什么 CLOSED 还允许删除**：关闭是可见性终态，不是数据保留义务；本人撤回数据的权利优先于状态机终态。删除只写 `deleted_at`，不回滚任何 pair/通知。

**错误：** `401/10006`、`403/10007`、`404/20001`。
**幂等：** 第一次 200，第二次 404；前端不应自动重试。

---

### 4.7 `GET /api/categories` — 分类字典

**权限：** 公开；不变。
**Query：** `onlyActive` boolean 默认 `true`。
**成功：** 200，`data` 数组按 `sortOrder` 升序：`{id,code,name,sortOrder,active}`；不返回 `keywords`。
**错误：** 无业务错误；DB 异常 10005。
**幂等：** 安全 GET；分类字典只读，可缓存（`Cache-Control: private, max-age=300`）。

---

### 4.8 `POST /api/match` — 同步匹配

**权限：** 登录；匿名 401。非所有者/所有者都可调用，但候选输出对所有人遮蔽（候选是别人的记录）。

**请求体：** 同 v1：`text`（2–2000 字）、`type`（可选）、`categoryId`（可选）、`topN`（默认 5，1–20）、`minScore`（默认 0.3，0–1）、`excludeRecordId`（可选）。

**成功：** 200，`data` 同 v1：`matchId,engine,inputType,topN,minScore,tookMs,total,items[]`。
`items[].title/location/reason` 过同一打码器；**不返回 contact/publisherName**；`engine ∈ {LOCAL_KEYWORD, EXTERNAL_EMBEDDING}`（由 v1 `findit.match.engine` 配置决定；DeepSeek 只用于 pair 后台打分，不用于 `/api/match`）。

**候选可见性（v2.1 新增，必须按此实现）：**

- 候选池 = `MATCHABLE`：`deleted_at IS NULL AND (status='PENDING' OR (status='COMPLETED' AND completed_at > NOW() - INTERVAL completed_retention_days DAY))`；**CLOSED 一律 0 命中**。
- `items[]` 增两个字段：`recordStatus ∈ {PENDING, COMPLETED}`、`detailAccess ∈ {OPEN, RESTRICTED}`。`PENDING` 候选为 `OPEN`；`COMPLETED` 候选为 `RESTRICTED`（保留期内可命中，但点详情可能 404，前端不得把匹配结果当详情授权）。
- **列表隐藏 ≠ 匹配隐藏**（正文口径）：COMPLETED 在公共列表 0 命中，但保留期内仍进候选池；输出仍是遮蔽摘要，**无号码、无原文**。到期后该记录从候选池消失（0 命中）。
- CLOSED 记录的 pair 一并失效：即使文本相似，也不会出现在 `/api/match` 结果里。

**错误：** `401/10006`、`10001`、`30001`、`30002`（503，引擎不可用）、`30003`（504，超时）、`40001`、`10005`。

**降级（ADR-009）：** **不自动降级**。外部引擎空 Key/401/超时/非 JSON/分数越界 → 503/30002 或 504/30003；**不得**静默换成关键词分数。与 pair 链路的差异见架构 §8.6。

**幂等：** 非幂等；每次成功调用写一条 `t_match_result`（含空结果），`matchId` 不同。`input_text`/`candidates` 写库前已打码。

---

### 4.9 `POST /api/auth/login` — 登录

**权限：** 公开；不开放注册。

**请求：** `{"studentNo":"20230001","password":"<口令>"}`；两者 1–32/1–64 字，缺失 → 400/10001。

**成功：** 200，`data={id,studentNo,displayName}`；响应头 `Set-Cookie: JSESSIONID=…; Path=/; HttpOnly; SameSite=Lax`（生产加 `Secure`）；`message="登录成功"`。

**错误：**

| 场景 | HTTP | code | 说明 |
|---|---|---|---|
| 账号不存在/密码错/停用 | 401 | 10006 | 统一 `message="学号或密码不正确"`，不区分 |
| 参数缺失/超长 | 400 | 10001 | `fieldErrors` |

> **登录失败计数不做账号锁定，也不返回 429**：同一 `studentNo+IP` 15 分钟内失败 5 次只写 WARN 日志/计数；响应始终 401 + 同一文案（与 B2 验收“始终 401、文案一致”对齐）。生产环境如需限流，在反向代理/IP 层做，不改变本契约。

**安全：** bcrypt 校验；session 固定防护；不记录口令；日志不打印请求体。

**幂等：** 已登录再登录返回 200 并轮换 session id；失败不改变 `password_hash`。

---

### 4.10 `POST /api/auth/logout` — 登出

**权限：** 公开；幂等。
**请求：** 无。
**成功：** 200，`data=null`，`message="已退出"`；`invalidate()` + 清 Cookie。
**错误：** 无业务错误；DB 异常 10005。
**幂等：** 未登录调用也 200；重复调用 200。

---

### 4.11 `GET /api/auth/me` — 当前用户

**权限：** 登录；匿名 401。
**成功：** 200，`data={id,studentNo,displayName}`。
**错误：** `401/10006`（无 session/过期/重启后）。
**幂等：** 安全 GET。

---

### 4.12 `GET /api/my/records` — 我的启事（含 pair 卡片）

**权限：** 登录；匿名 401。只返回 `publisher_id = session.userId` 的记录。

**Query：**

| 参数 | 默认 | 说明 |
|---|---|---|
| `status` | `ALL` | `PENDING`/`COMPLETED`/`CLOSED`/`ALL`；**三态都属于本人数据，全部可查** |
| `type` | 全部 | `LOST/FOUND` |
| `page`/`pageSize` | 1/10 | 同通用分页 |
| `sortBy`/`sortOrder` | `createdAt`/`desc` | 白名单 |

**`list[]` 字段（所有者视角）：**

| 字段 | 类型 | 说明 |
|---|---|---|
| `id,type,status,title,categoryId,categoryName,location,occurredAt,createdAt,updatedAt` | — | 本人记录，原文 |
| `completedAt` | string/null | `COMPLETED` 时非空；保留期起点 |
| `contact`/`publisherName` | string/null | 本人原值 |
| `pairState` | string | `PENDING/SCORING/READY/DEGRADED` |
| `pairCount` | number | 该记录**有效** pair 行数（含 `<contact_unlock_threshold`；不含已删/CLOSED/到期失效） |
| `relatedCount` | number | `score≥contact_unlock_threshold` 且 `pairUsable` 的 pair 数（卡片数） |
| `lowConfidenceCount` | number | `<contact_unlock_threshold` 的 pair 数（只给条数） |
| `hasMatchedPair` | boolean | 是否已有一对有效 MATCHED（决定「标记完成」可点） |
| `matchedPairCount` | number | 有效 MATCHED pair 数 |
| `detailVisible` | boolean | `PENDING=true`；`COMPLETED/CLOSED=false`（COMPLETED 按 A9 字面，所有者详情也 404；前端据此不渲染详情入口） |
| `pairs` | array | `PairCard[]`，仅 `score≥contact_unlock_threshold` 且 `pairUsable`；`relatedCount=0` 时 `[]` |

**`status` → 前端文案（D2 用）：**

| `status` | 文案 | 可做的操作 |
|---|---|---|
| `PENDING` | 「进行中」 | 编辑、标记完成（`hasMatchedPair=true` 才可点）、关闭、删除 |
| `COMPLETED` | 「已完成（yyyy-MM-dd HH:mm）」 | 删除；**不渲染详情入口**（`detailVisible=false`）；到期后 pair 卡片消失 |
| `CLOSED` | 「已关闭」 | 删除；**不渲染详情入口** |

**`pairState` → 文案：** `PENDING/SCORING` → 「分析中」；`READY` → 「已出结果」或「N 条可能相关」；`DEGRADED` → 「已降级」+「智能匹配暂不可用，已用关键词匹配」。

> **计数口径（D2 必读）：** 若「N 条可能相关」按 `t_pair_score` 行数统计，用 `pairCount`（含 `<contact_unlock_threshold`）；**卡片数**用 `relatedCount`；`lowConfidenceCount` 单独显示「另有 N 条低分结果（仅计数）」。三个数不要混用。`CLOSED` 记录的对端 pair、到期 COMPLETED 的 pair 不计入任何计数。前端文案统一用「可能相关」，不得自造同义词。

**成功示例（节选）：**

```json
{
  "code": 0, "message": "ok", "requestId": "r-1012",
  "data": {
    "page": 1, "pageSize": 10, "total": 1, "totalPages": 1, "hasNext": false,
    "list": [
      {
        "id": 1, "type": "LOST", "status": "COMPLETED",
        "title": "图书馆丢了黑色钱包", "categoryId": 1, "categoryName": "钱包卡证",
        "location": "图书馆三楼自习区", "occurredAt": "2026-10-19 17:30:00",
        "completedAt": "2026-10-27 09:20:00",
        "contact": "13800000001", "publisherName": "李同学",
        "pairState": "READY", "pairCount": 2, "relatedCount": 1, "lowConfidenceCount": 1,
        "hasMatchedPair": true, "matchedPairCount": 1, "detailVisible": false,
        "pairs": [ { "pairId": 101, "score": 0.92, "tier": "DIRECT", "status": "MATCHED", "myConfirmed": true, "otherConfirmed": true } ],
        "createdAt": "2026-10-20 09:10:00", "updatedAt": "2026-10-27 09:20:00"
      }
    ]
  }
}
```

> 上面的 `pairs[0]` 是节选；完整 `PairCard` 字段见 §5.1。

**错误：** `401/10006`、`10001`。
**幂等：** 安全 GET。

---

### 4.13 `PATCH /api/pairs/{id}` — pair 四态（联系 / 确认 / 忽略）

**权限：** 两端任一 `publisher_id`；匿名 401；非参与方 403/10007；`score < contact_unlock_threshold` → 403/50002；任一端 CLOSED 或 COMPLETED 到期 → 403/50005；任一端记录已删 → 404/20004。

**请求：** `{"action":"CONFIRM"}`；`action ∈ {CONTACT, CONFIRM, IGNORE}`；**不接收 `status`**，尤其**不能直传 `MATCHED`**（服务端派生）。

**判定顺序：** ①pair 不存在/任一端记录已删 → 404/20004；②非参与方 → 403/10007；③`pairUsable=false`（任一端 CLOSED 或 COMPLETED 到期）→ 403/50005；④`score < contact_unlock_threshold` → 403/50002；⑤按 action 与当前状态走下表（非法 action → 400/10001；终态再改 → 409/50003）。

| 当前 | `action` | 结果 | 说明 |
|---|---|---|---|
| `NEW` | `CONTACT` | 200 | `status='CONTACTED'` |
| `NEW` | `CONFIRM` | 200 | 调用者拥有的端写 `confirmed_at`；两端齐 → `MATCHED`，否则 `CONTACTED` |
| `NEW` | `IGNORE` | 200 | `status='IGNORED'` |
| `CONTACTED` | `CONTACT` | 200（幂等） | 不写，`updatedAt` 不变 |
| `CONTACTED` | `CONFIRM` | 200 | 写调用者端；两端齐 → `MATCHED` |
| `CONTACTED` | `IGNORE` | 200 | `status='IGNORED'` |
| `MATCHED` | `CONFIRM` | 200（幂等） | 不写，`confirmedAt` 不刷新 |
| `MATCHED` | `CONTACT` / `IGNORE` | 409 | `50003` |
| `IGNORED` | `IGNORE` | 200（幂等） | 不写 |
| `IGNORED` | `CONTACT` / `CONFIRM` | 409 | `50003` |

**一次 `CONFIRM` 写哪些端：** 调用者在该 pair 拥有的所有端（LOST 端 / FOUND 端；两端同主就都写）。已非空的 `confirmed_at` 不改。因此「同一人拥有两端」时一次请求即 `MATCHED`，不需要 `side` 参数。

**成功：** 200，`data={pairId,status,matched,mySide,myConfirmed,otherConfirmed,lostConfirmedAt,foundConfirmedAt,updatedAt}`；`matched = (status == 'MATCHED')`；`message`：`已联系` / `已确认，等待对方确认` / `已完成双方确认` / `已忽略`。

**错误：**

| 场景 | HTTP | code |
|---|---|---|
| 非参与方 | 403 | 10007 |
| `score < contact_unlock_threshold` | 403 | 50002 |
| 任一端 CLOSED / COMPLETED 到期（pair 失效） | 403 | 50005 |
| pair 不存在 / 任一端记录已删 | 404 | 20004 |
| `action` 非法，或请求体带 `status` | 400 | 10001 |
| MATCHED / IGNORED 再改 | 409 | 50003 |

**状态机：** `NEW→CONTACTED`（CONTACT 或单端 CONFIRM）、`NEW→MATCHED`（两端 CONFIRM）、`NEW→IGNORED`；`CONTACTED→MATCHED` / `CONTACTED→IGNORED`；`MATCHED` 与 `IGNORED` 为终态。**不存在「已解决」态**；`MATCHED` 不是客户端可写值，由两端 `confirmed_at` 派生。

**不联动：** 不写 `t_record.status`、不写 `t_record.completed_at`、不写 `t_record.updated_at`、不更新其他 pair、不发通知（`CONFIRM` 的完成可能触发前端刷新「标记完成」按钮，但不改记录状态）。A、B 两条 pair 各自独立。

**幂等：** 同 `action` 重复提交 200 且不写库（`updatedAt` 不变）；`CONFIRM` 重复不刷新 `confirmed_at`。**不幂等的是“改主意”**：终态再改一律 409/50003，接口不提供撤销。

---

### 4.14 `GET /api/pairs/{id}/contact` — 查看对方联系方式

**权限：** 两端任一；匿名 401，非参与方 403/10007；`score < contact_unlock_threshold` 403/50002；任一端 CLOSED 或 COMPLETED 到期 → 403/50005；任一端记录已删 → 404/20004。

**判定顺序：** ①pair 不存在/记录已删 → 404/20004；②非参与方 → 403/10007；③`pairUsable=false`（任一端 CLOSED 或 COMPLETED 到期）→ **403/50005**；④`score < contact_unlock_threshold` → 403/50002；⑤按档位/对方授权返回 200（见下表）。CLOSED 记录的 pair 取号必须是 403，不是 404（正文口径）。

**请求：** 无 body；path `id`。
**响应头：** `Cache-Control: no-store`（每次查看都写审计，不能缓存）。

**`data`：**

| 字段 | 类型 | 说明 |
|---|---|---|
| `pairId` | number | pair id |
| `score`/`scorePercent` | number/integer | 当前分数/百分比 |
| `tier` | string | `DIRECT`（≥`contact_direct_threshold`）/ `AUTHORIZABLE`（`contact_unlock_threshold`–直看档）/ `LOW`（<下界，仅 403 时内部使用，不返回成功体） |
| `viewerSide`/`targetSide` | string | `LOST`/`FOUND`。同一用户同时拥有两端时 `viewerSide` 固定为 `LOST`（确定性规则，避免歧义） |
| `available` | boolean | 是否能看对方号码 |
| `contact` | string/null | `available=true` 时为对方号码；否则 null |
| `reason` | string/null | `NOT_PUBLIC` / `NOT_PROVIDED` / null |
| `message` | string | 人类可读：`对方尚未公开` / `对方未填写联系方式` / `对方已公开联系方式` / `匹配度较高，可直接查看` |
| `unlockedAt` | string/null | 对方端授权时间；直看且未授权时为 null |
| `note` | string/null | 对方端附言（已打码）；仅 `available=true` 且对方授权时返回 |

**成功示例（未授权，HTTP 200）：**

```json
{
  "code": 0, "message": "ok", "requestId": "r-1014",
  "data": {
    "pairId": 101, "score": 0.80, "scorePercent": 80, "tier": "AUTHORIZABLE",
    "viewerSide": "LOST", "targetSide": "FOUND",
    "available": false, "contact": null, "reason": "NOT_PUBLIC",
    "message": "对方尚未公开", "unlockedAt": null, "note": null
  }
}
```

**成功示例（已授权）：**

```json
{
  "code": 0, "message": "ok", "requestId": "r-1015",
  "data": {
    "pairId": 101, "score": 0.80, "scorePercent": 80, "tier": "AUTHORIZABLE",
    "viewerSide": "LOST", "targetSide": "FOUND",
    "available": true, "contact": "13800000002", "reason": null,
    "message": "对方已公开联系方式", "unlockedAt": "2026-10-27 10:00:00",
    "note": "周六下午在图书馆门口等"
  }
}
```

**错误：** `401/10006`、`403/10007`（非参与方）、`403/50002`（档位不足）、`403/50005`（任一端 CLOSED / COMPLETED 到期，pair 失效）、`404/20004`（pair 不存在/任一端已删）。
**审计：** 每次调用写一条 `t_access_log`：`GRANTED` / `DENIED_NOT_PUBLIC` / `DENIED_NOT_PROVIDED` / `DENIED_LOW_SCORE` / `DENIED_NOT_OWNER` / `DENIED_PAIR_INVALID`；写失败返回 500，不出号。
**幂等：** 安全 GET 但**有审计副作用**；重试会多一条审计，这是预期行为（每次查看都要留痕）。

---

### 4.15 `POST /api/pairs/{id}/contact-unlock` — 授权公开 / 撤回

**权限：** 两端任一；匿名 401，非参与方 403/10007；任一端 CLOSED 或 COMPLETED 到期 → 403/50005；任一端记录已删 → 404/20004。

**请求：**

| 字段 | 类型 | 必填 | 校验 |
|---|---|---|---|
| `action` | string | 否 | 默认 `PUBLISH`；`PUBLISH`（公开本端）/ `REVOKE`（撤回本端）；其他 → 400/10001 |
| `note` | string | 否 | 0–200 字；`PUBLISH` 时可带；超 200 → 400/10001；写入前过打码；`REVOKE` 时忽略并清空 |

**语义：**

- `action` 省略时按 `PUBLISH` 处理（兼容 E1 只发 `{note}` 的调用）。
- `PUBLISH`：把**当前用户这一端**的 `lost_unlocked_at` 或 `found_unlocked_at` 设为 now（已公开则保持原时间），附言写本端 `*_unlock_note`（打码后）；`score≥contact_unlock_threshold` 才允许；成功后给对端写 `PAIR_UNLOCK` 通知（唯一键幂等）。
- `REVOKE`：把本端 `unlocked_at=null`、`*_unlock_note=null`；**分数任意（含低于 `contact_unlock_threshold`）都允许**（分数掉下去也应能撤回同意）。pair 已失效（CLOSED/到期）时接口返回 403/50005：对端已看不到号码，撤回不再影响可见性。
- 不改 `pair.status`、不改 `pair.confirmed_at`、不改 `t_record.status`、不泄露对方号码。

**成功：** 200，`data={pairId,side,public,unlockedAt,note}`；`side ∈ {LOST,FOUND}`；`public=true/false`；`note` 为打码后的值或 null。同一用户同时拥有两端时，`side` 固定为 `LOST`（与 §4.14 的确定性规则一致）。

**错误：**

| 场景 | HTTP | code |
|---|---|---|
| 非参与方 | 403 | 10007 |
| `PUBLISH` 且 `score < contact_unlock_threshold` | 403 | 50002 |
| 任一端 CLOSED / COMPLETED 到期（pair 失效） | 403 | 50005 |
| pair 不存在 / 任一端记录已删 | 404 | 20004 |
| `action` 非法 / note 超 200 | 400 | 10001 |

> **撤回与失效的边界：** CLOSED/到期后 pair 失效，`REVOKE` 也返回 403/50005；此时对端本来就看不到号码，撤回不再有安全意义。`REVOKE` 在一条 UPDATE 里把本端 `*_unlocked_at` 与 `*_unlock_note` 同时置空，**附言随撤回清空**；`t_access_log` 只记查看事实，不复制附言或号码。

**幂等：** `(pair,side,action,noteHash)` 自然幂等：重复 `PUBLISH` 同 note 返回 200 且 `unlockedAt` 不变、不重复通知；重复 `REVOKE` 返回 200 且保持 null。

---

### 4.16 `GET /api/notifications` — 通知列表

**权限：** 登录；只返回 `user_id = session.userId` 的通知。

**Query：** `page`（默认 1）、`pageSize`（默认 10，1–50）。

**`data`：** 在通用分页字段外增加 `unreadCount`（全部未读数，不是本页）：

```json
{
  "code": 0, "message": "ok", "requestId": "r-1016",
  "data": {
    "unreadCount": 3, "page": 1, "pageSize": 10, "total": 3, "totalPages": 1, "hasNext": false,
    "list": [
      {
        "id": 9001, "type": "PAIR_MATCH", "pairId": 101,
        "title": "有新的可能相关",
        "body": "有新的可能相关，请到「我的启事」查看",
        "read": false, "createdAt": "2026-10-27 10:00:00"
      }
    ]
  }
}
```

**字段：** `id,type,pairId,title,body,read,createdAt`；`type ∈ {PAIR_MATCH,PAIR_UNLOCK,SYSTEM}`；`body/title` 写入前已打码，**不含手机号**。
**失效 pair 的通知：** 记录 CLOSED / COMPLETED 到期后，历史通知不删除、仍返回（通知是已发生的投递事实）；点进对应 pair 会得到 403/50005，前端提示「该配对已失效」即可。
**错误：** `401/10006`、`10001`。
**幂等：** 安全 GET。

---

### 4.17 `PATCH /api/notifications/{id}/read` — 标记已读

**权限：** 接收人；他人通知 403；不存在 404。

**请求：** 无 body。
**成功：** 200，`data={id,read:true,readAt,unreadCount}`；`unreadCount` 为更新后的总未读数（方便顶栏红点直接刷新）。
**错误：** `401/10006`、`403/10007`、`404/50004`。
**幂等：** 已读再读返回 200，`readAt` 保持第一次的时间；不重复改。

---

### 4.18 运维接口（不计入 17）

#### `GET /api/health`

公开；200 `{status:"UP",db:"UP",version:"…",engine:"…"}`；DB 不可用 → 503 + `10005`，`data.db="DOWN"`，不 hang。

#### `POST /api/ops/pair-scan`

- 仅当环境变量 `OPS_SCAN_TOKEN` 非空时启用；请求头 `X-Ops-Token` 必须匹配，否则 **404**（隐藏入口）。
- 成功：200，`data={scanned,rescored,degraded,skipped,expired,limited,elapsedMs}`。
- 实现另附 `trigger`（`http/scan`）与 `counters`（进程内计数器快照），属于排障附加信息，不参与扫描结果口径。
- 并发：已有扫描在跑 → 409，body `code=50006`（`PAIR_SCAN_IN_PROGRESS`），`message=兜底扫描正在运行，请稍后重试`。
- 不写 `t_record.status/deleted_at/updated_at`；只补配对与通知。
- 不属于 17 个业务接口，不参与三态矩阵。

---

## 5. 响应对象

### 5.1 `PairCard`（内嵌在详情/我的启事）

| 字段 | 类型 | 说明 |
|---|---|---|
| `pairId` | number | `t_pair_score.id` |
| `score` | number | 0–1，4 位小数（展示用 `scorePercent`） |
| `scorePercent` | integer | `round(score*100)`，后端算好 |
| `tier` | string | `DIRECT`（≥`contact_direct_threshold`）/ `AUTHORIZABLE`（`contact_unlock_threshold`–直看档）；`<contact_unlock_threshold` 不出卡片 |
| `status` | string | `NEW` / `CONTACTED` / `MATCHED` / `IGNORED` |
| `matched` | boolean | `status == 'MATCHED'`（两端均确认；前端据此点亮「标记完成」入口） |
| `myConfirmed` / `otherConfirmed` | boolean | 本端 / 对方端是否已确认 |
| `lostConfirmedAt` / `foundConfirmedAt` | string/null | 两端确认时间；未确认为 null |
| `engine` | string | `DEEPSEEK` / `LOCAL_KEYWORD` |
| `degraded` | boolean | `engine == 'LOCAL_KEYWORD'`（pair 链路默认 LLM，本地即降级） |
| `counterpart` | object | `{recordId,type,title,location,occurredAt,createdAt,status}`；`title/location` 已打码；`status` 让前端知道对端是否 CLOSED/COMPLETED |
| `mySide` | string | `LOST` / `FOUND`（当前记录在 pair 中的一端） |
| `myUnlocked` | boolean | 本端是否已公开 |
| `otherUnlocked` | boolean | 对方端是否已公开 |
| `myNote` | string/null | 本端附言（已打码） |
| `otherNote` | string/null | 对方附言（已打码）；仅 `otherUnlocked` 或 `tier=DIRECT` 时返回，否则 null |
| `contactAvailable` | boolean | 当前是否可看对方号码（用于按钮可用性） |
| `contactHint` | string | `对方尚未公开` / `对方未填写联系方式` / `对方已公开联系方式` / `匹配度较高，可直接查看` |

> **`PairCard` 永远不含 `contact` 字段。** 号码只从 §4.14 取；`contactAvailable` 只是按钮状态，不是号码。
> `contactAvailable = (tier=DIRECT 或 otherUnlocked) 且对方 contact 非空`；`contactHint` 按此给「匹配度较高，可直接查看 / 对方已公开联系方式 / 对方尚未公开 / 对方未填写联系方式」。
> **失效 pair 不出卡片：** 任一端 CLOSED、任一端 COMPLETED 已到期、任一端已删除 → 该 pair 从 `pairs[]` 移除（`pairCount/relatedCount` 同步不计）；`MATCHED` 卡片照常显示，并带两端确认时间。

### 5.2 `RecordDetail`

见 §4.2 字段表；`pairs` 仅在 `isMine=true` 时出现，否则字段省略（不是空数组），前端用 `isMine` 判断。

### 5.3 `ContactView`

见 §4.14 字段表。

### 5.4 `PageResult`

`{page,pageSize,total,totalPages,list,hasNext}`；`/api/notifications` 额外 `unreadCount`；`/api/my/records` 的 `list[]` 是 `MyRecordItem`。

---

## 6. 错误码

| code | HTTP | 含义 | 出现接口 |
|---|---|---|---|
| 0 | 200 | 成功 | 全部 |
| 10001 | 400 | 参数校验失败 | 全部带参数接口 |
| 10002 | 413 | 请求体过大 | 任意带 body 接口 |
| 10003 | 404 | 路径不存在 | 任意 |
| 10004 | 405 | 方法不支持 | 任意 |
| 10005 | 500/503 | 未预期异常/依赖不可用 | 全部；`/api/health` DB DOWN 为 503 |
| 10006 | 401 | 未登录/会话失效/凭证错误 | 写接口、me、my、notifications、pair |
| 10007 | 403 | 越权（非所有者/非参与方/非接收人） | 记录写、pair、通知已读 |
| 20001 | 404 | 记录不存在、已删除或按可见性不可见 | records 详情/写；COMPLETED 对不合格查看者、CLOSED 与到期 COMPLETED 对任何身份 |
| 20002 | 409 | 记录状态流转非法 | status（同值、终态再流转） |
| 20003 | 409 | 记录当前状态不可执行该操作 | PUT（非 PENDING） |
| 20004 | 404 | 配对不存在或任一端记录已删除 | pair（**CLOSED 走 50005，不用 20004**） |
| 20005 | 409 | 标记完成前置未满足（无有效 MATCHED pair） | status（`PENDING→COMPLETED`）；零写入，status/updated_at 不变 |
| 30001 | 400 | 匹配输入无效 | match |
| 30002 | 503 | 匹配引擎不可用 | match（**不降级**） |
| 30003 | 504 | 匹配引擎超时 | match（**不降级**） |
| 40001 | 404 | 分类不存在 | records/match |
| 40002 | 409 | 分类停用不可选 | records |
| 50002 | 403 | 档位不足（score<contact_unlock_threshold） | contact / contact-unlock / PATCH pair |
| 50003 | 409 | 配对状态流转非法 | PATCH pair（MATCHED/IGNORED 再改） |
| 50004 | 404 | 通知不存在 | notification read |
| 50005 | 403 | 配对因记录终态失效 | 任一端 CLOSED / COMPLETED 到期：contact / contact-unlock / PATCH pair |
| 50006 | 409 | 兜底扫描正在运行 | ops/pair-scan（body code；契约 §4.18） |

**403 两个 code 必须分流：** `50002` = 分数不够；`50005` = 记录已关闭/已完成到期（分数再高也不行）。前端文案不同，不能合并成“无权限”。

**409 三个 code 必须分流：** `20002` = 状态机不允许（同值、终态）；`20003` = 非 PENDING 还调 `PUT`；`20005` = 未达 MATCHED 点「标记完成」（`data` 带前置说明，且 `status`/`updated_at` 未变）。

**「对方尚未公开」不是错误码**：HTTP 200 + `code=0` + `data.available=false, message="对方尚未公开"`。前端不得把它当 403 或异常。

**错误响应示例（403 越权）：**

```json
{"code":10007,"message":"无权操作该资源","data":null,"requestId":"r-2001"}
```

**错误响应示例（400 参数）：**

```json
{
  "code": 10001,
  "message": "请求参数校验失败",
  "data": {"fieldErrors": [{"field": "note", "reason": "长度需在 0 到 200 之间"}]},
  "requestId": "r-2002"
}
```

---

## 7. 幂等与重试（前端/测试口径）

| 接口 | 幂等 | 重试建议 | 重复调用的副作用 |
|---|---|---|---|
| `GET` 全部 | 安全 | 可重试 | 无；contact 每次写审计（预期） |
| `POST /api/records` | 否 | 不自动重试 | 产生多条记录 |
| `PUT /api/records/{id}` | 内容幂等 | 不自动重试 | 每次更新 `updated_at`，可能触发重算 |
| `PATCH /records/{id}/status` | 否（同值 409；无 MATCHED → 409/20005） | 先 GET 再决定 | 409 时零写入：`status`/`completed_at`/`updated_at` 不变 |
| `DELETE /records/{id}` | 否（第二次 404） | 不自动重试 | 第一次写 `deleted_at` |
| `POST /api/match` | 否 | 用户手动 | 每次写一条快照 |
| `POST /api/auth/login` | 是（成功轮换 session） | 失败不自动重试；前端可提示再试 | 新 session |
| `POST /api/auth/logout` | 是 | 可重试 | 清 session |
| `PATCH /api/pairs/{id}` | 同 action 200（不写库）；MATCHED/IGNORED 再改 409 | 可重试同 action | 只改该 pair；`CONFIRM` 不刷新 `confirmed_at` |
| `POST /api/pairs/{id}/contact-unlock` | 是（自然键） | 可重试 | 首次 PUBLISH 写通知；重复不重复通知 |
| `PATCH /notifications/{id}/read` | 是 | 可重试 | 只改该行 |

**通用：** 前端 `http.ts` 对 5xx/网络失败只提示、不自动重放写请求；`requestId` 必须显示在错误 toast 里。后端不实现“客户端幂等键”（v2 范围外；`POST /api/records` 的双击重复由前端 pending 禁用兜底）。

---

## 8. 前端接入注意（B3/C2/D2/E2 必读）

1. **credentials：** 所有请求 `credentials:'include'`；否则登录后仍 401。
2. **401：** 清本地登录态，跳 `/login?redirect=<当前路径>`；`/api/auth/me` 的 401 是“未登录”，不要 toast 成系统错误。
3. **403 三路：** `10007` 越权/非参与方；`50002` 档位不足；`50005` 记录已关闭/已完成到期。三者文案不同，不要合并成一个“无权限”。
4. **`available:false`：** contact 接口 200 时按 `data.message` 渲染「对方尚未公开」，按钮置灰；不要抛错、不要显示“无权限”。
5. **号码：** 只从 contact 接口的 `data.contact` 取；pair 卡片/详情/列表/通知都没有号码。禁止前端“拼号码/还原掩码”。
6. **三档：** `tier=DIRECT` 按钮可用；`AUTHORIZABLE` 且 `otherUnlocked=false` 显示「对方尚未公开」；低于 `contact_unlock_threshold` 只显示「存在 N 条可能相关」（用 `lowConfidenceCount`），不渲染卡片。
7. **多 pair：** 逐条渲染，按 `pairId` 操作；某条 `CONTACTED`/`CONFIRM` 后只刷新该条，其他条状态不变；页面禁止出现「已解决」。
8. **降级：** `degraded=true` 的卡片显示「已降级」+「智能匹配暂不可用，已用关键词匹配」；`/api/match` 的 503/504 不走这个文案（那是“匹配服务暂不可用”）。
9. **我的启事：** `pairState=PENDING/SCORING` 显示「分析中」；`READY` 显示 `relatedCount` 条可能相关；`DEGRADED` 显示「已降级」；`lowConfidenceCount` 单独显示条数，不展开。
10. **状态与详情入口：** `status=COMPLETED` 显示「已完成」、`CLOSED` 显示「已关闭」，两者 `detailVisible=false`，**不要渲染可点的详情链接**（点了必然 404）；`PENDING` 才渲染详情入口。删除按钮在三态都可渲染（DELETE 与状态正交）。
11. **标记完成：** 只在 `status=PENDING && hasMatchedPair=true` 时启用；`hasMatchedPair=false` 时置灰并提示「需双方确认匹配后才能标记完成」。收到 `409/20005` 时不要刷新整页，按 `data.matchedPairCount` 更新提示；成功后跳 `/my`（COMPLETED 详情 404）。
12. **通知：** 列表 `body` 已打码；标记已读后用返回的 `unreadCount` 更新红点；空态显示「暂无通知」。
13. **状态按钮：** 只有 `isMine=true` 且 `allowedStatuses` 非空才渲染；非所有者 `allowedStatuses=[]`。
14. **CORS：** 开发期走 Vite 代理（同源），直连 18080 时必须带 credentials 且后端显式 origin；不能 `*`。

---

## 9. 验收对照（门禁 0 逐条检查）

| 验收项 | 本文件位置 | 判定 |
|---|---|---|
| 17 接口 × 3 态逐格检查，无空格、无“视情况”、每格有状态码 | §3 矩阵 | 17 行 × 3 格 = 51 格；`⚠️` 分支条件写死（#2/#4/#5/#13/#14/#15） |
| 三态 × 召回/列表/详情无空格；COMPLETED/CLOSED 的 404、409 可复现 | 架构 §9.2/§9.3 + 本文 §4.1/§4.2/§4.5/§4.8/§4.12 | 三态表 + 409/20005 零写入 + CLOSED pair 403/50005 |
| 未达 MATCHED 调「标记完成」→ 409，`status`/`updated_at` 不变 | 本文 §4.5 | 流转表 + 零写入验收 SQL |
| COMPLETED 列表 0 命中、匹配保留期内可命中、<`completed_visible_threshold` 用户详情 404 | 本文 §4.1/§4.2/§4.8/§4.12 | 可见性判定 + `detailAccess` + `detailVisible` |
| CLOSED 列表/匹配均 0 命中，其 pair 取号 403 | 本文 §4.1/§4.8/§4.14 + 架构 §9.3 | `MATCHABLE` 排除 + 50005 |
| pair 四态含 MATCHED，`confirmed_at` 两端均非空 ⇔ MATCHED | 本文 §4.13 + 架构 §4.2.5/§9.1 | `action=CONFIRM` + `ck_pair_matched` |
| 遮蔽规则正反例各 3 条可复现 | 架构 §6.4 + 本文 §4.2/§4.14 | `location/description/contact/publisherName` 四类输入输出表 |
| pair 链路降级 vs `/api/match` 不降级，无矛盾 | 架构 §8.6 + 本文 §4.8/§4.12/§4.14 | 对照表 + 接口错误码 |
| 读 pair 状态机可找到“确认 A 不影响 B”，全文无「已解决」状态 | 架构 §9.1 + 本文 §4.13 | 单行 `WHERE id=?`；状态枚举四值；无占用/锁定 |
| `t_user` 无 phone、无短信、无自动关闭/下架 | 架构 §4.2.1/§4.4/§9.2 | DDL + 不变量 + 无任务 |
| 只出文档，`server/`、`web/` 0 行改动 | 本分支 diff | `git diff --stat` 只含 `docs/` |
| 17 个接口都有方法+路径+入参+出参+错误码+幂等性 | §4.1–§4.17 | 每节含四要素 |

---

## 10. 变更记录

| 日期 | 版本 | 变更 | 作者 |
|---|---|---|---|
| 2026-10-27 | v2.0 | 初稿：17 接口、三态矩阵、认证/遮蔽/分档/LLM/pair 状态机/通知/审计；与 v1 的 8 项破坏性变更 | 磐石石 |
| 2026-10-27 | v2.1 | 按 vF 终态口径返工：`t_record` 三态（COMPLETED 取代 CLAIMED）、`completed_at`、pair 四态含 MATCHED、两端 `confirmed_at`、三态×召回/列表/详情、409/20005、COMPLETED 详情门槛、配置项冻结名、`PATCH pair` 改 `action`、CLOSED pair 403/50005、矩阵 #1/#2/#5/#8/#14 重算 | 磐石石 |

> 冻结后任何接口/字段/状态码变更必须先改本文 + 架构文档，再改代码；门禁 0 未过之前允许修订，但需在任务评论记录差异。
