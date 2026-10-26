# find-it-campus · Windows 部署说明

> 适用版本：`0.0.1-SNAPSHOT`（部署包布局版本 7.1，文档版本 7.2）
> 本文所有命令、参数名、端口、路径与包内 `scripts/*.ps1`、`nginx/conf/nginx.conf` 逐字对应；
> 示例输出取自 2026-10-26 的真实运行（见 §12 自检记录）。脚本改动后本文必须同步，否则按缺陷处理。

## 1 这是什么

**面向 Windows 10/11 x64 的单机部署包**：Nginx 监听 **8088** 托管前端静态文件，并把 `/api/` 反向代理到只绑定本机 **127.0.0.1:18080** 的 Spring Boot 后端，后端连本机 MySQL 8。

解压后执行 4 条命令即可得到可访问的站点，**不需要管理员权限，不注册 Windows 服务，不打包 JRE / MySQL / Node**。

读到这里你应该能回答三个问题：

| 问题 | 答案 |
|---|---|
| 它做什么 | 把仓库里的前后端产物装成一台机器上的单端口网站：`http://<host>:8088` |
| 为什么需要它 | 课程交付要求「可部署」，而不是只能在一台装了 Node 和 Maven 的开发机上 `npm run dev` |
| 怎么开始 | 装 JDK 17 与 MySQL 8 → `scripts\install.ps1` → `scripts\start.ps1`（§3） |

**本包不包含**：Docker、Linux 部署、HTTPS/域名、MSI 安装器、开机自启、服务注册。这些都在本期范围之外（§11）。

## 2 前置条件

| 组件 | 要求 | 校验命令 | 不满足时 |
|---|---|---|---|
| Windows | Windows 10 / 11 x64，自带 Windows PowerShell 5.1 | `$PSVersionTable.PSVersion`（主版本应为 5） | 脚本按 5.1 编写并自检；PowerShell 7（`pwsh`）未验证，请用系统自带的 `powershell.exe` |
| JDK | **17**（主版本必须正好是 17） | `java -version` | `install.ps1` 报「需要 JDK 17，当前 java 主版本为 N」 |
| MySQL | **8.x 服务端** + `mysql.exe` 客户端；`FULLTEXT ... WITH PARSER ngram` 需 8.0.14+ | `mysql --version`，并确认服务已启动 | `install.ps1` 报「需要 MySQL 8.x」或连接失败 |
| 端口 | **8088**、**18080** 空闲 | `Get-NetTCPConnection -State Listen -LocalPort 8088,18080` | `start.ps1` 报端口被占用并给出 PID，不抢杀 |

`java -version` 的首行就是脚本判定所用的那一行（本包自检环境的实测首行）：

```
openjdk version "17.0.20.1" 2026-08-18
```

脚本只从这一行取主版本号（`17`），因此发行版（Temurin / Oracle / Zulu）不影响判定；主版本不是 17 一律中止。

**JRE / MySQL 不随包附带**，必须由目标机预装。JDK 与 MySQL 都可以不在 `PATH` 上——安装时用 `-JavaBin`、`-MysqlBin` 指向具体路径即可，安装结果会写进配置，后续 `start.ps1` / `status.ps1` 复用。

目标机**不需要** Node.js、npm、Maven；这三样只在构建机打包时使用。

## 3 快速开始

把 zip 解压到任意目录（示例：`D:\apps\find-it-campus-windows-x64`），在该目录打开 PowerShell，按顺序执行：

```powershell
# 1) 安装：校验 JDK/MySQL → 建库（utf8mb4）→ 导入 schema/seed → 生成 config\deploy.env.ps1
.\scripts\install.ps1 `
  -DbHost 127.0.0.1 -DbPort 3306 -DbName find_it_campus `
  -DbUser root -DbPassword '你的数据库密码' -Engine local `
  -MysqlBin 'C:\mysql\bin\mysql.exe' `
  -JavaBin 'C:\jdk17\bin\java.exe'

# 2) 启动：后端 18080 → 等健康 → Nginx 8088
.\scripts\start.ps1

# 3) 查看状态（全绿时退出码 0）
.\scripts\status.ps1

# 4) 停止（幂等，可重复执行）
.\scripts\stop.ps1
```

浏览器访问 <http://127.0.0.1:8088/>。健康检查 <http://127.0.0.1:8088/api/health> 期望 `code=0`、`data.status=UP`、`data.db=UP`。

