# v2 · F3-R 回归资产：从零到复跑

> 读者对象：**没参与过 F3-R 这一轮的人**。照本文从空库起，到五组脚本跑完、读懂结果为止。
> 本文只讲「怎么跑、怎么判」，不讲修复本身；修复结论见 `docs/testing/v2/F3-integration-rescan.md`，
> 安全审查口径见 `docs/testing/v2/security-review.md`。
>
> 日期：2026-10-28 立稿（对应 ZHYYKFX-86）· 执行者 端测测（`5d43311d`）
> 分支：`test/v2-F3-integration-rescan`（从 `64693ec` 切出，本文所在分支会在交付评论里给出 SHA）
> **本轮不改脚本逻辑、不改断言、不动业务代码**：改动只落在 `qa/` 与 `docs/testing/`。

---

## 0. 这批资产是什么

F3-R 是「F3 的 P1 + P2 四项修复在真实 HTTP + 真实 MySQL 下的复验」。验收时留下一条遗留：
**脚本与原始输出只作为运行产物存在，没入仓，最终集成复核时没有可复跑的命令与输出。** 本文补的就是这一条。

| 组 | 脚本（固定路径） | 断言对象 | 原始输出（2026-10-28 归档） | 退出码 |
|---|---|---|---|---|
| 组1 · 28 探针 | `qa/f3r/phone-scan-28.sh` → `qa/api/f2-scan.mjs` | 匿名/非本人响应体与通知里 `1[3-9]\d{9}` 0 命中，共 28 条探针 | `qa/reports/f3r-2026-10-28-phone-scan-28.txt` | 0 = 0 命中 / 1 = 有命中 |
| 组2 · 缓存头 | `qa/f3r/cache-headers.sh` | 5 个端点 × 多身份 × 多状态码，共 25 条：`Cache-Control: no-store` + `Vary: Cookie` | `qa/reports/f3r-2026-10-28-cache-headers.txt` | **恒 0**，判定看 `FAIL=0`（见 §5） |
| 组3 · 日志注入 | `qa/f3r/log-injection.mjs` | 9 条注入通路，每条一个独立假时间戳，日志里不得多出伪造整行 | `qa/reports/f3r-2026-10-28-log-injection.{txt,json}` | 0 = 无伪造行 / 1 = 有 |
| 组4 · 状态竞态 | `qa/f3r/state-race.mjs` | 终态记录的 PUT/PATCH 必须 409 且库内全行零写入（含真并发编排），11 条 | `qa/reports/f3r-2026-10-28-state-race.{txt,json}` | 0 = 11 条全 PASS / 1 = 有失败 |
| 组5 · 过期扫描 | `qa/f3r/expired-scan.mjs`（prepare + verify）<br>`qa/f3r/expired-scan-startup.mjs`（第二口径） | 过期 COMPLETED 不重算、不新建 pair/通知（8 条），启动扫描口径再 4 条 | `qa/reports/f3r-2026-10-28-expired-{prepare,scan}.{txt,json}`<br>`qa/reports/f3r-2026-10-28-expired-scan-startup.txt` | 0 = 全 PASS / 1 = 有失败 / **2 = 环境不满足而中止** |
| 附加 · P1 八类绕过 | `qa/f3r/p1-bypass.mjs` | 8 类绕过 × 8 个 HTTP 出口 + 对照组 5 条 + R-1 三例现状 | `qa/reports/f3r-2026-10-28-p1-bypass.{txt,json}` | 0 = 0 可读且对照组不误伤 / 1 = 有命中 |

> 附加组不在「五组」之内，是同一轮同时入仓的第 6 个脚本；跑法一样，一起跑不影响结论。
>
> 归档命名规则：`f3r-<YYYY-MM-DD>-<组名>.<后缀>`。**归档文件冻结**，不要手改；
> 复跑时脚本写的是**不带日期**的名字（`qa/reports/f3r-cache-headers.txt` 等），覆盖的只是复跑产物，
> 不会碰到归档。每组脚本头部都写了同样的口径（用途 / 前置 / 运行 / 期望输出 / 退出码）。

---

## 1. 环境清单

本轮实测通过的组合（2026-10-28 那轮与 2026-10-29 的文档校验轮都是这套）：

