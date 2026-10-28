# M7 候选包独立验收（端测测 · 真包复验）

- 任务：ZHYYKFX-91「M7 候选包独立验收（真包复验，不采信交付方自检）」
- 验收人：端测测（Agent `5d43311d-fac8-4288-be4f-79df5aa376d5`）
- 执行时间：2026-10-29 03:52–04:12 +08:00
- 候选包：`find-it-campus-windows-x64-30002de.zip`（28,535,449 字节，SHA256 `ACBA7323…D1557`）
- 证据分支：`test/v2-M7-independent-accept`（基于 `30002ded…`，本目录为唯一新增内容）
- 复验机：Windows 10 Pro for Workstations；JDK `17.0.20.1+1`（Temurin）；MySQL `8.0.29`（127.0.0.1:3306，root 空口令）；PowerShell 5.1（有效执行策略 Restricted）
- 部署目录：`C:\Project\deploy-accept\Z91-20261029-035223\pkg\find-it-campus-windows-x64`（仓库工作区之外）
- 数据库：部署实例用 `findit_accept91`；面 D 另起干净库 `findit_m7_faced`（同一 jar 的独立实例，端口 18085）

**结论：可进入人类发布门禁**（1–4、5a/5b/5d、6a、7、8 全绿；AI 相关分支因本机无 key 未执行，见 §4/§5/§6 与 §10 未测清单）。

## 判据总表