- 数据库是**空密码**时，把 `-DbPassword '...'` 换成 `-EmptyDbPassword`（两者不能同时出现）。
- 首次安装会自动建库并导入种子数据；**库已存在时默认拒绝执行**，确认可以清空才加 `-Force`（§5.3）。
- 如果本机 `PATH` 里已有 `java` 和 `mysql`，`-JavaBin` 与 `-MysqlBin` 可以省略。

## 4 包结构

```
find-it-campus-windows-x64/
  app/                find-it-campus-0.0.1-SNAPSHOT.jar   后端可执行 jar
  web/dist/           前端构建产物（index.html + assets/），Nginx 的 root
  nginx/              Nginx for Windows 1.30.6
    nginx.exe
    conf/nginx.conf   本包的单端口配置（8088 + /api 反代）
    conf/…            官方 conf 附带文件（mime.types 等）
    logs/ temp/       Nginx 自身目录（运行日志实际写在包根 logs/）
  db/                 schema.sql（三张表）/ seed.sql（9 分类 + 24 条记录）
  config/
    deploy.env.example  模板，可进仓库，无密码占位符
    deploy.env.ps1      安装后生成，含真实密码，只留本机
  scripts/
    install.ps1  start.ps1  stop.ps1  status.ps1  _common.ps1（公共函数，被点源引用）
  logs/               运行日志与脚本日志（详见 §8）
  runtime/            backend.pid / nginx.pid
  BUILD-INFO.txt      构建时间、分支、commit、JDK/Maven/Node/Nginx 版本与关键文件 SHA256
  SHA256SUMS.txt      包内每个文件的 SHA256
  DEPLOY-WINDOWS.md   本文
```

