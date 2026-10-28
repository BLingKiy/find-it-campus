# find-it-campus v2 · 回归清单（A2）

> 任务：ZHYYKFX-47 · 作者：端测测 · 日期 `2026-10-27`
> 用途：v2 任何一条任务改动前后，按改动面查这张表就知道**哪块要改、改什么、谁改**；结论只有「要改 / 不改」两种，第三种叫「新增」。
> 配对文件：`docs/testing/v2/acceptance-matrix.md`（204 条验收判据，本文的用例 ID 与之交叉引用）
> 基线：`main@21784f6`（v1 全部资产）+ A1 契约 v2.1（`b90270e`）

---

## 0. 判绿标准（先定标准，再看结论）

| # | 标准 | 判据 |
|---|---|---|
| 1 | 接口组 | `node qa/api/run.mjs` 通过数 = 用例总数，`KNOWN_FAIL` 计数 = 0（v1 的两条已登记缺陷都已修复，见 §1.3） |
| 2 | 匹配评估 | P1 协议 top1 ≥ 10/10、top3 = 10/10；两次调用结果一致；评估报告重新生成 |
| 3 | 三页面冒烟 | `node qa/e2e/walkthrough.mjs` 通过数 = 用例总数；截图齐全 |
| 4 | 落库取证 | `qa/reports/db-evidence.txt` 末尾结论行含「通过」，且项数 = 检查项数 |
| 5 | 全新失败 | 出现未登记的新失败 → 记缺陷，不许把已知缺陷从清单删掉来变绿 |
| 6 | 部署包 | 干净目录 `install → start → status(up) → stop → status(down)` 五步退出码全 0，`/api/health` 返回 `status="UP"` 且含 `version`/`engine` 两字段 |

**执行时机：**

| 时机 | 跑什么 | 预计耗时 |
|---|---|---|
| 每次提交（本地） | `qa/api/run.mjs` 中受改动接口所属的组 | < 20s |
| 合并前 | `qa/api/run.mjs` 全量 + 受影响页面的 E2E 子集 | < 1min |
| 每日构建 | `node qa/run-all.mjs`（接口 + 匹配 + E2E + 落库 + 一致性） | < 2min |
| 门禁 1（登录态/越权/遮蔽） | `AUTH` `PERM` `MASK` 三组（51+8+19 条） | < 2min |
| 门禁 2（幂等/降级/分档） | `IDEM` `DEG` `TIER` 三组（38 条） | < 3min（含 mock） |
| 门禁 3（发版） | 全量 204 条 + 7.x 干净目录 + 三页面 + 6.x 文档抽查 | 半天 |

---

## 1. 结论总表（五块，逐块给结论）

| 块 | 存量 | 结论 | 改什么（一句话） | 谁改 |
|---|---|---|---|---|
| 5.1 接口（`qa/api/run.mjs`） | 64 条（63 通过 + 1 已修复缺陷） | **要改** | 44 条改写（登录态/三态/遮蔽/匹配可见性）、20 条保留；另按验收矩阵新增 204 条判据（矩阵编号，与存量 64 条是两套 ID） | F1 端测测（脚本）+ B1/B2/C1/D1/E1（被测实现） |
| 匹配评估（`qa/match/evaluate.mjs`） | 10 样例 × 2 协议 | **要改** | 样例期望随 seed 重算；新增 COMPLETED 保留期内 / CLOSED / 到期边界 3 类样例；脚本补登录 | F1（脚本与样例）+ F2（重跑出数） |
| 三页面冒烟（`qa/e2e/walkthrough.mjs`） | 19 条 | **要改** | 14 条改写（登录跳转/遮蔽态/三态按钮）、5 条保留；新增 2 个页面（登录、我的启事）与 3 类交互冒烟 | F1（脚本）+ B3 吴八哥 / D2 速构构（`data-testid` 与页面） |
| 7.x 部署包（`deploy/`） | 5 脚本 + 1 文档 + 1 环境样例 | **要改** | 迁移脚本、7 表校验、四个新配置项与两个环境变量、已知限制五条；`start/stop/status` 不改 | G1 磐石石 + F2 复核 |
| 6.x 文档（`docs/delivery/`） | 4 文档 + 1 PPT + 1 自检脚本 | **要改** | 3 表→7 表、9 接口→17、13 错误码→22、`CLAIMED` 表述全部改三态、演示路径加登录与标记完成 | F4 文通通 |

