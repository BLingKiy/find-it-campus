# v2 · F3 安全与代码审查（ZHYYKFX-60）

> 状态：**P1 已闭环（修复 + 复验通过）→ 门禁 3 通过**
> 审查分支：`feat/v2-F3-security-review`（从 `feat/v2-F2-integration@941fdee` 切出；`origin/main` 实读 `21784f67`，v1，无 v2 代码）
> 审查基线：`941fdee506e6dad2263a6ba383cf4d84ca1d936a`（含 D1/E1/E1b/E2/D2 与 F1 回归资产）
> 审查时间：2026-10-28（Asia/Shanghai）
> 审查方式：静态代码审查 + 仓库 `MaskingPolicy` 纯函数 harness + 项目同版本 Jackson（2.21.4）反序列化 harness + 重新构建 jar 解包 + Git 历史扫描。
> 复验：2026-10-28，基于 `941fdee` + `08f4bb9`（Z-71）+ `fbd538f`（Z-72）合并后的独立工作树；服务端全量 **242/242** 通过。详见 §18。
> 未执行：本机 MySQL（3306）未运行，因此没有启动后端做 HTTP 级复验。会话/Cookie/缓存头等结论按代码与配置判定；动态复核项已标注「建议本地验证」。
> 修复单：P1 `ZHYYKFX-71`（磐石石）→ `fix/v2-F3-p1-masking-normalize@08f4bb9`；P2 `ZHYYKFX-72`（磐石石）→ `fix/v2-F3-p2-hardening@fbd538f`。均基于 `941fdee`，尚未合并进集成分支。

---

## 1. 结论

| 项 | 结果 |
|---|---|
| P0（可直接利用的高危漏洞 / 数据丢失 / 密钥明文） | **0** |
| P1（门禁阻塞；必须修复单 + 复验结论） | **0 未闭环**（原 1：MaskingPolicy 分隔符/国际前缀绕过，已由 Z-71 修复并复验通过） |
| P2（建议修复；不阻塞本轮门禁） | **0 未闭环**（原 4：状态竞态、缓存头、日志注入、过期扫描，已由 Z-72 修复并复验；可选 P2-5 部分完成） |
| P3（细节/文档/加固） | 见 §4.3；复验新增 R-1/R-2 两项非阻塞遗留，见 §18.3 |

**门禁 3 判定：通过。** 依据任务验收标准「有 P1 时必须修复单 + 复验结论齐备才可判通过」：P0 为 0；唯一的 P1-1 已由 `ZHYYKFX-71` 在 `fix/v2-F3-p1-masking-normalize@08f4bb9` 修复，§7 的 8 类输入与 JSON 入口全部复验命中，服务端全量 242/242 通过；P2-1..P2-4 也已在 `ZHYYKFX-72@fbd538f` 修复并复验。两个修复分支均基于 `941fdee`，与报告分支无文件重叠，可独立合并。

**最重要的三个关切（原始审查）：**


1. **遮蔽可被自然写法绕过**：`138 1234 5678`、`138-1234-5678`、`+8613812345678` 等输入不会被 `MaskingPolicy` 打码，匿名/非所有者响应、pair 附言、LLM prompt 与 `t_match_result` 快照都可能出现完整号码，等于绕过 0.75/0.90 的联系方式分档。
2. **一个终态不可改的竞态**：`PUT /api/records/{id}` 在「Java 查 PENDING」与「UPDATE」之间没有 `status='PENDING'` 条件；并发 `PATCH` 先关闭后，PUT 仍可改终态记录正文。见 P2-1。
3. **个性化 GET 响应没有 `Cache-Control`**：本人详情（明文 `contact`）、`/api/my/records`、通知等响应未设 `no-store/private`；当前单实例本地部署不构成现网可利用，但上共享缓存/CDN 后可能跨身份返回视图，绕过 COMPLETED 详情的 404 门槛。见 P2-2。

> 以上三项均已处理：关切 1 → §3 复验通过（Z-71）；关切 2 → §4.1.1 P2-1（Z-72）；关切 3 → §4.1.1 P2-2（Z-72）。

**做得好的地方（不是客套）：** 登录的时序等化与统一失败文案（`AuthService`）、`changeSessionId()` + 失效 + 清 Cookie（`SessionAuth`）、写默认拦 + controller 二次 `requireUser`（`AuthGateFilter`/各 controller）、pair 三档判定与错误码顺序（`PairAccessPolicy`/`PairContactService`）、审计 fail-closed（`AccessLogService`）、pair 重算只更新分数列（`PairScoreMapper.upsertScore`）、状态写入收敛（`RecordService`/`RecordMapper`）、SQL 全 `#{}` + ORDER BY 白名单、密钥只走 env。详见 §13。

---

## 2. 基线与方法

| 项 | 值 |
|---|---|
| 分支 | `feat/v2-F3-security-review` |
| 基线 | `feat/v2-F2-integration@941fdee`（merge-base 与 `origin/main` 为 `21784f67`） |
| 变更面 | `git diff --stat origin/main...HEAD`：400 files / 34406 insertions / 2083 deletions（含 v2 全部代码、测试与证据） |
| 构建 | `server/mvnw -o -q -DskipTests package` → exit 0，新 jar `server/target/find-it-campus-0.0.1-SNAPSHOT.jar`（28.6 MB） |
| 纯函数 harness | 直接用仓库 `MaskingPolicy.java` 编译运行；16 个输入见 §7 |
| JSON harness | 项目 jar 内 Jackson 2.21.4 + 仓库 `MaskingPolicy`；5 个输入见 §7 |
| 密钥扫描 | 代码 / docs（含 docx、pptx 解包 XML）/ 日志 / 打包产物 / Git 历史五面，见 §9 |
| 未执行事项 | 后端启动、MySQL、浏览器/E2E；本机 3306 未运行。相关动态项标注「建议本地验证」 |

**审查边界：** 本文只评审基线代码；没有修改业务代码、没有改 main、没有合并。所有结论都指向文件与行号；拿不到运行时上下文的地方（如容器实际下发的 `Set-Cookie`、生产反代是否缓存）明确写成「按配置/代码判定」或「建议本地验证」。

---

## 3. P0 / P1 清单

### P0

**未发现。** 没有 SQL 注入（`${}` 实际为 0，见 §10）、没有匿名写/越权读写（见 §6）、没有明文密钥（见 §9）、没有无条件的批量删除/更新（见 §11）。

### P1-1 · 遮蔽：MaskingPolicy 可被分隔符/国际前缀绕过

**优先级：P1（门禁阻塞）** · 类别：隐私边界 / 联系方式分档绕过

**位置：**
- `server/src/main/java/com/findit/campus/common/masking/MaskingPolicy.java:53`：`MOBILE_RE = (?<!\d)1[3-9]\d{9}(?!\d)`，要求 11 位连续。
- 同文件 `:115-125`：`maskDescription` 只跑四条正则，没有分隔符归一。
- 同文件 `:151-164`：`normalizeDigitsAndColon` 只把全角数字 `０-９` 与全角冒号 `：` 映射为 ASCII，不处理空白、制表符、换行、连字符、零宽字符、全角空格。
- 出口（同一套规则）：`RecordDetailResponse.java:117-122`、`RecordListItemResponse.java:58-61`、`MatchItemResponse.java:69-76`、`PairCard.java:72-80`、`PairPrompt.java:58-64`、`MatchService.java:259-283`、`PairUnlockService.java:157-174`。

**现状：** 连续 11 位手机号、全角数字手机号、带 QQ 关键词的 5–9 位号都能命中；但只要在数字之间插入空格/换行/制表符/连字符/零宽字符/全角空格，或加 `+86` 前缀，就不会命中，原样返回。带空格的 18 位证件号同样不命中。`maskLocation` 只做 NFKC + 空白折叠 + 8 码点截断，不做号码打码；号码放在地点开头时可能残留 4 位数字（见 §7 V13），不构成完整号码，但说明地点字段没有复用号码规则。

**原因：** 规则把「号码」定义为连续数字串，归一化只覆盖全角数字/冒号。攻击者或普通用户不需要任何工具，按日常习惯写 `138 1234 5678` 就能绕过。`+86` 场景下 `(?<!\d)` 把前缀里的 `6` 当成前导数字，进一步阻止匹配。

**影响：**
1. 匿名/非所有者读取列表、详情、`/api/match`、pair 卡片、pair 附言时可能看到完整手机号；
2. 发布者可用自然写法绕过联系方式分档（0.75 授权档 / 0.90 直看档），使「号码只从 `GET /api/pairs/{id}/contact` 出」的边界失效；
3. 同一规则用于 LLM prompt（`PairPrompt`）与 `t_match_result` 快照（`MatchService.writeSnapshot`），会把未打码号码发给第三方模型并写进评估证据；
4. 带空格证件号同样不被遮蔽。

**证据：** 仓库 `MaskingPolicy` 编译运行 + 项目同版本 Jackson 运行，输出见 §7；8 类输入可稳定复现（空格、换行、制表符、连字符、零宽字符、全角空格、`+86`、带空格证件号）。这不是「理论上可能」，而是当前实现的直接结果。