包根目录就是「安装目录」：脚本用自身所在位置推算包根（`scripts\` 的上一级），因此**整个目录可以整体移动**，但移动后需要重新执行 `start.ps1`（`runtime\*.pid` 里记的是进程，不是路径）。

## 5 安装：`scripts\install.ps1`

### 5.1 参数

| 参数 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `-DbHost` | string | `127.0.0.1` | MySQL 主机；只允许 `A-Z a-z 0-9 _ . : -` |
| `-DbPort` | int | `3306` | 允许 1–65535 |
| `-DbName` | string | `find_it_campus` | 只允许字母/数字/下划线，≤ 64 字符 |
| `-DbUser` | string | `root` | 允许字母/数字/`_ . @ -`，≤ 64 字符 |
| `-DbPassword` | string | 交互输入 | 不能含换行符；可以传空串 |
| `-EmptyDbPassword` | switch | 关 | 显式声明空密码，供非交互场景使用；与 `-DbPassword` 互斥 |
| `-Engine` | `local` \| `external` | `local` | 匹配引擎；`external` 还需手工补 embedding 配置（§11） |
| `-MysqlBin` | string | 查 `PATH` | `mysql.exe` 的完整路径，或包含它的 `bin` 目录 |
| `-JavaBin` | string | 查 `JAVA_HOME`/`PATH` | `java.exe` 的完整路径，或 JDK 的 `bin` 目录 |
| `-Force` | switch | 关 | 目标库已存在时 **先 DROP 再重建**，库内数据全部丢失 |

### 5.2 非交互与交互

**非交互的前提**是同时传齐 `-DbHost`、`-DbPort`、`-DbName`、`-DbUser`、`-Engine`，并且密码用 `-DbPassword` 或 `-EmptyDbPassword` 给出。齐全时一个提示都不会出现，适合自动化验收。

**缺哪个就问哪个**，已有默认值的提示会带上方括号默认值：

```
MySQL 主机 [127.0.0.1]:
MySQL 端口 [3306]:
数据库名 [find_it_campus]:
数据库账号 [root]:
匹配引擎（local/external） [local]:
数据库密码（直接回车表示空密码，输入不回显）:
```

`-MysqlBin`、`-JavaBin` 不参与上述判定：不传就按 `PATH`（Java 还会先试 `JAVA_HOME`）查找；找不到时才在交互模式下追问路径，非交互模式直接报错。

交互只负责「取值」，**不做重问**：端口填成非数字、`-Engine` 填第三个值、库名带非法字符，都会在取值后一次性报错退出（退出码 1），改完参数重跑即可。

### 5.3 脚本做了什么

1. **校验 JDK**：执行 `java -version`，从输出里取主版本，**不等于 17 就中止**（不会尝试降级兼容）。
2. **校验 MySQL**：用临时 option 文件连接（`protocol=TCP`），要求服务端版本匹配 `8.`。
3. **建库**：`CREATE DATABASE ... CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci`，并回查确认字符集。
   - 库已存在且**没有** `-Force`：直接失败，原文「数据库 X 已存在。默认不覆盖现有数据；如确认要清空重建，请追加 -Force。」
   - 库已存在且**有** `-Force`：先 `DROP DATABASE`。这一步不可撤销。
4. **导入**：执行 `db\schema.sql` 与 `db\seed.sql`。脚本在 `runtime\` 下生成临时副本，把库名占位替换成 `-DbName` 的值后再导入，**包内原始 SQL 不被修改**。
5. **导入校验**：要求 `t_category ≥ 9` 行、`t_record ≥ 24` 行，否则中止（说明 SQL 没导全）。
6. **生成配置**：读 `config\deploy.env.example`，替换占位符后写 `config\deploy.env.ps1`，并尝试用 `icacls` 去掉继承权限、只保留当前用户；若加固后自己反而读不到，脚本会恢复继承并打印警告（宁可权限宽一点，也不能把部署者锁在配置外）。

退出码：`0` 成功；`1` 任意失败（含参数校验、JDK/MySQL 不满足、库已存在、SQL 导入失败）。日志写在 `logs\install-<yyyyMMdd-HHmmss>.log`。

### 5.4 密码与配置边界

- 真实密码只出现在 `config\deploy.env.ps1`（本机文件）和安装期的临时 option 文件里；临时文件用完即删。
- `deploy.env.ps1` 已被仓库 `.gitignore` 忽略（`deploy/config/deploy.env.ps1`、`deploy/**/config/deploy.env.ps1`），**不要复制、不要提交、不要贴进工单**。
- 需要改数据库账号/密码时，重跑 `install.ps1`（会重新生成配置）。只想改连接串而不动数据，可以手工编辑 `deploy.env.ps1`——它是普通 PowerShell 脚本，`start.ps1` / `status.ps1` 直接点源加载。

## 6 启停与状态

三个脚本都**不需要管理员权限**，都不注册服务，都只操作「本包路径/本包 jar」的进程：PID 文件与进程命令行不匹配时拒绝操作，而不是按端口乱杀。

### 6.1 `scripts\start.ps1`

```powershell
.\scripts\start.ps1                 # 默认等待后端健康 60 秒
.\scripts\start.ps1 -TimeoutSec 120 # 冷启动慢（机械盘/杀软扫描）时放宽
.\scripts\start.ps1 -JavaBin 'D:\jdk17\bin\java.exe'  # 覆盖配置里的 JDK
```

| 参数 | 默认值 | 说明 |
|---|---|---|
| `-JavaBin` | 空 | 优先级：本参数 → `config\deploy.env.ps1` 里的 `$DeployJavaBin` → `JAVA_HOME` → `PATH` |
| `-TimeoutSec` | `60` | **仅**后端健康检查的超时；Nginx 就绪等待固定 30 秒 |

顺序与判据：

1. 读 `config\deploy.env.ps1`，缺文件或缺变量直接报错让先跑 `install.ps1`。
2. 校验 `app\find-it-campus-0.0.1-SNAPSHOT.jar`、`web\dist\index.html`、`nginx\nginx.exe`、`nginx\conf\nginx.conf` 都在。
3. 后端以 `--server.address=127.0.0.1 --server.port=18080` 启动，输出重定向到 `logs\backend.log` 与 `logs\backend-error.log`；启动前把上一轮这两个文件重命名为 `*.log.<时间戳>.old`。
4. 轮询 `http://127.0.0.1:18080/api/health`，要求 `code=0` + `status=UP` + `db=UP` 三者同时成立。
5. 启动 Nginx（`-p <包根>\nginx\ -c conf/nginx.conf`），等 `runtime\nginx.pid` 落盘，再要求 `GET http://127.0.0.1:8088/` 返回 200 且 8088 的 `/api/health` 同样三项成立。
6. 退出码 `0`。任一步失败退出码 `1`，且**回滚本次启动的进程**（只停本次拉起的那个 PID）。

**幂等**：已在运行的后端/Nginx 会被识别并复用，输出「已在运行（PID xxxx），不重复拉起」。第二次执行 `start.ps1` 是安全的。

**端口占用**：18080 或 8088 被**非本包**进程占用时直接失败，输出里带 PID、进程名与可执行文件路径，例如：

