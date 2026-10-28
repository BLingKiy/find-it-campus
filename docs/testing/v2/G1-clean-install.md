# G1 部署包干净目录实测（v2 / 包布局 7.4）

> 结论：**两轮实测均 PASS**。不设 `DEEPSEEK_API_KEY` 时登录、发布、出分走 `LOCAL_KEYWORD` 并显示降级；设置变量重启后，新发布 pair 落 `engine=DEEPSEEK`（score 1.0000 / latency_ms 889 / prompt_tokens 239 / completion_tokens 24 / model=deepseek-chat）。包内两类密钥模式各 0 命中。
>
> 包构建提交：`f5d47314cef34cecf50d86f9ddc9aa3ae6f38ecd`（分支 `feat/v2-G1-package`）
> 包文件：`deploy/dist/find-it-campus-windows-x64-f5d4731.zip`
> 包 SHA256：`64D1147D3B65A1CCF0CA240B5286923D85C477DB5D6033F1D716319AA85AA0F8`
> 包大小：28,466,168 字节（27.15 MB）

## 1. 基线、合并与构建

| 项 | 值 | 取证 |
|---|---|---|
| 集成分支基线 | `feat/v2-integration @ 0b9b94f` | `git merge-base --is-ancestor 0b9b94f HEAD` → YES |
| R-1 修复分支 | `fix/v2-R1-masking-separators @ d65dc94` | 已推远端，`MaskingPolicyTest` 21/21、全量 245/245 |
| R-1 合入 | `c781a99`（`git merge --no-ff`，父提交 `0b9b94f` + `d65dc94`），已合入 `feat/v2-integration` | `git merge-base --is-ancestor d65dc94 origin/feat/v2-integration` → YES |
| 包构建提交 | `f5d4731` | `BUILD-INFO.txt` 的 Git commit 与 `git rev-parse HEAD` 一致 |
| 构建命令 | `deploy\build-package.ps1` + JDK17 / Maven 3.10.0 / Node 22.23.3 / Nginx 1.30.6 | 构建日志 `deploy/.cache/logs/build-package-20261028-184420.log` |
| BUILD-INFO | layout 7.4、branch `feat/v2-G1-package`、dirty=false、jar/index SHA256 与包内文件实测一致 | 见 `G1-package-secret-scan.txt` §2/§4 |

说明：本文档与证据是包构建提交之后的 docs-only 提交，只改 `docs/`，不改变 zip 内任何字节；包内 `BUILD-INFO` 指向的 `f5d4731` 就是被测包的构建提交。

## 2. 测试环境

| 项 | 值 |
|---|---|
| 主机 | Windows 10 19045 x64 |
| JDK | OpenJDK 17.0.20.1（`C:\Project\tools\jdk17\...`） |
| MySQL | 8.0.29，`127.0.0.1:3306`，测试库 `findit_g1_62`（utf8mb4） |
| Nginx | 1.30.6（包内官方 Windows 构建） |
| 干净目录 | `C:\Project\hifox-workspaces\panshishi\g1-clean\find-it-campus-windows-x64`（解压后全新目录） |
| 访问入口 | 所有浏览器/API 请求都走 `http://127.0.0.1:8088`；后端只监听 `127.0.0.1:18080`，未启动 Vite/dev server |
| 数据 | 安装时导入包内 `db/schema.sql` + `db/seed.sql`：`t_category=9`、`t_record=24`、seed 三态 `PENDING:21 / COMPLETED:2 / CLOSED:1` |

## 3. 第一轮：不设 `DEEPSEEK_API_KEY`

### 3.1 安装与启动

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install.ps1 `
  -DbHost 127.0.0.1 -DbPort 3306 -DbName findit_g1_62 -DbUser root `
  -EmptyDbPassword -Engine local `
  -MysqlBin "C:\Project\tools\mysql\mysql-8.0.29-winx64\bin" `
  -JavaBin "C:\Project\tools\jdk17\jdk-17.0.20.1+1\bin\java.exe" -Force

