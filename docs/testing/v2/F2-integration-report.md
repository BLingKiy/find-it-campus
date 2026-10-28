# v2 · F2 集成回归（ZHYYKFX-59）

> 状态：**待人工验收** · 分支 `feat/v2-F2-integration` · 基线 `feat/v2-F1-regression-assets@db8b3dc`
> 执行时间：2026-10-28 10:05–10:30（本地 Windows 10 / MySQL 8.0.29 / JDK 17）
> 事实源：`docs/api-contract-v2.md`、`db/schema.sql`、`db/seed.sql`；本文只写可复现结果，不写「基本通过」。

---

## 1. 结论

**0 fail。**

| 组 | 结果 | 证据 |
|---|---|---|
| 全量 `qa/run-all.mjs`（api / match / e2e / db / consistency） | 五阶段退出码 0；接口 111/111、E2E 25/25、一致性 9/9、落库取证 19/19 | `F2-run-all-output.txt` |
| 三页面 × 三态冒烟 | 10/10 用例通过；9 张验收截图齐备；5 个页面 DOM 手机号 0 命中；页面错误 0 | `shots/01..09`、`shots/f2-smoke.json` |
| 手机号泄漏全局扫描 | 28 个探针，**0 命中**（匿名 / 非本人响应体、通知 title+body） | `F2-phone-scan.txt` |
| 降级路径实测 | 发布 200 → pair `LOCAL_KEYWORD + EMPTY_KEY` → 详情「已降级」可见 | `shots/11-degraded-detail.png`、`shots/f2-degradation.json` |
| 多对 pair 独立 | 1/1 通过：A/B 两对各成一行，处理 A 后 B 仍 NEW 且可联系，招领仍在候选池 | `F2-evidence-console.txt` |
| 终态组（vF） | 5/5 通过：409 / COMPLETED 隐藏+匹配可见 / 404 门槛 / CLOSED 双 0+403 / 到期消失 | 见 §9 |
| 包内冒烟 | **不适用**：`origin/main` 无 7.x 包，条件不成立（见 §10）；重打包归 G1 | `git ls-tree` 输出 |

**Match 评估单列说明**：P1「产品路径」（详情页真实调用方式，带 type）**10/10 通过**；P2「不限类型」是对照协议，6/10 top1 命中，4 条「不通过」为同事件另一侧记录抢占 top1 的已知观察项（与 F1 基线一致，脚本退出码 0，不计入门禁 fail）。未把 P2 的 4 条观察项写成通过。

---

## 2. 分支、基线与构建口径

| 项 | 值 |
|---|---|
| 功能分支 | `feat/v2-F2-integration`（从 `feat/v2-F1-regression-assets@db8b3dc` 切出，按派工补记修正，不用 main） |
| main 实读 | `21784f67`（v1，无 v2 代码；原「从 main 切」口径不成立） |
| 后端 | `server/target/find-it-campus-0.0.1-SNAPSHOT.jar`，18080；**未设置** `DEEPSEEK_API_KEY` |
| 复用构建产物 | jar 由 F1 在 `db8b3dc` 基线构建；`git diff --name-only ec670be db8b3dc -- server web` 为 **0**，源码等价，本轮未重打包 |
| 前端 | `npm run dev` → 5173；`web/.env.local`：`VITE_USE_MOCK=false` |
| 数据库 | MySQL 8.0.29，`db/schema.sql` + `db/seed.sql`；run-all 前后各重置一次；F2 取证脚本结束再 resetSeed |
| 后端日志 | `qa/reports/server.log`（PAIR-07 / TERM-06 的 EV-LOG 数据源） |

健康检查：`GET /api/health` → `200 / {"status":"UP","db":"UP","engine":"local"}`。

---

## 3. 全量执行（原文见附件）

命令：

```bash
node qa/run-all.mjs
```

汇总原文：

```text
| 阶段 | 退出码 | 结论 |
|---|---|---|
| 接口测试（node qa/api/run.mjs） | 0 | 通过 |
| 匹配效果评估（node qa/match/evaluate.mjs） | 0 | 通过 |
| E2E 走查（node qa/e2e/walkthrough.mjs） | 0 | 通过 |
| 落库取证（node qa/db/evidence.mjs） | 0 | 通过 |
| 跨页一致性（node qa/e2e/consistency.mjs） | 0 | 通过 |

- 接口：111 条，通过 111，失败 0
- E2E：25 条，通过 25，失败 0
- 一致性：9 条，通过 9，失败 0
```

