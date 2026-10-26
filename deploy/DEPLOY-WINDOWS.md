# find-it-campus · Windows 部署说明（7.1 占位版）

> **文档状态：7.1 占位。** 本文已包含解压即用的最短操作路径与已知限制；
> 7.2 文档任务会在此基础上补齐完整运维说明、截图与故障排查手册。
> 本文件不得为空，7.2 直接在本文件上增量补齐，不要另起文件名。

## 1. 这是什么

面向 **Windows 10/11 x64** 的单机部署包：Nginx 监听 **8088** 托管前端静态文件，
并把 `/api/` 反向代理到本机 **127.0.0.1:18080** 的 Spring Boot 后端。
不注册 Windows 服务、不需要管理员权限、不打包 JRE、不打包 MySQL。

## 2. 目标机前置条件

| 组件 | 要求 | 说明 |
|---|---|---|
| Windows | 10/11 x64 | 需要自带 Windows PowerShell 5.1 |
| JDK | **17** | 已安装并配置 `JAVA_HOME`/`PATH`，或安装时用 `-JavaBin` 指定 |
| MySQL | **8.0.x** | 服务已启动；需要能执行 `mysql.exe`，安装时用 `-MysqlBin` 指定 |
| 端口 | 8088、18080 空闲 | 被其他进程占用时脚本会报占用 PID，不会抢杀 |

目标机 **不需要** Node.js、npm、Maven；这些只在构建机使用。

## 3. 快速开始

在解压后的 `find-it-campus-windows-x64` 目录打开 PowerShell：

```powershell
# 1) 安装：建库 + 导入 schema/seed + 生成 config\deploy.env.ps1
.\scripts\install.ps1 `
  -DbHost 127.0.0.1 -DbPort 3306 -DbName find_it_campus `
  -DbUser root -DbPassword '你的数据库密码' -Engine local `
  -MysqlBin 'C:\mysql\bin\mysql.exe' `
  -JavaBin 'C:\jdk17\bin\java.exe'

# 空密码可改用：-EmptyDbPassword（不要同时传 -DbPassword）

# 2) 启动：后端 18080 → 健康检查 → Nginx 8088
.\scripts\start.ps1

# 3) 查看状态（全绿退出码 0）
.\scripts\status.ps1

# 4) 停止（幂等；不注册服务）
.\scripts\stop.ps1
```

浏览器访问：<http://127.0.0.1:8088/>

健康检查：<http://127.0.0.1:8088/api/health>，期望 `code=0`、`data.status=UP`、`data.db=UP`。

## 4. 安装参数

| 参数 | 必填 | 说明 |
|---|---|---|
| `-DbHost` | 是（非交互时） | MySQL 主机，默认 `127.0.0.1` |
| `-DbPort` | 是（非交互时） | MySQL 端口，默认 `3306` |
| `-DbName` | 是（非交互时） | 库名，只允许字母/数字/下划线 |
| `-DbUser` | 是（非交互时） | 数据库账号 |
| `-DbPassword` | 是（非交互时） | 密码；不传则用 SecureString 提示，不回显 |
| `-EmptyDbPassword` | 否 | 自动化场景显式使用空密码 |
| `-Engine` | 是（非交互时） | `local` 或 `external` |
| `-MysqlBin` | 否 | `mysql.exe` 或 bin 目录；不传则查 PATH |
| `-JavaBin` | 否 | `java.exe` 或 JDK bin 目录；不传则查 `JAVA_HOME`/PATH |
| `-Force` | 否 | 目标库已存在时 **DROP 重建**，会清空数据，慎用 |

参数齐全时完全非交互；缺哪个才提示哪个。密码只写入包内
`config\deploy.env.ps1`，该文件已被 `.gitignore` 忽略，并尽量收窄 ACL。

## 5. 目录与日志

```
find-it-campus-windows-x64/
  app/                后端 jar
  web/dist/           前端静态文件
  nginx/              Nginx for Windows 1.30.6（含 conf/temp/logs）
  db/                 schema.sql / seed.sql
  config/             deploy.env.example（模板）/ deploy.env.ps1（安装后生成，含密码）
  scripts/            install/start/stop/status
  logs/               backend.log、backend-error.log、nginx-*.log、脚本日志
  runtime/            backend.pid、nginx.pid
  BUILD-INFO.txt      分支/SHA/构建时间/JDK/Maven/Node/Nginx 版本
  SHA256SUMS.txt      包内关键文件校验值
```

## 6. 已知限制（7.1 边界）

- **不注册 Windows 服务**，也不做开机自启；机器重启后需手工执行 `start.ps1`。
- **不做 HTTPS/域名**：Nginx 按契约 `listen 8088`（默认绑定所有网卡），本机用 `http://127.0.0.1:8088` 访问；局域网访问受 Windows 防火墙控制，且没有 TLS，不建议暴露到不可信网络。
- **后端停止是进程终止**：Windows 下没有服务控制，`stop.ps1` 按 PID 结束 JVM，不保证在途请求优雅 drain；Nginx 先 `-s quit`。
- **Nginx 使用官方 Windows 构建**，不是 Linux 生产版；高并发/长连接能力有限，仅适合课程演示与单机小规模。
- **不打包 JRE/MySQL**；目标机必须预装 JDK17 与 MySQL8。schema 的 `FULLTEXT ... WITH PARSER ngram` 需要 MySQL 8.0.14+。
- `FINDIT_MATCH_ENGINE=external` 时，`install.ps1` 不询问 embedding 地址与密钥，需要手工编辑 `config\deploy.env.ps1`。
- 数据库密码以明文保存在包内 `config\deploy.env.ps1`（Windows 本机文件权限保护）；不要复制/提交该文件。

## 7. 故障排查

| 现象 | 先看 | 处理 |
|---|---|---|
| `install.ps1` 报 JDK 版本不符 | `java -version` | 安装 JDK17，或用 `-JavaBin` 指向 17 的 `java.exe` |
| `install.ps1` 报数据库已存在 | 目标库名 | 默认不覆盖；确认可清空后加 `-Force` |
| `start.ps1` 报端口被占用 | 输出里的 PID | 用 `status.ps1` 确认；脚本不会结束非本包进程 |
| 后端起不来 | `logs\backend-error.log` | 检查数据库 URL/账号/密码、JDK17、18080 |
| 8088 打不开 | `logs\nginx-error.log` | 检查 8088 占用、`nginx\conf\nginx.conf` 路径 |
| 页面 404 | `web\dist\index.html` 是否存在 | 重新解压完整包；不要只复制 jar |

## 8. 7.2 待补齐清单（占位标记）

- [ ] 完整安装/启动/停止/状态截图与逐步说明
- [ ] 常见故障的完整排查树与日志字段说明
- [ ] 备份/恢复、升级/回滚步骤（含数据库 dump 与包替换）
- [ ] 局域网访问与 Windows 防火墙配置说明
- [ ] 与 7.2 验收脚本的对应关系