**建议：**
1. 在 `maskDescription` 内增加「分隔符归一检测」：先把数字串中的空白（`\s`、U+3000、U+200B/U+200C/U+200D/U+FEFF）、`-`、`.`、`/`、`\`、括号等剥离，判断归一后的连续串是否匹配 11 位手机号或 18 位证件号；命中时替换原文中对应的整段，保留可读文案。
2. 识别 `+86` / `0086` 前缀后整段替换，不要只依赖 `(?<!\d)`。
3. 不扩大裸 5–9 位数字（学号/房间号/卡号）的打码，保持 A1 §6.2 的 N1/N2/N3 反例；地点字段建议在截断前也过一次号码规则。
4. 至少补 8 个单测（§7 的 1–8）+ 现有 P1–P3/N1–N3 全绿；建议加随机分隔符 fuzz。
5. 如果认为这属于修改 A1 §6.2 冻结规则，请先与 A1/架构 owner 确认口径。代码层可以按「归一后检测、原文替换」实现，不改变四条规则的顺序与语义。

**验收（修复单 `ZHYYKFX-71`）：**
- 上述 8 类输入在匿名/非所有者路径全部不再出现完整号码；连续号码、全角号码仍正常遮蔽。
- 现有 `MaskingPolicyTest` 与 C1 证据断言 0 回归。
- 回写修复 commit SHA + 新增用例执行输出；F3 复验后在本文 §3 更新 P1 状态与复验结论。
- 只改 `MaskingPolicy` + 测试，不改业务状态机、DB、接口契约。

**复验状态：已复验通过（2026-10-28）。**

- **修复**：`fix/v2-F3-p1-masking-normalize@08f4bb9`（基线 `941fdee`），只改 `MaskingPolicy.java` 与 `MaskingPolicyTest.java`，未改业务状态机、DB、接口契约。
- **方法**：用修复后的仓库 `MaskingPolicy` 重跑 §7 的 16 组输入 + 5 组 JSON 入口；同时在「`941fdee` + Z-71 + Z-72」工作树跑服务端全量测试。命令与原始输出见 §18.1/§18.2。
- **8 类判据逐条**（`FULL_PHONE=false` 表示输出中不再存在完整 11 位号码）：

| 原编号 | 变形 | 修复后输出 | 完整号码残留 |
|---|---|---|---|
| V3 | 空格分隔 | `联系 [手机号已隐藏]` | 否 |
| V4 | 换行拼接 | `联系 [手机号已隐藏]` | 否 |
| V5 | 连字符分隔 | `联系 [手机号已隐藏]` | 否 |
| V6 | 零宽字符 | `联系 [手机号已隐藏]` | 否 |
| V7 | `+86` 前缀 | `联系 [手机号已隐藏]` | 否 |
| V12 | 带空格 18 位证件号 | `证件 [证件号已隐藏]` | 否（`FULL_ID=false`） |
| V15 | 制表符分隔 | `联系 [手机号已隐藏]` | 否 |
| V16 | 全角空格 | `联系 [手机号已隐藏]` | 否 |

- **回归口径**：V1 连续号码、V2 全角数字仍正常遮蔽；V8/V9 仍原样保留（人眼不可读为号码，未计入绕过）；V13/V14 地点字段仍只残留 1–4 位（P3-4 未变，不构成完整号码）；J1 仍命中，J2 仍 400，J3/J4/J5 修复后全部命中。
- **测试**：`MaskingPolicyTest` **18/18**；出口层 `RecordDetailResponse`/`RecordListItemResponse` 的分隔号码断言通过；服务端全量 **242/242**、0 fail（§18.1）。
- **判据结论**：8 类输入在匿名/非所有者出口不再出现完整号码 ✓；现有 `MaskingPolicyTest` 与 C1 断言 0 回归 ✓；只改 `MaskingPolicy` + 测试 ✓。**P1-1 闭环。**
- **未覆盖**：本机 MySQL 未运行，未做 HTTP 级 28 探针重扫；出口 DTO 层已由单测覆盖，建议在集成环境重跑 `F2-phone-scan`。
- **复验新增遗留（非阻塞）**：逗号/冒号/分号（含全角）不在分隔符集合内，`138,1234,5678`、`138：1234：5678` 等仍可绕过；详见 §18.3 R-1。这不改变本轮 8 类判据的结论，但建议在 Z-71 追加一轮小修或另开单。


---

## 4. P2 / P3 清单

### 4.1 P2（建议修复，不阻塞本轮门禁）

| # | 问题 | 位置 | 为什么是问题 | 建议 |
|---|---|---|---|---|
| P2-1 | `updateContent` 缺少 `status='PENDING'` 条件 | `RecordService.java:182-217`（188-191 先查，205 再改）；`RecordMapper.xml:200-213`（WHERE 只有 id + deleted_at） | PUT 与 PATCH 并发时，PATCH 先把记录关成 CLOSED/COMPLETED 并提交，PUT 仍会改该终态记录的正文/联系方式；违反「终态不可再改内容」 | UPDATE 增加 `AND status='PENDING'`；0 行时回读并返回 20003/20002；补并发单测 |
| P2-2 | 个性化 GET 响应缺少 `Cache-Control`/`Vary` | `RecordController.java:54-66`、`MyRecordsController.java:38-43`、`NotificationController.java:41-46`、`MatchController.java:42-46`；只有 `PairController.java:64-67` 设了 `no-store` | 本人详情含明文 `contact`、`/my` 与通知是个人数据；上共享缓存/CDN 后可能把 A 用户视图返回给 B 用户，绕过 COMPLETED 详情的 404 门槛 | 对 `/api/**` 个性化响应统一加 `Cache-Control: no-store`（或 `private, no-store`）+ `Vary: Cookie`；补响应头断言 |
| P2-3 | 日志注入：客户端可控值未净化 | `AuthService.java:83-84` 直接 log `studentNo`；`LoginAttemptTracker.java:65-66` 直接 log key；`GlobalExceptionHandler.java:142` 直接 log 查询参数 `ex.getValue()`；`LoginRequest.java:18-20` 只限制长度 | JSON 字符串可含换行，查询参数经 URL 解码后也可含 `%0A`；可伪造日志行，影响审计可信度（不是数据泄露） | 统一 log-safe（替换 CR/LF/TAB/控制字符并限长），或给 `studentNo` 加严格 `@Pattern`；补单测 |
| P2-4 | 过期 COMPLETED 仍可能成为扫描目标 | `RecordMapper.xml:303-313`（只排除 CLOSED，没有 `completed_at` 保留期条件）；`PairScoringService.java:109-120`（只跳过 CLOSED） | 过期 COMPLETED 若处于 PENDING/SCORING/DEGRADED，会被重新打分，可能新建 pair/通知并消耗 LLM 额度；通知指向的详情按规则 404。与「到期不召回」不一致，但不改 status、不泄露号码 | 扫描目标加 `(status='PENDING' OR completed_at > #{cutoff})`，或在 `scoreRecord` 用 `RecordVisibilityPolicy.isMatchable` 跳过；补「过期 + DEGRADED」组合用例 |

### 4.1.1 P2 复验状态（Z-72 `fix/v2-F3-p2-hardening@fbd538f`，基线 `941fdee`）

| # | 状态 | 修复要点与证据 |
|---|---|---|
| P2-1 | **已修复** | `RecordMapper.xml:229` 的 `updateContent` WHERE 增加 `status='PENDING'`；0 行时 `selectStatusByIdForUpdate`（`FOR UPDATE` 当前读）区分 20001（不存在/已删）与 20003（并发终态）。`RecordServiceAccessTest` 新增并发终态、已删除、正常路径 3 例。JDBC URL 未设 `useAffectedRows=true`，Connector/J 默认返回 matched rows，提交相同内容不会把「无变化」误判成冲突。 |
| P2-2 | **已修复** | 新增 `ApiResponseHeadersFilter`：`/api/**` 统一 `Cache-Control: no-store` + `Vary: Cookie`，并补 `X-Content-Type-Options`/`X-Frame-Options`/`Referrer-Policy`/CSP 最小头；顺序为 `RequestIdFilter`（HIGHEST）→ 本过滤器（+10）→ `AuthGateFilter`（默认最低），401 短路响应同样覆盖。`RecordApiValidationTest` 对 200 详情/列表与 401 断言响应头。 |
| P2-3 | **已修复** | 新增 `LogSafe`：CR/LF/TAB→`_`，其余控制字符与 U+2028/U+2029→`?`，限长 128 并标注原始长度；应用于 `AuthService` 登录失败、`LoginAttemptTracker` 阈值日志、`GlobalExceptionHandler` 的参数值/请求体/路径。`LogSafeTest` 4/4，`AuthServiceTest`/`LoginAttemptTrackerTest` 各补 1 例日志注入断言。 |
| P2-4 | **已修复** | `selectPairScanTargets` 改为 `PENDING OR (COMPLETED AND completed_at > #{completedCutoff})`；`scoreRecord` 用 `RecordVisibilityPolicy.isMatchable` 再兜一层，过期返回 `EXPIRED` 且 `failed()=false`。`RecordMapperSqlTest` 断言 WHERE 结构，`PairScanServiceTest` 断言 cutoff ≈ now-30d，`PairScoringServiceTest` 覆盖「过期 + DEGRADED」与「保留期内仍重算」。`completedCutoff(now)=now-30d` 与 `isWithinRetention` 的严格 `>` 语义一致。 |

**复验结论：P2-1..P2-4 全部闭环。** 两个修复分支与报告分支文件不重叠，合并无冲突；在合并工作树上服务端全量 242/242 通过（§18.1）。

### 4.2 P2-5（可选加固，需产品/部署确认）

| 项 | 位置 | 建议 |
|---|---|---|
| 提示注入 / LLM 配额 | `PairPrompt.java:58-64`；`POST /api/records`、`POST /api/match` | 用户文本未做提示注入隔离，且没有每用户发布/匹配配额。建议加分隔/指令约束、分数合理性兜底、发布/匹配限流与 LLM 预算上限；不作为本轮门禁阻塞 |
| 生产 Cookie `Secure` + HSTS | `application.yml:23-32`（`secure: false`） | 当前是 HTTP 演示口径，文档已注明生产加 `Secure`；生产 profile 必须 `secure: true`，反代加 HSTS。否则会话 Cookie 可被网络嗅探 |
| 审计 IP 可伪造 | `AuditMeta.java:22-28` 信任 `X-Forwarded-For` | 审计字段用于追溯，可被客户端伪造；建议可信代理配置，或同时记录 `remoteAddr` |
| Session tracking modes | `application.yml:16-32` 未显式设置 | 建议显式 `server.servlet.session.tracking-modes: cookie`，避免 `;jsessionid` 出现在 URL/Referer |
| 安全响应头 | `WebConfig.java:27-36` 只有 CORS | 未用 Spring Security，默认无 `X-Content-Type-Options`/`X-Frame-Options`/CSP/HSTS；可加统一 filter（低风险，建议补） |

**P2-5 复验（Z-72，部分完成）：** `server.servlet.session.tracking-modes: cookie` 已加，禁止 `;jsessionid` URL 重写；`ApiResponseHeadersFilter` 同时补了上面最后一项的最小安全响应头。生产 Cookie `Secure`/HSTS、XFF 可信代理、提示注入/LLM 配额仍属部署或产品决策，保持「可选加固、不阻塞本轮」。

### 4.3 P3（细节 / 文档 / 已知风险）

| # | 项 | 位置 | 说明 |
|---|---|---|---|
| P3-1 | `db/README.md:72` 仍写「v1 的 `RecordStatus` 枚举仍是 `PENDING/CLAIMED/CLOSED`」 | `db/README.md:72-75` | 代码/DB 已三态；这句是过期的 v1 说明，建议改成「v1 历史取值，v2 已作废」或删除，避免下游误判 |
| P3-2 | 文档里的假密钥占位串 | `docs/testing/v2/gate2-review.md:47,136` | `sk-local-stub-not-a-real-key` 不是凭据（含连字符，不匹配真实密钥形状），但会被一些 secret scanner 误报；建议改成 `<demo-token>` |
| P3-3 | 登录失败日志含学号明文 | `AuthService.java:83-84`、`LoginAttemptTracker.java:65-66` | 学号属于个人信息（架构 §11.4）；若日志会导出/共享，建议脱敏（hash/掩码），至少与 P2-3 的 log-safe 一起处理 |
| P3-4 | 地点字段不打码，只截断 | `MaskingPolicy.java:79-105` | 号码放在地点开头时可能残留 1–4 位数字（见 §7 V13/V14），不构成完整号码；建议截断前过号码规则 |
| P3-5 | 无 CSRF token | `AuthConfig.java:13-21`、`v2-auth-session.md:119` | 按契约「SameSite=Lax + 写操作只用非 GET」；残余风险是同站 XSS/子域与旧浏览器。当前无 `v-html`，前端 Vue 默认转义，可接受；若威胁模型变化再加 token |
| P3-6 | 无登录速率限制/锁定 | `LoginAttemptTracker.java:24-26` | 契约明确「5 次不锁账号、只记日志」；残余风险是在线撞库。建议在反代/IP 层限流，不在应用层改契约 |
| P3-7 | `contact` 明文落库 | `schema.sql:117`、架构 §11.3 | C1 已拍板「只在输出层打码」；能读 DB 的人能看到号码。生产必须限制 DB 账号/网络并加密卷 |
| P3-8 | `t_access_log` 同库同账号可被改写 | `schema.sql:290-316`、架构 §11.3 | 审计是 append-only 约定，不是 DB 权限强制；生产建议只给应用 INSERT/SELECT |

---

## 5. 会话安全核对

| 项 | 结论 | 证据 / 说明 |
|---|---|---|
| Session 固定 | **通过** | 登录成功 `getSession(true)` 后立刻 `request.changeSessionId()`；`SessionAuth.java:76-80` |
| Cookie 属性 | **通过（生产需改 Secure）** | `Path=/`、`HttpOnly=true`、`SameSite=lax`，`application.yml:23-32`；`Secure=false` 是 HTTP 演示口径，生产必须 `true`（P2-5） |
| Cookie 作用域 | **通过** | 未设 `Domain`，host-only；登出删除 Cookie 用同 name/path（`SessionAuth.java:108-115`） |
| CSRF | **按合同接受** | `SameSite=Lax` + 所有写操作只用 POST/PUT/PATCH/DELETE（`v2-auth-session.md:119`）。登录与多数写接口要求 JSON body，跨站表单不能直接构造；`contact-unlock` 可无 body，但跨站 POST 不携带 Lax Cookie，仍会在 `AuthGateFilter` 处 401。CORS 只允许 `localhost:5173`/`127.0.0.1:5173` 且 `allowCredentials=true`（`WebConfig.java:28-36`）。残余风险：同站 XSS/子域、旧浏览器（P3-5） |
| 退出是否真失效 | **通过** | 服务端 `session.invalidate()`（`SessionAuth.java:88-98`）+ `Max-Age=0` 清 Cookie（`AuthController.java:61-67`）；无会话/重复调用幂等 |
| 会话超时 | **通过** | `server.servlet.session.timeout: 30m`（`application.yml:22`）；内存会话，重启即失效（已写进设计文档） |
| Session ID 泄漏 | **未发现** | 日志只记 URI/状态/耗时（`RequestIdFilter.java:49-50`），不记 Cookie/Authorization；不在 URL 中拼 session id（但建议显式 `tracking-modes: cookie`，P2-5） |
| 登录防枚举 | **通过** | 失败统一 401 + 10006 + 「学号或密码不正确」；不存在用户也跑一次 bcrypt dummy hash（`AuthService.java:56-86`） |
| 登录失败计数内存 | **通过** | 上限 10000，过期清理/整体清空（`LoginAttemptTracker.java:24-40,90-99`）；不参与鉴权结果 |
| 安全响应头 | **未配置** | 无 Spring Security；`WebConfig` 只有 CORS。建议补 `X-Content-Type-Options`/`X-Frame-Options`/CSP/HSTS（P2-5） |

**HTTP 级复核建议（本地验证）：** 启动后抓一次 `POST /api/auth/login` 的 `Set-Cookie`，确认 `Path=/; HttpOnly; SameSite=Lax`；再 `POST /api/auth/logout` 后带旧 Cookie 调 `/api/auth/me`，应 401。当前结论按 Spring Boot 配置与实现判定。

---

## 6. 越权核对

| 入口 | 判定 | 结论 | 证据 |
|---|---|---|---|
| `GET /api/records` | 匿名可读，按 `publisher_id` 判 `isMine` | **通过**：非本人标题打码、地点截断；列表不返回 description/contact | `RecordController.java:54-59`、`RecordListItemResponse.java:51-65` |
| `GET /api/records/{id}` | 匿名/登录非所有者/合格对端/所有者分流 | **通过**：PENDING 非所有者遮蔽且 `contact=null`；COMPLETED 仅「本人启事 + score≥0.90 + 非 IGNORED」的合格对端 200；CLOSED/到期/所有者 COMPLETED 404 | `RecordVisibilityPolicy.java:79-101`、`RecordService.java:123-147`、`RecordDetailResponse.java:80-131` |
| `POST /api/records` | 需登录；`publisher_id` 取 session | **通过**：客户端传值被忽略，status 固定 PENDING | `RecordController.java:69-74`、`RecordService.java:149-173` |
| `PUT/PATCH/DELETE /api/records/{id}` | 需登录 + 所有者 | **通过**：非所有者 403（可见）或 404（不可见）；状态只能本人改 | `RecordService.java:181-273`、`RecordVisibilityPolicy.java:103-113`；并发竞态见 P2-1 |
| `GET /api/my/records` | 需登录，SQL 按 session `publisher_id` | **通过**：不读请求里的任何用户 id；本人数据明文回显 | `MyRecordsController.java:38-43`、`MyRecordService.java:59-97`、`RecordMapper.xml:153-176` |
| `GET /api/pairs/{id}/contact` | 需登录 + pair 参与方 + 档位 + 对方授权 | **通过**：非参与方 403；失效 pair 403/50005；低分 403/50002；号码只在此出口；每次查看写审计，审计失败则 500 不出号（fail-closed） | `PairController.java:58-68`、`PairContactService.java:46-96`、`AccessLogService.java:32-47` |
| `POST /api/pairs/{id}/contact-unlock` | 参与方 + pair 可用 | **通过**：只写当前用户所在端；REVOKE 清空本端附言；PUBLISH 阈值 0.75 | `PairController.java:71-79`、`PairUnlockService.java:63-102` |
| `PATCH /api/pairs/{id}` | 参与方 + pair 可用 + 分数 ≥0.75 | **通过**：只接受 action（CONTACT/CONFIRM/IGNORE）；未知字段/`status` 400；MATCHED/IGNORED 终态再改 409 | `PairController.java:82-90`、`PairActionService.java:51-86`、`PairActionRequest.java:17-38` |
| `GET /api/notifications` | 需登录，SQL `user_id = session` | **通过**：不读请求用户 id；输出再打码 | `NotificationController.java:41-46`、`NotificationService.java:37-49`、`NotificationMapper.xml:21-31` |
| `PATCH /api/notifications/{id}/read` | 需登录 + 接收人 | **通过**：他人通知 403/10007，不存在 404/50004；已读幂等 | `NotificationService.java:61-88` |
| `POST /api/match` | 需登录；viewerId 从 session 取 | **通过**：候选可能含保留期内 COMPLETED，但返回 `detailAccess=RESTRICTED`，title/location/reason 遮蔽；不返回 description/contact | `MatchController.java:42-46`、`MatchService.java:113-160`、`MatchItemResponse.java:63-80` |
| `GET /api/categories`、`GET /api/health` | 匿名可读 | **通过**：分类字典与健康状态，无个人数据 | `CategoryController`、`HealthController.java:55-74` |
| `POST /api/ops/pair-scan` | `X-Ops-Token` 常数时间比较；未配置 404 | **通过**：不走会话；未配置时不暴露入口；只返回计数/耗时 | `OpsPairScanController.java:46-68` |

**横向/纵向/pair 级结论：** 未发现「改别人记录」「非所有者看号码」「看别人 pair 联系方式」的路径。所有写接口从 session 取身份；所有 pair 接口先判参与方；号码只在 `GET /api/pairs/{id}/contact` 出，卡片/列表/详情/匹配/通知都不返回对方号码（`PairFactsMapper.xml:104-146` 的 SELECT 列表没有 `contact`，只有 `counterpart_contact_present` 布尔）。

---

## 7. 遮蔽绕过用例（≥6 种变形）

> 下表是**修复前**（基线 `941fdee`）的原始结果，保留作为漏洞证据；修复后（Z-71 `08f4bb9`）的同一组输入复验见 §3 与 §18.2。

**方法：** 直接编译仓库 `server/src/main/java/com/findit/campus/common/masking/MaskingPolicy.java`，调用 `maskDescription`/`maskLocation`；JSON 变体用项目 jar 内的 Jackson 2.21.4 先反序列化再调用同一 `maskDescription`。

| # | 变形 | 输入（description，除注明） | 实际输出 | 结论 |
|---|---|---|---|---|
| V1 | 连续 11 位 | `联系 13812345678` | `联系 [手机号已隐藏]` | **命中（已遮蔽）** |
| V2 | 全角数字 | `联系 １３８１２３４５６７８` | `联系 [手机号已隐藏]` | **命中（全角归一有效）** |
| V3 | 空格分隔 | `联系 138 1234 5678` | `联系 138 1234 5678` | **未命中（绕过）** |
| V4 | 换行拼接 | `联系 138\n1234\n5678` | `联系 138\n1234\n5678` | **未命中（绕过）** |
| V5 | 连字符分隔 | `联系 138-1234-5678` | `联系 138-1234-5678` | **未命中（绕过）** |
| V6 | 零宽字符 | `联系 138\u200B12345678` | `联系 138​12345678` | **未命中（绕过）** |
| V7 | 国际前缀 | `联系 +8613812345678` | `联系 +8613812345678` | **遮蔽未命中；完整 11 位仍在响应中（正则可见）** |
| V8 | 字面 `\u0031` 文本 | `联系 \u0031\u0033...` | 原样（字面转义） | 未命中，但人眼不是号码；JSON 解码变体 J1 命中 |
| V9 | 百分号编码 | `联系 %31%33%38...` | 原样（字面 `%31...`） | 未命中，但不是可用号码；不构成实际绕过 |
| V10 | QQ 小写 | `qq 456789` | `qq[QQ号已隐藏]` | **命中（`(?i)` 有效）** |
| V11 | QQ 大写 | `QQ 456789` | `QQ[QQ号已隐藏]` | **命中** |
| V12 | 带空格证件号 | `证件 1101 0119 9001 0112 34` | 原样 | **未命中（绕过）** |
| V13 | 号码在地点开头 | `maskLocation("13812345678图书馆")` | `1381…` | 未命中完整号码；残留 4 位（P3-4） |
| V14 | 号码在地点末尾 | `maskLocation("图书馆13812345678")` | `图书馆1…` | 未命中完整号码；残留 1 位（P3-4） |
| V15 | 制表符分隔 | `联系 138\t1234\t5678` | 原样 | **未命中（绕过）** |
| V16 | 全角空格 | `联系 138　1234　5678` | 原样 | **未命中（绕过）** |
| J1 | JSON `\u0031` 转义 | `{"description":"联系 \u0031\u0033\u0038..."}` | `联系 [手机号已隐藏]` | **命中（Jackson 先解码）** |
| J2 | 数组包裹字段 | `{"description":["13812345678"]}` | Jackson `MismatchedInputException`，请求 400 | **未命中（不进入遮蔽；也不会入库）** |
| J3 | JSON 空格分隔 | `{"description":"联系 138 1234 5678"}` | 原样 | **未命中（绕过）** |
| J4 | JSON 换行分隔 | `{"description":"联系 138\n1234\n5678"}` | 原样 | **未命中（绕过）** |
| J5 | JSON +86 | `{"description":"联系 +8613812345678"}` | 原样，完整号码可见 | **未命中（绕过）** |

**原始输出摘录（可直接复现）：**

```text
1  V1-contiguous            HIT=false  OUT=联系 [手机号已隐藏]
2  V2-fullwidth             HIT=false  OUT=联系 [手机号已隐藏]
3  V3-space-separated       HIT=false  OUT=联系 138 1234 5678
4  V4-newline-separated     HIT=false  OUT=联系 138\n1234\n5678
5  V5-hyphen-separated      HIT=false  OUT=联系 138-1234-5678
6  V6-zero-width            HIT=false  OUT=联系 138​12345678
7  V7-plus86-prefix         HIT=true   OUT=联系 +8613812345678
8  V8-unicode-escape-literals HIT=false OUT=联系 \u0031\u0033...
9  V9-percent-encoded       HIT=false  OUT=联系 %31%33%38...
10 V10-qq-lowercase         HIT=false  OUT=qq[QQ号已隐藏]
11 V11-qq-uppercase         HIT=false  OUT=QQ[QQ号已隐藏]
12 V12-id-with-space        HIT=false  OUT=证件 1101 0119 9001 0112 34
13 V13-phone-in-location    HIT=false  OUT=1381…
14 V14-phone-in-location-tail HIT=false OUT=图书馆1…
15 V15-tab-separated        HIT=false  OUT=联系 138\t1234\t5678
16 V16-fullwidth-space      HIT=false  OUT=联系 138　1234　5678

J1-unicode-escaped  PARSE=OK   MASKED=联系 [手机号已隐藏]  FULL_PHONE_VISIBLE=false
J2-array-wrapped    PARSE=FAIL MismatchedInputException
J3-space-separated  PARSE=OK   MASKED=联系 138 1234 5678  FULL_PHONE_VISIBLE=false
J4-newline-separated PARSE=OK  MASKED=联系 138\n1234\n5678
J5-plus86           PARSE=OK   MASKED=联系 +8613812345678  FULL_PHONE_VISIBLE=true
```

> 说明：V3/V4/V15/V16 的严格正则 `1[3-9]\d{9}` 确实不命中（因为分隔符），但人眼可读为完整号码；V7/J5 的 11 位子串直接命中正则。两者都属于绕过。V8/V9 不是可用号码，未计入绕过；V13/V14 只残留 1–4 位，未构成完整号码，列为 P3-4。

**结论：** 至少 8 类输入可绕过（V3、V4、V5、V6、V7、V12、V15、V16；J3/J4/J5 为同一根因的 JSON 入口）。这满足验收「至少覆盖 6 种变形输入」的要求，且不是理论推断。

---

## 8. 日志泄漏核对

| 面 | 结论 | 证据 |
|---|---|---|
| 请求头 | **未发现** Cookie/Authorization/手机号被记录 | `RequestIdFilter.java:42-44` 只读 `X-Request-Id`；`:49-50` 只记 method/URI/status/耗时；`AuditMeta.java:22-32` 只取 XFF/UA/requestId 写审计表 |
| 响应体 | **未记录** | 没有 log 语句打印响应 body；`GlobalExceptionHandler` 只打印 message/fieldErrors |
| 异常栈 | **只进日志，不进响应** | `application.yml:12-15` `include-stacktrace: never`；`GlobalExceptionHandler.java:202-207` 500 时 `log.error` 打全栈，body 只回 10005 + requestId |
| 反序列化异常 | **未带请求体** | 项目 Jackson 2.21.4 `INCLUDE_SOURCE_IN_LOCATION disabled`，`ex.getMessage()` 只含行/列/字段路径；本地 harness 输出见下 |
| 手机号/附言原文 | **未发现** | 没有 `log.*contact/note/description`；`AccessLogService.java:44-46` 只记 pairId/viewerId/result；`PairUnlockService` 不打印 note；`DeepSeekPairScorer` 不打印 prompt/response/key |
| SQL 参数 | **默认 INFO 不打印** | `application.yml:127-133` root/`com.findit.campus` 均 INFO；MyBatis `Slf4jImpl` 的 SQL/参数在 DEBUG。若运维把包调到 DEBUG，`Parameters` 可能包含 `contact`——这是运维配置风险，不是默认泄漏 |
| 日志注入 | **已修复（原 P2-3）** | `AuthService.java:83-84` 的 `studentNo`、`LoginAttemptTracker` 的 key、`GlobalExceptionHandler` 的参数/请求体/路径均已过 `LogSafe`；`LoginRequest` 的长度约束保留。复验见 §4.1.1。 |
| 学号 PII | **存在（P3-3）** | 登录失败日志含学号明文；建议脱敏或至少 log-safe |
| F2 实测 | **0 命中** | `docs/testing/v2/F2-phone-scan.txt`：28 个探针（匿名/非本人响应、通知 title+body）0 命中；`t_match_result`/通知/访问审计均不含号码 |
| 仓库 docs/seed 号码 | **402 处形状命中；抽查为夹具/误报** | `git grep -hoE '1[3-9][0-9]{9}' -- 'docs/**' 'db/**'`：seed 明确声明 `138xxxxxxxx` 假号；`179...`/`19900101123` 等为编号/身份证片段，不是真实号码。未发现与真实用户关联的号码；如需对外发布文档，建议再按真实号段人工抽查一遍 |

**Jackson 红acted 证据（项目 jar 同版本）：**

```text
EX body={"description":"13812345678
  class=com.fasterxml.jackson.databind.JsonMappingException
  message=Unexpected end-of-input: was expecting closing quote for a string value
 at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 28] ...
EX body={"description":["13812345678"]}
  class=...MismatchedInputException
  message=Cannot deserialize value of type `java.lang.String` from Array value ... at [Source: REDACTED ...]
```

因此 `GlobalExceptionHandler.handleNotReadable` 的 `ex.getMessage()` 不会把请求体（可能含手机号）带进日志；数组包裹字段也在 Jackson 层 400，不会进入遮蔽逻辑。

---

## 9. LLM 密钥五面扫描

**口径：** 真实密钥形态 `sk-[A-Za-z0-9]{24,}`；另扫宽松 `sk-`、`DEEPSEEK_API_KEY = <值>`、`Bearer <长串>`。全部命令在仓库根执行；完整命令与命中数见本表与 §15.3。

| 面 | 命令（原文） | 命中数 | 结论 |
|---|---|---|---|
| 1 代码 | `git grep -nE 'sk-[A-Za-z0-9]{24,}' -- 'server/**' 'web/**' 'db/**'` | **0**（exit 1） | 无真实密钥形态 |
| 2 docs | `git grep -nE 'sk-[A-Za-z0-9]{24,}' -- 'docs/**'` | **0**（exit 1） | 无真实密钥形态 |
| 2 docs（宽松） | `git grep -n 'sk-' -- 'docs/**'` | **21** | 均为文档自述/占位；其中假占位串 `sk-local-stub-not-a-real-key` **6** 处（`gate2-review.md:47,136` 等），不是凭据 |
| 2 docs（docx/pptx） | `unzip -q program-design.docx -d ...; unzip -q 6.2-答辩PPT.pptx -d ...; grep -rhoE 'sk-[A-Za-z0-9]{24,}' /tmp/docscan` | **0** | 解包 XML 无真实密钥 |
| 3 日志 | `find . -path ./.git -prune -o -path ./node_modules -prune -o -type f -name '*.log' -print` | **0 个日志文件** | 仓库内无 `.log`；F2 日志证据只记路径/字节数/长度，不记值 |
| 3 日志（证据） | `grep -rE 'sk-[A-Za-z0-9]{24,}' docs/testing` | **0** | 证据文本无真实密钥 |
| 4 打包产物 | `server/mvnw -o -q -DskipTests package` → `unzip -q target/*.jar -d /tmp/jarscan2` → `grep -raE 'sk-[A-Za-z0-9]{24,}' /tmp/jarscan2` | **0** | 新构建 jar 无真实密钥；宽松 `sk-` **2** 处均在依赖 jar（`mysql-connector-j`、`spring-expression`），非本项目代码；`DEEPSEEK_API_KEY` **2** 处为类常量/注释，无值；`api-key` 仅 `${FINDIT_EMBED_API_KEY:}` 占位；`application-local.yml` 未被打包（0） |
| 5 Git 历史 | `git log -p --all \| grep -aE 'sk-[A-Za-z0-9]{24,}' \| wc -l` | **0** | 全历史无真实密钥形态 |
| 5 Git 历史（提交数） | `git log --all --oneline -S 'sk-' \| wc -l`；`-S 'DEEPSEEK_API_KEY' \| wc -l` | **6 / 10** | 只说明这些提交触碰过文档/代码中的字符串；硬编码赋值 **0**，`Bearer <长串>` **0** |
| 5 Git 历史（赋值） | `git log -p --all \| grep -aE 'DEEPSEEK_API_KEY[[:space:]]*=[[:space:]]*[A-Za-z0-9]{10,}' \| wc -l` | **0** | 无「变量 = 明文」提交 |

**结论：** 五面均 **0 真实密钥**。唯一需要处理的是文档里的假占位串（P3-2，建议改名以免 secret scanner 误报）。密钥文件位于仓库外的本机密钥目录（F2 记为 `~/.hifox-secrets/deepseek_api_key.txt`），F2 只记录路径/35 字节/长度 35，未打印值；本次审查未读取该文件。

---

## 10. SQL 注入 / 上传 / 导出

| 检查 | 命令 / 证据 | 结论 |
|---|---|---|
| MyBatis `${}` | `git grep -n '\${' -- 'server/src/main/resources/mapper/**'` → 唯一命中 `RecordMapper.xml:8` 注释「所有 `${}` 一律为零」 | **实际 SQL `${}` = 0** |
| ORDER BY 注入 | `RecordMapper.xml:105-138` 用 `<choose>` 硬编码列名/方向；service 只传 `RecordSortField`/`SortDirection` 枚举名（`RecordCondition.java:32-35`） | **无注入面** |
| LIKE 注入/通配符 | `RecordCondition.java:44-50` 对 `/`、`%`、`_` 转义，SQL 用 `ESCAPE '/'`（`RecordMapper.xml:94-97`） | **通过** |
| 参数绑定 | 所有 Mapper 用 `#{}`；`LIMIT/OFFSET`、`IN` 的 `<foreach>` 均 `#{}` | **通过** |
| 原生 JDBC | 仅 `PairLockService.java:63,110`：`GET_LOCK(?, ?)`/`RELEASE_LOCK(?)` 参数化；锁名由数字 id 拼成 | **通过** |
| 注解 SQL | `git grep -nE '@(Select|Update|Insert|Delete)\(' -- 'server/src/main/java/**'` → 0 | 无第二套 SQL |
| 上传 | `git grep -n 'MultipartFile' -- 'server/src/main/java/**'` → 0；`spring.servlet.multipart` 仅限 2MB，无上传接口 | **无上传路径** |
| 导出 | 无 `StreamingResponseBody`/`ResponseEntity<Resource>`/下载接口；端点清单见 §6 | **无导出路径** |
| 命令/文件操作 | `Runtime.getRuntime`/`ProcessBuilder`/`new File`/`Paths.get`/`Files.` → 0 | **无命令注入/路径遍历面** |
| SSRF | LLM base URL 来自配置/env（`application.yml:117`），不接受用户输入；`MatchEngine` 外部实现当前未装配 | **当前无用户可控 URL** |

---

## 11. pair 独立性与状态语义（代码级核对）

### 11.1 结论

**未发现任何自动置位/自动关闭路径。** `t_record.status` 只有三条写语句：`insert`（初始 PENDING）、`markCompleted`、`close`；后两者只被 `PATCH /api/records/{id}/status` 的所有者路径调用。系统任务（打分、通知、扫描、到期统计）不写 `status`/`completed_at`/`updated_at`。pair 的进展不触发记录状态变更。

**未发现「pair 被标为已解决」的路径。** `t_pair_score.status` 只有 NEW / CONTACTED / MATCHED / IGNORED 四态；MATCHED 由两端 `confirmed_at` 均非空派生，没有 RESOLVED/已解决字段或默认表达式。

### 11.2 状态写入路径证据

| 写语句 | 位置 | 写什么 | 谁调用 |
|---|---|---|---|
| `insert` | `RecordMapper.xml:187-194` | `status='PENDING'` | `RecordService.create` |
| `markCompleted` | `RecordMapper.xml:216-222` | `status='COMPLETED'` + `completed_at` + `updated_at`，`WHERE status='PENDING'` | `RecordService.updateStatus`（`RecordController.java:86-94`） |
| `close` | `RecordMapper.xml:224-230` | `status='CLOSED'` + `completed_at=NULL` + `updated_at`，`WHERE status='PENDING'` | 同上 |
| `updateContent` | `RecordMapper.xml:200-213` | 内容字段 + `pair_state='PENDING'`，**不写 status/completed_at** | `RecordService.update` |
| `markDeleted` | `RecordMapper.xml:233-238` | 只写 `deleted_at/updated_at`，不写 status | `RecordService.delete` |
| `markPairScoring` | `RecordMapper.xml:285-289` | 只写 `pair_state='SCORING'` | `PairScoringService` |
| `markPairScored` | `RecordMapper.xml:291-296` | 只写 `pair_state/pair_scored_at` | `PairScoringService` |
| `countExpiredCompleted` | `RecordMapper.xml:316-322` | 只 SELECT COUNT，不改列 | `PairScanService` |

pair 侧：

| 写语句 | 位置 | 写什么 |
|---|---|---|
| `upsertScore` | `PairScoreMapper.xml:68-85` | 只更新 score/engine/degraded_reason/prompt_version/latency/tokens/hash；**不碰 status/confirmed_at/unlock_note/created_at** |
| `markContacted` / `markIgnored` | `PairScoreMapper.xml:126-136` | 只改 `t_pair_score.status`，带 `WHERE status IN (...)` |
| `confirmOwned` | `PairScoreMapper.xml:144-159` | 只改本对两端 `confirmed_at` + `status`（CASE 派生 MATCHED），带 `WHERE status IN ('NEW','CONTACTED')` |
| `publishLost/Found`、`revokeLost/Found` | `PairScoreMapper.xml:161-188` | 只改本对解锁时间/附言 |
| 通知 | `PairScoringService.java:341-362`、`PairUnlockService.java:118-133` | 只写 `t_notification`，正文不含对方标题/号码 |
| 审计 | `AccessLogService.java:32-47` | 只 INSERT `t_access_log`，无 contact/note 列 |

### 11.3 「COMPLETED 当关闭处理」「CLOSED pair 留候选池」核对

- **公共列表**：`RecordMapper.xml:86-99` 的 `listJoins` 硬写 `r.status = 'PENDING'`；COMPLETED 与 CLOSED 都不出现在公共列表。**这不是把 COMPLETED 当 CLOSED**：召回/匹配走另一条谓词 `matchablePredicate`（`RecordMapper.xml:80-83`），其中 COMPLETED 只要 `completed_at > cutoff` 就参与。两处刻意不同，代码注释也写明了（`:75-79`）。
- **候选池**：`selectCandidatePool` 用 `matchablePredicate`（`RecordMapper.xml:244-255`），CLOSED 排除、保留期内 COMPLETED 保留；`PairRecallService.recall` 回表后再复核一次（`PairRecallService.java:93-99,135-143`）；`MatchService.loadSummaries` 也再复核（`MatchService.java:227-243`）。**未发现 CLOSED 进入召回。**
- **CLOSED pair**：`t_pair_score` 行保留（设计如此，读时失效，不做批量 UPDATE）；`PairAccessPolicy.isUsable` 任一端 CLOSED/到期即 false（`PairAccessPolicy.java:33-48`）；`PairFactsMapper` 的计数/卡片 SQL 均过滤 `l.status <> 'CLOSED' AND f.status <> 'CLOSED'` 与保留期（`:12-25,59-90,104-146`）。取号/PATCH/授权都先过 `isUsable`（`PairContactService.java:62-66`、`PairActionService.java:60-63`、`PairUnlockService.java:75-78`）。**未发现把 CLOSED pair 当可用返回的出口**（pair 行本身留在库是设计，不是漏洞）。
- **COMPLETED 404 门槛**：`RecordVisibilityPolicy.detailAccess`（`:79-101`）在 COMPLETED 分支先判保留期，再判 owner（owner 404，A9 字面），再判合格对端；`RecordService.detail` 只在 `!owner && COMPLETED && viewerId != null` 时查 `viewerHasQualifyingPair`（`:128-135`）；SQL 要求 pair `score >= threshold` 且 `status <> 'IGNORED'`（`PairFactsMapper.xml:31-42`）。**未发现绕过该门槛直接返回详情的管理/导出/缓存出口**；唯一的缓存风险是响应没有 `Cache-Control`（P2-2）。

### 11.4 404 门槛出口清单

| 出口 | 是否返回记录详情 | 备注 |
|---|---|---|
| `GET /api/records/{id}` | **唯一完整详情** | 按 §11.3 三档；非所有者 `contact=null` |
| `GET /api/my/records` | 否（本人摘要） | `detailVisible = status == PENDING`（`MyRecordListItemResponse.java:91`）；COMPLETED/CLOSED 不渲染详情入口 |
| `POST /api/match` | 否（摘要） | `detailAccess=RESTRICTED`（`MatchItemResponse.java:73`）；不含 description/contact |
| `GET /api/notifications` | 否 | 只有 pairId/title/body，正文打码 |
| pair 卡片 | 否 | `PairCard` 没有 contact 字段；`PairFactsMapper` 只取 `counterpart_contact_present` 布尔 |
| `GET /api/pairs/{id}/contact` | 否（只返回号码） | 参与方 + 档位 + 授权 + 审计 fail-closed；`Cache-Control: no-store` |
| 管理/导出 | **不存在** | 端点清单 §6；无 MultipartFile/导出 |
| 服务端缓存 | **不存在** | 无 `@Cacheable`/Caffeine/Ehcache；响应头已由 `ApiResponseHeadersFilter` 补齐（P2-2 已修复） |

### 11.5 三态/四态枚举一致性

| 层 | 取值 | 证据 |
|---|---|---|
| DB `t_record.status` | PENDING / COMPLETED / CLOSED | `schema.sql:147` `ck_record_status`；`:148-150` `ck_record_completed_at` |
| Java `RecordStatus` | PENDING / COMPLETED / CLOSED | `RecordStatus.java:21-27` |
| 前端 `RecordStatus` | PENDING / COMPLETED / CLOSED | `web/src/types/api.ts:6` |
| DB `t_pair_score.status` | NEW / CONTACTED / MATCHED / IGNORED | `schema.sql:237` `ck_pair_status`；`:238-240` `ck_pair_matched` |
| Java `PairStatuses` | NEW / CONTACTED / MATCHED / IGNORED | `PairStatuses.java:11-14` |
| pair「已解决」 | **不存在** | `PairScore.java:21` 注释；无 RESOLVED/已解决字段 |

**`CLAIMED` 残留：** `server/src` + `db` + `web/src` 共 **13** 处；除 `db/README.md:72` 的过期 v1 说明外，其余都是「已作废/收到即 10001」的注释或测试（如 `RecordStatus.java:15-16`、`StatusUpdateRequest.java:14`、`RecordStatusPolicyTest.java:35-39`、`MyRecordsApiTest.java:99-101`）；`web/src` 仅 1 处注释（`types/api.ts:5`）。**没有枚举值/DB CHECK/默认值使用 CLAIMED**。唯一需要改的是 `db/README.md:72-75` 的过期 v1 说明（P3-1）。

### 11.6 pair 独立性

- 唯一键 `(lost_id, found_id)` 一行一对（`schema.sql:224`）；`PairScoreMapper` 的所有用户动作都按 `id` 更新**这一行**（`:126-188`），不批量改其他 pair。
- `PairActionService` 不写 `t_record`、不更新其他 pair、不发通知（类注释 + 代码 `:51-143`）；`PairUnlockService` 只写本端解锁列/本对通知；`PairScoringService` 的 upsert 只更新分数列，不会把 IGNORED 翻回 NEW。
- 一条招领可与多条寻物各成 pair，处理完一对不影响其他 pair——代码没有占用/锁定字段或过滤条件（`PairStatuses` 只有四态；`PairActionService` 无「已认领」判断；候选池不按 pair 状态排除记录）。
- 例外/边界：P2-4 的过期 COMPLETED 扫描目标（不涉及 status/pair 独立性，但属于「到期不召回」的遗漏），以及 P2-1 的 `updateContent` 竞态。两者都不破坏「一对一 pair 行/不自动改记录状态」的核心语义，且均已在 Z-72 修复（§4.1.1）。

---

## 12. 测试覆盖评估

**已有（复验后）：**
- `MaskingPolicyTest` 由 10 条增至 **18 条**：保留 P1–P3、N1–N3、全角归一、所有者/列表/详情身份维度、QQ 大小写，新增分隔符 8 类、`+86/86/0086`、带分隔证件号、粘贴在其它数字后的号码、随机分隔符 fuzz（200 轮）、出口 DTO 层断言（`MaskingPolicyTest.java:144-252`）。
- 服务端全量 **242/242**、0 fail（`941fdee` + Z-71 + Z-72 工作树，§18.1）；C1 的原始 127 条断言包含在内，0 回归。
- F2：111 API / 25 E2E / 9 consistency，0 fail；手机号 28 探针 0 命中（`F2-phone-scan.txt`）。
- Pair/record 侧有 `RecordServiceAccessTest`（新增并发 0 行 3 例）、`PairAccessPolicyTest`、`PairContactServiceTest`、`PairActionServiceTest`、`PairScanServiceTest`（新增 cutoff 断言）、`RecordStatusPolicyTest` 等，覆盖了大量 403/404/409/零写入分支。
- 新增 `LogSafeTest` 4 条、`RecordMapperSqlTest` 2 条，以及 `RecordApiValidationTest` 的 `Cache-Control`/`Vary`/安全头断言。

**已关闭的缺口：**
- `MaskingPolicyTest` 已固化 P1-1 的 8 类输入 + fuzz + 出口层断言。
- `updateContent` 0 行分支已覆盖（service 层 mock；真实 MySQL 并发交错仍建议在集成环境补一次）。
- 响应头 `Cache-Control`/`Vary` 已在 MockMvc 层断言（200 与 401 短路）。
- 日志注入（学号换行、阈值 key、控制字符、限长）已覆盖。
- 「过期 COMPLETED + DEGRADED」与「保留期内仍重算」已覆盖；扫描 SQL 的 WHERE 结构有结构性测试防止后人删条件。

**仍缺：**
- 没有全链路 HTTP 级会话/Cookie/CSRF 复验的自动化断言（F2 有部分人工证据）；本机 3306 未运行，本轮未补。
- 没有针对逗号/冒号分隔符的回归用例（见 §18.3 R-1）。

**未执行：** 本次 F3 复验跑了服务端全量单测，但没有启动后端做 HTTP 级重扫；动态项建议在 F2 集成环境复验。
→ 后续已在 F3-R 集成复扫补做（`test/v2-F3-integration-rescan`，基线 `64693ec`）：见 §18.3 回填表与 `F3-integration-rescan.md`。

---

## 13. 做得好、值得保持的部分

1. **登录时序等化与统一文案**：不存在用户也跑一次 bcrypt dummy hash；失败原因只进日志、不进响应（`AuthService.java:50-86`）。这挡住了账号枚举的时间旁路。
2. **会话固定防护**：`getSession(true)` 后立刻 `changeSessionId()`；登出 `invalidate()` + `Max-Age=0` 清 Cookie（`SessionAuth.java:76-115`）。
3. **门禁 fail-closed**：非 GET/HEAD 的 `/api/**` 默认要求登录，白名单只有登录/登出/运维扫描；所有私有读 controller 再 `requireUser` 一次（`AuthPaths.java:41-73`、各 controller）。矩阵参数/路径归一化即使让 Filter 误放，controller 仍会 401——这是很好的纵深防御。
4. **pair 判定集中为纯函数**：`PairAccessPolicy` 把「可用/档位/contactAvailable/contactHint」集中，错误码顺序（404→403→50005→50002）写死在 `PairContactService.java:46-96`，避免非参与方通过错误码探测。
5. **审计 fail-closed**：取号必须先成功写 `t_access_log`，插入失败就 500，不出号（`AccessLogService.java:32-47`）。
6. **pair 重算不覆盖用户态**：`upsertScore` 只更新分数列，`status/confirmed_at/unlock_note` 不在 UPDATE 列表（`PairScoreMapper.xml:68-85`）；有 SQL 级测试 `PairScoreUpsertSqlTest`。
7. **状态写入收敛**：`t_record.status` 只有 insert/markCompleted/close 三条 SQL；`updateContent`/`markDeleted`/系统任务都不碰 status（`RecordMapper.xml:11-12,200-238`）。
8. **SQL 安全**：全 `#{}`、ORDER BY 白名单、LIKE 转义；`${}` 实际为 0。
9. **密钥管理**：`DEEPSEEK_API_KEY` 只走 env，`LlmProperties` 刻意没有 key 字段；日志只记 model/latency/tokens；五面扫描 0 真实密钥。
10. **工程防护**：`OpsPairScanController` 常数时间 token + 未配置 404；`PairLockService` 连接绑定的 MySQL 命名锁；`LlmCircuitBreaker` synchronized；`PairAsyncConfig` 有界队列 + 丢弃策略。

---

## 14. 待确认的问题（不替作者做决定）

1. **遮蔽规则扩展 vs 输入拒绝**：P1-1 的修复需要改 A1 §6.2 的规则 2（增加分隔符归一）或增加输入校验（拒绝带分隔符的号码）。前者 UX 更好，但属于冻结规则的解释/扩展；请 A1/架构 owner 确认口径。**Z-71 已按建议的「归一后检测、原文替换」实现，未改变四条规则的顺序与语义；合并前仍建议 A1 确认这一解释。**
2. **是否会上共享缓存/CDN**：如果生产只做单实例直连，P2-2 可降为文档风险；如果会上 Nginx/CDN 缓存，建议把 P2-2 升为 P1，并在本轮修复。**Z-72 已按保守口径对全部 `/api/**` 设 `no-store`，两种情况都覆盖。**
3. **生产 HTTPS/Secure/HSTS 的责任人**：`application.yml:31-32` 目前明确写 `secure: false` 是演示口径；请确认部署侧会加 `Secure` 与 HSTS，否则会话可被网络嗅探。
4. **提示注入/LLM 配额是否纳入 v2**：`PairPrompt` 直接拼接用户文本，且发布/匹配没有每用户配额；如果本轮不处理，请在已知风险里登记，至少加上发布/匹配限流与 LLM 预算告警。
5. **IGNORED pair 的取号语义**：当前 `PairContactService` 只判参与方/可用/档位/对方授权，不把 pair.status=IGNORED 当作不可取号；这与「IGNORED 只描述这一对进展」一致，但请产品确认「忽略后对方仍可看到已公开号码」是否符合预期。
6. **`db/README.md:72` 的过期说明**：是改成历史注记还是直接删除？（P3-1）
7. **所有者 COMPLETED 详情 404（A9）**：代码严格按 A9 实现（owner 也 404，从 `/my` 看摘要）。如果人类认为「所有者应能看自己的 COMPLETED 详情」，需要改 `RecordVisibilityPolicy` 第一行；目前按冻结口径不动。

---

## 15. 复现命令与输出

### 15.1 遮蔽 harness

```bash
# 1) 编译仓库里的真实 MaskingPolicy（无外部依赖）
export JAVA_HOME="$HOME/.jdks/jdk-17.0.20.1+1"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d /tmp/maskout \
  server/src/main/java/com/findit/campus/common/masking/MaskingPolicy.java

# 2) 用 §7 的输入调用 maskDescription/maskLocation（harness 未入库，输入见上表）
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp /tmp/maskout MaskHarness
```

输出：§7 的 16 行；V3/V4/V5/V6/V7/V12/V15/V16 为绕过。

### 15.2 JSON harness（项目同版本 Jackson）

```bash
CP="/tmp/maskout:$HOME/.m2/repository/com/fasterxml/jackson/core/jackson-databind/2.21.4/jackson-databind-2.21.4.jar:\
$HOME/.m2/repository/com/fasterxml/jackson/core/jackson-core/2.21.4/jackson-core-2.21.4.jar:\
$HOME/.m2/repository/com/fasterxml/jackson/core/jackson-annotations/2.21/jackson-annotations-2.21.jar"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$CP" -d /tmp/maskout MaskJsonHarness.java
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$CP" MaskJsonHarness
```

输出：J1 命中；J2 400；J3/J4/J5 绕过。

### 15.3 密钥五面

命令与命中数见 §9；完整日志：

```text
$ git grep -nE 'sk-[A-Za-z0-9]{24,}' -- 'server/**' 'web/**' 'db/**'   # exit 1, 0
$ git grep -nE 'sk-[A-Za-z0-9]{24,}' -- 'docs/**'                     # exit 1, 0
$ git grep -n 'sk-' -- 'docs/**' | wc -l                               # 21 (self-refs + fake stub)
$ git grep -n 'sk-local-stub-not-a-real-key' -- 'docs/**' | wc -l      # 6
$ find . ... -name '*.log' | wc -l                                     # 0
$ grep -rE 'sk-[A-Za-z0-9]{24,}' docs/testing | wc -l                  # 0
$ ./mvnw -o -q -DskipTests package                                     # exit 0
$ unzip -q target/*.jar -d /tmp/jarscan2
$ grep -raE 'sk-[A-Za-z0-9]{24,}' /tmp/jarscan2 | wc -l                # 0
$ grep -ra 'DEEPSEEK_API_KEY' /tmp/jarscan2 | wc -l                    # 2 (constant/comment)
$ git log -p --all | grep -aE 'sk-[A-Za-z0-9]{24,}' | wc -l            # 0
$ git log --all --oneline -S 'sk-' | wc -l                             # 6 (docs)
$ git log --all --oneline -S 'DEEPSEEK_API_KEY' | wc -l                # 10 (code/docs)
$ git log -p --all | grep -aE 'DEEPSEEK_API_KEY[[:space:]]*=[[:space:]]*[A-Za-z0-9]{10,}' | wc -l  # 0
$ git log -p --all | grep -aE 'Bearer [A-Za-z0-9._-]{20,}' | wc -l     # 0
```

### 15.4 SQL / 端点 / 枚举

```text
$ git grep -n '\${' -- 'server/src/main/resources/mapper/**'          # 1 (RecordMapper.xml:8 comment)
$ git grep -nE '@(Select|Update|Insert|Delete)\(' -- 'server/src/main/java/**' | wc -l  # 0
$ git grep -n 'MultipartFile' -- 'server/src/main/java/**' | wc -l    # 0
$ grep -rnE '@(Get|Post|Put|Patch|Delete)Mapping' server/src/main/java/com/findit/campus/*/web/*.java  # 19 mappings; 端点清单见 §6
$ git grep -n 'CLAIMED' -- server/src db web/src | wc -l              # 13 (comments/tests/docs)
```

---

## 16. 下一步与门禁判定

1. **P1（阻塞）——已闭环**：`ZHYYKFX-71` 在 `fix/v2-F3-p1-masking-normalize@08f4bb9` 修复 `MaskingPolicy` 分隔符/`+86`/带空格证件号；§7 的 8 类输入与 JSON 入口复验全部命中，`MaskingPolicyTest` 18/18。合并建议见 §18.4。
2. **P2（建议）——已闭环**：`ZHYYKFX-72` 在 `fix/v2-F3-p2-hardening@fbd538f` 修复 P2-1..P2-4，复验通过（§4.1.1）。如需拆分合并，P2 可与 P1 分开，但两者与报告分支均无文件重叠。
3. **P2-5 可选——部分完成**：`tracking-modes: cookie` 与最小安全响应头已完成；生产 `Secure`/HSTS、XFF 可信代理、提示注入/LLM 配额仍需部署或产品确认。
4. **不推 main、不合并**：本报告分支只包含 `docs/testing/v2/security-review.md`；两个修复分支保持独立，由集成负责人按 §18.4 合并。
5. **验收口径**：P0=0；P1 修复单 + 复验结论齐备。**当前判定：门禁 3 通过。**


---

## 17. 证据索引

| 证据 | 位置 |
|---|---|
| 遮蔽 harness 原始输出 | 本文 §7 |
| JSON harness 原始输出 | 本文 §7/§15.2 |
| 密钥五面命令与命中数 | 本文 §9/§15.3 |
| SQL/端点/枚举命令 | 本文 §10/§15.4 |
| F2 手机号实测 0 命中 | `docs/testing/v2/F2-phone-scan.txt` |
| F2 集成回归 0 fail | `docs/testing/v2/F2-integration-report.md` |
| C1 遮蔽证据/单测清单 | `docs/testing/v2/C1-masking-evidence.md`（§448） |
| 密钥可读性（不记值） | `docs/testing/v2/gate2-review/raw/g7-key-read.txt` |
| 设计口径 | `docs/design/v2-architecture.md` §6/§9.3/§11.2/§11.3；`docs/design/v2-auth-session.md` §cookie/CSRF；`docs/api-contract-v2.md` §1.1 |
| P1 复验 harness 原始输出 | 本文 §18.2 |
| 修复 commit | `08f4bb9`（Z-71）、`fbd538f`（Z-72），均基于 `941fdee` |

---

## 18. 复验记录（2026-10-28）

### 18.1 环境与全量测试

**复验工作树**：从 `08f4bb9` 建独立 worktree，再应用 `git diff 941fdee fbd538f`（两个修复分支文件不重叠），等价于「基线 + Z-71 + Z-72」的合并结果；未改动报告分支与集成分支。

```bash
# 1) 独立工作树：Z-71 已包含在 08f4bb9
git worktree add ../scratch/f3-verify 08f4bb9

# 2) 叠加 Z-72（与 Z-71 文件无重叠）
git diff 941fdee fbd538f | git apply

# 3) 服务端全量测试（JDK 17.0.20.1，Maven 离线）
export JAVA_HOME="$HOME/.jdks/jdk-17.0.20.1+1"
cd server && ./mvnw -o test
```

**结果（原文）：**

```text
[INFO] Tests run: 242, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time: 39.397 s
```

关键测试类：`MaskingPolicyTest` 18/18、`LogSafeTest` 4/4、`PairScanServiceTest` 5/5、`PairScoringServiceTest` 9/9；`RecordServiceAccessTest`（新增并发 0 行 3 例）、`RecordApiValidationTest`（200/401 响应头）、`RecordMapperSqlTest`（2 条 SQL 结构断言）均通过。

### 18.2 遮蔽 harness 复验输出

**方法**：直接编译修复后的仓库 `MaskingPolicy.java`，用项目同版本 Jackson 2.21.4 反序列化 JSON 变体；`FULL_PHONE`/`FULL_ID` 表示把输出中的非数字剥离后是否仍包含完整号码。

```text
V1-contiguous                  FULL_PHONE=false FULL_ID=false OUT=联系 [手机号已隐藏]
V2-fullwidth                   FULL_PHONE=false FULL_ID=false OUT=联系 [手机号已隐藏]
V3-space-separated             FULL_PHONE=false FULL_ID=false OUT=联系 [手机号已隐藏]
V4-newline-separated           FULL_PHONE=false FULL_ID=false OUT=联系 [手机号已隐藏]
V5-hyphen-separated            FULL_PHONE=false FULL_ID=false OUT=联系 [手机号已隐藏]
V6-zero-width                  FULL_PHONE=false FULL_ID=false OUT=联系 [手机号已隐藏]
V7-plus86-prefix               FULL_PHONE=false FULL_ID=false OUT=联系 [手机号已隐藏]
V8-unicode-escape-literals     FULL_PHONE=false FULL_ID=false OUT=联系 \u0031\u0033\u0038...
V9-percent-encoded             FULL_PHONE=false FULL_ID=false OUT=联系 %31%33%38...
V10-qq-lowercase               FULL_PHONE=false FULL_ID=false OUT=qq[QQ号已隐藏]
V11-qq-uppercase               FULL_PHONE=false FULL_ID=false OUT=QQ[QQ号已隐藏]
V12-id-with-space              FULL_PHONE=false FULL_ID=false OUT=证件 [证件号已隐藏]
V13-phone-in-location          FULL_PHONE=false OUT=1381…
V14-phone-in-location-tail     FULL_PHONE=false OUT=图书馆1…
V15-tab-separated              FULL_PHONE=false FULL_ID=false OUT=联系 [手机号已隐藏]
V16-fullwidth-space            FULL_PHONE=false FULL_ID=false OUT=联系 [手机号已隐藏]
J1-unicode-escaped             PARSE=OK   FULL_PHONE=false FULL_ID=false OUT=联系 [手机号已隐藏]
J2-array-wrapped               PARSE=FAIL MismatchedInputException
J3-space-separated             PARSE=OK   FULL_PHONE=false FULL_ID=false OUT=联系 [手机号已隐藏]
J4-newline-separated           PARSE=OK   FULL_PHONE=false FULL_ID=false OUT=联系 [手机号已隐藏]
J5-plus86                      PARSE=OK   FULL_PHONE=false FULL_ID=false OUT=联系 [手机号已隐藏]
```

**结论**：原 V3/V4/V5/V6/V7/V12/V15/V16 与 JSON J3/J4/J5 全部从「绕过」变为「命中」；V1/V2 连续/全角口径不变；V13/V14 仍只残留 1–4 位（P3-4，不构成完整号码）。

### 18.3 复验新增遗留（非阻塞，建议后续单）

| # | 级别 | 发现 | 证据 | 建议 |
|---|---|---|---|---|
| R-1 | 建议（P3） | 分隔符集合不含 `,` `，` `:` `：` `;` `；`，这些写法仍可绕过 | `138,1234,5678`、`138:1234:5678`、`138：1234：5678`、`138，1234，5678`、`138;1234;5678` 均原样输出 | 至少把 `:` `：` `;` `；` 加入 `isDigitSeparator`；逗号要单独处理——直接加入可能把 `1,234,567` 这类千分位拼成号码，建议只在归一后长度为 11/13/15 时把逗号当分隔符，并补 `1,234,567` 反例 |
| R-2 | 细节（P3） | QQ 号的分隔写法和手机号同源问题未扩展：`QQ 123 456` 仍原样 | harness 输出 `QQ 123 456` | 若团队把 QQ 也算敏感联系方式，把 `QQ_RE` 纳入同一分隔符归一；否则在 C1 规则里显式写明「QQ 只匹配连续数字」 |
| R-3 | 细节（P3） | 地点字段仍只截断不打码（原 P3-4） | `maskLocation("13812345678图书馆")` → `1381…` | 截断前先过一次号码规则；不构成完整号码，不阻塞本轮 |

> R-1 不影响本轮 P1 判据（任务验收的 8 类已全部闭环），但它是同一根因的剩余变体；如果团队认为逗号/冒号属于「自然写法」，可把 R-1 升为 P1 追加修复后再合并 Z-71。是否接受这个取舍由作者/架构 owner 决定。

**F3-R 集成复扫（2026-10-28，`test/v2-F3-integration-rescan`，基线 `64693ec`）对 §18.3 的回填**：本轮把静态结论打到真实 HTTP 出口上重测，逐项输出见 `docs/testing/v2/F3-integration-rescan.md` 与 `docs/testing/v2/F3-integration-rescan/`。

| # | 静态口径（本节原判定） | F3-R 实测（HTTP + MySQL 真实环境） | 变化 |
|---|---|---|---|
| R-1 | `138,1234,5678` / `138:1234:5678` / `138;1234;5678` 纯函数层原样输出 | 三例在 HTTP 出口**全部仍可整段读出**：匿名详情、非所有者详情、`t_match_result` 快照、取号附言四处 rawKeep=1（`F3R-03-p1-bypass.txt` 第 620–637 行） | 无变化，仍是遗留；按任务口径只测现状、未改规则，是否升 P1 交人类在门禁 3 裁决 |
| §18.2 八类绕过 | 静态 harness 16 输入变「命中」 | 8 类 × 8 个 HTTP 出口组 = **128 条断言全 PASS**，privacy 方向（匿名/非所有者/通知/match 对端/落库）exact 与 rawKeep 双 0；无过遮蔽对照 5/5 符合预期 | 静态结论在动态层成立 |
| P2-2 缓存头 | 按代码/配置判定，MockMvc 断言 | 原始 `curl -i` 20 条用例 FAIL=0，200/401/403/404 均带 `Cache-Control: no-store` + `Vary: Cookie`；401 短路也带头；`/api/pairs/{id}/contact` 未回退 | 动态层成立；新增 1 条非阻塞观察：该端点 `Cache-Control` 重复出现两次（拦截器 + controller 各设一次，语义相同），未改业务代码 |
| P2-3 日志注入 | 单测覆盖 | 9 条通路 × 各自独立假时间戳，HTTP 打到真实日志文件：**判据 A 全为 0 行、判据 B 独立行 0**；LogSafe 四档替换与 `...[324chars]` 截断逐条可见；并做了拦截层归因（J1/J1b 实为绑定层剥空白挡住，J1c 为 `@Pattern` 白名单挡住） | 动态层成立；分层归因补强了原结论 |
| P2-1 状态竞态 | service 层 mock，真实并发交错「建议在集成环境补一次」 | 11/11 PASS：终态 PUT 一律 409/20003 且 16 列指纹逐字不变；SQL 反证「无条件 `ROW_COUNT()=1`、有条件 `=0`」；**真并发已补**——InnoDB 行锁编排使 PUT 阻塞 2648 ms 后仍被拒 + 20 轮裸并发分布（11×PUT200 / 9×PUT409，0 不一致轮次） | 「建议补做一次」已执行 |
| P2-4 过期扫描 | SQL WHERE 结构测试 | 两个触发口径（`--pair-scan-once` 启动扫描 4/4、`POST /api/ops/pair-scan` 8/8）：过期 COMPLETED 的 `pair_state/pair_scored_at/updated_at` 一字未动、pair 24→24、通知 0→0、预置 pair 的 hash 与时间戳不变；正对照 X3/X4 确实被扫到（pair 0→20）；`expired=3` 与 EV-SQL 实数一致 | 动态层成立 |
| §18.4 第 2 条「未执行」 | 本机无 MySQL，28 探针与关键 HTTP 用例未执行 | **已执行**：28 探针 0 命中；五阶段 `qa/run-all.mjs` 退出码全 0（接口 111/111、E2E 25/25、一致性 9/9、落库取证 19/19、match P1 10/10 + P2 6/10 与 F2 持平、0 新增失败） | 该条遗留关闭 |

**仍未闭环（如实记录，不写成通过）**：本机没有真实 `DEEPSEEK_API_KEY`，只测到「无密钥降级」态（扫描日志逐条 `degradedReason=EMPTY_KEY`、`counters.llmCalls=0`、`engine=LOCAL_KEYWORD`）。「有 key 时过期记录同样不重算」这句缺实测支撑，需要持密钥环境复跑一次。

### 18.4 合并建议

1. **合并顺序**：`fix/v2-F3-p1-masking-normalize@08f4bb9` 与 `fix/v2-F3-p2-hardening@fbd538f` 均基于 `941fdee`，文件无重叠，可按任意顺序合并到 v2 集成分支；建议先 P1 后 P2，便于把副作用分开观察。
2. **合并前复核**：在目标集成分支上重跑 `server/mvnw -o test`（本轮合并工作树为 242/242）；在 F2 集成环境重跑 28 个手机号探针与关键 HTTP 用例（本机无 MySQL，未执行）。
   → **该项已于 F3-R 执行**（`test/v2-F3-integration-rescan`，基线 `64693ec`，真实 MySQL + 重打包 jar）：28 探针 0 命中、五阶段退出码全 0、P1/P2 四项修复逐项打到 HTTP 出口与 EV-SQL，读数见 §18.3 回填表与 `F3-integration-rescan.md`。
3. **回滚**：两个 commit 都是应用层改动，无 DB 迁移；`git revert` 即可回滚，不需要数据修复。P2-1 的 `selectStatusByIdForUpdate` 与 `selectPairScanTargets` 签名变更只影响本仓库调用点（已逐一核对）。
4. **报告分支**：`feat/v2-F3-security-review` 只含本报告，不合并进 main；修复代码由上述两个分支独立进入集成。