落库取证 19/19，关键项：7 张表齐全、公共列表 21 条、`t_pair_score` 行数=唯一 (lost,found) 对数（785=785）、pair 状态仅 NEW/CONTACTED/MATCHED/IGNORED、`t_record.status/completed_at` 双向绑定 0 违规、`t_user` 无手机号列、快照 JSON 无手机号。

完整 stdout：`docs/testing/v2/F2-run-all-output.txt`（含 PAIR-07 / TERM-06 的 EV-SQL 与 EV-LOG 摘录）。

---

## 4. 三页面 × 三态冒烟

命令：

```bash
node qa/e2e/f2-smoke.mjs
```

结果：**10/10 通过**；9 张验收截图（+1 张通知页），每张底部固定条含「身份 + URL」，关键区域可见。

| # | 页面 | 身份 | 截图 | 关键断言 |
|---|---|---|---|---|
| 1 | 列表 `/` | 匿名 | `shots/01-list-anon.png` | 10 张卡片 / 共 21 条 / DOM 无号码 |
| 2 | 列表 `/` | 本人 demo01 | `shots/02-list-owner.png` | 卡片加载 / 出现「我的启事」徽标 |
| 3 | 列表 `/` | 非本人 demo02 | `shots/03-list-non-owner.png` | 卡片加载 / DOM 无号码 |
| 4 | 详情 `/records/1` | 匿名 | `shots/04-detail-anon.png` | 匿名横幅 + 「查看联系方式」+ 遮蔽占位；DOM 无号码 |
| 5 | 详情 `/records/1` | 本人 demo01 | `shots/05-detail-owner.png` | 明文 `13800000001` + 配对面板 |
| 6 | 详情 `/records/1` | 非本人 demo02 | `shots/06-detail-non-owner.png` | 仅遮蔽占位；无本人节点；DOM 无号码 |
| 7 | 提交 `/records/new` | 匿名 | `shots/07-create-anon.png` | 守卫重定向 `/login?redirect=%2Frecords%2Fnew` |
| 8 | 提交 `/records/new` | 本人 demo01 | `shots/08-create-owner.png` | 表单可填 |
| 9 | 提交 `/records/new` | 非本人 demo02 | `shots/09-create-non-owner.png` | 表单可填（发布归自己） |
| + | 通知 `/notifications` | 非本人 demo02 | `shots/10-notifications-non-owner.png` | 通知列表可见；DOM 无号码 |

DOM 扫描：5 个匿名/非本人页面，正则 `1[3-9]\d{9}` **0 命中**；页面 JS 错误 0。明细 `shots/f2-smoke.json`。

---

## 5. Match 评估口径

命令：`node qa/match/evaluate.mjs`（退出码 0）

| 指标 | P1 产品路径 | P2 不限类型（对照） |
|---|---|---|
| top1 命中率 | 10/10（100%） | 6/10（60%） |
| top3 命中率 | 10/10（100%） | 10/10（100%） |
| 两次调用一致 | 10/10 | 10/10 |
| 引擎耗时 p95 | 1 ms | 1 ms |

P2 的 4 条不通过（S2/S3/S4/S6）原因是：不带 type 时，同事件的另一侧记录排到 top1；期望目标仍在 top3 内。该现象在 F1 基线（`feat/v2-F1-regression-assets@db8b3dc`）与 5.1 阶段一致，不是本轮回归新引入；产品实际调用路径带 type（P1 10/10）。**不把它改写成通过。**

---

## 6. 手机号泄漏全局扫描

命令：

```bash
node qa/api/f2-scan.mjs
```

结果：**28 个探针，0 命中**；输出原文 `docs/testing/v2/F2-phone-scan.txt`。

| 载体 | 覆盖 | 命中 |
|---|---|---|
| 匿名响应体 | 列表 `status=ALL` 第 1/2 页、11 条详情（含 contact 种子记录）、`POST /api/match`（401） | 0 |
| 非本人响应体 | demo02 看 demo01 的 #1/#2/#3/#5、demo01 看 demo02 的 #13/#16、双身份 `/api/match` 候选 | 0 |
| 通知正文 | demo01/demo02/demo03 的 `/api/notifications`（title+body，共 8 条） | 0 |

扫描细节（如实记录）：

- 首次扫描在匿名详情 #16 命中 1 条 `170****9394`，定位为响应 `requestId`（16 位随机十六进制）偶发拼成号码形状，**不是业务字段**。扫描器已剔除 `requestId` 后重跑；业务字段零命中。
- 初次把 #14/#15/#17/#18 误标为 demo02 所有，数据库实读 publisher_id=1（demo01），属「本人可见」设计内行为；已按真实 owner 重新划分探针。
- **残余风险（未承诺项）**：只遮蔽 `contact` 字段；若用户在 `description` 自由文本里手写手机号，详情页会原样展示。本轮种子数据无此情况；是否对描述做号码脱敏属于产品口径，未在本条擅自判失败。