| # | 判据 | 实际命令 / 观察 | 结果 |
|---|---|---|---|
| 1 | zip SHA256 = `ACBA…1557`、28,535,449 字节、与 `.sha256` 一致 | `Get-FileHash -Algorithm SHA256` → `ACBA7323689DA1387A5D51569720B7FDE7737D92F4F1FE3630EBB8F0122D1557`；`Length = 28535449`；`.sha256` 同值 | 通过 |
| 1 | BUILD-INFO：commit=30002de、dirty=false | 解包读 `BUILD-INFO.txt`：`Git commit: 30002ded5893661f680eaea4e0b858e40ca23255`、`Git dirty: false` | 通过 |
| 1 | 包内 jar / index 的 SHA256 必须等于 BUILD-INFO | 实算 `516653FD…54E83` / `E76443CA…3E55F`，与 BUILD-INFO 逐位一致 | 通过 |
| 1 | 证据分支 = bb3d15d5，diff 只含 `docs/testing/v2.1-candidate/` | `git rev-parse origin/test/v2-M6-candidate-package` → `bb3d15d5…`；`git diff --name-only 30002de..bb3d15d5` 共 40 文件，非该前缀文件数 = 0 | 通过 |
| 2 | install/start/status/stop 退出码均 0 | 四次调用 `INSTALL_EXIT=0` `START_EXIT=0` `STATUS_EXIT=0` `STOP_EXIT=0` | 通过 |
| 2 | status 全绿 | 后端 UP(18080/db=UP) / Nginx UP(8088) / MySQL OK / 8088 健康 UP / 8088 监听 OK / 18080 监听 OK → `RESULT: HEALTHY` | 通过 |
| 2 | stop 后 8088、18080 释放 | `stop.ps1` 输出「8088 已释放 / 18080 已释放」；随后 `Get-NetTCPConnection -State Listen -LocalPort 8088,18080` 无结果；无 java/nginx 残留 | 通过 |
| 2 | `/api/health`：code=0、status=UP、db=UP | `{"code":0,"message":"ok","data":{"status":"UP","db":"UP","version":"0.0.1-SNAPSHOT","engine":"local"}}` | 通过 |
| 2 | `/`、`/records/1`、`/records/new` 三页 + 截图 | 三页 HTTP 均 200；`/records/new` 匿名落地 `http://127.0.0.1:8088/login?redirect=%2Frecords%2Fnew`（HTTP 200 + 前端重定向）；登录 demo01 后表单正常 | 通过（截图 4 张） |
| 3 | 8088 同源 POST → 200（非 403） | `HTTP=200`，响应头无 `Access-Control-Allow-Origin`（同源语义正确） | 通过 |
| 3 | 无 Origin → 200 | `HTTP=200` | 通过 |
| 3 | `Origin: http://localhost:5173` 跨源 → 200 + ACAO 回显 + Credentials | `Access-Control-Allow-Origin: http://localhost:5173`；`Access-Control-Allow-Credentials: true`；`Access-Control-Expose-Headers: X-Request-Id`；`HTTP=200` | 通过 |
| 3 | 外部 Origin → 403 `Invalid CORS request` | `HTTP=403`，body 原文 `Invalid CORS request` | 通过 |
| 3 | `nginx -t`（包内配置）退出码 0 | `syntax is ok` / `test is successful`，`NGINX_T_EXIT=0` | 通过 |
| 4 | `review.mode=off` 默认：同步 APPROVED、`raw_*`/`match_*` 为 NULL、公共列表可见 | 新建 3 条：`review_state=APPROVED`、`review_engine=OFF`、`review_manual_required=0`、`is_visible=1`、`raw_title`/`match_title` 均 `<NULL>`；匿名 `GET /api/records` 返回 24 条，id 27/28/29 均在列表内 | 通过 |
| 4 | AI 分支（APPROVED / REJECTED / PENDING+manual） | 本机无 `DEEPSEEK_API_KEY`，未执行 | 未执行 |
| 5 | 无 key：pair 落 `LOCAL_KEYWORD` + `EMPTY_KEY` | `t_pair_score` 共 52 行，`engine='LOCAL_KEYWORD'` 40 行、`degraded_reason='EMPTY_KEY'` 39 行；记录 27/28/29 `pair_state=DEGRADED` | 通过 |
| 5 | 详情页出现「已降级 / 当前为关键词匹配」提示（截图） | 截图可见横幅「已降级 当前为关键词匹配…」，卡片带「已降级」徽标；`data-testid=pair-degraded-banner` 存在 | 通过 |
| 5 | `POST /api/ops/pair-scan?scope=degraded` 返回真实数字 | 首次：`scanned=0, rescored=0, scope=DEGRADED`（3 条降级记录仍在 60 分钟冷却内）；把测试记录 `pair_scored_at` 回拨 2 小时后重跑：`scanned=3, rescored=0, degraded=3, skipped=32`；同时跑默认 full：`scanned=1, rescored=8, degraded=8, pairsWritten=8, degradedByReason={EMPTY_KEY:8}` | 通过 |
| 5 | 恢复真实 key 后重扫升级 `DEEPSEEK`，字段逐项保留 | 本机无 key，未执行（无 key 时引擎不可能升级，见 §10） | 未执行 |
| 5 | 开关：`degraded-scan-enabled=false` 不执行定时重扫 / `true` 可执行 | 仅用 `SPRING_APPLICATION_JSON` 覆盖 `degraded-scan-cron=*/15 * * * * *`：`false` 观察 60 秒 → `trigger=degraded-scheduled` 日志 0 条；`true` 观察 60 秒 → 4 轮（8 行 done+summary） | 通过（仅证明调度被开关门控；「引擎升级」部分无 key 无法证明） |
| 6 | LOCAL：P01 0.73 → 卡片可见 `AUTHORIZABLE` | `score=0.73 tier=AUTHORIZABLE engine=LOCAL_KEYWORD`，卡片可见 | 通过 |
| 6 | LOCAL：P13 低于 0.45 → 无卡片、`lowConfidenceCount=1` | `卡片可见=false`，`lowConfidenceCount=1` | 通过 |
| 6 | DEEPSEEK：P01 0.98 DIRECT / P06 0.92 AUTHORIZABLE / P13 <0.80 只计数 | 本机无 key，未执行 | 未执行 |
| 7 | `server> .\mvnw.cmd -o test` 退出码 0 + `Tests run: 297, Failures: 0, Errors: 0, Skipped: 1` + `BUILD SUCCESS` | 原文命中：`[INFO] Tests run: 297, Failures: 0, Errors: 0, Skipped: 1` / `[INFO] BUILD SUCCESS`；`MVN_EXIT=0` | 通过 |
| 7 | `web> npm run type-check`、`npm run build` 退出码 0 | `TYPECHECK_EXIT=0`、`BUILD_EXIT=0`（`✓ built in 848ms`） | 通过（未使用、也不需要 `net use` shim） |
| 8 | `main` 未动、tag 只有两个、无 v2.0.1 | `git ls-remote`：`refs/heads/main = 0ff400a5e519bad9384481e59427633d860879eb`；tag 仅 `v1.0.0-windows`(21784f6)、`v2.0.0-windows`(0ff400a5)；无 `v2.0.1*`；`feat/v2-integration = 30002de`、`test/v2-M6-candidate-package = bb3d15d5` | 通过（Releases 无法枚举，见 §8） |