| 项 | 值 | 备注 |
|---|---|---|
| 系统 / Shell | Windows 10 · **Git Bash** | 本文所有命令按 Git Bash 写；PowerShell 下 `export` / `>` 重定向写法不同 |
| Node | `v22.23.3` | `node -v` |
| JDK | Temurin `openjdk 17.0.20.1`，路径 `C:/Project/tools/jdk17/jdk-17.0.20.1+1` | 与 `application.yml` 无关，只决定 jar 能不能跑 |
| MySQL | `8.0.29`，路径 `C:/Project/tools/mysql/mysql-8.0.29-winx64`，端口 3306，root **空密码** | 该目录**没有 my.ini**，见 §2 |
| 后端 | `http://localhost:18080`（`server/src/main/resources/application.yml` 的 `port`） | 探活 `GET /api/health` |
| 前端 | **本波不需要** | 五组全是 HTTP/DB 级，不碰浏览器；`qa/run-all.mjs` 的 E2E 阶段才要 5173 |
| 演示账号 | `demo01` / `demo02` / `demo03`，密码统一 `Demo@123456`（`db/seed.sql` 内） | pairId=1 是 demo01↔demo02 的 0.96 CONTACTED 对 |
| 打分配置 | **不设 `DEEPSEEK_API_KEY`** | 全部走 `LOCAL_KEYWORD` 降级（`degradedReason=EMPTY_KEY`、`llmCalls=0`）；有 key 的路径本轮未测，见 §9 |

---

## 2. 起库（MySQL + schema + seed）

### 2.1 拉起 mysqld

该安装目录里**没有 `my.ini`**（`--defaults-file` 会直接 FATAL），所以必须显式给 `basedir` / `datadir`。
在 Git Bash 里开一个**单独终端**常驻（前台跑，别关窗口）：

```bash
C:/Project/tools/mysql/mysql-8.0.29-winx64/bin/mysqld.exe \
  --basedir=C:/Project/tools/mysql/mysql-8.0.29-winx64 \
  --datadir=C:/Project/tools/mysql/mysql-8.0.29-winx64/data \
  --port=3306 --console
```

预期输出（最后两行是关键）：

```text
2026-10-28T16:03:49.155800Z 0 [System] [MY-011323] [Server] X Plugin ready for connections. Bind-address: '::' port: 33060
2026-10-28T16:03:49.156117Z 0 [System] [MY-010931] [Server] ... ready for connections. Version: '8.0.29'  socket: ''  port: 3306
```

> 起不来的常见原因：3306 已被别的实例占用；`data` 目录被上一次异常退出的进程锁着（先 `netstat -ano | grep :3306` 找 PID）。

### 2.2 导 schema + seed

```bash
MYSQL="C:/Project/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe"
"$MYSQL" --no-defaults -h127.0.0.1 -P3306 -uroot --default-character-set=utf8mb4 < db/schema.sql
"$MYSQL" --no-defaults -h127.0.0.1 -P3306 -uroot --default-character-set=utf8mb4 find_it_campus < db/seed.sql
```

两个必须注意的点：

- `--no-defaults` **必须是第一个参数**，否则本机 `%APPDATA%\MySQL\.mylogin.cnf` 之类会改写连接参数；
- `--default-character-set=utf8mb4` 不能省，否则 Git Bash 下中文 seed 会按 GBK 送进去。

验证（预期 `24` / `9` / `3`）：

```bash
"$MYSQL" --no-defaults -h127.0.0.1 -P3306 -uroot -N -B --default-character-set=utf8mb4 \
  -e "SELECT COUNT(*) FROM find_it_campus.t_record; SELECT COUNT(*) FROM find_it_campus.t_category; SELECT COUNT(*) FROM find_it_campus.t_user;"
```

```text
24
9
3
```

> `db/schema.sql` 可重复执行（会清空重建）。脚本自身在跑前/跑后也会调 `resetSeed()`（`qa/lib/db.mjs`），
> 所以**不需要**每跑一组就手工导一遍库；但手上有脏数据时先按上面两条重导一次，读数才对得上。

---

## 3. 起后端

### 3.1 构建（jar 已在仓库外/本地时跳过）

```bash
cd server && ./mvnw -q -DskipTests package && cd ..
ls -l server/target/find-it-campus-0.0.1-SNAPSHOT.jar
```

> 构建这一步**本次未重新验证**：2026-10-28 那轮与 2026-10-29 校验轮用的都是 14:38 打出的
> 28,595,191 字节 jar。要验构建请单独跑，本文只保证「jar 已就绪」之后的步骤。

### 3.2 启动（stdout 必须落到 `qa/reports/server.log`）