---

## 7. 降级路径实测（无 DEEPSEEK_API_KEY）

命令：

```bash
node qa/e2e/f2-degradation.mjs
```

链路与输出：

```text
[EV-API] DEG-UI POST /api/records → HTTP 200；recordId=27；pairState=DEGRADED
[EV-SQL] DEG-UI pairId=15 engine=LOCAL_KEYWORD degraded_reason=EMPTY_KEY score=0.5600
[EV-UI]  「已降级」提示可见：已降级 智能匹配暂不可用，已用关键词匹配。分数仅供参考。
```

验收对位：

- 发布 200 ✅（浏览器实际 `POST /api/records` 200，跳详情并显示「发布成功」）
- pair 落 `LOCAL_KEYWORD` + `EMPTY_KEY` ✅（直查 `t_pair_score`）
- 页面提示可见 ✅（`data-testid=pair-degraded-banner`，截图 `shots/11-degraded-detail.png`）

API 层另有 `F2-DEG-01`（`node qa/api/f2-evidence.mjs`）与 DEG-01..03（`node qa/api/run.mjs`）覆盖：`/api/match` 仍 200 + `LOCAL_KEYWORD`，不是 503。

---

## 8. 多对 pair 独立实测

命令：`node qa/api/f2-evidence.mjs`（用例 `F2-MP-01`）

```text
[EV-SQL] MP 初始 pair：15=NEW / 17=NEW / … / 44=NEW（同一招领 foundId=27）
[EV-API] MP CONTACT A(pairId=34) → HTTP 200；A=CONTACTED，B=NEW；CONTACT B(pairId=44) → HTTP 200/CONTACTED
[EV-API] MP /api/match 命中招领 recordId=27：true；三条记录 status 均 PENDING
[EV-SQL] MP 目标 pair（A/B）行数=2；该招领自动评分产生 pair 共 12 行（多条寻物各自成对，互不覆盖）
```

结论：

- 同一招领（demo03 发布）与两条寻物（demo01/demo02 分别发布）各成一行 pair，唯一键 `(lost_id, found_id)` 成立。
- 处理 A（标已联系）后，B 仍为 `NEW` 且 B 的参与方可正常 `CONTACT`；无占用/锁定。
- 招领仍在 `/api/match` 候选池；三条记录的 `t_record.status` 全部保持 `PENDING`，pair 进展不触发记录状态变更。
- 该招领被自动评分召回 12 条寻物，各行独立，未出现一行覆盖另一行。

---

## 9. 终态组（vF）单列

统一命令：`node qa/api/f2-evidence.mjs`。`recordId/pairId` 每轮由自增分配，以下为本轮实际值；原始行见 `docs/testing/v2/F2-evidence-console.txt`。

| 项 | 操作 | 输出（原文） | 判定 |
|---|---|---|---|
| 409 拒绝 | `PATCH /api/records/30/status {"status":"COMPLETED"}` | `HTTP 409 / code=20005 / {"currentStatus":"PENDING","required":"MATCHED_PAIR","matchedPairCount":0}`；快照前后 `status/completedAt/updatedAt/deletedAt` 完全一致 | 通过 |
| COMPLETED 列表隐藏 / 匹配可见 | 公共列表 `status=ALL`、关键词搜索、`POST /api/match`、三身份详情 | `列表命中=false；搜索 total=0；match 召回=true detailAccess=RESTRICTED`；详情 `owner 404 / 合格对端 200 / 非合格 404` | 通过 |
| 404 门槛（score 0.80） | 合格对端 demo02 读 COMPLETED 详情 | `pair score=0.80 < 0.90：HTTP 404/code=20001` | 通过 |
| CLOSED 双 0 命中 + 403 | 关闭记录后查列表/搜索/match、三身份详情、pair 取号与改状态 | `列表命中=false；搜索 total=0；match 召回=false`；详情 `{"anon":404,"owner":404,"other":404}`；`pair contact HTTP 403/50005；pair CONTACT HTTP 403/50005` | 通过 |
| 到期消失 | COMPLETED 的 `completed_at` 改为 31 天前 | `到期前召回=true，到期后召回=false；合格对端详情 HTTP 404`；`status=COMPLETED`、`completed_at=2026-09-27 10:27:15` 未被改写 | 通过 |

补充：`node qa/api/run.mjs` 的 STAT-01..05、TERM-06、API-D-05 在 111 条全量里同样通过（含 IGNORED pair 不作 COMPLETED 详情通道、改回 NEW 后 200）。