**不改的存量资产（明确列出，避免被顺手改坏）：** `docs/testing/5.1-*.md`（7 份 v1 历史报告，保留为 v1 基线，v2 报告另建 `docs/testing/v2/`）；`qa/e2e/consistency.mjs`（9 条视觉一致性，A3 未改 token）；`deploy/scripts/start.ps1` `stop.ps1` `status.ps1`（端口与生命周期未变）。

---

## 2. 块一 · 5.1 接口用例（64 条）

### 2.1 逐组结论

| 组 | 条数 | 结论 | 改什么 | 谁改 |
|---|---|---|---|---|
| `ENV-01` | 1 | 改写 | `total=21` 改为 v2 seed 实测值；增加「7 张表存在」的前置断言 | F1 |
| `API-CON-01..05` | 5 | 不改 | 四字段 / 404-10003 / 405-10004 / 非法 JSON / 无堆栈，v2 全部沿用 | — |
| `API-L-01..12` | 12 | 2 改 10 保留 | 保留 `L-01` `L-03` `L-05`…`L-12`（只随 seed 调数值）；`L-02` 加 `isMine` 与遮蔽断言（列表项从 v1 的 9 个键变为 v2 的 10 个键）；`L-04` 改语义：`status=ALL` 不再出现 `CLAIMED`，COMPLETED/CLOSED 恒 0 命中 | F1 |
| `API-D-01..06` | 6 | 3 改 3 保留 | `D-01` 拆三身份（匿名遮蔽 / 非所有者遮蔽 + `allowedStatuses=[]` / 所有者明文 + `pairs`）；`D-05` 必改（`CLAIMED` 作废、CLOSED 详情对任何身份 404）；`D-06` 加匿名身份；保留 `D-02` `D-03` `D-04` | F1 |
| `API-C-01..13` | 13 | 全部改写 | 13 条全部先登录（无 session → 401/10006）；`C-01` 加 `status="PENDING"`、`allowedStatuses=["CLOSED"]`、`publisherId` 取 session；`C-10` **反向重写**：v1 断言「字母/中文/空格可接受」，v2 只接受 `^1[3-9]\d{9}$`，字母/中文/空格/短横线一律 400/10001；`C-08` 从 `KNOWN_FAIL` 转 `PASS`（DEF-01 已由 `3548a27` 修复，`5.1-defects.md` 复测结论 PASS），断言保留 | F1 |
| `API-U-01..05` | 5 | 全部改写 | 加登录与所有者身份；`U-03` 由「CLOSED 不可改」扩为「COMPLETED/CLOSED 都 409/20003」；新增非所有者 403/10007 与「COMPLETED 合格对端 403」两分支（验收矩阵 `PERM-04b`/`AUTHZ-01`） | F1 |
| `API-S-01..05` | 5 | 全部改写 | `S-01` 由 `PENDING→CLAIMED→CLOSED` 改为 `PENDING→COMPLETED→(终态)`，且前置补 `MATCHED` pair；`S-02` 终态改 COMPLETED/CLOSED；`S-03` 加所有者身份；`S-04` 增加 `CLAIMED` 作为非法值（400/10001）；新增 409/20005 零写入断言（`ST-05`） | F1 |
| `API-X-01..03` | 3 | 全部改写 | 加登录与所有者身份；`X-01` 增加「已删除记录的 pair 走 404/20004」断言 | F1 |
| `API-CAT-01..02` | 2 | 不改 | 分类字典接口 v2 未变 | — |
| `API-M-01..12` | 12 | 全部改写 | 12 条全部先登录（匿名 401/10006）；候选池改 `MATCHABLE`（PENDING + 未到期 COMPLETED，CLOSED 排除）；`M-02` 增加 `recordStatus`/`detailAccess` 两字段与遮蔽断言；`M-05` 负例保持空结果；`M-06` 断言「已关闭(23) 与已删除(25) 都不返回」（v1 只断言已认领）；新增 503/30002 与 504/30003 两条（`DEG-08`/`DEG-09`），**取代** v1 的「失败降级到关键词」预期 | F1 |

