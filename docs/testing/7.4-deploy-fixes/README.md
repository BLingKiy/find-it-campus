# 7.4 部署包缺陷修复验收（ZHYYKFX-41）

- 交付包：`deploy/dist/find-it-campus-windows-x64-da8b936.zip`，28,161,794 字节（26.86 MiB），SHA256 `ABEE31E4C0BEC798CA4A66A90049E68225B90F15FBC105CC76134ADCCFDC15FD`
- 源分支：`feat/7.4-deploy-fixes` @ `da8b936731c6ffbe27e981c6b883e457b38a6f29`
- 基线：`origin/feat/7.2-windows-docs` = `270a54bf958ae107bf7eb5ba522c714743b0d639`
- 变更范围：`deploy/DEPLOY-WINDOWS.md`、`deploy/scripts/{_common,install,start}.ps1`、本目录；`server/` `web/` `db/` 零改动（`10-git-diff.txt`）
- 验收环境：Windows 10 22H2 x64（10.0.19045），非管理员；**有效执行策略 `Restricted`**（五个作用域全 Undefined，全程未修改）；JDK 17.0.20.1 / MySQL 8.0.29（均不在 PATH，用 `-JavaBin` / `-MysqlBin` 指定）；解压目录在工作区之外的 `C:\Project\deploy-accept\7.4-final3\`
- 结论：**两条缺陷均已修复，判据全部通过**（P1 四条命令在 Restricted 下逐字可执行；P2 管道场景 12.0 s 返回退出码 0）

## 逐条对照

### P1｜文档缺 ExecutionPolicy 门槛

| 项 | 内容 |
|---|---|
| 缺陷 | `deploy/DEPLOY-WINDOWS.md` 全文只写 `.\scripts\install.ps1` 这类直接调用；出厂 Windows 有效执行策略为 `Restricted`，照抄第一条命令即报「在此系统上禁止运行脚本」 |
| 复现 | 在 `Restricted` 的会话里执行 `.\scripts\install.ps1`、`.\scripts\status.ps1` → `System.Management.Automation.PSSecurityException`，原文见 `01-policy-blocked.txt` |
| 改法 | ① 全文脚本命令改为 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\*.ps1 …`，install / start / stop / status 四条全覆盖（§3 快速开始、§5 调用形式、§6.1–6.3 启停、§10.1–10.3 升级回滚卸载）；② 新增 §2.1「首次部署：执行策略」：说明出厂 `Restricted`、给出进程级绕过、说明不改全局策略且不需要管理员、附组策略例外的边界；③ §2 前置条件表加「执行策略」行；④ §9 新增第 17 条常见故障；⑤ `install.ps1` 收尾提示与文件头示例同步成可直接照抄的形式；⑥ §11 补「启动方式」行说明 cmd 包装进程 |
| 实测 | 最终 zip 解压到干净目录，在 `Restricted` 下**逐字**执行 §3 四条命令（只替换占位路径、按 §3 说明用 `-EmptyDbPassword`）：`install.ps1` 退出码 **0**（16.0 s）、`start.ps1` **0**（12.8 s）、`status.ps1` **0**（`RESULT: HEALTHY`，2.9 s）、`stop.ps1` **0**（`RESULT: STOPPED`，4.4 s，8088/18080 均释放） |
| 证据 | `01-policy-blocked.txt`、`04-four-commands.txt`、`03-install-log.txt` |
| 结论 | 通过。文档中每条脚本命令在 `Restricted` 下可逐字执行，退出码与预期一致 |

### P2｜`start.ps1` 在输出被管道捕获时不返回

