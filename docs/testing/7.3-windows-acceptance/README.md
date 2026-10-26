# 7.3 Windows 部署包干净目录实测验收（ZHYYKFX-40）

- 验收对象：`find-it-campus-windows-x64-270a54b.zip`（SHA256 `2060E4CC…60986`，28,158,005 B）
- 源分支：`feat/7.2-windows-docs` @ `270a54bf958ae107bf7eb5ba522c714743b0d639`
- 基线：`origin/main` = `544e24ac99a5ae04e3cae2e87102133f1d4ebc06`
- 验收环境：Windows 10 22H2 x64（10.0.19045），非管理员；PATH 只含 JDK 17.0.20.1 + MySQL 8.0.29（已剔除 node/npm/mvn）
- 解压目录：`C:\Project\deploy-accept\7.3-20261026T090657Z\find-it-campus-windows-x64`（开发工作区之外）
- 时间窗：2026-10-26 17:07–17:21（UTC+8）
- 结论：**可交付**（10 条判据 10 通过；另记 5 条观察项，均不阻塞交付）

## 逐条判据

| # | 判据 | 实际命令 | 观察输出 | 结果 |
|---|------|---------|---------|------|
| 1 | zip SHA256 与 7.2 一致；包内结构齐全 | `sha256sum <zip>`；解压后 `find` | `2060e4cc…60986` 与 `.sha256` 逐字一致；58 个文件含 jar / web/dist / nginx / db/schema+seed / 4 脚本 + `_common.ps1` / `config\deploy.env.example` / `DEPLOY-WINDOWS.md`；包内 `SHA256SUMS.txt` 44/44 OK | 通过 |
| 2 | 非交互 `install.ps1` 成功 | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install.ps1 -DbHost 127.0.0.1 -DbPort 3306 -DbName findit_accept73 -DbUser root -EmptyDbPassword -Engine local` | 退出码 **0**；JDK 17 可用 → MySQL 8.0.29 连通 → 建库 utf8mb4 → `t_category=9` / `t_record=24` → 生成 `config\deploy.env.ps1`；`logs\install-20261026-170956.log` | 通过 |
| 3 | `start.ps1` 拉起后端 + Nginx，只监听预期端口 | `... -File scripts\start.ps1 -TimeoutSec 120`；`netstat -ano` | 退出码 **0**；后端 PID 5028 / Nginx master 13036 → 第二轮 3096 / 13280；监听仅 `0.0.0.0:8088`（Nginx）与 `127.0.0.1:18080`（java） | 通过 |
| 4 | `GET /api/health` 200 且 code=0 / status=UP / db=UP | `curl http://127.0.0.1:8088/api/health` | `HTTP 200` `{"code":0,…,"data":{"status":"UP","db":"UP","version":"0.0.1-SNAPSHOT","engine":"local"}}` | 通过 |
| 5 | 三页面 200 且有实际内容，详情页刷新不 404 | `curl` 三路由 + 刷新 3 次；headless Edge 截图 | `/`、`/records/1`、`/records/new` 均 `HTTP 200`（967 B SPA 壳 + 资源 200），刷新 ×3 全 200；三张截图渲染出列表 24 条、详情 #1、发布表单 | 通过 |
| 6 | `POST /api/match` 200 且返回候选与分数 | `curl -X POST .../api/match --data-binary @match-req.json` | `HTTP 200`，`engine=LOCAL_KEYWORD`，`total=1`，`items[0].recordId=13`，`score=0.95`（95%），`tookMs=23`，reason「关键词命中：钱包、银行卡、黑色、书馆；分类词表命中：钱包卡证」 | 通过 |
| 7 | `status.ps1` 全绿且退出码 0 | `... -File scripts\status.ps1` | 后端 UP / Nginx UP / MySQL OK / 8088 健康 UP / 8088 监听 OK（PID 4972，master 13036）/ 18080 监听 OK（PID 5028）；`RESULT: HEALTHY`，退出码 **0** | 通过 |
| 8 | `stop.ps1` 释放端口；`status.ps1` 正确报停止 | 两个脚本 | `stop.ps1` 退出码 **0**：nginx -s quit 成功、后端 PID 终止、8088/18080 均释放，`RESULT: STOPPED`；停止后 `status.ps1` 后端/Nginx `DOWN`、8088 健康 `FAIL`、两端口 `DOWN 无监听`、`RESULT: UNHEALTHY`，退出码 **1**（内容准确） | 通过 |
| 9 | 无服务注册、无需管理员、日志在包内 `logs\` | `Get-CimInstance Win32_Service` 过滤；`IsInRole(Administrator)` | 无任何服务指向部署包，无 nginx/findit/campus 服务条目；IsAdmin=False；4 个脚本日志 + 后端/Nginx 日志全部落在包内 `logs\` | 通过 |
| 10 | 业务代码零改动 | `git diff --stat 544e24a 270a54b -- server web db`；`--name-only ... -- server web db qa` | 两条命令均**无输出**；7.2 全量变更只在 `deploy/`、`docs/`、`.gitignore`；包内 5 个脚本 + nginx.conf 与 `270a54b` blob 逐内容一致（仅 CRLF） | 通过 |

## 附：包内完整性

- `SHA256SUMS.txt` 44 条：44 OK / 0 FAILED（CRLF 规范为 LF 后复算；原始 CRLF 下 GNU `sha256sum -c` 会因 `\r` 全部报 FAILED）
- `BUILD-INFO.txt`：branch `feat/7.2-windows-docs`、commit `270a54b…`、dirty=false；jar SHA256 `A79B727D…F2EFA`、index.html `9ACEB18D…A9006` 与实测一致

## 观察项（不阻塞交付，按优先级排序）

1. **P1｜文档缺口：ExecutionPolicy 默认 Restricted，四条命令照抄会失败**。本机五个策略域全为 Undefined → 有效值 Restricted，`.\scripts\install.ps1` 直接报「在此系统上禁止运行脚本」。需加 `powershell -ExecutionPolicy Bypass -File` 或在文档给一行进程级绕过说明。这是每台出厂 Windows 客户端都会遇到的首次部署门槛。
2. **P2｜`start.ps1` 在输出被管道捕获时不返回**。`& powershell.exe ... start.ps1 2>&1 | ...`（或 CI 里 `| Tee-Object`）会挂住：实测脚本 17:10:57 已打印「启动完成」，调用方直到 17:17:15 `stop.ps1` 杀掉 JVM 才拿到退出码（6 分 30 秒）。人工交互执行不受影响，但自动化验收/CI 会被拖死。
3. **P3｜包内 `SHA256SUMS.txt` 为 CRLF**，MSYS/Git Bash 的 `sha256sum -c` 对 44 条全部报 FAILED（Windows 原生 `Get-FileHash`/`certutil` 不受影响）。Windows-only 包，影响有限。
4. **P3｜前端页脚硬编码「演示环境（dev server）」**，出现在正式部署包内（`index-*.js` 可见），部署到 8088 后仍显示「dev server」，对验收/答辩场景是误导性文案。
5. **P3｜种子数据含占位符**：`db\seed.sql:126` 记录 #26 标题为「捡到一张 unknown 的卡片」，首页列表直接展示。疑似模板残留。

补充观察（非缺陷）：未知路由 `/no-such-route` 返回 200 + index.html（SPA `try_files` 兜底），属设计行为，但意味着没有真正的 404 页；API 侧错误语义正常（缺 text → 400/30001、type 非法 → 400/10001、记录不存在 → 404/20001）。

## 证据文件

| 文件 | 内容 |
|------|------|
| `00-environment.txt` | OS/权限/PATH/版本/ExecutionPolicy/MySQL |
| `00-package.txt` | zip 校验、包内 58 文件清单、SHA256SUMS 校验 |
| `01-install-log.txt` `01-install-exitcode.txt` | install.ps1 完整输出与退出码 |
| `02-start-log.txt` `02-start-exitcode.txt` | start.ps1 完整输出与退出码 |
| `03-status-up.txt` `03-status-up-exitcode.txt` | status.ps1 运行中全绿 |
| `04-stop.txt` `04-stop-log.txt` `04-stop-exitcode.txt` | stop.ps1 停止过程 |
| `05-status-down.txt` `05-status-down-exitcode.txt` | 停止后 status.ps1 |
| `06-ports-processes.txt` | 端口监听绑定与进程树 |
| `07-service-and-logs.txt` | 服务注册检查、权限、日志落点 |
| `08-git-business-diff.txt` | 业务代码零改动 + 脚本 blob 一致性 |
| `health.json` `health-18080.json` | 8088 / 18080 健康响应 |
| `match-req.json` `match-resp.json` `neg1..3.json` | 匹配请求/响应与异常路径 |
| `shot-home.png` `shot-detail.png` `shot-create.png` | 三页面截图（headless Edge 1440×960） |
