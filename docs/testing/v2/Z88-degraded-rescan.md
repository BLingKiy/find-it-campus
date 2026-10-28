# Z88 · LLM 恢复后重新出分：degraded 重算接线与延迟收敛

- 任务：ZHYYKFX-88（父任务 ZHYYKFX-44）
- 分支：`feat/v2-Z88-degraded-rescan`
- 基线：`origin/feat/v2-integration @ 6ce5700`
- 证据：本文 §6 摘录 + `docs/testing/v2/Z88-evidence.txt` 原始输出（MySQL 8.0.29 + stub LLM + 真实 jar，脚本单次前台跑完）

## 1. 结论

**在原有每日全量扫描之外，新增"只扫 DEGRADED 的 10 分钟周期 + 熔断恢复事件"两条入口，并把 `degraded-retry-minutes` 同时接到选材层与逐对层。** 三个理由：

1. 只加定时器不够：恢复事件是唯一"确知通路已恢复"的时刻，此刻重算最省额度；定时器负责兜住"没有新发布"的静默恢复。
2. 只加事件不够：事件可能因为扫描在飞而被丢弃（单飞），10 分钟周期是它的兜底，两者都不依赖"事件一定送达"。
3. 冷却必须在选材层再生效一次：否则每 10 分钟把同一批刚试过的降级记录取出来、逐对判定再跳过，纯属空转；逐对冷却保留，作为"记录里还有别的 pair 需要算"时的最后一道闸。

**任务书事实校正（必须记录）**：`getDegradedRetryMinutes()` 在基线 `6ce5700` 并**不是**零调用 ——
`PairScoringService#shouldRetryDegraded`（`server/.../pair/service/PairScoringService.java:307-316`）已经用它做逐对冷却。真实缺口是：① 它只影响"已取出的 pair 是否重算"，不影响"记录是否进候选"；② 没有短周期调度；③ 熔断恢复没有事件。本单按这三个缺口实现，没有重做已有的逐对冷却。

## 2. 改动清单

| 文件 | 改动 |
|---|---|
| `pair/config/PairScoreProperties.java` | 新增 `degraded-scan-cron=0 */10 * * * *`、`degraded-scan-enabled=true`、`degraded-scan-limit=100`；校验；类注释写明冷却的两个落点 |
| `pair/engine/LlmCircuitBreaker.java` | 显式三态 `CLOSED/OPEN/HALF_OPEN`；冷却结束只放 1 次探测（探测超时 2×总超时兜底）；`HALF_OPEN→CLOSED` 触发恢复监听器（锁外执行） |
| `pair/service/PairScanService.java` | 抽出 `Scope=FULL/DEGRADED` 执行体；新增 `@Scheduled(degraded-scan-cron)` 与 `scanDegraded`；两条调度共用单飞；`max-llm-calls` 按本轮 LLM 调用差值硬停；熔断打开时降级重扫整体跳过 |
| `pair/service/DegradedRescanTrigger.java`（新增） | 注册恢复监听器 → 去重后提交单线程守护线程跑 `scanDegraded("llm-recovered")`；失败只记日志；`@PreDestroy` 收敛线程 |
| `pair/service/PairScoringService.java` | `scorePair` 增加"本轮为哪条记录重算"参数（只进日志）；`scoreRecord` 接受 `LlmCallBudget`，在**每对之前**检查额度（超调 0 次）；被截断时改用 `markPairScoredKeepTimestamp`，不推进冷却时钟；新增 `event=pair_score_upgraded` 留痕行（recordId/pairId/旧新 engine+score/耗时） |
| `record/mapper/RecordMapper.java` + `mapper/record/RecordMapper.xml` | 全量扫描的 DEGRADED 分支加 `pair_scored_at < degradedBefore`；新增 `selectDegradedScanTargets`（只取 DEGRADED、带冷却、带保留期、带 limit）；新增 `markPairScoredKeepTimestamp`（截断时只落状态） |
| `pair/web/OpsPairScanController.java` | `POST /api/ops/pair-scan?scope=full\|degraded`（默认 full，保持 v2.0 语义；非法值 10001） |
| `pair/web/dto/PairScanResult.java` | 新增 `scope`、`skipReason`（区分"没活干"与"熔断挡着"）；降级重扫 `expired=-1`（不做到期统计） |
| `application.yml` | 新增三个降级重扫配置项，默认开启 |
| 测试 | `PairScanServiceTest` 扩到 10 例；`LlmCircuitBreakerTest` 新增三态/探测/超时 3 例；新增 `DegradedRescanTriggerTest` 3 例；`PairScoringServiceTest` 新增冷却/升级/预算截断 3 例；新增 `PairScanTargetsSqlTest` 2 例 |

