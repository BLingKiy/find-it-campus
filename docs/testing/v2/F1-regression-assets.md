# v2 · F1 回归资产（ZHYYKFX-58）

> 状态：**待人工验收** · 分支 `feat/v2-F1-regression-assets` · 基线 `feat/v2-gate2-evidence@ec670be`
> 范围：`qa/api` 既有 64 条改造 + 新增 47 条；`qa/e2e` 登录前置与三态；`qa/match` 遮蔽断言与分数基线；`qa/db` 适配七表。
> 事实源：`docs/api-contract-v2.md`。本文只记录可复现的结果，不写「看起来正常」。

---

## 1. 结论

- `node qa/run-all.mjs` **五阶段全绿（0 fail）**：接口 111/111、E2E 25/25、一致性 9/9、落库取证 19/19、匹配评估 10/10 样例（评估脚本退出码 0）。
- 既有 64 条覆盖点**一条未删**：原用例 ID 全部保留，仅按 v2 契约修正断言；清单见 §3。
- 每条写接口用例都具备**匿名 / 本人 / 非本人**三态（§3.3）。
- 本轮四类新判据都有可执行用例：多对 pair 独立（PAIR-01/03/06）、`t_record.status` 不自动置位（PAIR-04/STAT-05/NOUNPUB-01）、手机号仅格式校验（API-C-10/PHONE-01）、不自动下架（NOUNPUB-01）。
- TERM-06 / PAIR-07 均给出 **EV-SQL + EV-LOG** 原始证据（§5），没有用推断代替。

---

## 2. 分支与运行环境

| 项 | 值 |
|---|---|
| 功能分支 | `feat/v2-F1-regression-assets` |
| 基线 | `feat/v2-gate2-evidence@ec670be`（含 `feat/v2-integration` 的 D1/E1/E1b/E2/D2 五个 merge） |
| 说明 | 任务书写「从最新 main 切」，但依赖 E1；`origin/main` 尚无 v2 登录/pair 代码，故以含 E1 的集成基线上切，只推功能分支、不合并 main |
| 后端 | `mvn -B -DskipTests package` → 18080；`DEEPSEEK_API_KEY` 未设置（降级路径） |
| 前端 | `npm run dev` → 5173，`web/.env.local: VITE_USE_MOCK=false` |
| 数据库 | MySQL 8.0.29，`db/schema.sql` + `db/seed.sql`（run-all 前后各重置一次） |
| 后端日志 | `qa/reports/server.log`（EV-LOG 数据源；`FINDIT_SERVER_LOG` 可覆盖） |

---

## 3. 用例数与覆盖点

### 3.1 前后对比

| 套件 | 改造前 | 改造后 | 说明 |
|---|---|---|---|
| `qa/api` | 64 | **111** | 原 64 条 ID 全保留 + 新增 47 条；达到「≥ 63 + 20」 |
| `qa/e2e/walkthrough` | 19 | **25** | 加登录前置与「匿名 / 本人 / 非本人」三态；保留原 19 条 ID |
| `qa/e2e/consistency` | 9 | **9** | 加登录前置（提交页被守卫），断言不变 |
| `qa/db/evidence` | 三表取证 | **七表 19 项检查** | 适配 v2 schema 与 UI 新流程 |
| `qa/match/evaluate` | 10 样例 | 10 样例 + 遮蔽断言 + `match-baseline.json` | 候选池未加 publisher 过滤，分数基线重录 |

### 3.2 既有 64 条覆盖点（全部保留，未删一条）

| 组 | 覆盖点 | 用例 ID |
|---|---|---|
| 前置 | 后端可达 / seed 基线 | ENV-01 |
| 通用契约 | 外层四字段、未知路径、405、非法 JSON、无堆栈 | API-CON-01..05 |
| 列表 | 默认分页/排序、字段白名单、type/status/category/keyword、LIKE 转义、翻页、排序、参数校验、越界 | API-L-01..12 |
| 详情 | 字段与 allowedStatuses、逻辑删除 404、不存在 404、非数字 id、终态可见性、无分类 | API-D-01..06 |
| 提交 | 成功结构、可选字段、分类错误、标题/描述/地点边界、occurredAt 边界与格式、type/必填、contact 校验、UTF-8、空格标题、非幂等 | API-C-01..13 |
| 修改 | 全量更新、可选字段清空、终态不可改、404、字段校验 | API-U-01..05 |
| 状态流转 | PENDING→CLOSED、终态冲突、同值、非法目标、不存在 | API-S-01..05 |
| 删除 | 三读取路径过滤、重复删除、不存在删除 | API-X-01..03 |
| 分类字典 | 启用列表、含停用 | API-CAT-01..02 |
| 语义匹配 | S1–S6、结构/排序、exclude、N1–N4、确定性、参数约束、非法输入、并发快照 | API-M-01..12 |

