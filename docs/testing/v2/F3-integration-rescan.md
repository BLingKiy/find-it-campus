# v2 · F3 集成复扫（F3-R）：P1/P2 修复后的真实 HTTP 级复验

- 任务：ZHYYKFX-74（`afee0afa-901b-4f1f-a44e-f5c5709c3547`）· 执行者 端测测（`5d43311d`）· 日期 2026-10-28
- 分支：`test/v2-F3-integration-rescan`（从 `64693ec` 切出）
- **本轮不改任何业务代码**：`git diff --stat 64693ec..HEAD` 只含 `docs/testing/v2/` 与 `qa/`（见 §11）
- 原始输出：`docs/testing/v2/F3-integration-rescan/`（下文每项标注对应文件名）

---

## 0. 环境与前置

| 项 | 值 | 取证方式 |
|---|---|---|
| 工作树 HEAD | `64693ec75da079cb52d36d40c4dc61c93cf6b66b`（= `origin/feat/v2-integration`） | `git rev-parse HEAD` |
| 基线祖先关系 | `08f4bb9`(P1) / `fbd538f`(P2) / `a64639e`(报告) / `941fdee`(F2) 全部 `merge-base --is-ancestor` 命中 | 4 条 `git merge-base --is-ancestor` 退出码 0 |
| `main` | 仍在 `21784f67`，本轮未推未合 | `git rev-parse main` |
| 后端产物 | `server/target/find-it-campus-0.0.1-SNAPSHOT.jar` · 28,595,191 字节 · mtime 2026-10-28 14:38:39 +0800 | `stat` |
| jar 来自修复后基线 | 基线合并提交时间 14:11:54 早于打包时间 14:38:39；且 jar 内含修复类 `common/log/LogSafe.class`、`pair/config/PairScanOnceRunner.class`、`pair/web/OpsPairScanController.class` | `jar tf` grep |
| JDK | Temurin `openjdk 17.0.20.1`（build 17.0.20.1+1）64-Bit | `java -version` |
| MySQL | `8.0.29`，本机独立实例 `C:/Project/tools/mysql/mysql-8.0.29-winx64`，库 `find_it_campus`，端口 3306 通 | `SELECT VERSION()` |
| API 基址 | `http://localhost:18080`（`application.yml:11 port: 18080`） | `netstat -ano` 有 LISTENING |
| 后端日志 | `qa/reports/server.log`，**控制台 pattern 按本机代码页写出为 GBK**，脚本用 `new TextDecoder('gbk')` 解码，关键字检索只用 ASCII | 项5 实测（§5） |
| 打分配置①（本轮实际状态） | `DEEPSEEK_API_KEY` **未设置**（application.yml 刻意无 api-key 键，密钥只走 env）⇒ 全部走 `LOCAL_KEYWORD` 降级 | 每条扫描日志 `degradedReason=EMPTY_KEY`、`counters.llmCalls=0` |
| 打分配置②（未能执行） | 有真实 key 的 LLM 路径 —— 本机无密钥，**未测**，见 §10 | — |
| 扫描参数 | `completed_retention_days: 30`、`stale-minutes: 10`、`scan-limit: 500`、`max-llm-calls: 500`、`scan-cron: '0 30 3 * * *'` | `application.yml:101-111` |
| 演示身份 | anon / demo01 / demo02 / demo03（`Demo@123456`），每身份独立 Cookie | `qa/lib/client.mjs` `contexts()` |
| ops 入口 | `POST /api/ops/pair-scan` 需 env `OPS_SCAN_TOKEN` + 头 `X-Ops-Token`；本机用一次性临时 token，**值不写入任何证据文件与本文**（全库 grep 0 命中） | §7 |
| 每轮前置 | 涉及写库的用例各自先 `resetSeed()`（`db/schema.sql` + `db/seed.sql`），跑完再 reset 一次 | 各报告首行 |

**环境巡检不重做**：调度达已确认 06:22Z 的只读巡检结论照收，本文只补「跑测试时才需要」的事实（jar 是否重打包、日志编码、两态配置）。

---

## 1. 结论速览（任务正文 1–8 逐项一行读数）

