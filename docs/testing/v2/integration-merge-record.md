# v2.1.0 总集成：六条分支合入 main + 真包四条验证（ZHYYKFX-92）

- 执行时间：2026-10-29
- 集成分支：`v2.1.0-main`（从 `origin/main` = `0ff400a` 起）
- 合并顺序：83 → 88 → 84 → 87 → 86（rebase 后）→ 85，每步后 `server/mvnw -o test`
- 测试：逐条合并后各跑一次，终态 **313 tests / 0 failure / 0 error / 1 skipped**（跳过的 1 条是 `ReviewAbHarnessTest`，需要真实 DeepSeek key）
- 前端：`npm ci && npm run build`、`npm run type-check` 均通过

## 1. 合并记录与冲突取舍

| 顺序 | 分支 | 分支 tip | 合并提交 | 冲突 / 人工取舍 |
|---|---|---|---|---|
| 1 | `fix/v2-Z83-cors-deploy` | `704c141` | `a2f4d1f` | 无冲突。`nginx.conf` 的 `Host` 改 `$http_host`、`WebConfig` 白名单扩到 loopback 任意端口，`nginx -t` 通过 |
| 2 | `feat/v2-Z88-degraded-rescan` | `c6ec799` | `cff8f59` | 无冲突。`application.yml` 的 `score.*` 与 84 的 `pair.*` 是两段，互不覆盖 |
| 3 | `feat/v2-Z84-threshold-calibration` | `dcb25a8` | `b41b005` | 无文本冲突；**口径复核另起一条提交** `bfab99d`（见 §2） |
| 4 | `docs/v2-Z87-deploy-troubleshooting` | `2227f5b` | `4ab25d0` | 无冲突，纯 `docs/` 与 `deploy/*.md` |
| 5 | `test/v2-F3-integration-rescan` | `741fa44` → rebase 后 `b258a2f` | `b03cffb` | rebase 到当时 main：4 个 add/add 冲突（`F3-integration-rescan.md`、`F3R-04-cache-headers.txt`、`qa/f3r/cache-headers.sh`、`qa/reports/f3r-cache-headers.txt`）全取分支侧；随后 86 自己的「按日期归档」提交把 `qa/reports/f3r-*.{json,txt}` 改名为 `f3r-2026-10-28-*`（内容 R100 不变） |
| 6 | `feat/v2-Z85-review-gate` | `33d3397` | `592479d` | 无文本冲突（Z85 的 base 已含 88/84，git 只应用 Z85 自身 delta）；但**合并后 testCompile 红**：`RecordServiceAccessTest` 的 `cardsOf(any(),any(),any(),any())` 打桩仍是 84 之前的 4 参签名，改为 2 参（阈值已移进 `PairFactsAdapter`），提交 `e812790` |

### `application.yml` / `schema.sql` / `RecordMapper.xml` 的取舍

- `application.yml`：合并结果相对合并前 main **只有新增行**（review 段），相对 Z85 **只删掉 Z85 那份过期的 `0.90/0.75` 阈值行** —— 即保留 84 的新阈值与 88 的 `score.*`，加上 85 的 `review.*`。两边配置都在。
- `schema.sql`：合并结果与 Z85 侧**逐字一致**，相对 main 只有 `t_review_audit` 与 `t_record` 新列（`review_state`/`raw_*`/`match_*`/`summary`/`is_visible` 生成列/`idx_record_review`）。即 main 侧列是 Z85 侧的子集，并集 = Z85 侧，无丢失。
- `RecordMapper.xml`：相对 Z85 只多出 88 的 `markPairScoredKeepTimestamp` 与 `selectDegradedScanTargets`；相对 main 只多出 85 的可见性谓词改写（`is_visible`）与审核态列。两边都在。
- `seed.sql`：85 追加一段 `UPDATE t_record SET review_state='APPROVED' … WHERE match_title IS NULL`。实测导入后 **24/24 条 `review_state='APPROVED'`**（首装 `install.ps1` 自动校验 `t_record=24 行`）。

## 2. 阈值口径复核（84，召回优先）

依据 `qa/datasets/pair-synonyms/results/syn-table.md`（6 物品 × 4 措辞 + 6 陷阱）：

- **本地引擎下调 `unlock` 0.45 → 0.40**：同义样本 **S1-OF（小米充电宝 O→F）本地分 0.41**，在 0.45 下被卡在卡片外（17/18 = 94.4%）；0.40 后 **18/18**。代价为零：陷阱集最低 **T2 = 0.33**，0.40 之下仍不可见，陷阱过阈维持 **5/6**。
- **默认（语义引擎）维持 `unlock` 0.80**：同义集 18/18、标注集最低正例 0.92 均无正例被卡，不存在"正例卡在 unlock 之下"的事实；而语义陷阱最高 **0.72**，降到 0.70 会让 **T1/T3/T5 三条负例**进可授权档。故不采纳建议值 0.70。
- 同步修改：`application.yml` 注释、`PairPolicyPropertiesTest` 出厂值断言、`docs/testing/v2/threshold-calibration.md` 的表格与 §6.2 变更说明。

## 3. 真包四条验证