**合计：保留 20 条（CON×5 + L×10 + D×3 + CAT×2）、改写 44 条、删除 0 条。**

### 2.2 一定要改的 6 个断言（错一条会整组误判）

| 用例 | v1 断言 | v2 断言 | 依据 |
|---|---|---|---|
| `API-C-10` | contact 允许数字/字母/中文/`-_()` | 只允许 `^1[3-9]\d{9}$`；字母/中文/空格/短横线 → 400/10001 | 契约 §0.2-3 |
| `API-S-01` | `PENDING→CLAIMED→CLOSED` | `PENDING→COMPLETED` 需已有 MATCHED，`COMPLETED`/`CLOSED` 均终态 | 契约 §4.5 |
| `API-D-05` | CLOSED 的 `allowedStatuses=[]`，`CLAIMED` 只能到 CLOSED | CLOSED 详情对**任何身份** 404/20001；`CLAIMED` 传参 400/10001 | 契约 §4.2 判定顺序 |
| `API-L-04` | `status=ALL` 含「已认领/已关闭」 | 公共列表恒 `PENDING`；`status=COMPLETED/CLOSED` 返回 200 + 空列表 | 契约 §4.1 可见性硬约束 |
| `API-M-06` | 不返回已认领(23)与已删除(25) | 不返回 CLOSED、已删除、**以及已到期的 COMPLETED**；保留期内 COMPLETED 返回且标 `detailAccess="RESTRICTED"` | 契约 §4.8 |
| `API-C-08` | `KNOWN_FAIL`（DEF-01） | `PASS`：`occurredAt` 日历非法 → 400/10001 | `5.1-defects.md` 复测结论 |

### 2.3 新增（不属于 5.1 存量，按验收矩阵落地）

| 新增组 | 条数 | 覆盖 | 谁写 |
|---|---|---|---|
| `AUTH` / `AUTHZ` | 8 + 6 | 登录态、401 统一出口、CORS、合格对端 | F1 |
| `PERM` | 51 | 17 接口 × 3 身份逐格 | F1（表驱动） |
| `MASK` | 19 | 遮蔽正反例 + 五个出口 | F1 |
| `TIER` / `IDEM` / `DEG` | 14 + 12 + 12 | 分档、幂等、双链路降级（含 LLM mock） | F1 |
| `PAIR` / `ST` / `PHONE` / `RET` / `TERM` | 7 + 12 + 8 + 6 + 14 | 新增五类判据 | F1 |
| `SCHEMA` / `AUDIT` / `NOTI` / `MY` / `API` / `CONF` / `SEC` | 5 + 4 + 5 + 4 + 7 + 5 + 5 | 数据层、审计、通知、我的启事、通用契约、配置集中、零明文 | F1 |

---

## 3. 块二 · 匹配评估（`qa/match/evaluate.mjs`）

**结论：要改。**