**未改**：打分算法、阈值（ZHYYKFX-84 的 `DEGRADED_PAIR_THRESHOLD` 等）、三档解锁语义、`t_record`/`t_pair_score` 表结构、每日 `scan-cron` 默认值。

## 3. 关键设计

### 3.1 两层冷却：为什么不是一层

- 选材层（新）：`pair_state='DEGRADED' AND (pair_scored_at IS NULL OR pair_scored_at < now-60min)`。一条记录被打成 DEGRADED 后 60 分钟内不进任何候选。
- 逐对层（已有）：`engine='LOCAL_KEYWORD' AND updated_at < now-60min` 才允许在哈希未变时重算。
- 为什么保留两层：选材层挡住"整条记录都还烫"的空转；逐对层挡住"记录因为别的原因被重新打分（编辑、发布），但这一对刚试过"的重复计费。两处读同一个 `degraded-retry-minutes`，口径不会分叉。
- 代价：同一个语义写在两处，未来改冷却策略要同时改；已在两边注释互相指向，并用 `PairScanTargetsSqlTest` 钉住选材 SQL。

### 3.2 熔断三态：恢复事件从哪来

- `OPEN` 冷却结束时只放 **1** 次探测（`HALF_OPEN`）。探测成功 → `CLOSED` 并触发一次监听器；失败 → 重新 `OPEN` 满 5 分钟。
- 代价：探测期间其它并发打分请求拿到 `CIRCUIT_OPEN` 本地降级。这是有意的背压：探测没证明通路可用之前放更多请求只会再打开一次。
- 探测超时（2×`total-timeout-ms`）后允许再放一次，避免代码缺陷导致永久半开。
- 监听器在锁外执行，且 `DegradedRescanTrigger` 只做 `executor.execute`：回调发生在持有 MySQL 命名锁的打分线程上，同步跑扫描会自锁/长期占连接。

### 3.3 事件丢了怎么办

`DegradedRescanTrigger` 用 `AtomicBoolean` 去重，最多 1 个在飞。撞上正在跑的全量/降级扫描时 `scanDegraded` 抛 409/50006，触发器捕获后只记一行 `skipped reason=IN_PROGRESS`。**这条链路允许丢事件，不允许丢数据**：候选由数据库状态决定，下一轮 10 分钟定时重扫会重新算出来。代价是"恢复瞬间立即出分"退化为"≤10 分钟出分"，在事件被丢弃的少数情况下成立。

### 3.4 额度闸门放在"每对之前"，不是"每条记录之间"

只按记录粒度检查 `max-llm-calls` 时，一条记录若有 200 个候选 pair，单条就能把额度撑破 200 次。本单把 `LlmCallBudget` 传进 `scoreRecord` 的候选循环，在**每一对打分之前**检查：

- 超调上限 = 0：最后那次把计数推到上限的调用之后，下一对之前就停。
- 被截断时**不刷新** `pair_scored_at`（`markPairScoredKeepTimestamp`）：否则"剩余留到下一轮"会变成"剩余留到一小时后"。
- `RecordScoreOutcome.truncated` 让扫描器立刻把 `limited=true` 并停止本轮；下一轮从这条记录继续。