---

## 10. 包内冒烟

**不适用，条件不成立。** 任务口径：「若 7.x 包已在 main，则用现有包验证启动；重打包归 G1」。

实读证据：

```text
$ git rev-parse origin/main
21784f674ca3a5915aaa86dc95edd7a0e55e973d          # v1，不含 v2
$ git ls-tree -r --name-only origin/main -- deploy/dist | wc -l
0
$ find . -iname "*.zip"（排除 node_modules）
（无输出）
```

- `origin/main` 只有 `deploy/` 构建脚本与文档，没有 7.x 交付包，也没有 `deploy/dist/*.zip`。
- 本机磁盘上存在 7.3/7.4 验收包（如 `C:\Project\deploy-accept\...`、`C:\Project\deploy-selftest\...`），但 `BUILD-INFO.txt` 记录的是 `feat/7.4-deploy-fixes@da8b936`（v1），**不能用于验证 v2**；启动它还会占用 8088 并与本轮 18080/3306 数据互相干扰。
- 因此本条按 N/A 记录；v2 包构建与包内启动归 G1。

---

## 11. 没测到的 / 风险声明

- **真实 DeepSeek**：本轮无 Key，pair 全链路走降级；`DEEPSEEK` 引擎与真实模型质量未复测（D1 已有 live 资产，需密钥到位单独跑）。
- **跨浏览器**：E2E 只跑 Edge（Playwright `channel=msedge`）；Firefox / WebKit 未跑。
- **保留期边界**：只验证 31 天（>30）到期消失；29/30 天边界未逐日验证。阈值边界 0.90 / 0.80 已覆盖，0.8999 未逐位验证（DECIMAL(5,4)）。
- **并发**：未做并发取号 / 并发 CONFIRM / 并发 CLOSE 压测；D1 已有发布并发与连接池证据。
- **自由文本号码**：见 §6 残余风险，描述字段不做号码脱敏。
- **a11y / 性能**：本轮未做 axe/WCAG、Lighthouse/Core Web Vitals；不把「页面能开」当成性能与可访问性结论。
- **包内启动**：见 §10，本轮不适用。
- 本报告证明上述用例在当前基线全绿，**不承诺零缺陷**。

---

## 12. 复现

```bash
# 1) MySQL（本机 8.0.29）
"C:/Project/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe" -u root < db/schema.sql
"C:/Project/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe" -u root find_it_campus < db/seed.sql

# 2) 后端（不设置 DEEPSEEK_API_KEY = 降级路径）
java -jar server/target/find-it-campus-0.0.1-SNAPSHOT.jar > qa/reports/server.log 2>&1

# 3) 前端
cd web && npm run dev    # 5173，VITE_USE_MOCK=false

# 4) 全量
node qa/run-all.mjs

# 5) F2 专项（顺序执行；脚本内部会 resetSeed）
node qa/api/f2-evidence.mjs        # 终态组 + 多对 pair + 降级 API
node qa/api/f2-scan.mjs            # 手机号全局扫描
node qa/e2e/f2-smoke.mjs           # 三页面三态 + 9 张截图 + DOM 扫描
node qa/e2e/f2-degradation.mjs     # 无 Key 发布 → 降级提示（UI）
```

---

## 13. 交付物

| 文件 | 内容 |
|---|---|
| `docs/testing/v2/F2-integration-report.md` | 本报告 |
| `docs/testing/v2/shots/01..09-*.png` | 三页面 × 三态 9 张验收截图（含 URL） |
| `docs/testing/v2/shots/10-notifications-non-owner.png` | 通知页 DOM 扫描佐证 |
| `docs/testing/v2/shots/11-degraded-detail.png` | 降级发布后「已降级」提示 |
| `docs/testing/v2/shots/f2-smoke.json` | 冒烟用例、DOM 扫描、页面错误明细 |
| `docs/testing/v2/shots/f2-degradation.json` | 降级实测 API/SQL/UI 证据 |
| `docs/testing/v2/F2-phone-scan.txt` | 28 个探针的扫描原文 |
| `docs/testing/v2/F2-run-all-output.txt` | run-all 完整 stdout |
| `docs/testing/v2/F2-evidence.txt` / `F2-evidence-console.txt` | 终态组 / 多对 pair / 降级 EV 原始行 |
| `qa/api/f2-evidence.mjs`、`qa/api/f2-scan.mjs`、`qa/e2e/f2-smoke.mjs`、`qa/e2e/f2-degradation.mjs` | 可复跑脚本 |

**fail 数：0。**