| # | 改什么 | 具体 | 谁改 |
|---|---|---|---|
| 1 | 样例期望值重算 | seed 从 21 条变成 v2 数据集，`S1..S6`/`N1..N4` 的 `top1` recordId 与分数全部重取；**不改样例文本**（文本是产品语义，改了就不是回归） | F1 |
| 2 | 新增 COMPLETED 保留期内样例 `S7` | 输入命中「保留期内 COMPLETED」记录：期望 `top1` 命中且 `detailAccess="RESTRICTED"`、`recordStatus="COMPLETED"` | F1（写）+ F2（出数） |
| 3 | 新增 CLOSED 样例 `S8` | 输入命中 CLOSED 记录：期望 0 命中（不是低分命中） | F1 + F2 |
| 4 | 新增到期边界样例 `S9` | `completed_at = NOW()-30d` 整：期望 0 命中；`NOW()-29d`：期望命中（与 `RET-06` 同口径） | F1 + F2 |
| 5 | 指标口径加一列 | 报告表增加「命中项中被 `RESTRICTED` 限制的条数」，top1/top3 判定只看 `recordId`，不因 `RESTRICTED` 降级 | F2 |
| 6 | 脚本补登录 | `/api/match` 在 v2 需 session；评估脚本先登录取 Cookie，匿名调用应得 401（这一条本身也是断言） | F1 |
| 7 | P2 协议（不限类型）结论 | 保留为对照，不设判绿门槛：v1 已有 3 条 top1 偏移（`S2`/`S3`/`S4`），v2 判定只看 P1 | F2 |

**判绿：** P1 的 top1 = 10/10（含 `S7`/`S8`/`S9` 时按新基数 13/13）、top3 = 13/13；两次调用 `items` 的 `recordId` 序列逐项相等。

---

## 4. 块三 · 三页面冒烟（`qa/e2e/walkthrough.mjs`，19 条）

**结论：要改。** 保留 5 条、改写 14 条，另新增 2 个页面与 3 类交互。

| 用例 | 结论 | 改什么 | 谁改 |
|---|---|---|---|
| `E2E-L-01` | 改写 | 「10 条 / 共 21 条 / 3 页」改为 v2 seed 实测数值；卡片上不再有「已认领」标签 | F1 |
| `E2E-L-02` | 改写 | 数量断言随 seed 变；保留 URL 同步断言 | F1 |
| `E2E-L-03` | 改写 | 「命中 2 条」随 seed 重算 | F1 |
| `E2E-L-04` | 改写 | DEF-02 已由 `1349f09` 修复，从 `KNOWN_FAIL` 转 `PASS`，断言不变 | F1 |
| `E2E-L-05` | 不改 | 翻页 URL 与去重断言 v2 未变 | — |
| `E2E-D-01` | 改写 | 匿名进详情：标题/分类与列表一致，但 `地点` 列表已截断、详情也截断（两处都带 `…`）；`描述` 是打码后文本；不再断言「与列表描述一致」 | F1 |
| `E2E-D-02` | 改写 | 匿名在详情页点「找相似」→ 跳 `/login?redirect=/records/{id}`；登录后才发起 `/api/match` | F1 |
| `E2E-D-03` | 改写 | 由「标记为已认领」改为三态：`PENDING` 显示「标记完成（置灰除非 hasMatchedPair）/关闭/删除」；标记完成后跳 `/my` 且详情入口消失（`detailVisible=false`） | F1 |
| `E2E-C-01` | 改写 | 提交前需登录；表单字段新增 `contact`（格式错误时字段红字）；提交成功跳详情 | F1 |
| `E2E-C-02` | 改写 | 「列表能看到刚提交的记录」需以同一 session 提交（v2 要求登录），断言不变 | F1 |
| `E2E-C-03` | 改写 | 同上，登录态下检索 | F1 |
| `E2E-C-04` | 改写 | 新浏览器上下文打开详情：匿名也能看（200 遮蔽态），内容**不是**全文——断言改为「标题一致、描述为打码文本、contact 不渲染」 | F1 |
| `E2E-E-01` | 改写 | 空表单校验摘要保留；新增 `contact` 格式错误提交一次（`1381234` → 字段红字 + 不发请求） | F1 |
| `E2E-E-02` | 不改 | 后端不可用 → 错误条 + 重试 | — |
| `E2E-E-03` | 不改 | 空数据空态 | — |
| `E2E-E-04` | 改写 | 404 文案保留「记录不存在或已删除」；新增「COMPLETED 不够格」与「CLOSED」两种 404 也落到同一空态（不带重试按钮） | F1 |
| `E2E-E-05` | 不改 | 500 错误条带 `requestId` | — |
| `E2E-E-06` | 改写 | 匹配 503 只降级匹配区；文案从 v1 的「已降级」改为「匹配服务暂不可用」，且**不出现**「已降级」字样（`DEG-10`） | F1 |
| `E2E-Z-01` | 不改 | 无未捕获异常 | — |
| **新增** `E2E-A-01..03` | 新增 | 登录页：错口令红字（文案与接口 `message` 一致）/ 成功跳 `redirect` / 未登录直开 `/my` 被拦 | F1 + B3 |
| **新增** `E2E-M-01..03` | 新增 | 我的启事：三态行与文案（进行中/已完成/已关闭）、`detailVisible=false` 不渲染详情链接、三计数不混用（`relatedCount` 卡片数 / `lowConfidenceCount` 仅条数） | F1 + D2 |
| **新增** `E2E-P-01..03` | 新增 | pair 卡片：`DIRECT` 可直接取号 / `AUTHORIZABLE` 未授权显示「对方尚未公开」/ 0.62 不出卡片只显示「另有 N 条低分结果」 | F1 + E2 |