代价：`scoreRecord` 多了一个可选参数与一个收尾分支；发布/编辑/手动路径传 `null`，行为与 v2.0 完全一致。

### 3.5 索引与容量

- 选材走 `idx_record_pair_scan (pair_state, pair_scored_at)`：`pair_state` 等值在前、`pair_scored_at` 范围在后，命中形态与索引列序一致。
- 逐对冷却走 `idx_pair_scan (engine, updated_at)`（本次未改，已有）。
- **无 schema 变更 = 无迁移、无回滚脚本**；降级重扫每 10 分钟一次，`expired` 统计被显式跳过（`-1`），避免随表增长的 `COUNT`。
- `degraded-scan-limit=100` 与线程池 2 是**经验估算，需压测校准**（本仓库没有生产流量数据，不给承诺值）。

## 4. 验收对照

| 验收标准 | 证据 |
|---|---|
| DEGRADED 距上次打分 < `degraded-retry-minutes` 不被重扫，超过后进入候选 | 单测 `PairScanServiceTest#degradedScanUsesDedicatedQuery`（捕获 `degradedBefore≈now-60min`）、`#degradedScanStopsAtMaxLlmCalls`；`PairScoringServiceTest#degradedRowWithinCooldownIsNotRescored`；e2e 第 5 段：连续 3 个周期 `scanned=0`、engine 保持 `LOCAL_KEYWORD`；第 6-7 段把证据 age 到 61 分钟，下一周期 `scanned=1` 并升级 |
| LLM 恢复，下一降级周期（≤10 分钟）内 engine 变 `DEEPSEEK`、`degraded_reason` 清空、可查日志 | 默认 `degraded-scan-cron=0 */10 * * * *`；e2e 用 `*/5s` 加速同一方法：恢复后一个周期内 `LOCAL_KEYWORD→DEEPSEEK`、`degraded_reason=NULL`；`PairScoringServiceTest#degradedRowPastCooldownUpgradesEngine` |
| 熔断 OPEN→HALF_OPEN/CLOSED 触发一次降级重扫 | `LlmCircuitBreakerTest#halfOpenAllowsSingleProbeThenCloses`（回调只触发一次）；`DegradedRescanTriggerTest#recoveryFiresOneDegradedScan`；e2e 第 11 段把 cron 设为夜间，**只靠恢复事件**升级历史行：`pair degraded rescan on recovery done trigger=llm-recovered scanned=1` |
| 重算不改 `MATCHED`/`IGNORED` 的 status、不清解锁时间/附言/确认时间 | `PairScoreUpsertSqlTest`（UPDATE 列白名单）；`PairScoringServiceTest#degradedRowPastCooldownUpgradesEngine`；e2e 前后逐字段对比（见 §6.2） |
| 达到 `max-llm-calls` 即停，剩余留到下一轮 | 单测 `#degradedScanStopsAtMaxLlmCalls`（记录之间）、`PairScoringServiceTest#budgetStopsWithinOneRecord`（记录内部每对之前，超调 0）、`PairScanServiceTest#truncatedRecordStopsRound`；e2e 第 9 段 `max-llm-calls=1`：round1 `scanned=1, limited=true`、剩余 1 条；round2 补完 `limited=false` |
| 默认夜间全量扫描不回归 | 全量扫描 9 例单测全绿（计数/失败不计/上限/单飞/保留期/降级冷却）；`scan-cron` 默认值未改；e2e 中 `scope=FULL` 分支行为与基线一致（唯一语义增量：03:30 前 60 分钟内刚打过的 DEGRADED 记录会推迟到降级周期，覆盖不减） |

**单测总数：261 run / 0 failed / 0 errors / 0 skipped**（基线 242 + 本单新增/扩展 19）。原始输出 `z88-fulltest.log`（随任务附件交付）。

## 5. 日志样例（真实运行，节选）

升级留痕（回答"这条什么时候从关键词分升级成模型分"）：