> 改造点只随 v2 契约变化：`CLAIMED`→`COMPLETED/CLOSED`、列表字段加 `isMine`、详情加 `completedAt/hasMatchedPair/pairs`、写接口加登录、`contact` 改为手机号正则、`/api/match` 加登录与 `recordStatus/detailAccess`。覆盖点本身没有删除。

### 3.3 新增 47 条

| 组 | 数量 | 用例 ID | 覆盖 |
|---|---|---|---|
| 登录态 | 6 | AUTH-01..06 | Cookie 属性、防枚举文案、参数校验、登出幂等、三会话隔离、失效后 401 |
| 写接口三态 | 7 | AUTHZ-01..07 | POST/PUT/PATCH/DELETE/pair 三条写接口/通知已读/匹配：匿名 401、本人 2xx、非本人 403 |
| 遮蔽五出口 | 6 | MASK-01..06 | 列表 / 详情 / 匹配 / 通知 打码，本人 `/my` 原值；非本人响应 `1[3-9]\d{9}` 0 命中 |
| 三档分档 | 4 | TIER-01..04 | DIRECT 双方取号 + GRANTED；AUTHORIZABLE 公开/撤回；LOW 403/50002；0.90/0.75/0.7499 边界 |
| 幂等 | 4 | IDEM-01..04 | CONTACT/CONFIRM/PUBLISH/REVOKE 重复提交不写库、不刷通知、不刷新时间 |
| 降级 | 3 | DEG-01..03 | 空 Key 落 `LOCAL_KEYWORD + EMPTY_KEY`；`/api/match` 不 503；hash 变化重算仍降级 |
| 多对 pair 独立 | 8 | PAIR-01..07、PAIR-07b | 一条招领×两条寻物互不影响、无占用；唯一键；1:1×N 可同时 MATCHED；IGNORED 不被重算重置 |
| 三态与终态 | 5 | STAT-01..05 | 未达 MATCHED 409/20005 零写入；COMPLETED 三套可见性；CLOSED 双 0；到期不召回 |
| A2 矩阵补丁 | 1 | TERM-06 | IGNORED pair 不作 COMPLETED 详情通道；改回 NEW 后 200 |
| 不自动下架 | 1 | NOUNPUB-01 | PENDING 记录打分跑完仍 PENDING、未删除、列表可见、`updated_at` 不变 |
| 手机号口径 | 1 | PHONE-01 | 仅格式校验、不落 `t_user`、无短信验证、contact 明文入库仅输出层遮蔽 |
| 七表前置 | 1 | ENV-02 | 7 张表及 pair 关键列存在 |

### 3.4 验收标准 → 用例映射

| 验收标准 | 证据用例 |
|---|---|
| `node qa/run-all.mjs` 全绿 | §4 输出，五阶段退出码 0 |
| 总数 ≥ 63 + 20，原覆盖点无删除 | §3.1 / §3.2（64 → 111） |
| 每条写接口具备匿名/本人/非本人三态 | AUTHZ-01..07 |
| 多对 pair 独立、无占用语义、未写「已解决」 | PAIR-01（A 标已联系后 B 仍 NEW、招领仍在候选池、记录全 PENDING）、PAIR-03、PAIR-06、PAIR-05（状态只有四态） |
| 自动任务跑完 status / updated_at 不变 | PAIR-04（pair 进展不改记录）、STAT-05（到期不改写 status）、NOUNPUB-01（打分后仍 PENDING、updated_at 不变） |
| 非本人响应 `1[3-9]\d{9}` 0 命中 | MASK-06（列表/详情/匹配/通知）、MASK-03（匹配候选） |
| vF 终态口径 | STAT-01（409）、STAT-02/03（COMPLETED 可见性 + 0.90 门槛）、STAT-04（CLOSED 双 0 + 50005）、STAT-05（到期） |
| TERM-06 / PAIR-07 可执行 | TERM-06、PAIR-07、PAIR-07b（§5 附 SQL/日志） |