```
[ERROR] 启动失败：端口 8088 已被其他进程占用：PID 12420 / powershell / C:\Windows\System32\WindowsPowerShell\v1.0\powershell.EXE。本脚本不会抢占或结束该进程。
```

脚本不会替你结束那个进程——先确认它是什么，再决定停它还是换端口。

### 6.2 `scripts\stop.ps1`

```powershell
.\scripts\stop.ps1
```

无参数。顺序：先 `nginx.exe -s quit` 让 Nginx 优雅退出（15 秒未退再按已校验 PID 强停），再按 `runtime\backend.pid` 停止后端（先普通终止，20 秒未退再强制），最后分别等 8088 与 18080 释放，各 20 秒。

退出码：`0` 已停止或本来就没运行；`1` 端口在超时后仍被占用（输出里给出占用 PID）。

**幂等**：重复执行输出「Nginx 未运行（幂等）。」「后端未运行（幂等）。」，退出码仍是 0。

**后端不是优雅停机**：Windows 下没有服务控制，`stop.ps1` 对 JVM 是进程终止，在途请求不会 drain。演示场景可接受；有真实流量时要在前面加负载均衡摘流。

### 6.3 `scripts\status.ps1`

```powershell
.\scripts\status.ps1
```

无参数。输出一张组件表，健康判据统一走 8088：

| 组件行 | 判据 |
|---|---|
| 后端 | PID 命中本包 jar，且 18080 的 `/api/health` 三项成立 → `UP`；进程在但健康失败 → `DEGRADED` |
| Nginx | PID 命中本包 `nginx.exe` → `UP` |
| MySQL | 端口可连 + 客户端能查 `SELECT VERSION()` 且是 8.x → `OK`；仅端口通但无客户端 → `WARN` |
| 8088 健康 | `code=0` + `status=UP` + `db=UP` → `UP` |
| 8088 监听 / 18080 监听 | 监听者是否为本包进程 → `OK`；被别人占用 → `CONFLICT` |

表后还有三行汇总（日志路径、进程号、两处健康），全绿时最后一行是 `RESULT: HEALTHY`；否则打印最近错误日志尾部与「问题：…」。

退出码：`0` 全绿；`1` 不健康；`2` 未安装（缺 `config\deploy.env.ps1`）。

## 7 验证部署

四条检查，全部通过才算部署成功。

### 7.1 健康检查

```powershell
Invoke-RestMethod http://127.0.0.1:8088/api/health | ConvertTo-Json -Depth 5
```

期望响应（2026-10-26 实测，格式化后展示；`requestId`、`matchId` 每次不同）：

```json
{
  "code": 0,
  "message": "ok",
  "data": { "status": "UP", "db": "UP", "version": "0.0.1-SNAPSHOT", "engine": "local" },
  "requestId": "7e552517c0fc4f26"
}
```

`data.db=DOWN` 时接口返回 HTTP 503 且 `code=10005`，说明数据库连不上（§9 第 9 条）。

### 7.2 三个页面

浏览器直接打开，或在 PowerShell 里批量检查状态码：

```powershell
foreach ($p in '/', '/records/1', '/records/new') {
  $r = Invoke-WebRequest -Uri ('http://127.0.0.1:8088' + $p) -UseBasicParsing
  '{0} -> HTTP {1}' -f $p, $r.StatusCode
}
```

期望三行都是 `HTTP 200`。`/records/1`、`/records/new` 是 Vue Router 的 history 路由，Nginx 用 `try_files $uri $uri/ /index.html` 回退，所以刷新不会 404；如果这里 404，先看 §9 第 7 条。

### 7.3 匹配接口

curl（Windows 10 1803+ 自带 `curl.exe`，PowerShell 里写 `curl.exe` 以免命中 `Invoke-WebRequest` 别名）：

```powershell
curl.exe -s -X POST http://127.0.0.1:8088/api/match `
  -H "Content-Type: application/json" `
  --data-binary '{\"text\":\"我在图书馆三楼弄丢了一个黑色皮钱包，里面有银行卡\",\"type\":\"FOUND\",\"topN\":5,\"minScore\":0.3}'
```

（上面是 PowerShell 写法：`\"` 是为了让 PowerShell 5.1 把双引号原样交给 `curl.exe`。若在 `cmd.exe` 里执行，把 `\"` 换成 `"` 即可。）

纯 PowerShell（中文不会因控制台编码变成乱码）：

