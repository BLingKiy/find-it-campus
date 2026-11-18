# find-it-campus 架构基线（1.2）

> 文档状态：定稿（范围已确认）
> 适用仓库：https://github.com/BLingKiy/find-it-campus
> 配套文件：`docs/api-contract.md`、`docs/ai-match-module.md`、`docs/frontend-baseline.md`、`docs/open-questions.md`、`db/schema.sql`、`db/seed.sql`
> 本文只定基线与契约，不含业务实现代码。下游 2.1 / 3.1 / 3.2 / 4.1 / 4.2 照此落地，不需回头再问。

---

## 1. 结论先行

**推荐：模块化单体（Spring Boot 单进程）+ 按能力分包 + MyBatis 显式 SQL + 匹配能力以接口隔离的本地/外部双实现。**

三条最关键理由：

1. **一人项目，边界还没长稳**。微服务/缓存/MQ 的成本由部署与联调支付，收益要规模才显现；本项目的规模输入（记录量级、并发）目前无人给出，不具备拆分依据。
2. **评分点在"接口解耦"而不是"架构复杂度"**。技术先进性 20 分看的是 `/api/match` 接口与实现可替换，这靠一个 Java 接口 + 一个 `@ConditionalOnProperty` 就能拿到，不需要独立进程。
3. **三张表恰好覆盖三个所有权**：记录（record）、分类主数据（category）、匹配快照（match）。多一张则超范围（需组内拍板），少一张则匹配效果评估没有证据链。

**放弃的东西**：单库单事务，将来若真要拆服务，`t_record` 与 `t_match_result` 的读写归属要重新划分（见 §10 演进路线、ADR-002）。

---

## 2. 约束与假设

### 2.1 已固定输入（不再讨论）

| 项 | 内容 |
|---|---|
| 前端 | Vue 3 + Vite |
| 后端 | Spring Boot + MySQL |
| AI 模块 | 语义匹配，接口独立、实现可替换 |
| 明确不做 | 微服务、权限体系与登录、消息推送、图片对象存储、管理后台、移动端壳 |
| 表数量 | 固定三张（第四张须先组内确认） |
| 协作 | 四人分工，按主责域提交与复核，代码只经仓库流转 |

### 2.2 我做的假设（未确认，需回填；影响项已标注）

| # | 假设 | 影响 | 状态 |
|---|---|---|---|
| A1 | 无登录 ⇒ 提交人自填联系方式，任何访客可看详情 | `t_record.contact` 可空性、是否脱敏 | **待拍板 Q-01** |
| A2 | 无登录 ⇒ 状态流转（待领→已领）由详情页调用方直接触发，无认领申请流程 | 是否保留 `PATCH /status` | **待拍板 Q-02** |
| A3 | 标题 ≤ 64 字、描述 ≤ 2000 字、地点 ≤ 128 字 | 列长度 + 校验上限必须同步 | **待 1.1 确认 Q-04** |
| A4 | 每页 10 条、上限 50 条 | 分页参数与索引设计 | 待确认 Q-04（上限类默认值与 A3 一并拍板） |
| A5 | 相似度分数归一到 0–1，展示为百分比 | 前端展示口径、跨实现可比性 | **待拍板 Q-06** |
| A6 | 匹配返回条数默认 5、上限 20 | `/api/match` topN 校验 | 待确认 Q-07 |
| A7 | 记录量级为"课程演示 + 校园真实使用皆小"（**无输入数据，我按 ≤1 万条估算，属经验区间，需实测**） | 候选池上限、是否引入检索方案 | 待补数字 |
| A8 | 课程版匹配先用本地实现，外部模型作为可替换实现并存 | 3.2 工作量 | 父任务待确认 #1 |
| A9 | 匹配快照属"评估证据"而非"业务数据"：写失败只告警，不回滚已返回的匹配结果 | `/api/match` 的事务边界与失败语义 | **待确认（见 §7.4 末段，改法是移动一行代码位置）** |

### 2.3 我对 1.1（并行任务）的依赖

1.1 尚未产出，因此本文里凡属"业务判断"的取值我都给了**默认值 + 明确标注**，不当作结论。需要 1.1 交付后我做一次反向核对（规则 → 字段落点）的条目集中在 §8.2 与 `docs/open-questions.md`。

---

## 3. 上下文地图（限界上下文与模块边界）

| 上下文 | 职责（一句话） | 数据所有权 | 核心不变量 | 与相邻上下文的关系 |
|---|---|---|---|---|
| **record** 失物招领记录 | 记录的生命周期：发布、查询、修改、状态流转、下架/删除 | `t_record` | ① `type ∈ {LOST, FOUND}`、`status ∈ {PENDING, CLAIMED, CLOSED}`；② 状态只能按 §7.4 流转表迁移；③ 逻辑删除的记录对所有读接口不可见；④ `occurred_at`、`title`、`description`、`location` 必填 | 只读依赖 category（**Customer/Supplier**，无 ACL：分类是字典，语义稳定）；被 match 只读消费候选池 |
| **match** 语义匹配 | 输入自由文本 → 返回候选记录与相似度分值，并落一份可评估的快照 | `t_match_result` | ① 引擎输出 `score ∈ [0,1]`；② 结果按 score 倒序、条数 ≤ topN、`score ≥ minScore`；③ 返回给调用方的候选必须先经 record 回表校验合法性（未删除、状态可展示）；④ 快照 `impl` 与实际执行引擎一致 | 依赖 record（**Conformist**：候选池查询走 record 的 mapper 出口，不自建表）；依赖 category 做可选加权 |
| **category** 分类字典 | 提供主数据（分类码、名称、检索关键词、展示顺序） | `t_category` | ① `code` 唯一；② 分类不可物理删除（被历史记录引用），只能 `is_active=0` 停用；③ 停用分类不再出现在下拉，但历史记录仍保留其名称 | 上游主数据，对下游只暴露只读接口（**OHS 的极简形态**：无鉴权、无版本协商） |
| **common** 共享内核 | 统一响应体、错误码、异常、Web 配置、时间/序列化约定 | 无 | ① 任何 controller 不得自定义响应包裹；② 任何错误必须映射到 `ErrorCode`；③ HTTP 状态码由 `ErrorCode.httpStatus` 决定，禁止手填 | 被三个上下文依赖，**自身不依赖任何上下文**（依赖方向单向） |

**依赖方向铁律**（可被 ArchUnit 检查，见 §9 可维护性）：

```
web → service → (mapper | engine 接口)
common ← 所有层（单向，common 不反向依赖业务包）
match  → record.mapper（只读候选池）   record  ✗→  match
category ✗→ 任何业务上下文
禁止：controller → mapper；service → controller；任何包 import engine 实现类
```