---

## 4. run-all 输出（2026-10-28）

```
# v2 · F1 全量回归 · 2026-10-28T01:33:41.421Z
- 后端：http://localhost:18080
- 前端：http://localhost:5173
- MySQL 客户端：C:/Project/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe
  前置检查 后端 /api/health: HTTP 200
  前置检查 前端 dev server: HTTP 200
=== 开始前：重置库（schema.sql + seed.sql） ===
=== 接口测试（node qa/api/run.mjs） ===
--- 接口测试 退出码 0
=== 匹配效果评估（node qa/match/evaluate.mjs） ===
--- 匹配效果评估 退出码 0
=== E2E 走查（node qa/e2e/walkthrough.mjs） ===
--- E2E 走查 退出码 0
=== 落库取证（node qa/db/evidence.mjs） ===
--- 落库取证 退出码 0
=== 跨页一致性（node qa/e2e/consistency.mjs） ===
--- 跨页一致性 退出码 0
=== 结束后：重置库（schema.sql + seed.sql） ===
=== 汇总 ===
| 阶段 | 退出码 | 结论 |
|---|---|---|
| 接口测试 | 0 | 通过 |
| 匹配效果评估 | 0 | 通过 |
| E2E 走查 | 0 | 通过 |
| 落库取证 | 0 | 通过 |
| 跨页一致性 | 0 | 通过 |
- 接口：111 条，通过 111，失败 0
- E2E：25 条，通过 25，失败 0
- 一致性：9 条，通过 9，失败 0
```

原始文件：`qa/reports/run-all-output.txt`；明细 `qa/reports/api-f1-v2.{json,txt}`、`qa/reports/e2e-f1-v2.{json,txt}`、`qa/reports/db-evidence.txt`、`qa/reports/match-eval.md`、`qa/reports/consistency-5.1.md`；截图 `qa/reports/screenshots/v2-*.png`（22 张）。

---

## 5. TERM-06 / PAIR-07 的 EV-SQL 与 EV-LOG

> 证据来源：`qa/reports/run-all-output.txt` 第 13–19 行；后端日志 `qa/reports/server.log`。记录 id 每轮由 AUTO_INCREMENT 分配，下面保留当轮实际 id。

### 5.1 PAIR-07：hash 变化重算不重置 IGNORED

夹具：LOST#94 / FOUND#93（demo02/demo03），pair#629 初始 `IGNORED`，两端 unlocked 与附言均非空。改 LOST 标题 + 描述触发重算，等待 `lost_hash` 实际变化后取值。

```
[EV-SQL] PAIR-07 pairId=629 重算前 status=IGNORED unlocked=2026-10-26 10:00:00/2026-10-26 10:05:00 notes=失主端附言/拾获端附言 hashes=fb018add…
[EV-SQL] PAIR-07 pairId=629 重算后 status=IGNORED unlocked=2026-10-26 10:00:00/2026-10-26 10:05:00 notes=失主端附言/拾获端附言 hashes=ae260faf…
[EV-LOG] PAIR-07 pairId=629 日志：新增 3 行，llmCalls=0
[EV-LOG] 摘录：pair score written lostId=94 foundId=93 trigger=update pairId=629 engine=LOCAL_KEYWORD score=0.78 degradedReason=EMPTY_KEY latencyMs=null promptTokens=null completionTokens=null
        | pair score skipped reason=UNCHANGED fastPath=false lostId=94 foundId=93 trigger=publish pairId=629
```

结论：
- `lost_hash` 已变化（`fb018add…` → `ae260faf…`），证明真的走了重算，不是「没触发所以没变」。
- `status` 仍 `IGNORED`；`lost_unlocked_at/found_unlocked_at/lost_unlock_note/found_unlock_note/confirmed_at` 逐字段不变；行数仍 1。
- 系统无恢复入口：`CONTACT`/`CONFIRM` 在 IGNORED 上均为 409/50003（重算分数 0.78 ≥ 0.75，判定顺序先到状态）；重复 `IGNORE` 200 且不写库。
- PAIR-07b：直接 SQL 置回 `NEW` 后再次改标题重算，status 保持 `NEW` —— 只有手动恢复才翻转。
- LLM 调用计数：本轮无 `DEEPSEEK_API_KEY`，日志中该 pair 的写入为 `LOCAL_KEYWORD + EMPTY_KEY`，`llmCalls=0`（不是用「没有输出」推断，而是按日志引擎字段计数）。