**判绿：** 25 条（19 改写/保留 + 9 新增）全 PASS；截图目录含 6 个页面（列表/详情/提交/登录/我的启事/匹配结果）。

---

## 5. 块四 · 7.x 部署包（`deploy/`）

**结论：要改（6 处改、3 处不改）。**

| # | 文件 | 结论 | 改什么 | 谁改 |
|---|---|---|---|---|
| 1 | `db/schema.sql` | 要改 | 3 表 → 7 表（`t_user`/`t_category`/`t_record` 扩展/`t_match_result`/`t_pair_score`/`t_notification`/`t_access_log`），含 `ck_record_completed_at`、`ck_pair_matched`、`uk_pair_lost_found` | B1 |
| 2 | `db/migration-v1-to-v2.sql` | 新增 | v1 原地升级：建 5 张新表、`t_record` 加 `completed_at`/`pair_state`/`pair_scored_at`/`publisher_id`、`CLAIMED` 行迁移策略（见 §7-3） | B1 |
| 3 | `server/src/main/resources/application.yml` | 要改 | 增 `findit.pair.{contact_direct_threshold,contact_unlock_threshold,completed_retention_days,completed_visible_threshold,notify_threshold}` 与 `findit.llm.*`；删旧键 `direct-view-threshold`/`contact-auth-threshold`（`grep` 必须 0 命中） | B1 + D1 |
| 4 | `deploy/config/deploy.env.example` | 要改 | 增 `DEEPSEEK_API_KEY=`（空值占位，**不允许填实值**）、`OPS_SCAN_TOKEN=`（空值 = 入口 404） | G1 |
| 5 | `deploy/scripts/install.ps1` | 要改 | 依次执行 schema-v2 / migration + seed；装完校验 `SHOW TABLES` = 7 张，缺一张则退出码 ≠ 0 | G1 |
| 6 | `deploy/build-package.ps1` | 要改 | 打包清单加入 `db/migration-v1-to-v2.sql` 与 `deploy.env.example` 的新键说明 | G1 |
| 7 | `deploy/DEPLOY-WINDOWS.md` | 要改 | 「已知限制」五条：内存 session 重启失效、COMPLETED 30 天保留期（到期不改 status）、手机号无短信验证、PENDING 不自动下架、pair 链路降级语义 | G1 |
| 8 | `deploy/scripts/start.ps1` `stop.ps1` `status.ps1` | 不改 | 端口（18080 / 8088）与进程管理未变；v2 不需要新端口 | — |
| 9 | `docs/testing/7.3-windows-acceptance/` `7.4-deploy-fixes/` | 要重跑 | 证据目录另建 `docs/testing/v2/7x/`，不复用 v1 数值：`health-18080.json` 需含 `version`/`engine`；`07-pages.txt` 需含 `/login` 与 `/my` | G1 + F2 |