---

## 4. C4 示意图（文字描述，照此画图）

### 4.1 Level 1 系统上下文（给业务方/答辩开场）

- 中心框：`find-it-campus 校园失物招领`（Vue3 Web + Spring Boot API + MySQL）
- 左侧人：`校园访客`（浏览/筛选/查看详情/提交启事/用自然语言找匹配）→ 指向系统，协议 HTTPS/JSON
- 右侧可选框（虚线，标注"可替换实现"）：`外部嵌入/大模型服务` ← 系统在 `findit.match.engine=external` 时调用，协议 HTTPS，超时 3s，失败返回 503
- 底部框：`MySQL 8` ← 系统读写，JDBC

### 4.2 Level 2 容器（给技术负责人）

```
[浏览器 SPA  Vue3+Vite :5173]
   │  HTTP JSON /api/** （开发期经 Vite 代理转发，无跨域）
   ▼
[后端应用  Spring Boot :18080] ← 单一进程，无状态（默认端口 18080，非 8080，见 §9.2）
   ├─ record 能力   ─┐
   ├─ category 能力 ─┼─→ JDBC/HikariCP → [MySQL 8  find_it_campus: t_record / t_category / t_match_result]
   └─ match 能力   ──┘
        └─ MatchEngine(接口)
             ├─ KeywordMatchEngine（本地，默认）      ：无外部依赖
             └─ ExternalEmbeddingMatchEngine（可选）  → HTTPS → [外部嵌入服务]
```
框间标注：调用方向 + 协议 + 同步/异步。全部同步（本项目无异步链路，明确不引入 MQ）。

### 4.3 Level 3 组件（给开发，只画 match 与 record）

```
MatchController → MatchService → MatchEngine(接口)
                     │  ↑              │
                     │  └─ MatchCandidatePoolPort ← RecordMapper（候选池 SELECT）
                     ├─→ MatchResultMapper（写快照）
                     └─→ RecordMapper（回表取 title/type/status，过滤非法候选）
RecordController → RecordService → RecordMapper → t_record
                                 → CategoryMapper（校验 categoryId 存在且 active）
CategoryController → CategoryService → CategoryMapper → t_category
```

---

## 5. 仓库与包结构基线

### 5.1 仓库顶层（2.1 按此建，路径一一对应）

```
find-it-campus/
  README.md                 # 环境要求 + 启动/构建命令（2.1 写，内容取自本文 §6/§9）
  docs/                     # 本基线 5 份文档；6.1 设计文档从这里长出来
  db/
    schema.sql              # 三张表 DDL（附字段注释）
    seed.sql                # 分类字典 + 演示记录（兼作 5.1 匹配自测样例集）
    README.md               # 建库/导入/重置三条命令
  server/                   # Spring Boot（Maven Wrapper 自带，无需本机 Maven）
    pom.xml
    mvnw / mvnw.cmd / .mvn/wrapper/
    src/main/java/com/findit/campus/...
    src/main/resources/application.yml
    src/main/resources/application-example.yml   # 提交；本机私密配置写 application-local.yml，不提交
    src/main/resources/mapper/**  *.xml
  web/                      # Vue3 + Vite
    package.json / vite.config.ts / tsconfig.json
    src/{api,router,views,components,composables,styles,types}
  .gitignore                # 现有文件需增补，见 §9.5
```
**为什么 `db/` 放根目录而不是 `server/src/main/resources/`**：建库是前后端共同的启动前置条件，放根目录 README 才能一条命令指到位；放 resources 里会被误认为应用资源。代价：脚本不在 classpath，不能用 Spring 自动执行——本项目不需要自动执行。

### 5.2 后端包结构（按能力分包，层在能力内部）

```
com.findit.campus
├─ FindItCampusApplication
├─ common
│  ├─ api        ApiResponse / PageResult / ErrorCode / FieldError
│  ├─ error      BizException / GlobalExceptionHandler
│  ├─ config     WebConfig(CORS) / JacksonConfig(时间格式) / MatchEngineConfig(引擎装配)
│  └─ util       （只在出现 ≥2 处真实复用时才建，禁止预先放"通用"代码）
├─ record
│  ├─ web        RecordController + dto/（RecordCreateRequest、RecordUpdateRequest、
│  │             StatusUpdateRequest、RecordQuery、RecordListItemResponse、RecordDetailResponse）
│  ├─ service    RecordService、RecordStatusPolicy（状态流转表）
│  ├─ mapper     RecordMapper（接口）+ resources/mapper/record/RecordMapper.xml
│  └─ domain     Record（实体）、RecordType、RecordStatus
├─ category
│  ├─ web        CategoryController + dto/
│  ├─ service    CategoryService
│  ├─ mapper     CategoryMapper
│  └─ domain     Category
└─ match
   ├─ web        MatchController + dto/（MatchRequest、MatchResponse、MatchItemResponse）
   ├─ service    MatchService（编排：参数校验 → 取候选池 → 调引擎 → 回表 → 写快照）
   ├─ engine     MatchEngine（接口）、MatchQuery、MatchCandidate、MatchEngineException、
   │             MatchCandidatePoolPort（引擎取候选文本的出口接口）
   ├─ engine.local   KeywordMatchEngine（默认实现）
   ├─ engine.remote  ExternalEmbeddingMatchEngine + EmbeddingClient（外部 HTTP 细节挡在这里）
   ├─ mapper     MatchResultMapper
   └─ domain     MatchResult
```

**为什么按能力分包，不在顶层建 controller/service/mapper 三个大桶**（ADR-003）：
- 它挡住的是一类变化："新增一个能力"（比如将来加认领申请流程）只增一个包，不动已有包的 import 关系；顶层分层会把新能力摊到三个桶里，改动面 ×3。
- 代价：新人若看惯了"controller 桶"式教程，前 10 分钟要适应；答辩时需多解释一句分包理由（我认为值得，且这是可以讲成加分点的部分）。

---

## 6. 分层职责与禁区

| 层 | 允许 | 禁止 |
|---|---|---|
| Controller (`*Controller`) | 参数绑定、`@Valid` 校验、调 service、把结果映射成 Response DTO、返回 HTTP 状态码 | 写业务规则；直接注入 Mapper；返回实体；手工拼 `ApiResponse` 的 code；catch 异常后自行决定错误码 |
| Service (`*Service`) | 业务规则与不变量、事务边界（`@Transactional` 只标在写方法）、编排引擎调用、排序字段白名单校验 | import `HttpServletRequest/Response` 或 `ResponseEntity`；把实体直接当出参给 controller；跨聚合开一个事务改多张表（见 §7.4 事务口径） |
| Mapper (`*Mapper` + XML) | 单表 CRUD、显式分页 SQL、FULLTEXT/LIKE 检索 | 写业务分支；用 `${}` 拼接任何来自请求的值（**注入点，见 ADR-014**） |
| Engine (`MatchEngine` 实现) | 纯文本打分/排序，消费 `MatchCandidatePoolPort` 给出的候选池 | 访问 `t_record` 之外的表；直接返回实体给 web 层；知道 HTTP 概念 |
| Domain 实体 | 与表字段一一对应 | 承载校验注解（校验放 DTO）；出现在响应体里 |