| # | 任务项 | 读数 | 判定 |
|---|---|---|---|
| 1 | `qa/run-all.mjs` 五阶段 | 五阶段退出码全 0；接口 **111/111**、E2E **25/25**、一致性 **9/9**、落库取证 **19/19**、match P1 **10/10** + P2 **6/10**（4 条为 F1/F2 同事件另一侧既有观察，脚本仍 exit 0）—— 与 F2 持平，**0 新增失败** | 通过 |
| 2 | `qa/api/f2-scan.mjs` 28 探针在修复后基线复跑 | 28 探针 / **0 命中**（正则 `1[3-9]\d{9}`） | 通过 |
| 3 | P1 八类绕过在 HTTP 出口的实测 | 8 类 × 8 个出口组 = **128 条断言全 PASS**；privacy 方向（匿名/非所有者/通知/match 对端/落库）完整号码与原文保留双 0；「本人自有内容原文回显」按契约 §4.2/§4.12 属设计内；无过遮蔽对照 **5/5** 符合预期 | 通过 |
| 4 | P2-2 缓存头 5 端点 + 401 短路 + contact 不回退 | 20 条用例 **FAIL=0**；`Cache-Control: no-store` 与 `Vary: Cookie` 在 200/401/403/404 四类响应上都在；401 短路也带全两键；`/api/pairs/{id}/contact` 未回退 | 通过（1 条观察项 §4.3） |
| 5 | P2-3 日志注入 | 9 条通路 × 各自独立假时间戳 ⇒ **判据 A 全为 0 行**、判据 B 独立行 **0**；LogSafe 四档替换与 `...[324chars]` 截断在真实日志里逐条可见 | 通过（含分层归因） |
| 6 | P2-1 状态竞态（HTTP + EV-SQL） | 11/11 PASS；终态 PUT/PATCH **409/20003、20002** 且 16 列指纹逐字不变；条件更新 `ROW_COUNT()=0` vs 无条件 `=1` 的 SQL 反证成立；**真并发已做到**：InnoDB 行锁编排让 PUT 阻塞 2648 ms 后仍被拒 + 20 轮裸并发分布 | 通过 |
| 7 | P2-4 过期扫描 | 两个触发口径都跑：`--pair-scan-once` 启动扫描 **4/4 PASS**、ops HTTP 扫描 **8/8 PASS**；过期 COMPLETED 的 `pair_state/pair_scored_at/updated_at` 一字未动、pair 24→24、通知 0→0；正对照 X3/X4 确实被扫到（pair 0→20） | 通过 |
| 8 | R-1 逗号/冒号/分号（只测现状） | **三例全部命中**（`138,1234,5678` / `138:1234:5678` / `138;1234;5678` 在匿名详情、非所有者详情、快照、附言仍可整段读出，rawKeep=1）；本轮未改任何规则 | 现状已如实记录，**是否升 P1 由人类在门禁 3 裁决** |

一句话：**F3 的 P1 + P2 四项修复在真实 HTTP + 真实 MySQL 下都站住了；R-1 是同一根因的剩余变体，只测不改。**

---

## 2. 项1 · 全量回归 `qa/run-all.mjs`

命令：

```bash
node qa/run-all.mjs            # 五阶段：api / match / e2e / db / consistency
```

输出节选（完整见 `F3R-01-run-all.txt`，14:44 那轮）：

```text
=== 汇总 ===
| 阶段 | 退出码 | 结论 |
| 接口测试（node qa/api/run.mjs） | 0 | 通过 |
| 匹配效果评估（node qa/match/evaluate.mjs） | 0 | 通过 |
| E2E 走查（node qa/e2e/walkthrough.mjs） | 0 | 通过 |
| 落库取证（node qa/db/evidence.mjs） | 0 | 通过 |
| 跨页一致性（node qa/e2e/consistency.mjs） | 0 | 通过 |

- 接口：111 条，通过 111，失败 0
- E2E：25 条，通过 25，失败 0
- 一致性：9 条，通过 9，失败 0
落库取证 19/19 关键项：
| t_pair_score 行数 = 唯一 (lost,found) 对数 | 通过 | rows=784 distinct=784 |
| t_record 的 status/completed_at 双向绑定 | 通过 | violations=0 |
| t_user 无 phone/mobile/contact 列 | 通过 |
| t_access_log 无 contact/note 列（审计不复制敏感值） | 通过 |
```

match 评估（同文件）：

```text
| top1 命中率 | 10/10（100%） | 6/10（60%） |      ← P1 产品路径 / P2 不限类型对照
| top3 命中率 | 10/10（100%） | 10/10（100%） |
P2 不通过 4 条：S2/S3/S4/S6，原因均为「不带 type 时同事件另一侧记录排到 top1，期望目标仍在 top3」
```

与 F2 对照（`F2-integration-report.md:15,106-111`）：**111/25/9/19 全等**，P2 的 4 条观察项与 F1/F2 完全同一组（S2/S3/S4/S6），不是本轮新增。`t_pair_score` 绝对行数 784 vs F2 的 785 差 1：本轮 E2E 走查创建的记录 id 不同（109 vs F2 那轮），断言的是「行数 = 唯一对数」这个不变量，仍成立。

判定：**通过，0 新增失败**。P2 的 4 条不写成通过。

---

## 3. 项2 · 手机号泄漏全局扫描（修复后基线复跑）

命令：

```bash
node qa/api/f2-scan.mjs        # 前置：后端 18080 + 库为 seed 初始态
```

输出节选（`F3R-02-phone-scan.txt`）：

```text
# F2 手机号泄漏全局扫描 · 2026-10-28T06:45:06.285Z
- 正则：1[3-9]\d{9}
- 载体：匿名响应体 / 非本人响应体 / 通知 title+body
- 去噪：扫描前剔除 requestId（16 位随机十六进制，会偶发撞出号码形状，本轮实际撞到 1 次）
- 探针：28 个；命中：0
- 结论：PASS（0 命中）
```