### 5.2 TERM-06：IGNORED pair 不作 COMPLETED 详情通道

夹具：FOUND#（demo03，COMPLETED，completed_at 为 2 天前）、LOST A（demo01）与 LOST B（demo02）。`(A,C)=0.95 MATCHED`，`(B,C)=0.93 IGNORED`。

```
[EV-SQL] TERM-06 pairId=773 通道 IGNORED=IGNORED；访问审计 before=0 after=0；改 NEW→200，改回 IGNORED→404
[EV-LOG] TERM-06 pairId=773 日志新增 0 行，llmCalls=0（详情读取不应触发打分/LLM）
```

结论：
- demo02（IGNORED 一端）看 COMPLETED 详情 → 404/20001；demo03（所有者）→ 404/20001；demo01（MATCHED 0.95 合格对端）→ 200。
- 三次详情读取前后 `t_access_log` 均为 0 行：详情读不写审计，且 IGNORED 通道不产生授权。
- 对照：SQL 把同一 pair 改回 `NEW`，demo02 立刻 200；改回 `IGNORED` 后恢复 404 —— 差异只来自 pair.status。
- 该 pair 的日志前后 0 新增行、`llmCalls=0`：读详情不会触发打分/LLM。

---

## 6. E2E 三态与截图

| 页面 | 匿名 | 本人（demo01） | 非本人（demo02） |
|---|---|---|---|
| 列表 | 无「我的启事」徽标；#3 地点截断 | 本人行原值 + 徽标 | 他人行截断、无徽标；自己的 #6 有徽标 |
| 详情 | 匿名横幅 + 登录引导；号码不可得 | 本人徽标 + 明文 contact + 状态操作 | 非本人横幅 + 遮蔽占位；无号码 |
| 提交 | `/records/new` → `/login?redirect=%2Frecords%2Fnew` | 表单可填、提交成功 | 表单可填（发布归自己） |

对应用例：E2E-A-01 / E2E-A-02 / E2E-A-03；截图 `v2-05..v2-09`。E2E-D-04/05 覆盖「标记完成 → 我的启事已完成 → 公共列表 0 命中 → 所有者详情 404 回 /my」；E2E-D-06 覆盖 DIRECT 档在 pair 卡片上取号。

---

## 7. 没测到的 / 风险声明

- **真实 DeepSeek**：本轮无 Key，pair 链路全部走降级；`DEEPSEEK` 引擎未复测（门禁 2 已有 D1-deepseek-live 资产，需密钥到位后单独跑）。
- **并发边界**：只做了 10 并发 `/api/match`；并发取号 / 并发 CONFIRM / 并发 PUBLISH 同一条 pair 未做压测。
- **阈值小数边界**：0.90 / 0.75 / 0.7499 已覆盖；0.74999、0.89999 未逐位验证（浮点存储为 DECIMAL(5,4)，实际落在 4 位小数）。
- **跨浏览器**：E2E 只跑 Edge（Playwright channel=msedge）；Firefox / WebKit 未跑。
- **a11y**：本次未做 axe/WCAG 扫描；三态页面的对比度、键盘路径未单独取证。
- **测试数据**：所有号码为 `1380000xxxx` 假号；用例创建的记录收尾逻辑删除，run-all 结束重置 seed。
- 「零缺陷」不是本资产的结论；本资产只证明上述 111 条契约断言在当前基线上全绿。

---

## 8. 复现

```bash
# 1) MySQL
"C:/Project/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe" -u root < db/schema.sql
"C:/Project/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe" -u root find_it_campus < db/seed.sql

# 2) 后端（日志给 EV-LOG 用）
cd server && mvn -B -DskipTests package
java -jar target/find-it-campus-0.0.1-SNAPSHOT.jar > ../qa/reports/server.log 2>&1

# 3) 前端
cd web && npm run dev

# 4) 全量
node qa/run-all.mjs

# 5) 只跑 EV 相关
FINDIT_SERVER_LOG=qa/reports/server.log node qa/api/run.mjs
```