环境：`deploy/dist` 的 zip 解压到干净目录 → `install.ps1 -DbName findit_v210 -DbUser root -EmptyDbPassword`（MySQL 8.0.29，本地实例）→ `start.ps1`（后端 18080 + 包内 nginx 8088）。
LLM：本机无 `DEEPSEEK_API_KEY`，用**本地 OpenAI 兼容替身**（`FINDIT_LLM_BASE_URL=http://127.0.0.1:18090/v1`，固定返回 `APPROVE` / `score=0.97`）驱动。**本节结论只覆盖链路与状态机，不代表模型判断质量**；真实模型路径由 `ReviewGateServiceTest` / `PairScoringServiceTest` 覆盖。

### ① 8088 写请求不再是 `Invalid CORS request`

| 请求 | 结果 |
|---|---|
| `POST /api/auth/login`，`Origin: http://127.0.0.1:8088` | **200**（旧版为 403 `Invalid CORS request`） |
| `POST /api/auth/login`，`Origin: http://127.0.0.1:5173` | 200，回 `Access-Control-Allow-Origin: http://127.0.0.1:5173`（loopback 任意端口放行） |
| `POST /api/auth/login`，`Origin: http://evil.example.com` | **403**（白名单外仍拒绝） |
| `OPTIONS /api/records` 预检 | 200 |
| 包内 `nginx.exe -t` | `syntax is ok` / `test is successful` |

### ② 未审核（`PENDING`）记录四处 0 命中

做法：替身对含 `Z85PEND` 的 prompt 返回 500 → 审核 3 次重试（1s/2s/4s）耗尽 → 记录 #30 停在 `PENDING` + `RULE_ONLY` + `reviewManualRequired=true` + `ownerConfirmAvailable=true`。

| 出口 | 结果 |
|---|---|
| 公共列表 `GET /api/records?keyword=Z85PEND`（匿名） | `total=0` |
| `POST /api/match` 用同文案检索（demo02） | `total=1`，命中 #9（另一条 APPROVED 保温杯），**不含 #30** |
| 他人详情 `GET /api/records/30`（demo02） | **404** |
| 他人「我的启事」`GET /api/my/records`（demo02） | **不含 #30**；对照本人（demo01）**含 #30** |

附带验证双通道：本人 `POST /api/records/30/confirm-publish` → `APPROVED`、`reviewEngine=RULE_ONLY` 不补审、公共列表出现、他人详情 200；`t_review_audit` 落 `OWNER_CONFIRM/APPROVE/HUMAN`，与 `REVIEW/PENDING` 两次失败记录并存。

### ③ `review.mode=ai` 正常记录可上架并触发打分；`mode=off` 与 v2.0 一致

- ai 模式：发布 #28（键盘，`contact=13800000002`）→ 异步审核 `decision=APPROVE`（日志 `review llm ok`）→ **`APPROVED` + `reviewEngine=DEEPSEEK`**，`summary`/`reviewReason` 落库 → 公共列表按关键词 `total=1` → 他人详情 200 → `pair_state` 由 `PENDING` 推进到 `READY`、`pair_scored_at` 有值（打分已触发）。
- 反例（闸门有效）：同样 ai 模式下，替身返回的展示文本若残留确定性身份特征，`ReviewGateService` 判 **`REJECTED`，理由「规则双检命中」**，不进公共列表。
- off 模式（重启，`FINDIT_REVIEW_MODE=off`，LLM 指向不可达端口）：发布 #31（描述里带手机号）→ **创建响应即 `APPROVED` + `reviewEngine=OFF`**，公共列表立即可见，`pair_state=DEGRADED`（无 LLM 走本地引擎）—— 与 v2.0 行为一致，闸门不介入。

### ④ `DEGRADED` 记录在 LLM 可用后一个周期内升级为 `DEEPSEEK`

- 构造：#29（LOST）与 #28（FOUND）已配对 `t_pair_score#34`，将其改为 `engine=LOCAL_KEYWORD, score=0.42, degraded_reason=EMPTY_KEY`，#29 置 `pair_state=DEGRADED`、`pair_scored_at` 回拨（降级冷却已过）。
- 验证时为缩短等待，`degraded-scan-cron` 由 `0 */10 * * * *` 临时改为 `0 */1 * * * *`、`degraded-retry-minutes=1`（仅测试启动参数，包内默认值未改）。
- 观测：`08:49:01` 该记录被降级重扫捞起（`pair_state=SCORING`）→ `08:49:04` **`engine=DEEPSEEK`、`score=0.97`、`degraded_reason=NULL`、`pair_state=READY`**，即**一个周期内**完成升级。
- 附注：若 pair 的降级冷却（`updated_at`）尚未到期，第一轮扫描只会把记录重新标 `DEGRADED` 并推后时钟，下一轮才重算——这是设计内的节流，不是丢失。

## 4. 与验收标准的对应

| 验收标准 | 证据 |
|---|---|
| main 包含六条分支全部改动 | `git merge-base --is-ancestor <tip> origin/main` 逐条 YES（83/88/84/87/86-rebased/85） |
| 全量测试 0 failure / 0 error | `Tests run: 313, Failures: 0, Errors: 0, Skipped: 1` |
| Release tag 指新 main、单文件资产、BUILD-INFO 与 tip 一致 | 见 GitHub Release `v2.1.0-windows` 与包内 `BUILD-INFO.txt` |
| `v1.0.0-windows` / `v2.0.0-windows` 不受影响 | 两个旧 tag 未移动（发布前后 `git rev-parse` 一致） |