28 条逐条为 `PASS ... 命中 0`，覆盖：匿名列表 2 页、匿名详情 11 个 id、匿名 `POST /api/match`（401/10006 也扫）、非所有者详情 6 个 id、双方 `/api/match` 候选、三账号通知正文 + title/body 字段（5/2/1 条）。

判定：**通过**。这条扫描时间在 14:38 重打包之后，落在修复后基线上。

---

## 4. 项3 · P1 八类绕过输入的 HTTP 出口实测

命令：

```bash
node qa/f3r/p1-bypass.mjs      # 每条用例自己 resetSeed()，造一对可配对记录后跑全部出口
```

设计（`F3R-03-p1-bypass.txt` 头部）：

```text
- A 侧号码归一=13812345678 / B 侧号码归一=13923456789；证件号 A=110101199001011234 B=110202199002021234
- 两侧演示账号 contact 固定 13800000001 / 13800000002，与绕过串不同值，避免「本人可见自己 contact」混入判定
- 判据：privacy 方向（匿名/非所有者/通知/match 对端/落库）exact 与 rawKeep 双 0；
        self 方向（本人自有内容）只判「不得出现对端号」
```

- **写入位置**：`title`、`description`、取号附言（落 `t_pair_score.lost_unlock_note/found_unlock_note`）三处都写。
- **读出出口**：① 匿名列表 ② 匿名详情 ③ 非所有者详情 ④ `/api/my/records` 本人集合 ⑤ `/api/notifications` ⑥ `POST /api/match` 的 `items[].title/reason` ⑦ owner 详情 `pairs[].counterpartTitle/myNote` + `contact-unlock` 响应 `note` ⑧ 落库层 `t_match_result` 快照与 `t_pair_score` 附言。
- **八类输入**：B1 空格拆 / B2 换行拆 / B3 制表符拆 / B4 连字符拆 / B5 零宽字符拆 / B6 全角空格拆 / B7 `+86` 前缀 / B8 证件号分段。

输出节选（B1）：

```text
### B1 空格拆｜A 写入="138 1234 5678"｜B 写入="139 2345 6789"｜lostId=27 foundId=28 pairId=34
  PASS  出口2 匿名详情 GET /api/records/{lostId}  [privacy]
         GET http://localhost:18080/api/records/27 → HTTP 200 ｜ title+description 遮蔽视角
         - A(demo01) 138␣1234␣5678: exact=0 rawKeep=0
         - B(demo02) 139␣2345␣6789: exact=0 rawKeep=0
  PASS  出口4 GET /api/my/records(demo01 本人集合)  [self]
         ! A(demo01) 138␣1234␣5678: exact=0 rawKeep=1 样本="F3RB1L 拾到物品 请联系 138 1234 5678"
         本人自有内容原文回显=是（契约 §4.2/§4.12 设计内）
  PASS  出口8 t_pair_score 附言落库  [privacy]
         SQL SELECT CONCAT(IFNULL(lost_unlock_note,'∅'),' || ',IFNULL(found_unlock_note,'∅')) FROM t_pair_score WHERE id=34;
         ｜ A 侧附言:我的号码 [手机号已隐藏]，随时打 || B 侧附言:我的号码 [手机号已隐藏]，随时打
```

汇总：

```text
## 汇总
- 八类绕过：8/8 越权/对端/落库方向 0 可读
- 对照组：5/5 符合预期
```

判定：**通过**。128 条出口断言 0 失败；本人自有内容原文回显是契约内行为，没有算成泄漏，也没有算成失败。

### 4.3 无过遮蔽对照（同一脚本内的 C 组）

```text
  PASS  C1 连续 11 位｜写入="13812345678"          → 匿名详情 title="… 请联系 [手机号已隐藏]"        遮蔽=true
  PASS  C2 全角 11 位｜写入="１３８１２３４５６７８"  → 匿名详情 title="… 请联系 [手机号已隐藏]"        遮蔽=true
  PASS  C3 裸 8 位学号｜写入="学号 20230101"        → 原文保留，rawKeep=1，未被误伤                  遮蔽=false
  PASS  C4 楼栋房间号｜写入="3 号楼 201 房间"       → 原文保留，rawKeep=1，未被误伤                  遮蔽=false
  PASS  C5 日期时间｜写入="2026-10-21 13:30"        → 原文保留，rawKeep=1，未被误伤                  遮蔽=false
```

判定：**5/5 符合预期** —— 该遮的遮（连续/全角 11 位），不该遮的逐字保留（学号、楼栋房间号、日期时间）。

---

## 5. 项4 · P2-2 缓存头（原始 `curl -i`）

命令：

```bash
bash qa/f3r/cache-headers.sh > qa/reports/f3r-cache-headers.txt   # 全部用原始 curl -i，不看封装客户端
```