### 6.1 统一响应体

所有接口（含错误）都返回同一外层结构，`Content-Type: application/json`：

```json
{ "code": 0, "message": "ok", "data": {}, "requestId": "6f1c…" }
```

| 字段 | 类型 | 必填 | 含义 |
|---|---|---|---|
| `code` | int | 是 | 0 成功；非 0 见 §6.2 错误码表。**业务分支只认 code，不认 message 文案** |
| `message` | string | 是 | 面向人的中文说明，可直接展示；不为空串 |
| `data` | object/array/null | 否 | 成功时为业务数据；参数校验失败时为 `{"fieldErrors":[…]}`；其他错误可为 null |
| `requestId` | string | 是 | 一次请求的追踪号（logback MDC 同值），排障用；前端错误提示里可原样带出 |

**HTTP 状态码与 body code 双轨**（ADR-005）：状态码由 `ErrorCode.httpStatus` 映射，controller 不许手填。
- 好处：前端拦截器可按状态码分流（4xx 归用户、5xx 归系统），同时按 `code` 出精确文案。
- 代价：两处表达同一事实，可能写歪。缓解手段：`ApiResponse.success/fail` 是唯一构造入口，`GlobalExceptionHandler` 是唯一失败出口，controller 不允许 `new ApiResponse(...)`。

### 6.2 错误码表（基线，新增须在此登记）

分段：`1xxxx` 通用 · `2xxxx` 记录 · `3xxxx` 匹配 · `4xxxx` 分类。

| code | HTTP | 含义 | 触发场景 | data 里给什么 |
|---|---|---|---|---|
| 0 | 200 | 成功 | — | 业务数据 |
| 10001 | 400 | 参数校验失败 | `@Valid` 未过、必填缺失、超长、枚举值非法 | `fieldErrors[{field,reason}]` |
| 10002 | 413 | 请求体过大 | 超过 `spring.codec.max-in-memory-size`（基线 64KB） | null |
| 10003 | 404 | 资源不存在 | 路径 id 不存在（通用兜底） | null |
| 10004 | 405 | 方法不支持 | 路由存在但动词错 | null |
| 10005 | 500（默认）；依赖不可用（如 DB DOWN）时由 controller 覆写 503 | 依赖（DB、外部引擎）不可用或未预期异常；**响应体不含堆栈**，只含 requestId | null（`GET /api/health` 例外：`{"status":"DOWN","db":"DOWN",…}`） |
| 20001 | 404 | 记录不存在或已删除 | `GET/PUT/PATCH/DELETE /api/records/{id}` | null |
| 20002 | 409 | 状态流转非法 | 违反 §7.4 流转表（如 CLOSED→CLAIMED） | `{"current":"CLOSED","allowed":[…]}` |
| 20003 | 409 | 记录当前状态不可执行该操作 | 业务禁止的删除/修改（**依赖 1.1 规则，Q-03**） | null |
| 30001 | 400 | 匹配输入无效 | `text` 空白或长度 < 2 字 | `fieldErrors` |
| 30002 | 503 | 匹配引擎不可用 | 外部服务网络失败/配额耗尽/未配置 | `{"engine":"external"}` |
| 30003 | 504 | 匹配引擎超时 | 超过 `findit.match.external.timeout` | `{"engine":"external"}` |
| 30004 | 429 | 匹配调用过于频繁 | 若确认需要限流才启用（当前基线**不实现**） | null |
| 40001 | 404 | 分类不存在 | 提交/筛选带了不存在的 `categoryId` | null |
| 40002 | 409 | 分类停用不可选 | 提交时 `categoryId` 指向 `is_active=0` | null |

> **10005 的 HTTP 口径：默认 500，依赖不可用时由 controller 覆写 503**（2026-10-22 组内定案，方案 A）。
>
> 结论（口径已闭合，不再有待确认项）：
> - `ErrorCode.INTERNAL_ERROR` 枚举自身映射 **500**，未预期异常走这个默认值，body `code=10005`。
> - 依赖不可用（典型是 DB 探测失败）不属于"未预期"，语义上更接近"暂时不可用"，
>   由 controller 在返回时**显式覆写 HTTP 为 503**，body 的 `code` 仍是 10005（"两轨不同源"是刻意的）。
> - 覆写点只有一个：`HealthController`（`GET /api/health` DB DOWN → 503）。新增依赖探测点必须沿用同一写法，
>   即 body 用 `ErrorCode.INTERNAL_ERROR`、HTTP 层单独覆写，不许改枚举的 `httpStatus`。
>
> 与 `docs/api-contract.md` 三处一致、无需改契约：第 45 行与第 367 行「未预期异常 500 + 10005」对上默认值，
> 第 56 行「DB 不可用 503 + 10005」对上覆写分支。

### 6.3 全局异常处理

`GlobalExceptionHandler`（`@RestControllerAdvice`）四类出口，其余一律落到 `INTERNAL_ERROR`：
1. `MethodArgumentNotValidException`（`@Valid` 请求体）/ `HandlerMethodValidationException` 与 `ConstraintViolationException`
   （`@RequestParam`/`@PathVariable` 上的 `@Min`/`@Pattern` 等约束，见 §6.4）→ 10001 + fieldErrors（**HTTP 400，不是 500**）
2. `MethodArgumentTypeMismatchException`（路径/查询参数类型不符，如 `/api/records/new` 被当 id 请求）→ 10001，`fieldErrors:[{field:"id",reason:"必须为数字"}]`
3. `BizException`（携带 ErrorCode）→ 对应 code 与 httpStatus
4. `Exception` → 10005，`log.error` 打全栈（含 requestId），响应体只回 message + requestId

日志：logback 默认文本格式 + MDC `requestId`；一次请求一行 INFO 访问日志（方法、路径、状态、耗时）。不引入 JSON appender（无人消费，成本 > 收益）。

### 6.4 方法级参数校验的两个出口（为什么两个都注册）

`@RequestParam`/`@PathVariable` 上直接挂 `@Min`/`@Pattern`/`@NotBlank` 时，约束不是被参数解析器拦下的，
而是方法校验阶段抛的。**同一个错误在不同装配下抛的是两个不同的异常**，漏一个就等于给前端漏一个 500：