**判绿（F2 复核命令，五步全 0 退出码）：** `install.ps1` → `start.ps1` → `status.ps1`（`UP`）→ `stop.ps1` → `status.ps1`（`DOWN`）；另加 `curl -s localhost:18080/api/health` 返回 `db="UP"`。

---

## 6. 块五 · 6.x 文档（`docs/delivery/`）

**结论：要改。**

| # | 文件 | 结论 | 改什么 | 谁改 |
|---|---|---|---|---|
| 1 | `6.2-claims-evidence.md` | 要改 | 第 4 页「三张表是硬上限」→ 7 张表；第 5 页「37 条需求/38 行判据」→ 说明 v2 为 46 条 REQ；第 7 页「3 表 31 列」→ 7 表逐表列数；第 8 页「九个接口」→ 17 个 + 2 运维；「13 个错误码」→ 22 个 | F4 |
| 2 | `6.2-defense-ppt.md` + `6.2-答辩PPT.pptx` | 要改 | 与 ①同步改四页；新增一页「三态 × 四态状态机」与一页「列表隐藏 ≠ 匹配隐藏」；`CLAIMED` 全部删除 | F4 |
| 3 | `6.2-demo-checklist.md` | 要改 | 演示路径加四步：登录（两个账号）→ 我的启事 → 两端确认后「标记完成」→ 取号三档（0.95 直看 / 0.80 未授权 / 0.62 拒绝）；环境前置加 `DEEPSEEK_API_KEY` 可选说明 | F4 |
| 4 | `6.2-recording-script.md` | 要改 | 分镜按 ③ 的四步重排；原「认领」分镜删除 | F4 |
| 5 | `docs/delivery/tools/demo-path-check.mjs` | 要改 | 断言加：登录成功、`/my` 三态行数、标记完成后的 `detailVisible=false`、`/api/health` 含 `engine` | F4 |
| 6 | `docs/design/api-list.md` | 要改 | 9 条 → 17 条 + 2 运维；错误码索引 13 → 22 行；逐条补入参/出参/错误码/幂等（可直接索引 A1 契约 §4） | F4 |
| 7 | `docs/design/program-design.md` | 要改 | 补 v2 章节（状态机、配对单元、遮蔽算法、分档、降级双链路）；v1 的 37 条需求表保留为历史，另立 v2 的 46 条 REQ 表 | F4 |
| 8 | `README.md` | 要改 | 「已知限制」段增加五条（与 `DEPLOY-WINDOWS.md` 同文）；快速开始补 seed 三个账号 | F4 |
| 9 | `docs/testing/5.1-*.md`（7 份） | 不改 | v1 历史报告，保留原样；v2 报告放 `docs/testing/v2/`（本目录） | — |

---

## 7. 差异申报（先报差异，再下结论）

