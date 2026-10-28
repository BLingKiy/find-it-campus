# v2.1 候选包与真包四面自检（M6 / ZHYYKFX-90）

- 基线：`origin/feat/v2-integration @ 30002ded5893661f680eaea4e0b858e40ca23255`
- 证据分支：`test/v2-M6-candidate-package`（仅证据，不改业务代码）
- 候选包：`deploy/dist/find-it-campus-windows-x64-30002de.zip`
- 包 SHA256：`ACBA7323689DA1387A5D51569720B7FDE7737D92F4F1FE3630EBB8F0122D1557`
- 包大小：`28,535,449` 字节（27.21 MB）
- `BUILD-INFO.txt`：`Git commit = 30002de...`、`Git dirty = false`、`Git branch = test/v2-M6-candidate-package`
- 结论：**安装/启动/健康/三页 200/停止全部通过；A/B/C/D 四面通过；回归 297 / 0 / 0 / 1 与基线一致。**
- 边界：未合并 `main`、未推 `main`、未发 Release、未打 tag、未改 `review.mode` 默认值（仍为 `off`）。

## 1. 环境

| 项 | 值 |
|---|---|
| 机器 | Windows 10 Pro for Workstations x64，PowerShell 5.1 |
| JDK | Temurin `17.0.20.1` |
| Maven | 3.9.11（`server/mvnw.cmd`） |
| Node / npm | v22.23.3 / 10.9.9 |
| MySQL | 8.0.29（本机 3306，root 空密码，测试库 `findit_m6`） |
| Nginx | 1.30.6（包内官方 Windows 构建） |
| 浏览器 | Edge（Playwright `channel=msedge`），视口 1280×900 |
| 部署目录 | `C:\Project\hifox-workspaces\panshishi\m6-deploy\find-it-campus-windows-x64`（仓库工作树之外） |

## 2. 结论总表

| 面 | 结论 | 关键证据 |
|---|---|---|
| 部署 | 安装/启动/状态/停止退出码均 0；`/api/health` 为 `code=0 / status=UP / db=UP`；`/`、`/records/1`、`/records/new` 均 200 | `raw/part1-evidence.txt`、`raw/health-8088.json`、`shots/01..05` |
| A 部署 CORS | 8088 部署源 POST 200（不再是 403）；无 Origin 200；外部 Origin 403；`localhost:5173` 跨源回显 ACAO/ACAC；`nginx -t` 通过 | `raw/faceA-cors.json`、`03-faceA-cors.md` |
| B 审核闸门 | `mode=off` 立即 `APPROVED` 上架、`raw/match=NULL`；真实 DeepSeek 下 APPROVED 上架、REJECTED 不上架、连续失败 `PENDING+manual=1` | `raw/part1-evidence.txt`、`04-faceB-review.md` |
| C 降级重扫 | 无 key 落 `LOCAL_KEYWORD + EMPTY_KEY` 且页面显示「已降级」；手动降级重扫升级 `DEEPSEEK`，`MATCHED`/确认/解锁/附言全保留；开关可回退 | `raw/part2-evidence.txt`、`raw/faceC-backend-log.txt`、`05-faceC-degraded.md` |
| D 阈值/可见 | LOCAL：0.73 → `AUTHORIZABLE` 出卡片；低于 0.45 只计数。DEEPSEEK：0.98 `DIRECT`、0.92 `AUTHORIZABLE`；低于 0.80 只计数 | `06-faceD-threshold.md`、`shots/06`、`shots/07` |
| 回归 | 后端 `Tests run: 297, Failures: 0, Errors: 0, Skipped: 1`、`BUILD SUCCESS`、exit 0；前端 type-check 0、build 0 | `regression/` |

## 3. 候选包

`BUILD-INFO.txt` 原文见 [`BUILD-INFO.txt`](BUILD-INFO.txt)，关键字段：

```
Git branch: test/v2-M6-candidate-package
Git commit: 30002ded5893661f680eaea4e0b858e40ca23255
Git dirty: false
Backend jar SHA256: 516653FD56D511579FF90210C24BDA0E2C207CA1B071D7CFFC94487F76254E83
Web index SHA256: E76443CA5CFFA718591537AFCAB8B2D0494E196B6A60564B6912AE99DF33E55F
Runtime ports: 8088 (nginx, public) -> 127.0.0.1:18080 (backend)
```

包名带 `-30002de` 后缀，与 v2.0.0 的既有资产可区分；`.sha256` 首行与实测 zip SHA256 完全一致（见 `package-sha256.txt`）。未上传 Release、未覆盖 v2.0.0 资产。

## 4. 三页与截图

| 页面 | 匿名 | 登录后 |
|---|---|---|
| `/` | 200，列表有 21 条真实数据 | — |
| `/records/1` | 200，详情正文（非空壳） | demo01 可见所有者操作与 96% 高置信配对卡片 |
| `/records/new` | 200，但按产品逻辑重定向到 `/login?redirect=...`（截图 `03`） | demo01 直接进入发布表单（截图 `04`） |