| 异常 | 何时出现 | 出处 |
|---|---|---|
| `HandlerMethodValidationException` | Spring MVC 内建方法校验（Spring 6.1+ 默认开启，controller 上无需 `@Validated`） | `org.springframework.web.method.annotation` |
| `ConstraintViolationException` | 类上标了 `@Validated`，走 AOP `MethodValidationPostProcessor` 代理 | `jakarta.validation` |

两个都注册，行为一致（都是 10001 + 400 + 全量 `fieldErrors`），成本只是多一个方法；
不注册的代价是：换一种装配方式就静默退化成 10005/500 或 Spring 默认 ProblemDetail —— 前端按 `code` 取值直接崩。

`fieldErrors` 必须返回**全部**违规字段，不只第一条：多参数同时错时前端要一次性标红全部表单项，
只给一条会让用户"改一个、提交、再报下一个"。

---

## 7. 三张表设计

完整可执行 DDL 见 `db/schema.sql`，种子数据见 `db/seed.sql`。字段与索引的设计理由在此。

### 7.1 ER 概览

```
t_category 1 ──< N t_record        (record.category_id → category.id，可空，RESTRICT)
t_category 1 ──< N t_match_result  (input_category_id → category.id，可空，SET NULL)
t_match_result.candidates(JSON) …→ t_record.id   （无外键，见 §7.3 说明）
```

### 7.2 `t_record` 失物/招领记录主表

| 列 | 类型 | 约束 | 谁在用（→ 规则落点） |
|---|---|---|---|
| `id` | BIGINT UNSIGNED | PK, AUTO_INCREMENT | 详情/改/删 URL、匹配候选、快照引用 |
| `type` | VARCHAR(8) | NOT NULL, CHECK IN ('LOST','FOUND') | 列表页「寻物/招领」切换；匹配按类型过滤候选池 |
| `status` | VARCHAR(16) | NOT NULL, CHECK IN ('PENDING','CLAIMED','CLOSED') | 状态筛选、流转校验、已领是否仍进列表 |
| `title` | VARCHAR(64) | NOT NULL | 列表卡片与详情页标题；FULLTEXT 参与列 |
| `description` | TEXT | NOT NULL | 详情页正文；**匹配的主文本输入** |
| `category_id` | BIGINT UNSIGNED | NULL, FK→t_category, RESTRICT | 列表筛选、提交页下拉、匹配加权 |
| `location` | VARCHAR(128) | NOT NULL | 详情展示；本地实现的地点就近加分项 |
| `occurred_at` | DATETIME | NOT NULL | 详情展示「丢失/发现时间」；时间邻近加分项 |
| `contact` | VARCHAR(64) | NULL | 详情页联系方式（**A1/Q-01 待定必填与脱敏**） |
| `publisher_name` | VARCHAR(32) | NULL | 详情页「联系人称呼」；若 1.1 判定不需要则删列（Q-01 一并拍板） |
| `deleted_at` | DATETIME | NULL | 逻辑删除标记；所有查询默认 `deleted_at IS NULL` |
| `created_at` / `updated_at` | DATETIME | NOT NULL DEFAULT CURRENT_TIMESTAMP [ON UPDATE] | 列表默认排序、"发布时间"展示 |

索引用意（`schema.sql` 内注释同步）：
- `idx_record_list (type, status, deleted_at, created_at)` —— 覆盖列表主查询：三个等值条件 + 按 `created_at DESC` 免排序；同时覆盖 3.2 的候选池取数（`type=? AND status='PENDING' AND deleted_at IS NULL …LIMIT 500`）。
- `idx_record_category (category_id, deleted_at, created_at)` —— 分类筛选路径。
- **已知薄弱点**：`/api/match` 不带 `type`（不限类型）时，候选池查询的 `type` 条件缺位，`idx_record_list` 的首列用不上、排序也无法靠索引满足，会退化为较大范围的扫描。**当前不为此预先加第二条索引**（无实测依据），处置方式：3.2 用 `EXPLAIN` 看一次真实计划，若 >1s 再补 `idx_record_status_created (status, deleted_at, created_at)` —— 加索引是可逆操作。
- `idx_record_category (category_id, deleted_at, created_at)` —— 分类筛选路径。
- `ft_record_search (title, description) WITH PARSER ngram` —— 关键词检索/本地匹配的取数加速。**可选**：非 MySQL 8 或内核未含 ngram 时该句失败，退化路径是 LIKE + 候选池内存打分，**接口契约不变**（见 §11 ADR-008 的容错设计）。
- 不建 `idx_record_occurred`：1.1 未要求按发生时间筛选/排序，无读取方即不建（避免写入成本与冗余）。若 1.1 加时间范围筛选，再补一条索引即可（可逆）。

`DATETIME` 取秒精度（不带毫秒）以与线上 JSON 时间格式 `yyyy-MM-dd HH:mm:ss` 完全对齐；代价是同一秒内创建的记录排序不稳定 ⇒ **所有分页排序一律追加 `, id DESC` 兜底**（写进 §8 与 api-contract）。

### 7.3 `t_match_result` 匹配快照

每次 `/api/match` 成功调用写一行（含返回空数组的调用）。它的存在理由：5.1 要交"AI 匹配效果评估记录"、6.2 录屏要证据、外部/本地两种实现需要 A/B 对比 —— 没有这张表，评估只能靠手工截图。

| 列 | 类型 | 为什么留 |
|---|---|---|
| `id` | PK | 快照主键（响应体回传 `matchId`） |
| `input_text` | TEXT NOT NULL | 复现评估的原始输入 |
| `input_type` | VARCHAR(8) NULL CHECK | 本次要找 LOST 还是 FOUND，统计口径要用。**3.1 起可空**（契约 §8.1 允许不传 `type`）：不传时快照如实记 NULL，编一个 LOST/FOUND 会污染 5.1 评估数据；ALTER 见 `db/schema.sql` 附 2 |
| `input_category_id` | BIGINT UNSIGNED NULL FK→t_category **SET NULL** | 分类加权参数；分类删除不影响快照可读 |
| `impl` | VARCHAR(32) NOT NULL | 产出该结果的引擎标识（`LOCAL_KEYWORD`/`EXTERNAL_EMBEDDING`），换实现后的对比维度 |
| `top_n` / `min_score` | SMALLINT / DECIMAL(6,4) NOT NULL | 请求参数留痕，评估时可分组统计"阈值 0.3 vs 0.5 命中率" |
| `candidates` | JSON NOT NULL | 候选数组 `[{recordId,score,reason}]`，与响应体 `items` 一致（不含展示字段） |
| `cost_ms` | INT NULL | 引擎耗时，性能证据；外部实现的关键指标 |
| `created_at` | DATETIME NOT NULL | 时序 |