覆盖 5 个端点 × 多身份 × 多状态码，共 20 条断言。逐条原文（`F3R-04-cache-headers.txt` 汇总表）：

```text
| PASS | 200 | A1 匿名 GET /api/records/1（200 遮蔽详情）   | Cache-Control: no-store | Vary: Cookie;Vary: Origin … |
| PASS | 200 | A2 本人 GET /api/records/1（200 明文详情）   | Cache-Control: no-store | Vary: Cookie …             |
| PASS | 200 | A3 非所有者 GET /api/records/1（200 遮蔽详情）| Cache-Control: no-store | Vary: Cookie …             |
| PASS | 200 | B1/B2 GET /api/my/records（两种身份）        | Cache-Control: no-store | Vary: Cookie …             |
| PASS | 200 | C1/C2 GET /api/notifications（两种身份）     | Cache-Control: no-store | Vary: Cookie …             |
| PASS | 200 | D1/D2 POST /api/match（两种身份）            | Cache-Control: no-store | Vary: Cookie …             |
| PASS | 200 | E1 参与者 GET /api/pairs/{id}/contact（不回退）| Cache-Control: no-store;Cache-Control: no-store |
| PASS | 200 | E2 对方   GET /api/pairs/{id}/contact（不回退）| Cache-Control: no-store;Cache-Control: no-store |
| PASS | 401 | E3 匿名 GET /api/pairs/{id}/contact（401 短路带头）| Cache-Control: no-store | Vary: Cookie |
| PASS | 404 | E4 不存在 pairId /api/pairs/999999/contact   | Cache-Control: no-store | Vary: Cookie …             |
| PASS | 401 | F1/F2/F3 匿名 my/notifications/match（401 短路）| Cache-Control: no-store | Vary: Cookie        |
| PASS | 404 | F4 匿名 GET 不存在的记录（404 统一体）        | Cache-Control: no-store | Vary: Cookie …             |
| PASS | 200 | G1 匿名 GET /api/records（公共列表）          | Cache-Control: no-store | Vary: Cookie …             |
| PASS | 403 | H1 非所有者 DELETE /api/records/1（越权短路） | Cache-Control: no-store | Vary: Cookie …             |
| PASS | 200 | H2 匿名 GET /api/health（/api/** 前缀覆盖）   | Cache-Control: no-store | Vary: Cookie …             |

断言用例数=20，FAIL=0
```

401 短路原文（证明「未进 controller 也带上了两键」）：

```text
HTTP/1.1 401
X-Request-Id: 98d4a7ef9ce14839
Cache-Control: no-store
Vary: Cookie
…
{"code":10006,"message":"请先登录","data":null,"requestId":"98d4a7ef9ce14839"}
```

判定：**通过（20/20）**。

### 4.3 观察项（不是回归，记一笔）

`/api/pairs/{id}/contact` 的成功响应里 `Cache-Control: no-store` 出现**两次**（一次在拦截器位置、一次在响应尾），原始 curl 可见：

```text
X-Request-Id: 9bac1fa4ab1f4bc7
Cache-Control: no-store
Vary: Cookie
… 安全头 …
Vary: Access-Control-Request-Headers
Cache-Control: no-store          ← 第二处
Content-Type: application/json
```

两值语义相同（都是 `no-store`），缓存行为不受影响，所以判 PASS；但这是同一响应里重复设头，来源是「拦截器 + 该 controller 方法各设一次」。是否清理属业务代码改动，**本轮不改**（禁改业务代码），留给门禁 3 决定是否开单。

---

## 6. 项5 · P2-3 日志注入（HTTP 打到真实日志文件）

命令：

```bash
node qa/f3r/log-injection.mjs   # 前置：后端 18080 已起，日志文件 FINDIT_SERVER_LOG（默认 qa/reports/server.log）
```

做法（`F3R-05-log-injection.txt`）：每条通路塞一条**形状完整**的假日志行（时间戳+级别+线程+logger+requestId+文案），且**每通路用互不相同的假时间戳** `23:59:51…59`，跑完只读日志增量：增量里出现该假时间戳开头的行 ⇒ 真多出一整行 ⇒ FAIL。关键字只用 ASCII（`FORGED_Jx`），因为日志文件是 GBK，grep 中文不可靠。

9 条通路：

```text
- J1 ｜查询参数 page 含 %0A（Integer 绑定失败 → handleTypeMismatch/handleConstraintViolation）
- J1b｜查询参数 pageSize 含 %0D%0A
- J1c｜sortBy 含 %0A（@Pattern 白名单先挡）
- J1d｜路径参数 /api/records/{id} 含 %0A（@PathVariable long）
- J1e｜路径参数含 CRLF + TAB + U+2028 + 200 字符超长（补齐 LogSafe 四档替换与截断）
- J3 ｜请求体非法 JSON，串内含 %0A（detail 限长 512）
- J3b｜LF 放进 JSON 字符串值内部（未转义控制字符）→ Jackson 报错原文
- J2f｜登录 studentNo 含 LF（≤32 字符，真正到达 AuthService 日志行）
- J4 ｜查询参数 keyword 含 LF（String 型、校验通过 → 考察 RequestIdFilter 的访问日志行）
```