| # | 任务书表述 | 实测 | 结论 |
|---|---|---|---|
| 1 | 「5.1 接口（**63 用例**）」 | `qa/reports/api-5.1.txt` 头部：用例总数 **64**，通过 63，失败 1（已登记缺陷 DEF-01） | 63 是**通过数**，不是用例数。按 **64 条**处理；其中 `API-C-08` 已由 `3548a27` 修复并在 `5.1-defects.md` 复测 PASS，故 v2 起点应为 64/64 |
| 2 | 「三页面冒烟」 | `qa/reports/e2e-5.1.txt`：19 条，通过 18，失败 1（DEF-02） | 按 19 条处理；`E2E-L-04` 已由 `1349f09` 修复，v2 起点 19/19；v2 页面从 3 个扩到 5 个（+登录、+我的启事） |
| 3 | 「`CLAIMED` 作废」的存量数据 | `db/seed.sql` 里存在 `CLAIMED` 记录与 `qa/reports/db-evidence.txt` 的 `status=CLAIMED` 证据 | 迁移脚本必须把 `CLAIMED` 映射到 `PENDING`（**不是** COMPLETED——`COMPLETED` 需要 MATCHED 前置，迁移期无 pair）。此决定需 B1 在迁移 SQL 里写死并在 `DEPLOY-WINDOWS.md` 记一行；若人类要求映射到别处，属需求变更 |
| 4 | 本文与验收矩阵的条数关系 | 矩阵 204 条判据 vs v1 存量 64 条接口用例 | 不同层级：64 条是现有资产（改写/保留后仍是 64 条骨架），204 条是验收判据（F1 落地后由 `qa/` 承载，允许一条判据由一个测试函数的多个断言覆盖）。两者对不上时以矩阵为准，差额由 F1 在交付说明里逐组列明 |

---

## 8. 覆盖缺口（哪些没测到，说清楚）

| # | 缺口 | 影响 | 处置 |
|---|---|---|---|
| 1 | **前端无单元测试**：`web/` 下 0 个 `*.spec.*`，v2 也不建 | 组件级回归只能靠 E2E；遮蔽/分档的渲染分支靠截图判断 | 接受（测试金字塔里 v2 不补前端单测，理由：团队 1–3 人、页面 5 个、E2E 已覆盖业务断言）；风险：改样式类重构不会被单测拦住 |
| 2 | **`/api/match` 外部引擎实现对 v2 仍未落地** | `DEG-08`/`DEG-09` 只能对着 mock 验「不降级」，没有真实外部引擎的兼容性证据 | 登记为缺口；`docs/ai-match-module.md` §3.2 已声明该实现不在本期 |
| 3 | **pair 并发 CONFIRM 竞态**（两端同时点确认） | 可能两条 UPDATE 都成功但 `status` 只有一端生效 | 本文只覆盖 `IDEM-11`（扫描并发）；建议 F2 补 1 条并发用例，暂列 P2 缺口 |
| 4 | **时区**：所有时间按 `Asia/Shanghai` 本地时间处理，无跨时区用例 | 单机部署假设（A1 §1.3 A2）成立时无影响 | 不做；若将来多时区，`completed_retention_days` 的窗口计算要重测 |
| 5 | **移动端断点/手势**：A3 只补了桌面三档 | 窄屏下的登录页与我的启事未验 | 缺口保留；`qa/e2e/consistency.mjs` 的 320/768 溢出检查仍跑，只保证不溢出，不保证交互 |
| 6 | **`t_access_log` 增长**（每次取号一行，append-only） | 长期运行后表膨胀，无归档策略 | A1 §4.5 已标为估算；本文不加用例，登记为实现侧观察项 |
| 7 | **安全渗透**（SQL 注入 / 越权绕过 / CSRF 残余风险） | 不在本 Agent 范围 | 交 F3 火眼眼与专用工具；本文只覆盖契约内的越权格（`PERM` 51 格） |

---

## 9. 与验收矩阵的对应关系

| 矩阵组 | 回归落点 |
|---|---|
| `AUTH` `PERM` `AUTHZ` `MASK` | 5.1 接口组重写（`D-01`/`L-02`/`C-10` 等）+ 新增表驱动用例 |
| `TIER` `IDEM` `DEG` | 5.1 的 `S-*`/`M-*` 改写 + 新增 mock 服务 |
| `PAIR` `ST` `PHONE` `RET` `TERM` | 5.1 无存量，全部新增 |
| `SCHEMA` `AUDIT` `NOTI` `MY` `API` | `qa/db/evidence.mjs` 扩项 + 新增用例 |
| 7.x / 6.x 文档 | 不进 `qa/`，由 G1 / F4 自查 + F2 抽查 |