| 项 | 内容 |
|---|---|
| 缺陷 | `powershell -File scripts\start.ps1 2>&1 \| Tee-Object …` 时调用方永久挂住；7.3 实测 6 分 30 秒，直到 `stop.ps1` 杀掉 JVM 才拿到退出码 |
| 复现 | 基线包 270a54b：脚本 17:38:32 打印「启动完成」且服务已可用，调用方 17:39:11 才返回，**51.9 s**，期间只有手动杀 JVM 才解开（`09-before-hang.txt`） |
| 根因 | `Start-Process` 带 `-RedirectStandardOutput/-RedirectStandardError` 时走 `UseShellExecute=$false`，.NET Framework 以 `bInheritHandles=$true` 创建进程：后端/Nginx 除拿到重定向的日志文件句柄外，还连带继承调用方的 stdout 管道句柄。管道要等所有写入端关闭才到 EOF，后端一直活着 → 调用方永久阻塞 |
| 改法 | `_common.ps1` 新增 `Start-DetachedProcess`：由 `cmd.exe` 在自身命令行内完成 `>` / `2>` 重定向，用 ShellExecute 创建（不继承调用方句柄）；`Wait-ChildProcessId` 按父进程发现真正的 `java.exe` / `nginx.exe` PID 写进 `runtime\backend.pid`；`start.ps1` 的后端与 Nginx 都改走这条路径，失败路径用 `Stop-LauncherProcess` 收尾，不留孤立 cmd |
| 语义不变 | 端口 8088 / 18080、`runtime\*.pid` 记录的进程（java / nginx 自身 PID，不是包装进程）、日志路径（`logs\backend.log`、`backend-error.log`、`nginx-stdout.log`、`nginx-stderr.log`）全部保持；启停顺序、幂等、端口占用防护、健康判据未改 |
| 实测 | 最终包三种调用方式全部退出码 **0**，8088/18080 起来，health `code=0 / status=UP / db=UP`：① 直接执行 12.8 s（幂等重跑 2.9 s）；② `-TimeoutSec 120` 12.1 s；③ 管道捕获 `2>&1 \| Tee-Object` **12.0 s**（修复前 51.9 s） |
| 证据 | `05-start-modes.txt`、`09-before-hang.txt`、`timings.txt`、`08-ports-and-processes.txt` |
| 结论 | 通过。管道场景 12.0 s ≪ 30 s 判据；三方式回归全绿 |

## 验收判据自检

| 判据 | 结果 | 依据 |
|---|---|---|
| 文档中每条脚本命令在 Restricted 下逐字可执行，有命令转录与退出码 | 通过 | `04-four-commands.txt`（四条命令 + 四条日志 + 退出码）、`01-policy-blocked.txt`（旧写法确实被拦） |
| 管道场景 `start.ps1` 30 秒内返回退出码 0（原 6m30s / 51.9 s） | 通过：12.0 s | `05-start-modes.txt`、`timings.txt` |
| 三种 start 方式回归全绿 | 通过 | 同上 + `06-health-8088.json`、`07-pages.txt` |
| `git diff --name-only 270a54b HEAD` 只含 `deploy/` 与本目录 | 通过 | `10-git-diff.txt` |
| 新 zip 的 `SHA256SUMS.txt` 全 OK、包内文档与分支一致、`BUILD-INFO.txt` commit 为本分支 HEAD | 通过：44/44 OK、md 与 5 个脚本仅行尾差异、`Git commit: da8b936`、`Git dirty: false` | `02-package.txt` |

## 未纳入本次范围（沿用 7.3 的 P3）

- 包内 `SHA256SUMS.txt` 为 CRLF（GNU `sha256sum -c` 会全报 FAILED；本次校验先去掉 CR）
- 前端页脚硬编码「演示环境（dev server）」
- `db/seed.sql:126` 的 `unknown` 占位记录

三条都需要动 `web/` 或 `db/`，按任务边界不在本任务处理，等人类决定。

## 证据文件

| 文件 | 内容 |
|---|---|
| `00-environment.txt` | OS / 权限 / PowerShell / 执行策略五域 / JDK / MySQL / Nginx / 解压目录 |
| `01-policy-blocked.txt` | 旧写法 `.\scripts\*.ps1` 在 `Restricted` 下的异常原文 |
| `02-package.txt` | zip 大小与 SHA256、BUILD-INFO、包内文件清单、44/44 校验、包内文档与 5 个脚本比对 |
| `03-install-log.txt` | `install.ps1` 完整输出（`logs\install-20261026-174836.log`） |
| `04-four-commands.txt` | §3 四条命令的逐字转录 + 各自完整日志 + 退出码；附停止后 status（1）与幂等 stop（0） |
| `05-start-modes.txt` | 三种调用方式回归、修复前后对比、方式②③ 原始输出 |
| `06-health-8088.json` | `GET /api/health` 响应原文 |
| `07-pages.txt` | `/`、`/records/1`、`/records/new` 状态码 |
| `08-ports-and-processes.txt` | 监听端口、cmd 包装进程 → java/nginx 进程树、pid 文件内容、logs 清单 |
| `09-before-hang.txt` | 修复前管道挂住的时序与脚本输出原文 |
| `10-git-diff.txt` | 变更范围与业务代码零改动 |
| `timings.txt` | 逐次耗时与退出码 |