**取舍**：JSON 存候选 ⇒ 一张表搞定，但 (a) 无外键、不能索引；(b) 记录被删后快照里的 `recordId` 会指向已删除行；(c) SQL 级聚合难做。
- (b) 由"回表取展示字段"的设计天然吸收：回表 `WHERE deleted_at IS NULL` 过滤后，前端看到的结果永远合法（ADR-008）。
- (c) 若 5.1 需要"命中率按分类聚合"这类 SQL 统计，就要第四张表 `t_match_candidate` ⇒ **列为待确认 Q-09，不写进基线**。当前建议：评估脚本读 JSON（样例集几十条量级，够用）。

### 7.4 `t_category` 分类字典 + 状态流转表

`code`(唯一) / `name` / `keywords`（本地匹配加权词表，`NULL` 表示只靠 name）/ `sort_order` / `is_active` / 时间戳。理由：把分类做成字典而不是 `ENUM` 列，挡住的变化是"调整分类集合"（新增一类、改名、停用）—— 用 ENUM 则每次调整都要 DDL + 全量数据迁移 + 前后端枚举同步。代价：多一次 JOIN 与一张表的名额。

**状态流转（结构已定，取值待 1.1 确认）**：

| 当前 | 允许迁往 | 说明 |
|---|---|---|
| `PENDING` 待领 | `CLAIMED`、`CLOSED` | 正常认领 / 下架 |
| `CLAIMED` 已领 | `CLOSED` | 撤销认领（回到 PENDING）**是否允许 → Q-03** |
| `CLOSED` 关闭 | （终态） | 不允许再流转，避免历史状态被改写 |

**不变量的事务口径**：一次 `@Transactional` 只改一个聚合。改记录状态只写 `t_record`；`/api/match` 里"写快照 + 读候选"不共享事务 —— 快照写失败**不回滚已返回的匹配结果**（先算完返回、快照异步/尽力写；课程版实现为同请求内同步写、写失败仅告警）。理由：快照是评估证据不是业务数据，不该让用户体验承担它的一致性。这一条我明确放宽了一致性，**依据是我对业务的判断（假设 A9），若你要求"快照必须落库才算成功"，改成写库前置并整体回滚即可**（一行代码位置变化）。

---

## 8. API 契约要点（全文见 `docs/api-contract.md`）

| # | 方法 | URL | 用途 |
|---|---|---|---|
| 1 | GET | `/api/records` | 列表（筛选 + 分页 + 排序） |
| 2 | GET | `/api/records/{id}` | 详情 |
| 3 | POST | `/api/records` | 提交（增） |
| 4 | PUT | `/api/records/{id}` | 修改内容 |
| 5 | PATCH | `/api/records/{id}/status` | 状态流转 |
| 6 | DELETE | `/api/records/{id}` | 删除（逻辑） |
| 7 | GET | `/api/categories` | 分类字典 |
| 8 | POST | `/api/match` | 语义匹配 |
| 9 | GET | `/api/health` | 健康检查（2.1 冒烟用） |

统一约定（细节以契约文档为准）：
- **前缀不加版本段**：`/api`，不写 `/api/v1`。理由：单一前端消费者、无对外契约；将来毕设要对外时，base path 是一处常量，改它不影响任何字段结构。
- **命名**：资源复数、kebab-case 路径、camelCase JSON 字段；DB 列 snake_case，靠 `map-underscore-to-camel-case` 自动映射（ADR-013）。
- **时间**：`yyyy-MM-dd HH:mm:ss`，本地时间无时区（与 `DATETIME` 对齐，避开 JS 时区偏移坑）；入参同格式。
- **分页**：请求 `page`（1 起）/`pageSize`（默认 10、上限 50）；响应 `page/pageSize/total/totalPages/list/hasNext`。**不用** `offset/limit`，**不用** `pageNum/pageRow`。
- **排序**：`sortBy ∈ {createdAt, occurredAt}` + `sortOrder ∈ {asc, desc}`，service 层白名单映射到列名（ADR-014）。
- **空结果**：列表返回 `200` + `list: []`，不是 404；匹配无命中同样 `200` + `items: []`。

---

## 9. 环境与构建基线

### 9.1 版本

| 组件 | 基线 | 来源与说明 |
|---|---|---|
| JDK | **17**（Boot 官方最低支持线；本机若只有 21 也可，写进 README 即可） | start.spring.io 元数据：default Java 17，可选 21/25/27（查询于 2026-10-21） |
| Spring Boot | **3.5.16**（3.5 线，GA 中） | repo1.maven.org `spring-boot/maven-metadata.xml`，查询于 2026-10-21。备选 4.1.1 → ADR-016 |
| MyBatis starter | **3.0.5**（与 Boot 3.x 配对） | repo1.maven.org `mybatis-spring-boot-starter`：Boot 3.2–3.5 ↔ 3.0.x；Boot 4.0.x ↔ 4.0.x/4.1.0 |
| MySQL Connector | `mysql-connector-j`，版本由 Boot BOM 管理，不手写 `<version>` | 避免与 Boot 传递依赖冲突 |
| MySQL Server | **8.0.x**（FULLTEXT ngram / JSON 类型 / CHECK 约束生效都要求 8.x） | 8.0.14+ 起支持 ngram 与 CHECK；具体小版本由你的安装决定（**需实测**） |
| Node | **22.x LTS**（本机运行时实测 `v22.23.3`、npm `10.9.9`） | 本地实测，来源：本会话命令输出 |
| Vue | **3.5.44** | `npm view vue version`，2026-10-21 |
| Vite | **8.3.5** | 同上 |
| vue-router | **5.4.0** | 同上 |
| @vitejs/plugin-vue | **6.0.10** | 同上 |
| TypeScript | **建议 5.x**（npm latest 当前为 `7.0.2`，是原生编译器线；`vue-tsc` latest `3.3.13` 与 TS 7 的组合**尚未在本项目验证**） | `npm view`，2026-10-21。2.1 建骨架时**先锁 TS 5.x + 配套 vue-tsc**，把实际解析到的版本写进 README；升级 TS 7 另立任务 |
| Pinia | **不引入**（理由见 `docs/frontend-baseline.md` §3） | `npm view pinia version` = 4.1.0（仅作记录） |
| Maven | 通过 **Maven Wrapper（mvnw）** 使用，不要求本机安装 Maven | 满足 2.1 验收「干净环境按 README 命令执行」 |