```powershell
$body = '{"text":"我在图书馆三楼弄丢了一个黑色皮钱包，里面有银行卡","type":"FOUND","topN":5,"minScore":0.3}'
Invoke-RestMethod -Uri http://127.0.0.1:8088/api/match -Method Post `
  -ContentType 'application/json; charset=utf-8' `
  -Body ([Text.Encoding]::UTF8.GetBytes($body)) | ConvertTo-Json -Depth 6
```

期望响应（2026-10-26 实测，格式化后展示；`requestId`、`matchId` 每次不同，只保留一条候选）：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "matchId": 2,
    "engine": "LOCAL_KEYWORD",
    "inputType": "FOUND",
    "topN": 5,
    "minScore": 0.3,
    "tookMs": 4,
    "total": 1,
    "items": [
      {
        "recordId": 13,
        "score": 0.95,
        "scorePercent": 95,
        "reason": "关键词命中：钱包、银行卡、黑色、书馆；分类词表命中：钱包卡证",
        "type": "FOUND",
        "status": "PENDING",
        "title": "图书馆拾到黑色钱包",
        "categoryName": "钱包卡证",
        "location": "图书馆三楼自习区",
        "occurredAt": "2026-10-20 15:00:00",
        "createdAt": "2026-10-20 15:30:00"
      }
    ]
  },
  "requestId": "fa71ff3cc9e6421d"
}
```

判据只看三件事：HTTP 200、`code=0`、`items` 非空且 `recordId=13`。`score` 是关键词统计分数，不是语义置信度；`tookMs` 随机器波动。

### 7.4 状态表

`scripts\status.ps1` 全绿时如下（摘自实测输出，省略了脚本自身的 `[STEP]` 行；列宽随控制台字体变化）：

```
组件       状态 详情                                        PID   最近日志
--       -- --                                        ---   ----
后端       UP 127.0.0.1:18080 / db=UP / engine=local    4620  logs\backend.log
Nginx    UP 8088 / nginx 1.30.6                       11596 logs\nginx-error.log
MySQL    OK 127.0.0.1:3306 / MySQL 8.0.29             -     -
8088 健康  UP code=0 / status=UP / db=UP / engine=local -     logs\nginx-access.log
8088 监听  OK 监听 PID 12312 / nginx（master 11596）        12312 -
18080 监听 OK PID 4620 / java                           4620  -