判据结果：

```text
- 注入后日志大小：698989 字节（增量 6269 字节 / 27 行）
### 判据 A：以各通路假时间戳开头的行数（>0 即真的多出一整行）
  PASS  J1 23:59:51 开头 0 行     PASS  J1b 23:59:52 开头 0 行   PASS  J1c 23:59:53 开头 0 行
  PASS  J1d 23:59:54 开头 0 行    PASS  J1e 23:59:59 开头 0 行   PASS  J3 23:59:55 开头 0 行
  PASS  J3b 23:59:58 开头 0 行    PASS  J2f 23:59:56 开头 0 行   PASS  J4 23:59:57 开头 0 行
### 判据 B
- 以 FORGED_xx 起头的独立行数：0（应为 0）
- 含 FORGED_xx 的行数：6（应全部是「真实日志行里被净化后的字段」）
```

LogSafe 四档替换在真实日志里的原文痕迹：

```text
2026-10-28 15:19:13.120 WARN … GlobalExceptionHandler - [0bab769abcc54ff5] 参数类型不符: field=id value=1_2026-10-28 23:59:54.111 WARN  [forged-thread] … FORGED_J1d 登录成功 userId=9999
2026-10-28 15:19:13.127 WARN … 参数类型不符: field=id value=9___?2026-10-28 23:59:59.111 WARN … FORGED_J1e 登录成功 userId=9999CCCC...[324chars]
2026-10-28 15:19:13.264 WARN … AuthService - [ef96c7771a5240ad] 登录失败 studentNo=P_2026-10-28 23:59:56.111 INFO ip=… reason=STUDENT_NO_NOT_FOUND consecutiveFailures=5

- LF → 下划线（value=1_… / studentNo=P_…）：2 行
- CRLF+TAB → 三个下划线（J1e 的 value=9___?…）：1 行
- U+2028 → ?（J1e）：1 行
- 超长截断并标注原长：...[324chars]
- 访问日志行（RequestIdFilter）共 13 条，其中把 LF 记成字面 %0A 的：2 条（URL 是编码态写出，天然无换行）
```

分层归因（同一句「没有伪造行」，靠哪一层挡住的差别很大，逐条实测而不是猜）：

```text
- 绑定层 WebDataBinder 剥空白（出口：`For input string:`）：2 行，其中值内仍含 CR/LF/TAB 的 0 行
    → J1/J1b 的换行是被**绑定层**（默认移除字符串里所有空白）吃掉的，没有走到 LogSafe；
      该行的 reason 是 Spring 原文，确实把入参值写进了日志（只是值已无空白）。
- LogSafe.sanitize 实际生效并可见下划线：3 行（J1d/J1e 路径参数 + J2f 登录学号）
- @Pattern 取值白名单先挡（J1c sortBy）：1 行，其中携带注入原文的 0 行 → 固定文案，用户串未入日志
- 请求体不可解析出口（J3/J3b）：2 行，其中携带注入原文的 0 行
```

对照组（校验层与 LogSafe 是两层不同防御）：

```text
- J2a 裸 LF + 完整假行（>32）：126 字符 → HTTP 400 / code=10001 / reason="学号长度需在 1 到 32 之间"
- J2b CRLF + TAB + 假行（>32）：133 字符 → 同上
- J2c U+2028（>32）：141 字符 → 同上
- J2d 300 字符 + 换行：426 字符 → 同上
```

判定：**PASS（9 条通路均无伪造整行）**。

需要如实记下的一条：J1/J1b 之所以安全，靠的是 **Spring 绑定层默认剥空白**，不是 LogSafe。这条出口写进日志的是 Spring 原文（含入参值）。当前无换行所以不成立注入，但如果哪天关掉 `trimWhitespace` 或换成不用 binder 的入口，这层保护就没了 —— 属分层防御的强度说明，不是本轮缺陷。

---

## 7. 项6 · P2-1 状态竞态（HTTP + EV-SQL，含真并发）

命令：

```bash
node qa/f3r/state-race.mjs     # 跑完自己 resetSeed()
```

关键手法：`updated_at` 先冻结成哨兵 `2026-10-20 12:00:00`（任何写入都会让它前进），再用 `CONCAT_WS(0x1F, 16 列)` 做整行指纹比对；并发用 **InnoDB 行锁编排**制造可控交错（外部会话 `FOR UPDATE + DO SLEEP(3)` 占锁，让 HTTP PUT 的快照读必然早于其自身执行）。

结果 11/11：

