# D1 配对打分 · 幂等 / 并发 / 兜底扫描 证据

任务：[ZHYYKFX-54](https://app.hifox.com/nhnok/task/ZHYYKFX-54) · 分支 `feat/v2-D1-pair-scoring` · 基线 `origin/feat/v2-integration @ 4cc90ce`

> 本文件只记录**可复现的实测输出**。所有数字都标注了来源（命令/环境）；凡是估算或需重测的，都在括号里写明。

## 1. 环境与构建

| 项 | 值 |
|---|---|
| 基线 | `git ls-remote` 实读 `feat/v2-integration = 4cc90ce`（与派工单一致），从它切 `feat/v2-D1-pair-scoring` |
| JDK / Maven | Temurin 17.0.20.1 / Maven 3.10.0（离线本地仓库 `m2repo`） |
| 构建 | `mvn -s m2-settings.xml -DskipTests package` → `find-it-campus-0.0.1-SNAPSHOT.jar` |
| 单测 | `mvn -s m2-settings.xml test` → **Tests run: 155, Failures: 0, Errors: 0**（基线 127 + 本任务新增 28） |
| 数据库 | MySQL 8.0.29，端口 3307，`db/schema.sql` + `db/seed.sql` 全量重建（7 表 / 24 条记录 / 14 条种子 pair） |
| 应用 | `java -jar ... --server.port=18082`，`DEEPSEEK_API_KEY` **未设置**（本机机器级/用户级/进程级三处均为空） |
| 本轮 DDL | **无变更**：B1 的 `t_pair_score` 已含 `engine/degraded_reason/latency_ms/prompt_tokens/completion_tokens/lost_hash/found_hash/*_confirmed_at`，D1 只写代码 |

复现脚本（本机执行，不随仓库分发）：`evidence-main.ps1`（幂等/IGNORED/双失主/扫描）、`concurrency-run.ps1` + `concurrency.js`（并发）、`llm-stub.js`（降级）。

## 2. 幂等键实现与交叉验证

实现：`PairContentHasher`（`engine/PairContentHasher.java`）

```text
hash = lower(hex(sha256( trim(title) + "\n" + trim(description) + "\n" +
                         trim(location) + "\n" + (categoryId ?? "") + "\n" + type )))
```

**与 B1 种子数据交叉验证**（不是自证）：取种子记录 `id=1`，按上式手算

```text
computed = bbe57bedb134d1e5116df136f7325100763799c054cd577929951f1cbffcb070
seeded   = bbe57bedb134d1e5116df136f7325100763799c054cd577929951f1cbffcb070   ← t_pair_score.lost_hash（id=1）
```

两者逐字节相同，说明 hash 口径与 B1 建种子时使用的算法一致；`PairContentHasherTest` 另外锁定
「title/description/location/category 任一变化必变、trim 不算变化、type 参与哈希」五条。

## 3. 验收逐条

### 3.1 同一 pair 重复触发 3 次：仍 1 行，LLM 调用计数不变

`t_pair_score` 的写路径只有一条 `upsertScore`，唯一键 `(lost_id, found_id)`；重算前先读旧行的
`lost_hash/found_hash`，未变直接跳过（**不调 LLM、不更新 `updated_at`**）。

同一对（LOST=27 ↔ FOUND=28，pairId=33）在 **进程内计数器** 上的前后对比（应用有 key、本机假端点稳定返回成功）：

| 时点 | llmCalls | pairsWritten | skippedUnchanged | pair 行数 | pair 33 updated_at |
|---|---|---|---|---|---|
| 触发前 | 44 | 44 | 260 | 1 | 2026-10-27 22:53:13 |
| 触发 1：PUT 相同内容 | — | — | — | — | — |
| 触发 2：`POST /api/ops/pair-scan` | — | — | — | — | — |
| 触发 3：`POST /api/ops/pair-scan` | — | — | — | — | — |
| 触发后 | **44** | **44** | 466 | **1** | **2026-10-27 22:53:13（未变）** |

> 三个入口（发布/编辑、兜底扫描、手动触发）都走同一个 `PairScoringService.scorePair`；`llmCalls`
> 从 44 没动过，pairsWritten 也没动，`skippedUnchanged` 增加 206 —— 这是"没有重复调用"的直接读数。
> 计数器是进程内原子数（`PairScoringMetrics`），进程重启归零；业务事实以 `t_pair_score` 行为准。

无 key 场景下的同样结论（降级分，`llmCalls` 恒为 0）：

```text
P2-before  : 33 | 27 | 28 | 0.9100 | NEW | 2026-10-27 22:49:34
scan #1    : {"scanned":3,"rescored":10,"degraded":3,"skipped":20,"expired":1,...,"llmCalls":0}
scan #2    : {"scanned":3,"rescored":0,"degraded":3,"skipped":30,"expired":1,...,"llmCalls":0,"skippedUnchanged":61}
P2-after   : 33 | 27 | 28 | 0.9100 | NEW | 2026-10-27 22:49:34
rows_for_pair: 1
```

> scan #1 的 `rescored=10` **不是**这对被重写：那一轮扫到一条种子记录 `pair_state=PENDING`
> 且从未打过分，10 条新 pair 是它的首次补算；pair 33 的 `updated_at` 前后完全相同，
> 行数仍是 1。scan #2 中 `rescored=0` 且 `skippedUnchanged` 从 31 涨到 61，才是本对的读数。

### 3.2 改标题：只重算涉及该记录的 pair，未变 pair 不动

改 LOST=27 的标题后（P3），涉及 27 的 11 行全部重写（10 行原有 + 1 行新入池的 27↔18）：

```text
P3-before（节选）                      P3-after（节选）
15  27 13 0.0300 NEW 22:49:32    →    15  27 13 0.0300 NEW 22:49:49
21  27 16 0.1700 NEW 22:49:33    →    21  27 16 0.1600 NEW 22:49:50
33  27 28 0.9100 NEW 22:49:34    →    33  27 28 0.8100 NEW 22:49:52
                                      49  27 18 0.0100 NEW 22:49:50   ← 新进 top10
控制行（与 27 无关，必须不变）：
 6   6  18 0.9600 CONTACTED 2026-10-24 12:00:00   （不变）
12  10  23 0.9500 MATCHED   2026-10-23 22:49:05   （不变）
14  25  13 0.9000 MATCHED   2026-09-25 22:49:05   （不变）
```

**一个必须写明的设计点**：召回只取 `topN=10`。如果只重算召回结果，某条记录的某个老对端一旦掉出
top10，它的 hash 会永远停在旧内容上（界面上还挂着按旧标题算出的 0.9）。因此 `scoreRecord` 会把
「已存在 pair 的对端」并进本次候选（`withExistingCounterparts`，上限 `pre-score-pool-size=200`，
按 `updated_at` 升序）。上例里 27↔28 当时掉出了 top10，正是靠这条被重算成 0.81。

### 3.3 20 并发发布：P99 < 500ms

压测脚本 `concurrency.js`：登录 → **15 次串行预热** → **一轮完整 20 并发预热** → 再发 20 并发并计时
（容量测试的通行口径：只计稳态那轮；冷启动另列）。

| 轮次 | 是否稳态 | ok | p50 | p95 | **p99** | max |
|---|---|---|---|---|---|---|
| steady-run1 | 稳态 | 20/20 | 199ms | 230ms | **239ms** | 239ms |
| steady-run2 | 稳态 | 20/20 | 184ms | 226ms | **235ms** | 235ms |
| steady-run3 | 稳态 | 20/20 | 229ms | 259ms | **263ms** | 263ms |
| final-run1 | 冷启动（无预热） | 20/20 | 234ms | 284ms | 705ms | 705ms |
| final-run2 | 冷启动（无预热） | 20/20 | 515ms | 541ms | 931ms | 931ms |
| final-run3 | 冷启动（无预热） | 20/20 | 282ms | 317ms | 331ms | 331ms |

环境：4 核、MySQL 与应用同机、每轮前重置数据库（InnoDB 缓冲池为冷）。

**结论**：稳态 P99 = 235–263ms，满足 < 500ms；**冷启动第 1 秒不满足**（331–931ms），原因是 JVM JIT
与 InnoDB 冷缓冲池，而不是打分链路阻塞发布（发布与打分通过 `AFTER_COMMIT + @Async` 解耦，
20 并发的那一轮里 20 条记录的 `pair_state` 最终仍全部落到 `DEGRADED/READY`）。
这台机器上冷启动数字**不可当作承诺值**，换机器要重测。

**过程中发现并修掉的一个真实瓶颈**：Hikari 默认 `maximum-pool-size: 10` 时，20 并发发布
（每条请求在 `@Transactional` 内持有一条连接）+ 2 个打分 worker（各持一条命名锁连接）会把池占满，
实测 P99 969ms / 1344ms。调到 30 后 P99 265ms。已把默认值改成
`${FINDIT_DB_POOL_SIZE:30}` 并在配置里写清推导过程与代价（连接是数据库的有限资源，
生产要按实例数与 `max_connections` 分摊）。

### 3.4 一条招领匹配两位失主：两行 pair，状态互不影响

```text
P5-before
105  29  31  0.9200  NEW
106  30  31  0.8300  NEW

把 (29,31) 置 CONTACTED 后：
105  29  31  0.9200  CONTACTED
106  30  31  0.8300  NEW          ← 不受影响

三条记录的业务状态仍然是 PENDING（pair 进展不触发记录状态变更）：
29 LOST   PENDING  DEGRADED
30 LOST   PENDING  DEGRADED
31 FOUND  PENDING  DEGRADED
```

扫描不会重置任何 pair 状态：`scorePair` 的 UPDATE 列清单里没有 `status`，见 3.5。

### 3.5 IGNORED 不被重算覆盖（连跑两次结果一致）

先把 pair 33 置 `IGNORED` 并写入两端授权时间、附言、单端确认；随后**改两次标题**触发两次真实重算：

```text
初始      : 33 | IGNORED | 0.8100 | lost_unlocked 2026-10-26 12:00 | found_unlocked 12:10 | note 已线下联系 | lost_confirmed 12:05 | found_confirmed NULL
重算第1次 : 33 | IGNORED | 0.8400 | 同上全部不变 | updated_at 22:49:58
重算第2次 : 33 | IGNORED | 0.8400 | 同上全部不变 | updated_at 22:50:04
控制行    : 6 CONTACTED / 12 MATCHED / 14 MATCHED，updated_at 全部不变
```

**库层落点**：`PairScoreMapper.xml` 的 `ON DUPLICATE KEY UPDATE` 只列 9 个分数类列
（`score/engine/degraded_reason/prompt_version/latency_ms/prompt_tokens/completion_tokens/lost_hash/found_hash`），
且 INSERT 列清单**不写 `status`**（让默认值 NEW 只对首次插入生效）。`PairScoreUpsertSqlTest`
直接解析 XML 断言：UPDATE 子句出现 `status/*_unlocked_at/*_unlock_note/*_confirmed_at/created_at`
即构建失败；写入路径不存在 `DELETE`。

### 3.6 兜底扫描：输出扫描/补算/重算/降级/到期，单飞 409，隐藏入口 404

```text
POST /api/ops/pair-scan （X-Ops-Token 正确）
{"scanned":6,"rescored":2,"degraded":6,"skipped":66,"expired":1,"limited":false,"elapsedMs":1409,
 "counters":{"llmCalls":0,"llmSuccess":0,"degraded":94,"skippedUnchanged":127,
             "pairsWritten":94,"enqueueRejected":0,"lockMisses":0,
             "degradedByReason":{"EMPTY_KEY":94}}}

并发第二个请求：
httpStatus=409 body={"code":50006,"message":"兜底扫描正在运行，请稍后重试","data":null,"requestId":"..."}

错误 token：wrong-token httpStatus=404
不传 token：no-token   httpStatus=404
```

- `scanned/rescored/degraded/skipped/expired/limited/elapsedMs` 为契约 §4.18 要求的计数；
  `counters` 是进程内计数器快照（便于无仪表盘时核对"有没有重复调 LLM"）。
- 单飞用进程内 `AtomicBoolean`，多实例部署不成立 —— 这一点写进已知限制，不做假的多实例保证。
- 新增错误码 **50006**（409）：契约 §4.18 只写了「409」没给 body code，这里补一个并在交付说明里点明，
  避免前端按 message 匹配。

### 3.7 到期窗口：COMPLETED 在召回里、到期后消失、CLOSED 恒 0 命中

取种子记录 23（FOUND / COMPLETED，`completed_at` 距今 3 天）：

```text
到期前 /api/match:
  before: engine=LOCAL_KEYWORD total=10 ids=23,10,18,... contains23=True detailAccess23=RESTRICTED
  （COMPLETED 仍在召回池里，且对"非合格对端"标 RESTRICTED，不返回号码）

把 completed_at 改到 31 天前 → POST /api/ops/pair-scan:
  scan: scanned=6 rescored=0 degraded=6 skipped=65 expired=2 limited=False elapsedMs=3069
  （expired 从 1 变 2；status 仍为 COMPLETED，completed_at 不再改写）

到期后 /api/match:
  after : engine=LOCAL_KEYWORD total=10 ids=10,18,6,15,... contains23=False
  t_record: 23 | COMPLETED | 2026-09-26 22:50:56（状态未被系统改写）

CLOSED 记录 24（对照）:
  closed: total=10 ids=22,16,4,18,8,13,1,19,27,28   ← 24 不在其中
```

口径落地：召回 SQL = `PENDING` OR (`COMPLETED` AND `completed_at > now - 30d`)，`CLOSED` 排除；
到期只计数与打日志，**不改写 `status`/`completed_at`**（`RecordMapper.countExpiredCompleted` 是纯 SELECT）。

## 4. 已知限制与代价

| 项 | 事实 | 影响 |
|---|---|---|
| 进程内单飞 | 扫描互斥是 JVM 级的 | 多实例部署时需要换成数据库锁；当前是单实例演示形态 |
| 扫描成本 | 每条记录最多 `topN=10` 个候选，逐对做 1 次读 + 命名锁 + 可能的写；本机实测约 200–700ms/记录（取决于积压与库冷热） | 默认 `scan-limit=500` 的一轮是分钟级，03:30 跑可接受；数据量到十万级要改成分页 + 断点续扫 |
| 降级分数偏保守 | 本地关键词算法的覆盖率口径会把"地点词"计为未覆盖，近似同一对约 0.81–0.91 | 降级时很少达到 0.90 直看档，这是有意的：不给弱证据发号码 |
| 冷启动 P99 | 见 3.3，冷启动 > 500ms | 验收/演示时先预热再测，否则会看到 JVM/InnoDB 冷启动噪声 |
| 连接池 | 默认调到 30 | 数据库连接是共享资源，生产按 `max_connections` 与实例数分摊，不能直接抄 |

## 5. 复现步骤（本机，PowerShell）

```powershell
# 1. 库：重置到 B1 种子
mysql --no-defaults -h 127.0.0.1 -P 3307 -u root < db/schema.sql
mysql --no-defaults -h 127.0.0.1 -P 3307 -u root find_it_campus < db/seed.sql
# 2. 应用（无 key：走降级路径）
$env:FINDIT_DB_URL="jdbc:mysql://127.0.0.1:3307/find_it_campus?..."
$env:OPS_SCAN_TOKEN="<自定>" ; java -jar server/target/find-it-campus-0.0.1-SNAPSHOT.jar --server.port=18082
# 3. 幂等/IGNORED/双失主/扫描
powershell -File evidence-main.ps1
# 4. 并发（关掉上一个应用实例后）
powershell -File concurrency-run.ps1 -PoolSize 0 -Label steady-run1
```

`db/schema.sql` 无 D1 变更；若要跑真实 LLM，见 `D1-deepseek-live.md` 的补测步骤。