```bash
java -jar server/target/find-it-campus-0.0.1-SNAPSHOT.jar > qa/reports/server.log 2>&1
```

日志落到这个文件不是可选项：**组3（日志注入）与组5（过期扫描）都靠读它的增量取证**，
文件不存在会直接报错。第二次起后端要用 `>>` 追加，不要覆盖（组5 的启动扫描脚本按字节偏移读增量）。

探活：

```bash
curl -s http://localhost:18080/api/health
```

```json
{"code":0,"message":"ok","data":{"status":"UP","db":"UP","version":"0.0.1-SNAPSHOT","engine":"local"},"requestId":"..."}
```

`data.db` 必须是 `UP` —— 它同时证明 3306 通、schema 在。起不来时先看 `qa/reports/server.log` 尾部的 `APPLICATION FAILED TO START`。

> 语义提醒：**不设 `DEEPSEEK_API_KEY`** 是这一波的默认口径（降级路径）。设了 key 会改变打分的
> `engine` / `llmCalls`，组5 的正对照读数会不一样，别混着比对。

### 3.3 停后端（Git Bash 的坑）

```bash
netstat -ano -p TCP | grep ':18080' | grep LISTEN      # 取最后一列 PID
MSYS_NO_PATHCONV=1 taskkill /PID <PID> /F
```

`MSYS_NO_PATHCONV=1` 不能省：Git Bash 会把 `/PID` 当成路径重写成 `C:/Program Files/Git/PID`，
报 `错误: 无效参数/选项`。组5 的启动扫描脚本内部用 `execFile('taskkill', ...)`，不受这个影响。

---

## 4. 跑五组（按顺序）

顺序有依赖：组5 的 `prepare` 造夹具 → 带 token 重启 → `verify` → `startup`，其余三组互相独立。
**所有命令都在仓库根目录执行。**

### 4.1 组1 · 28 探针（手机号泄漏全局扫描）

```bash
bash qa/f3r/phone-scan-28.sh
```

预期开头与结论：

```text
# F2 手机号泄漏全局扫描 · 2026-10-28T06:45:06.285Z
- 正则：1[3-9]\d{9}
- 载体：匿名响应体 / 非本人响应体 / 通知 title+body
- 探针：28 个；命中：0
- 结论：PASS（0 命中）
```

逐条是 `PASS  匿名-列表 PENDING/ALL 第 1 页` 这种 28 行，每行下一行带 `HTTP 200 / code=0 / 命中 0`。
退出码 `0`。

> 这是 F3-R 的**入口**，真正的探针实现在 `qa/api/f2-scan.mjs`（F2 资产）—— 复跑同一份，不另写副本，
> 否则 F3-R 的读数就不再等于 F2 的口径。
> **注意：它会覆盖 F2 那轮的原始证据** `docs/testing/v2/F2-phone-scan.txt` 与 `qa/reports/f2-phone-scan.{txt,json}`。
> 覆盖后用 `git checkout -- docs/testing/v2/F2-phone-scan.txt qa/reports/f2-phone-scan.txt qa/reports/f2-phone-scan.json` 还原。

### 4.2 组2 · 缓存头（25 条）

```bash
bash qa/f3r/cache-headers.sh > qa/reports/f3r-cache-headers.txt
```

预期开头：

```text
# F3-R 项4 · P2-2 缓存头实测 · 2026-10-28T08:16:20Z
# API=http://localhost:18080  pairId=1
# 登录 demo01 → HTTP 200 / demo02 → HTTP 200
```

预期结尾（判定就看这一行）：

```text
断言用例数=25，FAIL=0
```

中间是一张 `| 判定 | HTTP | 用例 | Cache-Control | Vary |` 的汇总表，25 行全 `PASS`。
**退出码恒为 0**（脚本末尾是 `echo`，本轮按「不改逻辑」保留），机器判定请用：

```bash
grep -c '^FAIL' qa/reports/f3r-cache-headers.txt    # 期望 0（grep 无匹配时自身退出码是 1，别误读）
```

### 4.3 组3 · 日志注入（9 条通路）

```bash
node qa/f3r/log-injection.mjs
```

预期结论行：

```text
## 结论：PASS（9 条通路均无伪造整行，LogSafe 替换可见）
```

正文会列出每条通路的「以假时间戳开头的行数」（必须 0）、LogSafe 四档替换痕迹、
以及 `...[324chars]` 截断样例。退出码 `0`。