```text
C1 控制组：PENDING 记录 PUT → 200 且确实写入（指纹变化）
C2 COMPLETED PUT   → 409 / code=20003，16 列指纹 167 字符逐字相同
C3 CLOSED   PUT    → 409 / code=20003，指纹 139 字符逐字相同
C4 软删后   PUT    → 404 / code=20001
C5/C6 终态→终态 PATCH → 409 / code=20002
C7 匿名 PUT → 401/10006；非所有者 PUT → 404/20001（不泄漏 20003，不暴露记录存在性）
S1 SQL 反证：带守卫条件 UPDATE 的 ROW_COUNT()=0；把条件摘掉的同型 UPDATE ROW_COUNT()=1 且 title 变成 UNGUARDED-WRITE、updated_at 前进
```

真并发两条（这是任务里「打不出真并发就如实写」的那一项）：

```text
PASS  R1 锁编排：PUT 应因条件更新返回 409/20003，正文零写入，且 PUT 确实被行锁阻塞过
        PUT：HTTP 409 / code=20003 / message="记录当前状态不可执行该操作" / 耗时 2648 ms（>1200 ms 才说明真在等行锁）
        A 会话（持锁）：耗时 3403 ms      C 会话（改 CLOSED）：耗时 3237 ms
        终态行：status=CLOSED title="QA F3R-R1 锁编排竞态原正文" updated_at=2026-10-28 15:23:42.000000
R2 控制组：同编排但无并发终态提交 → PUT 等锁后 200 成功（耗时 3201 ms），排除「阻塞导致失败」的解释

## ⑤ 裸并发：PUT 与 PATCH(CLOSED) 同时发出，跑 20 轮看结局分布
    11 × PUT=200/0 × PATCH=200/0
     9 × PUT=409/20003 × PATCH=200/0
PASS  B1 裸并发 20 轮：终态记录未出现「PUT 被拒却仍写入正文」
        终态行被后写签名次数=0；无「拒绝但已写入」「成功但流转失败」的不一致轮次
```

诚实边界（脚本原文照抄，不粉饰）：

```text
诚实说明：两条请求确实在服务端同时在飞（同一 tick 发出），但「PUT 的快照读发生在 PATCH 提交之前」
这一点对裸并发不可外部观测，重叠窗口是否命中只能由上面的分布推断；可复现的窗口证明在 R1 锁编排那一条。
```

结论原文：

```text
- 断言项：11；失败：0
- P2-1 复验通过：终态 PUT 一律 409/20003（或非所有者 404/403、匿名 401），库里零写入；
  条件更新在真实锁编排下挡住了窗口，SQL 层反证也说明没有条件时窗口会被打穿。
```

判定：**通过，且做了真并发**（锁编排 R1/R2 + 20 轮裸并发）。限制只在 B1 的内部时序不可外部观测，已按原文写明。

---

## 8. 项7 · P2-4 过期扫描（两个触发口径）

命令：

```bash
node qa/f3r/expired-scan.mjs prepare     # 造夹具（先 resetSeed）
# 重启后端：带 --pair-scan-once 与 OPS_SCAN_TOKEN（token 值不入证据）
node qa/f3r/expired-scan.mjs verify      # ops HTTP 扫描 + EV-SQL 对照，8 条判据
node qa/f3r/expired-scan-startup.mjs     # 杀 18080 → --pair-scan-once 重启 → 抓启动扫描汇总行
```

夹具（`F3R-07-expired-prepare.txt`）：

```text
- 夹具：X1=27 X2=28 X3=29 X4=30（对照） C1=31 C2=32（对手端）
- 已等待创建路径的异步打分落地（READY/DEGRADED），之后写入的夹具状态不会被它覆盖
- X1/X2 置为 COMPLETED 且 completed_at = now-31d（超过保留期 30d）；pair_state 分别为 DEGRADED / PENDING
- X3 为保留期内 COMPLETED（now-1d, PENDING）、X4 为 PENDING+DEGRADED —— 两者是「扫描确实在跑」的正对照
- 预置 pair 行 id=66 score=0.8200 status=MATCHED lost_hash=ffffffffffff…（故意与当前内容不一致：若扫描真的重算了 X1，哈希与 updated_at 一定会变）
```

① 启动扫描（`PairScanOnceRunner`，需启动参数 `--pair-scan-once`）原文：

```text
2026-10-28 15:38:06.634 INFO … PairScanService - [] pair scan done trigger=startup scanned=5 rescored=14 degraded=5 skipped=36 expired=3 limited=false elapsedMs=4978 counters={llmCalls=0, llmSuccess=0, degraded=14, skippedUnchanged=36, pairsWritten=14, enqueueRejected=0, lockMisses=0, degradedByReason={EMPTY_KEY=14}}
2026-10-28 15:38:06.634 INFO … PairScanOnceRunner - [] pair scan once done scanned=5 rescored=14 …（同上）
```

② ops 扫描（`POST /api/ops/pair-scan` + `X-Ops-Token`）：