> ⚠️ **执行环境提示（实测发现）**：当时的开发机 `PATH` 上无 `java`/`mvn`/`mysql`（仅 Node 22 与 Git 2.56），`Program Files` 下也未找到 JDK/MySQL/Maven 安装。2.1 的验收要求"干净环境按 README 命令可启动前后端、脚本可在 MySQL 建表"，**该验收必须在装有 JDK + MySQL 的机器（开发机或配好环境的机器）上跑**，否则 2.1 会因环境缺失而卡住，与代码无关。分工时确认执行机器。

### 9.2 配置约定

- `server.port=18080`、`web` dev server `5173`（Vite 默认）。**不是 8080**：本机 8080 已被另一个服务（upload-svc）长期占用，整合时把仓库默认端口整体对齐到 18080，前端 Vite 代理同步指向它，避免每人各配一次。
- 敏感配置（DB 密码、外部模型 API Key）只从环境变量读：`FINDIT_DB_URL` / `FINDIT_DB_USER` / `FINDIT_DB_PASSWORD` / `FINDIT_EMBED_BASE_URL` / `FINDIT_EMBED_API_KEY`；`application.yml` 里写 `${VAR:默认值}`。
- **提交 `application-example.yml`，不提交 `application-local.yml` 与任何 `.env`**；缺必要变量时启动即失败（`@Validated` 配置类或 `MatchProperties` 校验），不要带病启动。
- 外部模型类配置遵循 `findit.match.*` 前缀（见 `docs/ai-match-module.md`）。

### 9.3 命令基线（README 就抄这段）

```bash
# 0. 建库（仅需一次）
mysql -u root -p -e "SOURCE db/schema.sql"
mysql -u root -p find_it_campus -e "SOURCE db/seed.sql"

# 1. 后端（server 目录，Windows 用 mvnw.cmd）
./mvnw spring-boot:run                      # http://localhost:18080/api/health
./mvnw -q clean package                     # 构建产物 server/target/*.jar

# 2. 前端（web 目录）
npm install
npm run dev                                 # http://localhost:5173  （/api 经代理转 18080）
npm run build && npm run preview            # 构建 + 本地预览
```

重置演示数据：`mysql -u root -p find_it_campus -e "SET FOREIGN_KEY_CHECKS=0; TRUNCATE t_match_result; TRUNCATE t_record; TRUNCATE t_category; SET FOREIGN_KEY_CHECKS=1;"` 然后重跑 `db/seed.sql`。

### 9.4 跨域方案（双轨，ADR-012）

1. **开发期主路径 = Vite 代理**：`server.proxy['/api'] → http://localhost:18080`，浏览器视角同源，零 CORS 问题，生产部署时前端配同源 base 或反代。
2. **后端同时显式配置 CORS**，为"直接用 `http://localhost:18080`/非代理消费者"兜底：`allowedOriginPatterns=["http://localhost:5173","http://127.0.0.1:5173"]`、`allowedMethods=GET,POST,PUT,PATCH,DELETE,OPTIONS`、`allowCredentials=false`（无鉴权体系，不需要 credentials；**不用 `*`**）。

代价：两条路径都可能"看起来能用"，联调时容易分不清错误来自哪一层。缓解：README 里写明默认走代理，若前端 `VITE_API_BASE_URL` 直指 18080 则走 CORS。

### 9.5 `.gitignore` 增补（现仓库只忽略了 `/TheClassMaterial/`）

需追加：`node_modules/`、`dist/`、`target/`、`.mvn/wrapper/maven-wrapper.properties`（**不忽略**，需提交）、`*.log`、`.idea/`、`.vscode/`、`server/src/main/resources/application-local.yml`、`.env`。2.1 落实。

### 9.6 迁移工具：不引入 Flyway（ADR-015）

三张表、一人改、课程周期短 ⇒ `db/schema.sql` 手工执行 + 每次结构变更同时改 schema 并在提交说明里附 `ALTER` 语句。备选 Flyway/Liquibase：一致性更强，但引入版本目录、基线迁移、启动期行为三个新变量，对 2.1 的"骨架能跑"是净负担。**若 3.x 阶段出现"表结构反复改 + 多人并行"，再引入 Flyway 是可逆操作**（脚本迁到 `db/migration/V1__init.sql`，不改 DDL 内容）。

---

## 10. 质量属性

| 属性 | 目标（标注来源） | 达成手段 | 验证方式 |
|---|---|---|---|
| 可扩展性 | 【我估】≤1 万记录、单机 MySQL、无并发压力输入 | 后端无状态（无 session、不落本地文件、上传不在范围）⇒ 可多实例；候选池上限 500 条封顶，匹配成本不随全表增长 | `EXPLAIN` 列表/候选池 SQL 命中 `idx_record_list`；压测需组内安排实跑，我给判定口径（P95、慢查询数）——**当前无数字，不作承诺** |
| 可靠性 | 【业务未给输入】按"课程演示不能当场挂"设计 | 外部模型失败→ 30002/503 明确错误（不静默回退）；超时 1s/3s；DB 启动即校验；无自动重试（匹配是计算型只读，重试可安全但不做，先保持链路简单） | 5.1 断网/填错 Key 各调一次 `/api/match`，断言 503 + code 30002 且**无未捕获异常**；`/api/health` 在 DB 不可用时明确返回失败而不是 hang |
| 可维护性 | 【我的建议】一个能力一个包、依赖单向、无隐式共享模型 | §3 依赖方向 + §6 层禁区 + 实体不出 web 边界 + DTO 与契约同名（改接口必改 DTO，编译期暴露） | 建议 5.1 加一条 ArchUnit 测试（约 20 行）自动检查依赖方向；**可选，需组内确认**（Q-14） |
| 可观测性 | 【我的建议】出问题先于用户发现，且答辩能展示 | 每请求 `requestId` 贯穿（MDC + 响应体）；访问日志一行/请求；慢查询由 MySQL `long_query_time=1` + slow log；`t_match_result` 自带 `impl`/`cost_ms` | 演示：给一个错误 requestId，从前端提示 → 日志行 → DB 快照串起来；开启 slow log 跑一遍列表页 20 次，无 >1s 查询（**需实测**） |

**幂等与重复提交**（我主动提醒的坑）：无登录 ⇒ `POST /api/records` 无法去重，用户双击即产生两条几乎相同的记录。基线处理方式：前端提交按钮 pending 禁用 + 后端不加唯一约束（业务上重复描述本就合法）。若你要更强保证，需引入客户端幂等键（`clientRequestId` + `t_record` 唯一索引）⇒ **属字段级不可逆项，列 Q-11 待拍板**。

---

## 11. 演进路线（每步可独立回滚）