## 1–3 补充说明

- **同源不返回 ACAO 是正确语义**：判据 3 用 `localhost:5173` 单独证明 CORS 头，没有拿「同源必须回 ACAO」当判据。
- **为什么 8088 同源不 403**：`nginx/conf/nginx.conf` 用 `proxy_set_header Host $http_host` 保留端口，后端据此判定源为 `http://127.0.0.1:8088`，与浏览器 `Origin` 相同。该注释在包内配置里就有，本次实测复核成立。
- **8088 上的 ops 扫描**：`OPS_SCAN_TOKEN` 不在安装问卷里，本次只在**启动进程的环境变量**里临时注入（不写包内任何文件、不改默认值），否则 `/api/ops/pair-scan` 按设计返回 404（错误口令同样 404 `10003 资源不存在`，已实测）。
- 部署脚本日志原文：`raw/02-install.txt`、`02-start.txt`、`02-status.txt`、`02-stop.txt`、`02-start-with-ops-token.txt`。

## 4 面 B（审核闸门）

`findit.review.mode` 取 `application.yml` 默认 `${FINDIT_REVIEW_MODE:off}`，本次未改任何配置。三条新记录（id 27/28/29）全部 `APPROVED` + `review_engine=OFF` + `raw_*`/`match_*` 为 NULL + `is_visible=1`，匿名列表可见，与 v2.0.0 路径一致。

AI 三分支（正常 `APPROVED` / 身份信息 `REJECTED` / 连续失败转 `PENDING + manual_required=1 + is_visible=0`）**未执行**：本机 User/Machine/Process 三处 `DEEPSEEK_API_KEY` 均未设置，包内 `config/deploy.env.ps1` 里该行也只是被注释的占位符。按验收纪律，不伪造 key、不改默认值、不搬用交付方日志充当本方证据。

## 5 面 C（降级与恢复）

- 无 key 时降级链路成立：`engine=LOCAL_KEYWORD`、`degraded_reason=EMPTY_KEY`、记录 `pair_state=DEGRADED`，详情页出现降级横幅与「已降级」徽标（`shots/05-record-27-degraded-hint.png`）。
- `POST /api/ops/pair-scan?scope=degraded` 可跑，返回真实计数。第一次 `scanned=0` 是**真实语义**（3 条记录的 `pair_scored_at` 都在 `degraded-retry-minutes=60` 冷却窗口内），不是接口坏；把这三条测试记录的 `pair_scored_at` 回拨 2 小时后重跑得到 `scanned=3`。回拨只动本方测试库的测试数据，不动包内文件与默认配置。
- **恢复真实 key 后重扫升级 DEEPSEEK**：未执行（无 key）。因此「`MATCHED`、两端确认时间、解锁时间、附言逐字段保留」这一条本轮**没有本方证据**。
- 开关：`degraded-scan-enabled=false` 时 `scheduledDegradedScan()` 直接 return，60 秒窗口内 0 条 `trigger=degraded-scheduled` 日志；`true` 时同一窗口内 4 轮。两轮都用 `SPRING_APPLICATION_JSON` 把 cron 临时压到 15 秒（仅为可观察），`application.yml` 默认值未改、包内文件未改。**「引擎不升级 / 可升级」这半句在无 key 环境下不可观测**，只证明调度被开关门控。

## 6 面 D（阈值与卡片可见）

用 `qa/datasets/pair-calibration/pairs.json` 原样样本（文件 SHA256 `85C87813397F623C4602E3728C746D762DC2F75F5929A73856CAC270BFDFCB70`，未改一个字节），在**干净库 `findit_m7_faced`** 上跑（3 账号 / 9 分类 / 0 记录），避免 seed 记录把 `lowConfidenceCount` 顶上去：

| 样本 | 分数 | 档位 | 引擎 | 卡片 | lowConfidenceCount |
|---|---|---|---|---|---|
| P01（丢失/捡到小米充电宝） | 0.73 | `AUTHORIZABLE` | `LOCAL_KEYWORD` | 可见 | 0 |
| P13（应不匹配） | — | — | — | 不可见 | 1 |