```text
- 不带 token：HTTP 404 / code=10003（未配置或无 token 都应是 404，不暴露入口存在）
- 第 1 次：{"scanned":5,"rescored":0,"degraded":5,"skipped":50,"expired":3,"limited":false,"trigger":"http",
           "counters":{"llmCalls":0,…,"degradedByReason":{"EMPTY_KEY":14}}}
- 第 2 次（幂等对照）：同 scanned=5 rescored=0 expired=3，skippedUnchanged 累加到 136
```

③ EV-SQL 对照（扫描前 → 扫描后）：

```text
- X1（过期+DEGRADED）：pair_state DEGRADED→DEGRADED，pair_scored_at 2026-09-27 15:37:48.000000→同，updated_at 同
- X2（过期+PENDING）：pair_state PENDING→PENDING，pair_scored_at null→null，updated_at 同
- X3（保留期内，对照）：pair_state PENDING→DEGRADED，pair_scored_at null→2026-10-28 15:38:17.000000  ← 确实在跑
- X4（PENDING+DEGRADED，对照）：pair_scored_at 2026-10-28 13:37:49→15:38:18                          ← 确实在跑
- 端点为过期记录的 pair 行数：24 → 24；通知数：0 → 0
- 端点为对照记录的 pair 行数：0 → 20；全表 pair 行数 70 → 84；通知总数 8 → 8
- 预置 pair 行：score 0.8200→0.8200，status MATCHED→MATCHED，lost_hash ffffffff…→同，updated_at 15:37:49→同
```

⑤ SQL 层反证（同一个 WHERE，修复前后分别选中谁）：

```text
- 修复前条件选中的目标（含本轮全部夹具）：26,27,28,29,30,31,32
- 修复后条件选中的目标：26,29,30,31,32
- 过期夹具在「修复前」被选中：2 / 2；在「修复后」被选中：0 / 2
- 对照夹具在「修复后」仍被选中：2 / 2（29,30 = X3,X4）
```

判据（ops 口径 8 条）：

```text
PASS V1 过期夹具 X1/X2 未被重算：pair_state 与 pair_scored_at 一字不变（连 markPairScoring 都没进）
PASS V2 未新建 pair：端点为 X1/X2 的 t_pair_score 行数不增
PASS V3 预置 pair 行未被重算覆盖：score/status/lost_hash/updated_at 全部不变
PASS V4 未发通知：端点为 X1/X2 的通知数扫描前后都为 0
PASS V5 日志里没有 X1/X2 的打分痕迹（没有 record scored、也没有 written）
PASS V6 正对照成立：X3（保留期内 COMPLETED）与 X4（PENDING+DEGRADED）确实被扫到
PASS V7 SQL 层反证：修复前的 WHERE 会选中 X1/X2，修复后的不会，而对照仍在
PASS V8 ops 返回的 expired 计数与库内过期 COMPLETED 行数一致（只计数、不改写任何列）
```

启动触发单独取证 4 条（`F3R-08-expired-scan-startup.txt`）：U1 捕获到 `trigger=startup` 汇总行且 `expired=3` == EV-SQL 实数；U2 重启前后 X1/X2 整行指纹逐字相同；U3 过期端 pair 24→24、通知 0→0；U4 本轮新增的 5 行 `record scored` 与 `written` 命中 X1/X2 = 0。

一条踩坑记录（对结论无害但必须写）：**第一次重启没带 `--pair-scan-once`，启动扫描根本没跑**，脚本如实报「未捕获到 trigger=startup 的汇总行」。`PairScanOnceRunner.java:38-41` 用 `args.containsOption("pair-scan-once")` 判定，该参数不是默认行为。第二次重启补上参数才有上面 U1–U4 的证据。同理 ops 入口需要 env `OPS_SCAN_TOKEN`，未配置时 `OpsPairScanController` 直接 404。

判定：**通过**（ops 8/8 + startup 4/4，两个触发口径都验了）。

---

## 9. 项8 · R-1 逗号/冒号/分号（只测现状，不改规则）

命令与项3 同一条（R-1 三例是 `p1-bypass.mjs` 末尾的一段，没有为它加任何规则）：

```bash
node qa/f3r/p1-bypass.mjs
```

实测输出（`F3R-03-p1-bypass.txt:620-637`）：

```text
## R-1（逗号/冒号/分号）现状实测 —— 只记录命中与否，不改规则、不加正则
  HIT（现状仍可整段读出）  R1a 逗号拆｜写入="138,1234,5678"｜lostId=27 foundId=28 pairId=34
         匿名详情 description="…正文描述里的联系方式写作 138,1234,5678，请据此联系我。" exact=0 rawKeep=1
         非所有者详情 exact=0 rawKeep=1｜match items exact=0 rawKeep=0｜快照 exact=0 rawKeep=1
         附言落库="号码 138,1234,5678" exact=0 rawKeep=1
  HIT（现状仍可整段读出）  R1b 冒号拆｜写入="138:1234:5678"｜…（同上，四个出口 rawKeep=1）
  HIT（现状仍可整段读出）  R1c 分号拆｜写入="138;1234;5678"｜…（同上，四个出口 rawKeep=1）

- R-1 现状：R1a逗号拆=HIT ｜ R1b冒号拆=HIT ｜ R1c分号拆=HIT（不改规则；是否升 P1 追加修复由人类在门禁 3 裁决）
```