# 关键：安装期间不询问 DEEPSEEK_API_KEY，进程环境也没有该变量
Remove-Item Env:DEEPSEEK_API_KEY -ErrorAction SilentlyContinue
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start.ps1
```

| 检查 | 结果 |
|---|---|
| `install.ps1` | 退出码 0；JDK 17 / MySQL 8.0.29 通过；建库 utf8mb4；schema/seed 导入成功；`t_category=9`、`t_record=24` |
| 是否询问 DeepSeek 密钥 | 否；`config\deploy.env.example` 只有一行等号两侧带空格的注释占位符 |
| `KEY_STATE` | `empty len=0` |
| `start.ps1` | 退出码 0；后端健康 `code=0 / status=UP / db=UP / engine=local`；Nginx 8088 就绪 |
| 8088 首页 / 健康 | `GET /` → HTTP 200；`GET /api/health` → HTTP 200、`code=0` |

### 3.2 登录、发布、出分

三个 seed 账号均通过 8088 登录，HTTP 200：`demo01` / `demo02` / `demo03`（密码为 seed 的演示密码）。

| 动作 | 结果 |
|---|---|
| demo02 发布 LOST（充电宝） | HTTP 200，id=27 |
| demo03 发布 FOUND（充电宝） | HTTP 200，id=28 |
| 新 pair | `id=34 / score=0.8000 / engine=LOCAL_KEYWORD / degraded_reason=EMPTY_KEY / latency_ms=NULL / prompt_tokens=NULL / completion_tokens=NULL / status=NEW` |
| 所有者详情 | `pairState=DEGRADED`、`lowConfidenceCount=10`、卡片 `tier=AUTHORIZABLE`、`degraded=true`、`contactAvailable=false` |
| 页面降级提示 | 详情接口返回 `pairState=DEGRADED` + 卡片 `degraded=true`；构建产物中降级 banner 文案「智能匹配暂不可用 / 已降级」命中 3 处（`MyRecords-*.js`、`PairCard-*.js`） |

降级提示口径：`PairPanel.vue` 在 `pairState=DEGRADED` 或任一卡片 `degraded=true` 时渲染 `已降级 / 智能匹配暂不可用，已用关键词匹配。分数仅供参考。` 本轮未做浏览器截图，采用「接口状态 + 构建产物文案」双重核对，原始行见 `G1-round1-evidence.txt`。

### 3.3 三态与可见性（vF 口径）

| 场景 | 预期 | 实测 |
|---|---|---|
| 未达 MATCHED 调「标记完成」 | 409，记录状态不变 | HTTP 409 / code 20005「需双方确认匹配后才能标记完成」；DB 仍 `PENDING` |
| 双方 CONFIRM | pair → MATCHED | 先 CONTACTED，后 MATCHED，`lost_confirmed_at`/`found_confirmed_at` 均非空 |
| 本人标记完成 | 200，写 `completed_at` | HTTP 200，`status=COMPLETED`、`completedAt=2026-10-28 18:52:46` |
| COMPLETED 匿名详情 | 404 | HTTP 404 / code 20001 |
| COMPLETED 低分对端（0.80 < 0.90） | 404 | HTTP 404 / code 20001 |
| CLOSED（id=29）匿名详情 | 404 | HTTP 404 / code 20001 |
| 公共列表 | COMPLETED / CLOSED 0 命中 | `total=22`，不含新 COMPLETED、新 CLOSED、seed 23、seed 24 |
| seed 23 COMPLETED 可见性 | 匿名 404；demo01（LOST10 与 23 有 .95 MATCHED）200；demo02（LOST6 与 23 是 .93 IGNORED）404 | 404 / 200 / 404 |
| seed 24 CLOSED | 任何身份 404 | 匿名 404 |

安装后 schema 三列与状态约束实际可查：

```
SCHEMA_V2_COLUMNS_COMPLETED_AT_LOST_FOUND=3/3
SCHEMA_T_RECORD_STATUS=CLOSED:1,COMPLETED:2,PENDING:21
SCHEMA_T_PAIR_STATUS=CONTACTED:5,IGNORED:1,MATCHED:2,NEW:6
```

### 3.4 三档卡片（seed 固定夹具，8088 实读）

| 档位 | pair / 记录 | 结果 |
|---|---|---|
| DIRECT（≥0.90） | pair 1 / record 1，score .96 | `cardTier=DIRECT`，`contactAvailable=true`，`hint=匹配度较高，可直接查看` |
| AUTHORIZABLE（0.75–0.90） | pair 4 / record 4，score .86 | `cardTier=AUTHORIZABLE`，`contactAvailable=true`，`hint=对方已公开联系方式` |
| LOW（<0.75） | pair 8 / record 8，score .34 | 不返回卡片（`cards=0`），`lowConfidenceCount=2`，页面只显示数量 |

## 4. 第二轮：设置 `DEEPSEEK_API_KEY` 并重启

密钥只从本机 `%USERPROFILE%\.hifox-secrets\deepseek_api_key.txt` 读入**当前进程环境**，不写文件、不进日志、不进证据；证据只记 `KEY_STATE=present len=35`，不打印任何前缀或片段。

```powershell
# 同一个 shell 内注入，再重启；start.ps1 拉起的 java 继承该环境变量
$env:DEEPSEEK_API_KEY = (Get-Content "$env:USERPROFILE\.hifox-secrets\deepseek_api_key.txt" -Raw).Trim()
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start.ps1
```

| 检查 | 结果 |
|---|---|
| 重启前数据仍在 | `CLOSED:2, COMPLETED:3, PENDING:22`（第一轮写入未丢） |
| `start.ps1` | 退出码 0；8088 健康 200 / UP / db=UP |
| demo02 发布 LOST | id=30 |
| demo03 发布 FOUND | id=31 |
| 新 pair | `id=64 / score=1.0000 / engine=DEEPSEEK / degraded_reason=NULL / latency_ms=889 / prompt_tokens=239 / completion_tokens=24 / prompt_version=pair-score-v1 / status=NEW` |
| 所有者详情 | `pairState=READY`，卡片 `tier=DIRECT`、`engine=DEEPSEEK`、`degraded=false` |
| 真实调用日志 | `pair llm ok lostId=30 foundId=31 engine=DEEPSEEK score=1.0 latencyMs=889 promptTokens=239 completionTokens=24 model=deepseek-chat` |
| model | `deepseek-chat`（日志实测；DB 不存 model 列，不把配置值伪装成 DB 值） |
| 引擎分布 | `DEEPSEEK:32, LOCAL_KEYWORD:32`；`latency_ms/prompt_tokens/completion_tokens` 均非空的 DEEPSEEK 行 32 条 |

## 5. 验收标准逐条对照

| # | 验收标准 | 结论 | 证据 |
|---|---|---|---|
| 1 | 干净目录不设变量：可登录、可发布、pair 有分数且 `engine=LOCAL_KEYWORD`，页面显示降级 | PASS | §3.2；`G1-round1-evidence.txt` |
| 2 | 设置变量重启：新发布 pair 落 `engine=DEEPSEEK`，证据记 model / latency_ms / tokens | PASS | §4；`G1-round2-evidence.txt` |
| 3 | 包内两类密钥模式各 0 命中，扫描命令与输出留档 | PASS | `G1-package-secret-scan.txt` |
| 4 | 解压目录无 `deploy.env.ps1`；BUILD-INFO 版本与包内容一致 | PASS | zip 条目 0 命中；BUILD-INFO commit/dirty/layout 与包内文件一致 |
| 5 | 记录 zip SHA256 | PASS | `64D1147D...5AA0F8`（见文首） |
| 6 | 登录 + 发布 + 三档卡片在 8088 单端口全部可用，不依赖 dev server | PASS | 全部 HTTP 走 8088；后端仅 127.0.0.1:18080；§3.4 三档 |

## 6. 环境限制与残留风险（不隐藏）

1. **本机沙箱拒绝 CIM**：`Get-CimInstance Win32_Process` 返回「拒绝访问」。原 `start.ps1` 依赖它从 cmd 包装进程找 `java.exe` 子进程，会误报「java.exe 没有起来」；本次已在 `start.ps1` 增加回退：18080 在拉起前已确认空闲，若进程树查询失败，则取 18080 的监听者并用已校验的 `java.exe` 路径核对，识别成功即继续。该回退已包含在包构建提交 `f5d4731` 中，属于对受限主机的加固，正常主机路径不变。
2. **`stop.ps1` 仍依赖命令行匹配**：在 CIM 被拒的主机上它无法确认 PID 属于本包，会输出 `PORT_NOT_RELEASED`。本次测试脚本在 `stop.ps1` 之后只按「18080 的 java.exe 路径 = 已校验 JDK」与「8088 的 nginx.exe 路径在包内」精确结束进程，未按进程名杀任何进程。正常 Windows 主机 CIM 可用，`stop.ps1` 逻辑未改。
   - 残留风险：若目标机也禁用 CIM，`stop.ps1` 可能停不掉服务；建议后续单开一条给 `stop.ps1` 补同样的端口归属回退，本轮为控制改动面未动它。
3. **降级提示未截图**：本轮以 `pairState=DEGRADED` + 卡片 `degraded=true` + 构建产物 banner 文案三重核对，未使用浏览器截图（本机无可用的无头浏览器自动化路径）。功能判定不受影响。
4. **MySQL 为空密码测试实例**：仅用于本机干净目录实测（`-EmptyDbPassword`），不代表部署建议；`deploy.env.ps1` 在安装后生成且已被 `.gitignore` 忽略，未进入 zip。

## 7. 复现命令摘要

```powershell
# 1) 解压后安装（不会询问 DeepSeek 密钥）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install.ps1 `
  -DbHost 127.0.0.1 -DbPort 3306 -DbName findit_g1_62 -DbUser root `
  -EmptyDbPassword -Engine local -MysqlBin <mysql bin> -JavaBin <jdk17 java.exe> -Force

# 2) 第一轮：无密钥
Remove-Item Env:DEEPSEEK_API_KEY -ErrorAction SilentlyContinue
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start.ps1
# 浏览器/接口统一走 http://127.0.0.1:8088

# 3) 第二轮：同 shell 注入密钥后重启
$env:DEEPSEEK_API_KEY = (Get-Content "$env:USERPROFILE\.hifox-secrets\deepseek_api_key.txt" -Raw).Trim()
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start.ps1

# 4) 包内扫描
node docs/testing/v2/G1-scan.mjs <解压后的包根目录>
```

## 8. 证据索引

| 文件 | 内容 |
|---|---|
| `docs/testing/v2/G1-package-secret-scan.txt` | 两类密钥模式逐文件扫描、BUILD-INFO 专项、zip 无 deploy.env.ps1、SHA256SUMS、schema 三列、jar 内 MaskingPolicy 反编译 |
| `docs/testing/v2/G1-round1-evidence.txt` | 第一轮安装/启动/登录/发布/出分/409/MATCHED/COMPLETED/CLOSED/列表/seed 可见性/三档的原始判定行 |
| `docs/testing/v2/G1-round2-evidence.txt` | 第二轮重启/发布/DEEPSEEK pair/latency/tokens/model/详情卡片的原始判定行 |
| `docs/testing/v2/G1-scan.mjs` | 本次实际使用的扫描脚本（可复跑） |