```text
pair score upgraded event=pair_score_upgraded recordId=2 pairId=1 lostId=2 foundId=1
trigger=degraded-scheduled oldEngine=LOCAL_KEYWORD oldScore=0.5400 newEngine=DEEPSEEK
newScore=0.93 degradedReason=null latencyMs=10 elapsedMs=400
```

恢复事件驱动（cron 设为夜间，排除定时路径）：

```text
LLM 熔断恢复：HALF_OPEN 探测成功，状态 CLOSED
LLM 恢复事件：已提交一次降级重扫（trigger=llm-recovered）
pair degraded rescan on recovery done trigger=llm-recovered scanned=1 rescored=1 degraded=0
skipped=0 limited=false skipReason=null elapsedMs=449
pair score upgraded ... trigger=llm-recovered oldEngine=LOCAL_KEYWORD oldScore=0.6200
newEngine=DEEPSEEK newScore=0.93
```

周期扫描汇总（冷却期，`scanned=0`；升级时 `scanned=1`）：

```text
pair scan done scope=DEGRADED trigger=degraded-scheduled scanned=0 ... limited=false
pair scan done scope=DEGRADED trigger=degraded-scheduled scanned=1 rescored=1 ... limited=false
```

熔断打开时降级重扫整体跳过（不推后冷却时钟）：

```text
pair degraded scan skipped trigger=degraded-scheduled reason=CIRCUIT_OPEN remainingSeconds=300
```

## 6. 端到端证据摘录

环境：MySQL 8.0.29（127.0.0.1:3306）+ Node stub LLM（可切 fail/ok）+ 打包 jar，端口 18088；三段配置通过 `spring.config.additional-location` 注入，**未改仓库默认配置**。

### 6.1 冷却与恢复（phase 1）

```text
pair state after outage: pairId=1 engine=LOCAL_KEYWORD reason=HTTP_ERROR score=0.5400 status=NEW
record state after outage: recordId=2 pair_state=DEGRADED pair_scored_at=2026-10-29 01:31:53
after 9s (circuit expired, degraded-retry=60min): engine=LOCAL_KEYWORD pair_state=DEGRADED
after 12 more seconds: engine=LOCAL_KEYWORD pair_state=DEGRADED
ASSERT OK: fresh DEGRADED row is NOT selected before degraded-retry-minutes elapses
ASSERT OK: degraded cron ran and selected 0 records during cooldown
before rescore: LOCAL_KEYWORD|HTTP_ERROR|0.5400|MATCHED|2026-10-20 10:00:00|2026-10-20 10:05:00|2026-10-20 09:00:00|2026-10-20 09:10:00|e2e-note-lost|e2e-note-found
ASSERT OK: LLM recovery within the next degraded cycle upgraded engine to DEEPSEEK
after rescore:  DEEPSEEK|NULL|0.9300|MATCHED|2026-10-20 10:00:00|2026-10-20 10:05:00|2026-10-20 09:00:00|2026-10-20 09:10:00|e2e-note-lost|e2e-note-found
record after rescore: recordId=2 pair_state=READY
```

### 6.2 额度上限（phase 2，`max-llm-calls=1`）

```text
round1 HTTP 200 {"data":{"scanned":1,"rescored":1,"limited":true,"scope":"DEGRADED",...}}
remaining degraded records after round1=1
round2 HTTP 200 {"data":{"scanned":1,"rescored":1,"limited":false,"scope":"DEGRADED",...}}
ASSERT OK: no degraded records left after round2
```

### 6.3 纯恢复事件路径（phase 3，cron=夜间）

```text
phase3 degraded: pairId=1 engine=LOCAL_KEYWORD reason=HTTP_ERROR
aged history; now publish one new record to carry the HALF_OPEN probe (no cron involved)
pair degraded rescan on recovery done trigger=llm-recovered scanned=1 rescored=1 ... elapsedMs=449
pair score upgraded ... trigger=llm-recovered oldEngine=LOCAL_KEYWORD oldScore=0.6200
newEngine=DEEPSEEK newScore=0.93
phase3 final pair: DEEPSEEK|NULL|0.9300|NEW
phase3 final record: READY|...
```