> 脚本头部注释写的是「七条注入通路」，是初版口径；**以实际输出的 9 条为准**
> （J1 查询参数 / J1b 路径参数 / 取值白名单 / J2a–J2f 学号与请求体 / 访问日志）。
> 日志按 JVM 默认字符集（本机 GBK）写，脚本已用 `TextDecoder('gbk')` 读；用别的工具 grep 中文会假阴性。

### 4.4 组4 · 状态竞态（11 条）

```bash
node qa/f3r/state-race.mjs
```

预期结尾：

```text
## 结论
- 断言项：11；失败：0
## 收尾：恢复 seed 初始态
```

中间包含：C1–C7 逐条 PASS（带「指纹逐字符相同」佐证）、S1 的 `ROW_COUNT()` 反证、
R1 行锁编排（PUT 被阻塞后仍 409）、B1 裸并发 20 轮分布。退出码 `0`。

### 4.5 组5 · 过期扫描（prepare → 重启 → verify → startup）

**第一步：造夹具**（会给 X1/X2 造过期 COMPLETED，并预置一行「与内容不一致」的 pair 用于反证）

```bash
node qa/f3r/expired-scan.mjs prepare
```

预期：

```text
# F3-R 项7 · prepare 阶段（造过期扫描夹具）· 2026-10-28T07:37:09.019Z
- resetSeed() 已执行，库回到 seed 初始态（计数可复现）
- 夹具：X1=27 X2=28 X3=29 X4=30（对照） C1=31 C2=32（对手端）
- 已等待创建路径的异步打分落地（READY/DEGRADED），之后写入的夹具状态不会被它覆盖
```

> id 每次不同（`27/28/29/30` 是 2026-10-28 那轮的），以实际输出为准。夹具 id 与快照写到
> `qa/reports/f3r-expired-fixtures.json`，**下一步的启动扫描脚本要读它**。

**第二步：带 ops token 重启后端**（`POST /api/ops/pair-scan` 只在 env 有 token 时存在，否则 404）

```bash
# 先停掉 §3.2 起的进程（§3.3 两条命令），再：
export OPS_SCAN_TOKEN="$(openssl rand -hex 8)"     # 一次性本地值，别写进任何证据文件
java -jar server/target/find-it-campus-0.0.1-SNAPSHOT.jar >> qa/reports/server.log 2>&1
curl -s http://localhost:18080/api/health
```

**第三步：verify**（同一个 shell 里 `OPS_SCAN_TOKEN` 必须还在，脚本自己读这个 env 去调 ops 接口）

```bash
node qa/f3r/expired-scan.mjs verify
```

预期逐条 `V1`–`V8` 全 PASS，结尾：

```text
## 结论
- 断言项 8，失败 0
```

**第四步：第二口径（启动扫描）**

```bash
node qa/f3r/expired-scan-startup.mjs
```

预期结论：

```text
## 结论：以「重启」为触发点同样复验通过（P2-4 的两个触发口径一致）
```

> 这一步会**自己把 18080 上的进程 kill 掉**，再用 `java -jar ... --pair-scan-once` 以脱离方式启动，
> 并把 `OPS_SCAN_TOKEN` 透传给新进程。所以：跑之前要有进程可杀；跑完之后那个新进程还在，
> 不想留着就按 §3.3 收掉。退出码 `2` 表示**环境不满足**（找不到监听进程 / 90 秒内没探活成功），
> 不是判据失败 —— 先回去看 §3。

### 4.6 附加组 · P1 八类绕过（可选，但建议跑）

```bash
node qa/f3r/p1-bypass.mjs
```

预期结尾：

```text
## 汇总
- 八类绕过：8/8 越权/对端/落库方向 0 可读
- 对照组：5/5 符合预期
- R-1 现状：R1a逗号拆=HIT ｜ R1b冒号拆=HIT ｜ R1c分号拆=HIT（不改规则；是否升 P1 追加修复由人类在门禁 3 裁决）
```

> R-1 三个 HIT 是**如实记录的现状**，不是回归：`MaskingPolicy` 的分隔符集不含 `,` `:` `;`，
> 本轮按任务范围只测不改。看到 HIT 不要当成这组「失败」。

---

## 5. 判读结果