判定阈值来自 `application.yml` 现状（local 覆盖 `unlock 0.45 / direct 0.90`），本次未改。DEEPSEEK 那组（P01 0.98 `DIRECT`、P06 0.92 `AUTHORIZABLE`、P13 <0.80 只计数）未执行（无 key）。

## 7 回归

- `server> .\mvnw.cmd -o test`：`MVN_EXIT=0`；原文 `[INFO] Tests run: 297, Failures: 0, Errors: 0, Skipped: 1`、`[INFO] BUILD SUCCESS`（与基线一致）。跳过的那 1 条是「A/B：改写对匹配的影响（真实 DeepSeek，需 key）」。
- `web> npm run type-check` / `npm run build`：均退出码 0。本次**没有**使用 Vite `net use` shim，构建产物 `dist/assets/index-Dqnrz-qD.js` 与包内 `web/dist/index.html` 引用的文件名一致（旁证包内前端确由此提交构建）。
- 全量原文：`raw/08-mvn-test.txt`、`08-web-typecheck.txt`、`08-web-build.txt`。

## 8 远端

`git ls-remote origin`（凭证可用，能独立读远端；原文 `raw/09-ls-remote.txt`，共 61 个 ref）：

- `refs/heads/main = 0ff400a5e519bad9384481e59427633d860879eb`（与基线一致，未动）
- tag 仅 `v1.0.0-windows` → `21784f67…`、`v2.0.0-windows` → `0ff400a5…`；**无 `v2.0.1`**
- `feat/v2-integration = 30002de`、`test/v2-M6-candidate-package = bb3d15d5`

**GitHub Releases 未能独立枚举**：本机无 `gh`，也不允许绕过 CLI 直连。按 tag refs 判断无新增发布（发 Release 必先有 tag），但这一条属于推断，不是直接观测。

## 9 边界自检

- 未改 `server/` `web/` `db/` `deploy/` 任何文件；未改包内任何文件；未改 `application.yml` 默认值；未伪造 key。
- 未推 `main`、未合并 `main`、未发 Release、未打 tag、未删分支。
- 本分支只在 `30002de` 之上新增本目录。
- 收尾：`stop.ps1` 退出码 0，8088/18080 均已释放、无 java/nginx 残留；MySQL 实例已按验收惯例关停。

## 10 未测清单（说清楚「哪些没测到」）

1. 面 B 的 AI 审核三分支（APPROVED / REJECTED / PENDING+manual）。
2. 面 C 的「恢复真实 key → 引擎升级 DEEPSEEK」及升级前后字段保留对照。
3. 面 C 开关项中「引擎可/不可升级」的实质部分（只证明了调度门控）。
4. 面 D 的 DEEPSEEK 阈值组（P01 0.98 DIRECT、P06 0.92 AUTHORIZABLE、P13 <0.80）。
5. GitHub Releases 的独立枚举。

以上 5 项全部因为**本机没有可用的 `DEEPSEEK_API_KEY`**（或没有 `gh`）。它们不是「通过」，也不是「产品缺陷」——是**本轮无证据**。是否接受在这个前提下进入发布门禁，由人类拍板。

## 目录

```
README.md                       本文
raw/01-package-integrity.txt    判据 1 复算原文
raw/02-*.txt                    install / start / status / stop 脚本日志原文
raw/03-health.json              /api/health 原文
raw/04-cors-*                   CORS 四例的响应头与响应体、nginx -t
raw/05-public-list.json         匿名列表（含新建 27/28/29）
raw/05-06-db-evidence.txt       审核态与 pair 引擎/降级的库内原文
raw/06-ops-*.json               ops pair-scan 两种 scope 的返回
raw/06-switch-*.txt             开关两轮的 java 日志全文
raw/07-faced-local.json         面 D 采集结果与断言
raw/08-*.txt                    回归三件套原文
raw/09-ls-remote.txt            git ls-remote 全量
shots/                          01 首页 / 02 详情 / 03 匿名发布被拦 / 04 登录后表单 / 05 降级提示
tools/                          本次实际使用的三个脚本（截图 ×2、面 D 采集 ×1）
```