读数：**三例全部命中**——逗号/冒号/分号仍不在 `isDigitSeparator` 分隔符集合内，非所有者详情、匿名详情、`t_match_result` 快照、取号附言四处都能整段读出完整号码（`rawKeep=1`）。`match items` 出口是 `rawKeep=0`，因为那条路径本身走 `reason` 固定文案，不是因为规则覆盖了它们。

本轮**未做任何规则改动、未加正则**，与 `security-review.md` §18.3 R-1 的静态描述一致。是否把 R-1 升为 P1 追加修复，交门禁 3 由人类裁决。

---

## 10. 未做与限制（诚实清单）

1. **打分配置②未测**：本机没有真实 `DEEPSEEK_API_KEY`，`application-local.yml` 不存在、env 未设置。所有扫描日志都是 `degradedReason=EMPTY_KEY` + `counters.llmCalls=0` + `engine=LOCAL_KEYWORD`，这是配置①（无密钥降级）的直接证据，**不能当成 LLM 路径通过**。项7 的判据只依赖「是否被重算/是否新建 pair/是否发通知」，与走哪条打分引擎无关，所以结论仍成立；但「有 key 时过期记录同样不重算」这句本轮没有实测支撑。
2. **项6 的裸并发内部时序不可外部观测**：20 轮里 11 轮 PUT 成功、9 轮 PUT 被拒，但「PUT 的快照读发生在 PATCH 提交之前」无法从外部证明；可复现的窗口证明只在 R1 锁编排那一条。已按原文写明，没有写成「全部可观测」。
3. **项5 的一条分层事实**：J1/J1b 不产生伪造行靠的是 Spring 绑定层默认剥空白，不是 LogSafe；该出口日志里的值是 Spring 原文。当前安全，保护强度依赖 binder 配置。
4. **项4 的重复头观察**：`/api/pairs/{id}/contact` 的 `Cache-Control: no-store` 出现两次（拦截器 + controller 各一次）。语义不变，判 PASS，未改业务代码。
5. **R-1 未裁决**：只如实记录三例全 HIT，没有加规则、没有升降级。
6. **未推 `main`、未合并 `main`、未改任何业务代码**：本轮改动只在 `docs/testing/v2/` 与 `qa/`。
7. **`sk-` 9 处字面命中的假占位判定**不在本任务范围内，本轮未动相关文档。

---

## 11. 证据文件索引

| 文件 | 对应任务项 | 内容 |
|---|---|---|
| `F3R-01-run-all.txt` | 项1 | `node qa/run-all.mjs` 完整输出（五阶段 + 汇总 + 落库 19 项表） |
| `F3R-02-phone-scan.txt` | 项2 | 28 探针逐条 HTTP 状态码与命中数 |
| `F3R-03-p1-bypass.txt` | 项3 + 项8 | 8 类 × 8 出口组 = 128 断言 + C 组 5 条无过遮蔽 + R-1 三例现状 |
| `F3R-04-cache-headers.txt` | 项4 | 20 条原始 `curl -i` 响应头全文 + 判定表 |
| `F3R-05-log-injection.txt` / `.json` | 项5 | 9 通路、判据 A/B、净化痕迹原文、分层归因、GBK 增量日志尾 30 行 |
| `F3R-06-state-race.txt` / `.json` | 项6 | C1–C7、S1 SQL 反证、R1/R2 锁编排、20 轮裸并发分布 |
| `F3R-07-expired-scan.txt` / `.json` | 项7 | ops 扫描两次结果、EV-SQL 前后对照、修复前后 WHERE 反证、V1–V8 |
| `F3R-07-expired-prepare.txt`、`F3R-07-expired-fixtures.json` | 项7 | 夹具构造与扫描前快照（id、状态、时间戳、预置 pair） |
| `F3R-08-expired-scan-startup.txt` | 项7 | `--pair-scan-once` 启动扫描原文与 U1–U4 |

脚本：`qa/f3r/p1-bypass.mjs`、`qa/f3r/cache-headers.sh`、`qa/f3r/log-injection.mjs`、`qa/f3r/state-race.mjs`、`qa/f3r/expired-scan.mjs`、`qa/f3r/expired-scan-startup.mjs`（本轮新增，均只读接口 + 读写 seed 库，不含业务改动）。

复跑要点：所有脚本前置「后端 18080 已起 + 库为 seed 态」；`expired-scan` 必须 `prepare` 与 `verify` 分两次跑，中间用 `--pair-scan-once` + `OPS_SCAN_TOKEN` 重启后端；涉及日志的脚本需要 `FINDIT_SERVER_LOG` 指向真实控制台日志（默认 `qa/reports/server.log`）。