- `shots/01-home.png`：首页
- `shots/02-record-1-anon.png`：详情（匿名）
- `shots/03-record-new-anon-login.png`：未登录访问发布页的登录拦截（HTTP 仍为 200）
- `shots/04-record-new-auth-form.png`：登录后的发布表单
- `shots/05-record-1-auth-pair.png`：登录后的详情与配对卡片

> 三页 HTTP 均为 200；`/records/new` 匿名时是前端路由守卫重定向，不是 404/403。截图同时给出匿名与登录后两种真实渲染。

## 5. 四面细节

- [A 部署 CORS](03-faceA-cors.md)
- [B 审核闸门](04-faceB-review.md)
- [C 降级重扫](05-faceC-degraded.md)
- [D 阈值与匹配可见](06-faceD-threshold.md)
- [回归](07-regression.md)

## 6. 复跑

```powershell
# 包构建（本机离线缓存；NODE_OPTIONS 仅绕过沙箱对 Vite `net use` 探测的 spawn EPERM）
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy\build-package.ps1 `
  -MavenBin .\server\mvnw.cmd `
  -JavaHome 'C:\Project\tools\jdk17\jdk-17.0.20.1+1' `
  -NpmBin 'C:\Users\NhNoK\.hifox\managed-runtimes\node\v22.23.3\windows-x64\npm.cmd' `
  -NginxZip 'C:\Project\hifox-workspaces\panshishi\rel82\build\deploy\.cache\nginx-1.30.6.zip'

# 四面驱动（各自在单次前台脚本内启停 MySQL/应用/Nginx）
powershell -NoProfile -ExecutionPolicy Bypass -File ..\m6-tmp\part1.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File ..\m6-tmp\part2.ps1
```

`raw/commands.txt` 是实际命令的逐条记录；`raw/part1-console.txt` / `raw/part2-console.txt` 是完整控制台转录。

## 7. 限制与口径（必须与结论一起读）

1. **真实 DeepSeek**：本轮从本机 `%USERPROFILE%\.hifox-secrets\deepseek_api_key.txt` 读取 35 字符 key，只注入测试进程环境变量；仓库、证据、日志均不含 key。B 面 APPROVED / REJECTED 是真实 `deepseek-chat` 判定；连续失败分支保留真实 key、把 `FINDIT_LLM_BASE_URL` 指向本机未监听端口 `127.0.0.1:19099`，制造真实的连接失败（HTTP_ERROR），不是伪造 key。
2. **同源 CORS 的 ACAO**：nginx 已用 `$http_host` 保留 8088 端口，后端把 `Origin: http://127.0.0.1:8088` 视为同源，因此**响应不带 ACAO 是正确的**（浏览器同源请求不需要 CORS 头）；Z-83 的缺陷是同一请求返回 403。为证明 CORS 头本身正确，另发 `Origin: http://localhost:5173` 的真实跨源请求：200 + `ACAO=http://localhost:5173` + `ACAC=true`；外部源仍 403。
3. **Face C 的恢复与开关验证**：恢复阶段使用真实 key（`DEEPSEEK`），通过 `SPRING_APPLICATION_JSON` 在**本次进程**内把 `degraded-scan-cron` 调成 10 秒、并切换 `degraded-scan-enabled`，仅用于缩短验证时间；`application.yml` 默认值（`0 */10 * * * *`、`true`）未改。为使冷却窗口到期，测试库把 `t_pair_score.updated_at` 与 `t_record.pair_scored_at` 回拨 61 分钟；这是测试数据准备，不是代码改动。
4. **Face C 开关回退**：先把同一 pair 行重置为 `LOCAL_KEYWORD + EMPTY_KEY`、记录置 `DEGRADED`，再分别以 `degraded-scan-enabled=false/true` 启动。false 的 25 秒内 `trigger=degraded-scheduled` 日志 0 条且引擎保持 LOCAL；true 的 25 秒内出现定时重扫并升级。重置只影响测试库。
5. **前端 build 与沙箱**：本机受限沙箱禁止 Vite 探测映射盘时 `child_process.exec('net use')` 的管道创建（`spawn EPERM`）。用 `vite-exec-shim.cjs` 仅把 `net use` 返回为空（本工作区在本地盘，语义等价），不改 Vite/构建配置，shim 不进入产物。普通开发机直接 `npm run build` 应同样通过；本证据的退出码是在该 shim 下取得的。
6. **Z-85 六项开放项未拍板**：`findit.review.mode` 仍默认 `off`；失败策略、`rewritten` 默认采用、ops 权限、`raw_*` 清除、A/B 硬门槛六项维持分支现状，候选包不替人类改默认值。详见既有 `docs/testing/v2/Z85-review-gate/` 与本单评论。

## 8. 远端状态

以 `git ls-remote` 实读为准，见 `raw/git-ls-remote.txt`：`refs/heads/main` 保持 `0ff400a...`，本轮未推 `main`。候选包与证据只出现在 `test/v2-M6-candidate-package`；未创建 tag / release。