日志：安装=logs\install-20261026-152224.log；启动=logs\start-20261026-152301.log；停止=-
进程：后端 PID=4620；Nginx PID=11596
健康：8088=UP；18080=UP
[OK] RESULT: HEALTHY
```

停止后同一条命令应变成：后端 `DOWN 未运行`、Nginx `DOWN 未运行`、8088 健康 `FAIL`、两个监听 `DOWN 无监听`，末行 `RESULT: UNHEALTHY`，退出码 1。

## 8 端口、路径与配置项

### 8.1 端口

| 端口 | 归属 | 绑定地址 | 可否修改 | 依据 |
|---|---|---|---|---|
| **8088** | Nginx，唯一对外端口 | 所有网卡（`listen 8088`） | 改 `nginx\conf\nginx.conf` 的 `listen`，并同步 `start.ps1`（`$publicUrl`、`$publicHealthUrl`、`Get-PortOwners -Port 8088`）、`stop.ps1`（`Wait-PortFree -Port 8088`）、`status.ps1`（健康 URL 与端口检查） | `nginx/nginx.conf`、`start.ps1`、`stop.ps1`、`status.ps1` |
| **18080** | Spring Boot 后端，仅本机 | `127.0.0.1` | 改三处：`start.ps1` 的 `--server.port=18080` 与 `$backendUrl`、`nginx.conf` 的 `upstream findit_backend`、`status.ps1` 的 `$backendUrl` 与端口检查（另加 `stop.ps1` 的 `Wait-PortFree -Port 18080`） | 同上 |

8088 也是 `client_max_body_size`（3 MB）与 gzip 的生效点；后端不对局域网暴露，外部只能通过 8088 访问。

### 8.2 目录

| 路径 | 内容 | 谁写 |
|---|---|---|
| `config\deploy.env.ps1` | 连接配置（含密码） | `install.ps1` 生成，人工可编辑 |
| `runtime\backend.pid`、`runtime\nginx.pid` | 进程号 | `start.ps1` 写，`stop.ps1` 删 |
| `logs\` | 见 §8.3 | 脚本与进程 |
| `nginx\conf\`、`nginx\temp\`、`nginx\logs\` | Nginx 自身 | Nginx |

### 8.3 日志

| 文件 | 内容 | 排查什么 |
|---|---|---|
| `logs\backend.log` | 后端 stdout（Spring 启动、访问日志） | 启动是否成功、端口是否绑定 |
| `logs\backend-error.log` | 后端 stderr（异常栈） | 数据库连接失败、Bean 装配失败 |
| `logs\nginx-error.log` | Nginx `error_log`（`warn` 级别起） | 502/403、配置错误、端口占用 |
| `logs\nginx-access.log` | Nginx 访问日志 | 页面是否真的打到 8088、哪些请求 4xx/5xx |
| `logs\nginx-stdout.log`、`logs\nginx-stderr.log` | Nginx 进程自身输出 | `nginx.exe` 起不来时的原始报错 |
| `logs\install-*.log`、`start-*.log`、`stop-*.log`、`status-*.log` | 每次脚本运行的完整输出 | 复现某次操作；失败时脚本会在末尾打印本次日志路径 |

### 8.4 配置项

`config\deploy.env.ps1` 由模板生成，脚本点源加载，因此变量名固定：

| 变量 | 来源参数 | 说明 |
|---|---|---|
| `$DeployDbHost` / `$DeployDbPort` / `$DeployDbName` / `$DeployDbUser` | `-DbHost` 等 | 供 `status.ps1` 展示与 MySQL 探测 |
| `$DeployMysqlBin` / `$DeployJavaBin` | `-MysqlBin` / `-JavaBin` | 安装时解析到的绝对路径 |
| `$DeployEngine` | `-Engine` | `local` / `external` |
| `$env:FINDIT_DB_URL` | 由 host/port/name 拼出 | JDBC URL，带 `useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8&connectTimeout=3000&socketTimeout=10000` |
| `$env:FINDIT_DB_USER` / `$env:FINDIT_DB_PASSWORD` | `-DbUser` / 密码 | 后端唯一读取的账号密码来源 |
| `$env:FINDIT_MATCH_ENGINE` | `-Engine` | 后端据此装配引擎 |
| `$env:FINDIT_EMBED_BASE_URL` / `$env:FINDIT_EMBED_API_KEY` | 空串 | 仅 `external` 引擎需要手工填写 |

## 9 常见故障

按顺序看第一列的现象，第二列是脚本或日志里的原文，第三列是处理。

| # | 现象（原文） | 先看 | 处理 |
|---|---|---|---|
| 1 | `需要 JDK 17，当前 java 主版本为 <N>` | 该行后面的 `java -version` 输出 | 装 JDK 17，或用 `-JavaBin` 指向 17 的 `java.exe`；`JAVA_HOME` 指向别的版本时尤其要显式传参 |
| 2 | `未找到 java.exe：请安装 JDK 17 并设置 JAVA_HOME/PATH，或用 -JavaBin 指定。` | `Get-Command java` | 同上；`start.ps1` 也认 `-JavaBin` |
| 3 | `找不到MySQL 客户端：…（应为可执行文件或包含 mysql.exe 的目录）` | 传入的 `-MysqlBin` | 传到 `bin` 目录或 `mysql.exe` 本身，不要传安装根目录 |
| 4 | `安装失败：数据库 find_it_campus 已存在。默认不覆盖现有数据；如确认要清空重建，请追加 -Force。` | 目标库名 | 换 `-DbName`，或备份后加 `-Force`（会 DROP） |
| 5 | `需要 MySQL 8.x，服务端返回版本：5.7.x` | `mysql --version` / 服务端版本 | 升级到 MySQL 8；8.0.13 及以下另外缺 ngram 解析器 |
| 6 | `端口 8088 已被其他进程占用：PID xxxx / xxx / 路径。本脚本不会抢占或结束该进程。` | 输出里的 PID | `Get-Process -Id xxxx` 确认是谁；停掉它或改端口（§8.1），不要直接杀未知进程 |
| 7 | 页面刷新 404，或首页 404 | `logs\nginx-access.log`、`web\dist\index.html` | ① 确认 `web\dist` 完整（不是只复制了 jar）；② `nginx.conf` 的 `root ../web/dist` 与 `try_files` 未被改动 |
| 8 | `后端健康检查未通过（超过 60 秒仍未就绪）。请检查 JDK17、数据库连接和 logs\backend-error.log。` | `logs\backend-error.log` 尾部 20 行 | 常见是库连不上（看 `Access denied` / `Communications link failure`）或 18080 被占；确认 MySQL 已启动、`deploy.env.ps1` 里的账号密码正确 |
| 9 | `data.db=DOWN`、HTTP 503、`code=10005` | `logs\backend-error.log` | 后端起来了但数据库探测失败：核对 `FINDIT_DB_URL`/账号/密码，确认 MySQL 服务在跑 |
| 10 | `logs\nginx-error.log` 里 `bind() to 0.0.0.0:8088 failed (10013: ...)` | 端口与权限 | 8088 被占或端口段受限：先 `Get-NetTCPConnection -State Listen -LocalPort 8088`，再决定换端口 |
| 11 | `502 Bad Gateway` | 后端是否在跑：`status.ps1`、`logs\backend-error.log` | 后端没起或崩了；`start.ps1` 会等到后端健康才启动 Nginx，502 多数是后端中途退出 |
| 12 | `403 Forbidden` | `web\dist` 目录权限、Nginx 进程账号 | 解压时用了受限目录或权限被裁剪；把包放到当前用户可读写的目录重试 |
| 13 | `nginx: [emerg] ... unknown directive` / Nginx 起不来 | `logs\nginx-stderr.log` | `nginx\conf\nginx.conf` 被改坏或与 Nginx 版本不符；用包内原始文件覆盖 |
| 14 | `config\deploy.env.ps1 仍是模板占位内容，请重新运行 install.ps1。` | `config\deploy.env.ps1` 是否含 `__` | 安装没跑完或文件被替换成模板；重跑 `install.ps1` |
| 15 | 状态表 `8088 监听 CONFLICT` | 该行 PID | 8088 被非本包进程占用；`start.ps1` 不会抢，先停占用者 |
| 16 | 停止后 `RESULT: PORT_NOT_RELEASED`（退出码 1） | 8088 / 18080 的占用 PID | 有进程没退干净；先确认是不是本包的残留（`status.ps1` 的 `CONFLICT` 行），必要时手工结束该 PID 后重跑 `stop.ps1` |

## 10 升级、回滚与卸载

### 10.1 升级（保留数据）

```powershell
.\scripts\stop.ps1                     # 1) 先停，避免文件占用
# 2) 覆盖三类文件：app\*.jar、web\dist\*、nginx\*（若 Nginx 也升级）
# 3) 保留 config\deploy.env.ps1、db\、logs\、runtime\
.\scripts\start.ps1                    # 4) 用原配置重新启动
.\scripts\status.ps1                   # 5) 全绿即完成
```

`config\deploy.env.ps1` 是**不要覆盖**的那一个：它含本机密码，且新包解压出来只有 `deploy.env.example`。如果新版本改了配置项名，`start.ps1` 会报缺变量，此时按 §8.4 补齐后再启动。

数据库结构有变更时，用新包 `db\schema.sql` 里附带的 ALTER 语句手工执行（本项目不用迁移工具，库里已有数据时不要直接重跑整份 `schema.sql`）。

### 10.2 回滚（保留上一版包）

上一版目录整体留着即可：
1. `stop.ps1` 停当前版本；
2. 把旧目录换回来（或用上一版 jar/dist 覆盖回去）；
3. `start.ps1`。

数据库若被升级脚本改过，回滚软件不会自动回滚数据——改结构前先 `mysqldump` 备份。

### 10.3 卸载

```powershell
.\scripts\stop.ps1        # 确保进程退出、端口释放
# 删除整个包目录
```

- **数据库默认保留**：卸载不影响 MySQL 里已有的库。确实要删，手工执行 `DROP DATABASE find_it_campus;`（或你安装时用的库名）——这一步不可撤销，先备份。
- 卸载后 8088/18080 应无监听，可用 `Get-NetTCPConnection -State Listen -LocalPort 8088,18080` 自查。

### 10.4 备份

```powershell
# 只备份数据，不含建库语句
mysqldump -h 127.0.0.1 -P 3306 -u root -p --databases find_it_campus --single-transaction > backup.sql
```

恢复：`mysql -h 127.0.0.1 -P 3306 -u root -p < backup.sql`。备份文件同样含数据内容，按 §5.4 的规矩处理。

## 11 安全与边界

| 项 | 现状 | 含义 |
|---|---|---|
| 对外端口 | 只有 8088（Nginx） | 后端 18080 绑定 `127.0.0.1`，局域网无法直连后端 |
| 传输加密 | **无 HTTPS** | 明文 HTTP；只在可信内网/本机使用，不做公网暴露 |
| 进程形态 | 普通进程，不注册服务 | 不开机自启，机器重启后需手工 `start.ps1`；不做崩溃自动拉起 |
| 权限 | 不需要管理员 | 不为安装提权，不写系统目录，不改系统环境变量 |
| 密码 | 只落 `config\deploy.env.ps1` | 该文件不进仓库、不进 zip（zip 里只有 `deploy.env.example`）；ACL 尽力收窄到当前用户 |
| 运行身份 | 当前登录用户 | Nginx 与 JVM 都以该用户身份运行，没有单独的守护账号 |
| 请求体上限 | 3 MB（`client_max_body_size`） | 超出由 Nginx 直接 413 |
| 响应头 | `X-Content-Type-Options: nosniff`、`X-Frame-Options: SAMEORIGIN`、`Referrer-Policy: no-referrer-when-downgrade`、`server_tokens off` | 基础加固；`/assets/` 下这些头重复声明了一次 |
| 认证与权限 | **无登录、无鉴权** | 写接口对任何能访问 8088 的人开放（课程版已声明的范围取舍），不要放进不可信网络 |
| Nginx 版本 | 官方 Windows 构建 1.30.6 | 单进程单 worker（`worker_processes 1`），面向演示与单机小规模，不是 Linux 生产形态 |

`engine=external` 时，`install.ps1` 只写 `FINDIT_MATCH_ENGINE=external`，**不会**询问 embedding 地址与密钥；必须在启动前手工编辑 `config\deploy.env.ps1` 填 `FINDIT_EMBED_BASE_URL`、`FINDIT_EMBED_API_KEY`，否则匹配接口返回失败（503 / `code=30002`）。本期交付只验证了 `local` 引擎。

## 12 自检记录与未覆盖项

本节说明本文的哪些结论是实测的、哪些是读代码得出的，避免把「没验过」当成「验过」。

### 12.1 实测环境与结果

- 环境：Windows 10 Pro for Workstations x64；Windows PowerShell 5.1；Temurin JDK 17.0.20.1；MySQL 8.0.29（本机 3306）；Nginx 1.30.6（包内）；非管理员身份。
- 包来源：用 `deploy\build-package.ps1` 从 `feat/7.1-windows-deploy` 头（`db59673`，即最终 zip 的基线）重新构建并解压到工作区之外的干净目录执行。最终 zip 与自检时唯一的差别是本文档本体（7.2 重写了它）以及随之重算的 `SHA256SUMS.txt`，`app/`、`web/dist/`、`nginx/`、`db/`、`scripts/` 完全一致。
- 结果：`install.ps1` 退出码 0（自定义库名 `findit_deploy72`，避开本机既有库）→ `start.ps1` 退出码 0 → `/api/health` 200 且 `status=UP`/`db=UP` → `/`、`/records/1`、`/records/new` 均 200 → `POST /api/match` 200，`recordId=13`、95% → `status.ps1` 退出码 0 且 `RESULT: HEALTHY` → `stop.ps1` 退出码 0、8088/18080 均释放 → 停止后 `status.ps1` 退出码 1、`RESULT: UNHEALTHY` → 再次 `stop.ps1` 退出码 0（幂等）。
- 额外验证：库已存在时 `install.ps1` 按预期拒绝并给出 `-Force` 提示；8088 被占用时 `start.ps1` 报出占用 PID 并回滚已拉起的后端（18080 随之释放）。

### 12.2 未实测、依据为读脚本/读配置的条目

| 条目 | 依据 | 说明 |
|---|---|---|
| JDK 版本不符、MySQL 版本不符的报错原文 | `scripts\_common.ps1:169`、`scripts\install.ps1:146` | 本机只装了 17 与 8.0.29，未构造错误版本环境；原文逐字取自代码 |
| `-Force` 的 DROP 重建、`-DbPassword` 与 `-EmptyDbPassword` 互斥 | `install.ps1:62-63`、`:155-162` | 未在既有库上执行 DROP（避免破坏其他任务的数据） |
| 非交互模式的交互提示文本 | `install.ps1:66-95` | 本次全部按非交互执行，提示文本取自代码 |
| Nginx 403 / 502、`bind() failed` 等系统级报错 | `nginx/conf/nginx.conf`、§9 通用经验 | 未逐一制造；表格中给出的是定位方向，不是逐字原文 |
| `engine=external` 的行为 | `install.ps1:277-279`、`MatchService` | 本期无外部实现，只验证了 `local` |
| 升级/回滚/卸载/备份 | 脚本行为与目录结构 | 只验证了「停止 → 保留配置」这一半，未做整包替换演练 |