| 步 | 内容 | 为什么这个顺序 | 回滚方式 | 后悔风险 |
|---|---|---|---|---|
| 1 | `db/schema.sql` + `db/seed.sql` 落地建库（2.1） | 数据模型是唯一"改起来贵"的地基，先钉住 | DROP DATABASE（尚无消费者） | **不可逆点①**：字段含义一旦有数据即难改。缓解：三列已标可空、分类走字典 |
| 2 | 后端骨架：common（响应体/错误码/异常/时间格式/CORS）+ 三能力空包 + `/api/health`（2.1） | 契约先行，3.1/4.1 并行时不用互相等 | 纯新增，可整体删 | 低 |
| 3 | category + record 接口（3.1） | 只读接口最简单，先把 DTO/分页/排序白名单跑通 | 单接口删除，无依赖 | 低 |
| 4 | `/api/match` 的 controller/service 编排 + **本地引擎**（3.1/3.2） | 先把"接口独立"这个得分点做出来，不依赖网络与配额 | 换 `findit.match.engine` 一个配置值 | 中：若先做外部实现，联调会被 Key/配额卡住 |
| 5 | 外部实现并存但默认关闭（3.2 可选） | 用第 4 步的接口验证"可替换"这条验收，只增文件不改调用方 | 删除 `engine.remote` 包 | 低 |
| 6 | 前端三页面（4.1/4.2） | 后端契约已冻结，前端不需要 mock | — | 低 |
| 7 | 测试与评估（5.1） | 用 `t_match_result` 做效果证据链 | — | 低 |
| 8 | 交付材料（6.1/6.2） | 文档从 `docs/` 长出来，不重写 | — | 低 |
| — | **可后置/可不做** | ArchUnit、CI、限流、embedding 缓存、图片上传 | — | 引入越早越容易变成"架构宇航员"，全部需先组内确认 |

**明确建议现在不要做的**（写下来是为了挡住将来有人顺手加）：缓存（Redis）、消息队列、独立 AI 服务进程、读写分离、对象存储、鉴权、管理后台、软删改物理删、OpenAPI 代码生成。每一条在当前输入下都缺收益依据。

---

## 12. 架构决策记录（ADR）

格式：标题 · 状态 · 背景 · 决策 · 后果（含被放弃的备选与放弃原因）。

**ADR-001 一张 `t_record` 同时承载失物与招领** · 提议
背景：两类启事字段几乎同构，但三张表的名额要留给别的用途。决策：单表 + `type` 枚举区分，跨类型匹配（寻物↔招领）在 service 层按 `type` 过滤候选池。后果：CRUD/DTO/页面各一套通吃两类，代码量约省一半；代价是 `type` 参与所有查询条件与索引，且若将来两类字段分叉（如招领需保管地点），要在同表上加可空列或拆表——届时属**不可逆变更**，需提前报。放弃：`t_lost`/`t_found` 两张表（放弃原因：表名额不够 + 匹配必须跨表 UNION，复杂度高收益为零）。

**ADR-002 三张表 = 记录 + 匹配快照 + 分类字典** · 提议
背景：范围讨论给出"失物/招领记录 + 匹配结果或分类"，需要在二者中取舍。决策：都保留，第三张即分类字典。后果：评估证据链完整、分类可配置；代价是 JOIN 与一次额外写入。放弃：用户表/图片表（无登录、对象存储出范围）；放弃：把分类塞成 ENUM 列（改分类要 DDL + 迁移）。**触发第四张表的条件（须先组内确认）**：① 需要 SQL 级统计匹配候选；② 需要 embedding 持久化；③ 需要认领申请流程；④ 需要图片凭证。

**ADR-003 后端按能力分包，层在能力内部** · 提议
背景：课程教程常见顶层 controller/service/mapper 三桶。决策：`record`/`category`/`match` 三包 + `common`。后果：新增能力只增包；代价与适用见 §5.2。放弃：顶层三桶（放弃原因：改动面 ×3、包内聚信息丢失、能力边界靠命名约定维持）。

**ADR-004 原生 MyBatis（接口 + XML），不用 MyBatis-Plus** · 提议
背景：可替换性与 SQL 可读性的权衡。决策：`mybatis-spring-boot-starter 3.0.5`，手写 SQL 与 `LIMIT` 分页，不引 PageHelper。后果：SQL 全在仓库里、答辩可直接讲执行计划、依赖最少、无 Boot 大版本 starter 匹配坑（外部资料：MP 的 Boot4 starter 需 ≥3.5.15 才稳定）；代价：约 6 个方法要手写样板 SQL。放弃：MyBatis-Plus（省样板但引入"版本对照表"风险与隐式查询语义，评分项 20 分不吃这个）。**注：Boot 4.1.1 + starter 4.1.0 同样可行，见 ADR-016。**

**ADR-005 HTTP 状态码 + body `code` 双轨** · 提议
背景：错误表达有两条传统路径。决策：双轨且由 `ErrorCode` 单一映射，controller 不可手填。后果：前端可分流、文案可精确；代价是两处事实需一致性保证。放弃：全 200 + code（放弃原因：把传输层错误藏进 200，浏览器/网关/日志看不到失败率）；放弃：纯 HTTP 码 + message（细分原因不足，前端要按文案匹配字符串，脆）。

**ADR-006 逻辑删除（`deleted_at`）+ `status=CLOSED` 表达下架，两者语义分离** · 提议
背景：1.1 要定义"删除与下架规则"，DDL 必须先能承载两者。决策：`CLOSED`=下架（可展示、不参与匹配候选池）；`deleted_at`≠NULL=删除（一切读路径不可见）。后果：`t_match_result` 的历史引用不会因删除而失真，回表过滤保证前端不出现死链；代价：每条查询都要带 `deleted_at IS NULL`，忘带即 bug。放弃：物理删除（放弃原因：快照外键无法约束 JSON、评估数据会莫名变少）。**若 1.1 判定"删除即 CLOSED"，则 `deleted_at` 删列 —— 不可逆点⑥，列 Q-03。**

**ADR-007 `/api/match` 用 POST 且每次调用写快照** · 提议
背景：语义匹配"像查询"但输入是长文本（GET + query string 有长度与编码限制），且快照是有副作用的写。决策：`POST /api/match`，允许非幂等写。后果：URL 语义诚实、日志里看得出是一次计算；代价：不符合"GET 才安全"的直觉，重试会重复写快照，且无 GET 缓存可用（本项目不需要）。放弃：`GET /api/matches?text=…`（放弃原因：描述文本 2000 字塞不进 query）；放弃：不落快照（放弃原因：5.1 评估与答辩证据没有落点）。**响应体含 `matchId` 属对外契约的一部分，一旦前端用了就去不掉——标为不可逆点②。**