## 7. 权衡记录

| 决策 | 备选 | 放弃原因 |
|---|---|---|
| 独立 `selectDegradedScanTargets` | 复用全量 SQL 加 `scope` 动态条件 | 两种调度、两个 limit、是否统计 expired 都不同；动态拼接会让"这一轮到底碰哪些记录"难以一眼核对 |
| 选材层 + 逐对层双冷却 | 只保留逐对冷却 | 每 10 分钟仍会取出、加锁、读一遍冷却中的记录；数据量上来后是纯浪费 |
| 恢复事件异步 + 去重 + 可丢 | 同步在回调里跑 | 回调线程持有 pair 命名锁；同步扫描会自锁或长期占连接 |
| 恢复事件不重试 | 失败重排/重试队列 | 候选本来就是数据库状态，定时重扫会覆盖；重试只增加复杂度，不提高正确性 |
| 熔断 HALF_OPEN 单探测 | 冷却结束全量放行（原行为） | 恢复瞬间会形成并发尖峰；单探测同时给出"恢复"的确定时刻 |
| 降级重扫在熔断打开时整体跳过 | 照常扫描、逐对拿 CIRCUIT_OPEN 降级 | 会把 `updated_at` 刷新，把冷却时钟往后推，反而延长恢复时间 |
| 降级重扫不统计 expired | 每轮都 COUNT | 10 分钟一次的全表范围 COUNT 随数据量增长；到期可见性本来由读时谓词保证 |
| 额度闸门放在每对之前 | 只在记录之间检查（v2.0 现有行为） | 单条记录最多可多花 `pre-score-pool-size` 次调用，与"防额度风暴"目标冲突；改造成本只是给 `scoreRecord` 一个可选参数 |
| 截断时不刷新 `pair_scored_at` | 与完整一轮一样刷新 | 刷新会把剩余 pair 推进 60 分钟冷却，"留到下一轮"变成"留到一小时以后" |
| 无 schema 变更 | 加 `degraded_scanned_at` 列/新表 | 现有 `pair_state + pair_scored_at` 与 `idx_record_pair_scan` 已足够；迁移有回滚成本，收益为零 |

## 8. 已知限制与待验证

- **单实例假设不变**：单飞是进程内 `AtomicBoolean`；多实例会重复扫描。这是 v2.0 已有的限制，本单没有引入新的多实例保证，也没有假装解决。
- `degraded-scan-limit=100`、`max-llm-calls=500`、线程池 2 均为经验值，**需压测校准**；本单不提供吞吐/延迟承诺。
- 通知正文里的百分比是首次插入时的值（`insertIfAbsent` 幂等）；重算跨阈值时按新分数重新判定是否插入，但**不回溯修改历史通知正文**，符合"不回溯删除历史通知"的边界。若产品要求正文随分数更新，需要单独定义（会改写历史证据，不建议）。
- 恢复事件在"扫描正在跑"时会丢一次（日志可见 `skipped reason=IN_PROGRESS`），由 10 分钟周期兜底；极端情况下最坏延迟仍是 10 分钟，不是 0。
- 没有新增业务指标（沿用 `PairScoringMetrics` 的进程内计数）；上线接仪表盘时建议加：`degraded_scan_scanned_total`、`degraded_rescan_done_total{trigger}`、`circuit_state`、`upgraded_pairs_total`。阈值需按真实流量定，本单不给拍脑袋的告警值。

## 9. 回滚

- **配置级**：`findit.pair.score.degraded-scan-enabled=false` → 退回"只有每日全量扫描"的 v2.0 行为，无需发版（重启生效）。
- **代码级**：revert 本分支提交即可；无 schema 变更、无数据迁移，不存在"回滚后数据不兼容"。