1. **先看退出码**。除组2 外，`0` = 该组判据成立，非 0 = 有失败项（组5 的 `2` 是环境中止）。
2. **组2 单独判**：`grep -c '^FAIL' <输出>` 必须为 0，并且末行是 `断言用例数=25，FAIL=0`。
3. **再看每组末段的结论行**（上面 §4 各条贴的就是），逐条 PASS 才叫过。
4. **数一下条数对不对**：28 / 25 / 9 / 11 / (8+4) / (8+5+3)。条数变少说明探针没跑全（常见原因是前置没满足、脚本提前抛错），比报 FAIL 更危险。
5. **失败先分两类**：
   - 环境类：`登录夹具失败`、`EV-LOG 不可用`、`mysql 执行失败`、`18080 没有监听` → 按 §2/§3 修环境，不是代码问题；
   - 判据类：出现 `FAIL` 且带具体读数（HTTP 码、指纹、行数） → 真失败，原样贴进任务评论。

已知的一次不稳定（2026-10-29 校验轮实测）：组4 的 **C6（CLOSED → COMPLETED 的 PATCH）偶发「指纹发生变化」**。
指纹里含 `pair_state` 列，而接口创建的夹具会触发异步打分（prepare 脚本明确要「等异步打分落地」才继续，
state-race 没有等）——后台打分在两次指纹之间把 `pair_state` 从 `SCORING` 推到 `READY`，就会报指纹变化，
但正文与 `updated_at` 一字未动。同一环境重跑即 11/11 PASS。判读建议：
看到 C6 单独失败且 C2/C3/C5/C7 都 PASS 时，先重跑一次；若稳定复现再当回归查。
（这是夹具时序问题，**本轮按范围不改脚本**，只在此登记。）

---

## 6. 一轮完整命令（可直接整段复制）

```bash
# 0) 仓库根目录，分支 test/v2-F3-integration-rescan
cd <repo>

# 1) 库（mysqld 另开一个终端按 §2.1 常驻）
MYSQL="C:/Project/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe"
"$MYSQL" --no-defaults -h127.0.0.1 -P3306 -uroot --default-character-set=utf8mb4 < db/schema.sql
"$MYSQL" --no-defaults -h127.0.0.1 -P3306 -uroot --default-character-set=utf8mb4 find_it_campus < db/seed.sql

# 2) 后端（另开一个终端常驻）
java -jar server/target/find-it-campus-0.0.1-SNAPSHOT.jar > qa/reports/server.log 2>&1
curl -s http://localhost:18080/api/health     # data.db 必须是 UP

# 3) 组1 / 组2 / 组3 / 组4
bash qa/f3r/phone-scan-28.sh
bash qa/f3r/cache-headers.sh > qa/reports/f3r-cache-headers.txt
node qa/f3r/log-injection.mjs
node qa/f3r/state-race.mjs

# 4) 组5（prepare → 带 token 重启 → verify → startup）
node qa/f3r/expired-scan.mjs prepare
netstat -ano -p TCP | grep ':18080' | grep LISTEN          # 取 PID
MSYS_NO_PATHCONV=1 taskkill /PID <PID> /F
export OPS_SCAN_TOKEN="$(openssl rand -hex 8)"
java -jar server/target/find-it-campus-0.0.1-SNAPSHOT.jar >> qa/reports/server.log 2>&1
curl -s http://localhost:18080/api/health
node qa/f3r/expired-scan.mjs verify
node qa/f3r/expired-scan-startup.mjs

# 5) 附加组
node qa/f3r/p1-bypass.mjs

# 6) 还原被复跑覆盖的证据文件（可选，仅当你要把工作树还原干净）
git checkout -- docs/testing/v2/F2-phone-scan.txt qa/reports/f2-phone-scan.txt qa/reports/f2-phone-scan.json
```

耗时参考：组1 ≈ 20 s、组2 ≈ 30 s、组3 ≈ 30 s、组4 ≈ 90 s（含 20 轮裸并发）、组5 ≈ 3 min（含两次重启）、附加组 ≈ 7 min（8 类 × 8 出口 + 对照组）。串起来一轮约 12–13 分钟。

---

## 7. 环境变量一览