**ADR-008 引擎只返回 `recordId + score + reason`，展示字段由 service 回表** · 提议
背景：外部实现不该知道"未删除/状态可展示"这类业务规则。决策：引擎输出最小三元组，`MatchService` 回 `t_record` 取 title/type/status 并过滤非法候选。后果：外部实现可做成纯文本→排名的无状态调用，替换成本最低；删除记录自动从结果消失；代价是多一次 `IN` 查询（候选 ≤20 条，可忽略）。放弃：引擎直接返回完整展示对象（放弃原因：候选合法性由算法负责，跨实现不一致，正是"分布式单体"式隐性耦合的微缩版）。

**ADR-009 引擎切换用配置 + `@ConditionalOnProperty`，不做自动回退** · 提议
背景：验收要求"换实现只换实现类，接口与调用方不变"。决策：`findit.match.engine=local|external` 二选一装配，外部不可用即 30002/503。后果：调用方只认 `MatchEngine`；行为可预期、分数口径不因静默降级而混杂；代价是可用性下降（外部挂 ⇒ 匹配不可用）。放弃：自动回退本地（放弃原因：用户以为在用"语义匹配"实际拿到关键词分数，评估数据被污染——这是答辩口径上的诚信问题）；放弃：多引擎并行取交集（成本与延迟 ×2，收益无法证明）。

**ADR-010 前端不引入 Pinia，筛选状态放 URL** · 提议
背景：任务要我回答"是否需要 Pinia"。决策：不引入。唯一的跨页状态是分类字典，用一个模块级 Promise 缓存（`src/api/categoryCache.ts`，约 12 行）；筛选/分页写进 query（`?type=FOUND&categoryId=2&page=2&keyword=…`）。后果：刷新可保留、浏览器前进后退自然可用、链接可分享、无 store 心智成本；代价：缓存模块要自己处理并发去重与失效。放弃：Pinia（放弃原因：当前没有需要响应式共享的客户端状态；引入的成本是目录 + 概念 + devtools，收益不到）。**补加条件**：4.1 若出现"跨页保留表单草稿/多步交互"，再加 Pinia 且只建 `useRecordDraftStore`，现有代码零改动（可逆）。

**ADR-011 原生 `fetch` 封装，不引入 axios / UI 组件库** · 提议
背景：三页面 + 一个表单。决策：`src/api/http.ts` 一个 60 行内的封装（超时、`ApiResponse` 解包、错误转 `BizError`）；样式手写 + CSS 变量 token。后果：依赖面小、4.2 自定义视觉不受组件库主题约束；代价：加载态/空态/分页条要自己写（组件清单见前端基线）。放弃：Element Plus（放弃原因：与 4.2 的自定义视觉基线冲突，且评分项不吃组件库）；放弃：axios（放弃原因：拦截器带来的抽象在本项目只有一个用途——加 base url，fetch 一行即可）。

**ADR-012 Vite 代理为主 + 后端显式 CORS 兜底** · 提议
见 §9.4。放弃：只配 CORS（放弃原因：开发期浏览器告警噪声，且新同学容易写死绝对 URL）。

**ADR-013 DB snake_case ↔ JSON camelCase，靠 `mapUnderscoreToCamelCase=true`** · 提议
后果：Mapper 不必为每个列写别名，少一类"漏映射成 null"的错误；代价：DB 与前端两侧命名不同，答辩时要解释一句。放弃：全链路 snake_case（Java 字段名违规）；放弃：两侧都 alias 手写（样板易漏）。

**ADR-014 排序/筛选字段一律 service 层白名单映射** · 提议
背景：MyBatis 的 `ORDER BY ${col}` 是本项目唯一可能的 SQL 注入面。决策：`sortBy` 只接受枚举，映射到硬编码列名；任何来自请求的值不得进入 `${}`；`keyword` 走 `#{}` + LIKE，特殊字符按 LIKE 语义转义。后果：注入面关闭，代价是新增排序字段要改白名单（一处）。放弃：直接透传（放弃原因：OWASP Top-10 级别的现实风险，且这门课的评分不会为它加分）。

**ADR-015 不用 Flyway/Liquibase** · 提议
见 §9.6。

**ADR-016 Spring Boot 取 3.5.x 而非 4.1.x** · 提议
背景：Boot 4.1.1 是当前 GA 最新线，但生态（MyBatis starter 4.x 刚出、教材与课程资料多为 3.x、毕设改造要复用现有参考）。决策：**3.5.16 + mybatis-spring-boot-starter 3.0.5 + JDK 17**。后果：与课程材料一致、依赖版本对照表最短；代价：错过 Boot 4 的新特性，将来升级是一次独立任务。放弃：Boot 4.1.1 + starter 4.1.0（放弃原因：对一人课程项目而言，把时间花在三方 starter 兼容性上的期望成本为负；若你倾向追新，这条**随时可换**——只在 `pom.xml` 一处，接口与 DDL 不受影响）。
> 版本号来源：repo1.maven.org 的 `maven-metadata.xml`（2026-10-21 查询）+ MyBatis 官方兼容性说明（Boot 3.2–3.5 ↔ starter 3.0.x，Boot 4.0.x ↔ 4.0.x）。**实际可用性需 2.1 在真实构建中确认。**

---

## 13. 自检对照（交付前逐条核过）

- 三个上下文都有职责、数据所有权、不变量 → §3 ✔
- 每个决策都写了被放弃的备选与原因 → §12（16 条）✔
- 每个抽象自证挡什么变化 → `MatchEngine`(§12-008/009)、`t_category`(001/002)、包结构(003)、不引 Pinia/axios/UI 库(010/011) ✔；未引入抽象的项也写明了不引入的理由（缓存/MQ/鉴权）✔
- 两个以上候选方案 + 失效场景 → §1、§12、`docs/open-questions.md` 的选项表 ✔
- 不可逆项单独标出，统一编号（`open-questions.md` 与 `api-contract.md` 都按这套编号引用）：**①** 列长度/字段含义随数据固化（§11 步 1、Q-04）· **②** 响应体含 `matchId`，前端一旦使用就去不掉（ADR-007）· **③** 前端 TS vs JS（Q-16）· **④** `contact`/`publisherName` 的可空性与可见性（Q-01）· **⑤** 第四张表 / `edit_token` 加列（Q-09、Q-02 选 B）· **⑥** 软删改物理删（ADR-006、Q-03）。✔
- 质量属性标注业务要求/我的建议 + 验证方式 → §10 ✔
- 阶段可独立回滚 → §11 ✔
- 每个数字标来源；无依据的一律标"估算/需实测" → §9.1（版本有仓库来源）、§10（QPS/量级标待实测）、A7 标经验区间 ✔
- 范围外条目未写进基线，全部转待确认 → §11 末段 + `open-questions.md` ✔