| 变量 | 默认值 | 谁用 | 说明 |
|---|---|---|---|
| `FINDIT_API_BASE` | `http://localhost:18080` | 组1/3/4/5/附加 | 后端基址 |
| `FINDIT_SERVER_LOG` | `qa/reports/server.log` | 组3、组5 | 后端 stdout 落盘位置，必须与 §3.2 一致 |
| `MYSQL_BIN` | `C:/Project/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe` | 组4/5/附加（EV-SQL 与夹具） | mysql 客户端 |
| `MYSQL_USER` / `MYSQL_PASSWORD` / `MYSQL_DB` | `root` / 空 / `find_it_campus` | 同上 | 本机 root 空密码 |
| `OPS_SCAN_TOKEN` | 无 | 组5 | 非空才启用 `POST /api/ops/pair-scan`；**不要写进任何证据文件或文档** |
| `JAVA_BIN` | `C:/Project/tools/jdk17/jdk-17.0.20.1+1/bin/java.exe` | 组5 两个脚本 | 重启后端用 |
| `API` / `PAIR_ID` | `http://localhost:18080` / `1` | 组2 | `cache-headers.sh` 自己的变量 |

---

## 8. 常见坑（都是本轮实际撞过的）

| 现象 | 原因 | 处理 |
|---|---|---|
| `mysqld: Failed to open required defaults file ... my.ini` | 该安装目录没有 `my.ini` | 按 §2.1 显式给 `--basedir` / `--datadir` |
| `taskkill` 报 `无效参数/选项 - 'C:/Program Files/Git/PID'` | Git Bash 把 `/PID` 当路径重写 | `MSYS_NO_PATHCONV=1 taskkill /PID <pid> /F` |
| 请求返回 `400` / `code=10001`「Invalid UTF-8 middle byte」 | 命令行 `-d` 带中文，Git Bash 按本机代码页送出 | 用文件传 body（组2 就是这么做的），不要 `curl -d '{"text":"中文"}'` |
| 日志里中文读不出来 / 关键字匹配不到 | 日志按 GBK 写出 | 用 `TextDecoder('gbk')` 解码；关键字只用 ASCII |
| 组1 跑完 `git status` 多了 `docs/testing/v2/F2-phone-scan.txt` | 组1 复用 F2 脚本，覆盖了 F2 证据 | `git checkout --` 还原（见 §4.1） |
| 组5 startup 退出码 `2` | 找不到 18080 监听进程 / 90 s 内没探活成功 | 先按 §3.2 起后端再跑 |
| 组5 verify 报读不到 fixtures | 没跑 `prepare`，或换了库/重置过 seed | 重跑 `prepare` |
| 组4 C6 偶发「指纹发生变化」 | 夹具在异步打分落地前就被冻结指纹（见 §5） | 重跑一次；稳定复现才当回归 |

---

## 9. 这批资产证明什么、不证明什么

**证明**（2026-10-28 那轮，归档见 §0 表；2026-10-29 校验轮用同一套命令复跑，除组4 C6 一次夹具抖动外全组退出码 0）：

- P1 遮蔽：28 探针 0 命中；八类绕过在 8 个 HTTP 出口 0 可读，对照组 5/5 不误伤；
- P2-2 缓存头：25 条全 PASS，200/401/403/404/405 都带 `no-store` + `Vary: Cookie`；
- P2-3 日志注入：9 条通路 0 伪造整行；
- P2-1 终态竞态：11 条 PASS，含 SQL 反证与真并发编排；
- P2-4 过期扫描：8 + 4 条 PASS，两个触发口径一致。

**不证明**（同一轮如实登记的限制，别把这份资产读成「零缺陷」）：

- 只跑了**降级路径**（无 `DEEPSEEK_API_KEY`）；「有 key 时过期记录同样不重算」缺实测；
- R-1（`,` `:` `;` 分隔符绕过）三例仍是 HIT，只测不改，是否升 P1 待人类裁决；
- 组2 有 1 条非阻塞观察：`/api/pairs/{id}/contact` 的 `Cache-Control: no-store` 出现两次（拦截器 + controller 各一次），语义无害；
- 组3 里 J1/J1b 是 Spring binder 默认剥空白挡住的，不是 LogSafe —— 保护强度依赖 binder 配置；
- 组4 的裸并发（B1）内部时序不可外部观测，「窗口真实存在」的可复现证明只在 R1 行锁编排那一条；
- 没做跨浏览器、性能、可访问性结论。

---

## 10. 参考

- 复验报告：`docs/testing/v2/F3-integration-rescan.md`（含 §11 改动面自证：`docs/testing/` + `qa/` 之外 0 文件）
- 安全审查原始口径：`docs/testing/v2/security-review.md`
- 回归资产总览：`qa/README.md`（`qa/f3r/` 一节）
- 数据层与账号：`db/README.md`、`db/seed.sql`
