# D1 配对打分 · 降级证据（空 Key / 401 / 超时 / 非 JSON / 越界）

任务：[ZHYYKFX-54](https://app.hifox.com/nhnok/task/ZHYYKFX-54) · 分支 `feat/v2-D1-pair-scoring`

> 目标不是"证明降级不会崩"，而是证明**每一条降级都写了分数、写了原因、接口不失败、前端能识别**。
> 本文件里的 401/超时/非 JSON 用**本机假端点**复现（真实供应商无法按需返回这些状态），
> 走的是同一条 `DeepSeekPairScorer` 代码路径；空 Key 场景用真·无密钥环境。

## 1. 降级矩阵与实现落点

| 触发 | 判据 | `engine` | `degraded_reason` | `latency_ms` | 证据 |
|---|---|---|---|---|---|
| 空 Key | 环境变量为空/空白，**不发请求** | `LOCAL_KEYWORD` | `EMPTY_KEY` | `NULL` | §2 |
| 401 / 403 | 响应状态码 | `LOCAL_KEYWORD` | `UNAUTHORIZED` | 实测值 | §3.1 |
| 超时 | connect/read 超时（本机用 read=1000ms 复现） | `LOCAL_KEYWORD` | `TIMEOUT` | 实测值（1002ms） | §3.2 |
| 非 JSON | 响应体/`content` 不是 JSON 对象 | `LOCAL_KEYWORD` | `NON_JSON` | 实测值 | §3.3 |
| 分数越界 | 非数字 / NaN / <0 / >1 | `LOCAL_KEYWORD` | `SCORE_OUT_OF_RANGE` | 实测值 | §4（单测） |
| 其他非 2xx | 429/5xx | `LOCAL_KEYWORD` | `HTTP_ERROR` | 实测值 | §4（单测） |
| 熔断打开 | 连续失败达阈值，冷却期内 | `LOCAL_KEYWORD` | `CIRCUIT_OPEN` | `0`（不发请求） | §4（单测） |

所有降级分支的**共同不变量**（由代码保证，不由测试碰运气）：

1. `PairScoreResult.score == null` 时由调用方用本地关键词算法补分，**一定会写一个 [0,1] 的分数** ——
   否则 `/my` 的 `pairCount/relatedCount/lowConfidenceCount` 会全变 0，用户看到"没有任何匹配"。
2. 降级**不改** `pair.status`、不改 `t_record.status/completed_at`，也不清空授权/附言/确认时间。
3. 发布/编辑接口**不感知**降级：打分是 `AFTER_COMMIT` 之后在线程池里跑的。

## 2. 空 Key（本机无密钥，真实环境）

启动环境：`DEEPSEEK_API_KEY` 未设置（机器级/用户级/进程级三处均为空，已实读）。

发布一对内容相同的寻物/招领（demo02 发 LOST，demo03 发 FOUND），5 秒后：

```text
P1-publish
publish LOST httpLatencyMs=527 id=27 status=PENDING pairState=PENDING
publish FOUND httpLatencyMs=34 id=28 status=PENDING pairState=PENDING   ← 发布不等打分，返回 200/code 0

P1-db
27  LOST   PENDING  DEGRADED  2026-10-27 22:49:34
28  FOUND  PENDING  DEGRADED  2026-10-27 22:49:34
33  27  28  0.9100  LOCAL_KEYWORD  EMPTY_KEY  NULL  NULL  NULL  NEW
          ↑ 有分数      ↑ 引擎        ↑ 原因    ↑ latency/tokens 均为 NULL（没发请求，不估算）

通知（跨 0.75 阈值，双端各一条，唯一键幂等）
9   2  33  PAIR_MATCH  你发布的寻物启事有一条可能相关的招领  相关度约 91%，点击查看详情
10  3  33  PAIR_MATCH  你发布的招领启事有一条可能相关的寻物  相关度约 91%，点击查看详情
```

**前端标志位**已随接口返回（`GET /api/my/records`）：

```json
{"id":27,"status":"PENDING","pairState":"DEGRADED","pairDegraded":true,
 "pairCount":10,"relatedCount":1,"lowConfidenceCount":9,"hasMatchedPair":false}
```

- `pairState=DEGRADED`：契约 §4.12 的既有字段（`PENDING/SCORING/READY/DEGRADED`）。
- `pairDegraded=true`：本任务新增的**布尔投影**（additive，不破坏既有前端）。加它的理由：让前端不必把
  "降级"编码成 `pairState === 'DEGRADED'` 这种字符串比较，改一个枚举值不会静默失效。
- 前端文案按契约：“已降级” + 「智能匹配暂不可用，已用关键词匹配」。
- 分数 0.9100 说明**降级分也能跨通知阈值**（完全相同的标题/描述/地点），不是"降级就等于没结果"。

## 3. 401 / 超时 / 非 JSON（本机假端点，真实代码路径）

做法：把 `findit.llm.base-url` 指到本机 `llm-stub.js`（127.0.0.1:18099），
`DEEPSEEK_API_KEY` 设为一个**明显的假值**（仅用于走"有 key → 发请求"的分支，不是真实密钥），
`read-timeout-ms=1000 / total-timeout-ms=2500 / circuit-failure-threshold=100`（阈值调高是为了让 11 对
逐对都拿到真实失败原因，而不是第 6 对之后变成 `CIRCUIT_OPEN`；熔断本身在 §4 单测覆盖）。
每轮改一次记录标题制造 hash 变化，强制重算，观察同一对（27↔28 / pairId=33）。

### 3.1 HTTP 401

```text
PUT /api/records/27 -> http 200 code=0 status=PENDING      ← 编辑接口不失败
33 | 27 | 28 | 0.8900 | LOCAL_KEYWORD | UNAUTHORIZED | latency 3ms | tokens NULL | status IGNORED
counters: llmCalls=11 llmSuccess=0 degraded=11 reasons={"UNAUTHORIZED":11}
```

### 3.2 读超时（假端点 sleep 3s，客户端 read=1000ms）

```text
PUT /api/records/27 -> http 200 code=0 status=PENDING
33 | 27 | 28 | 0.8900 | LOCAL_KEYWORD | TIMEOUT | latency 1002ms | tokens NULL | status IGNORED
counters: llmCalls=22 llmSuccess=0 degraded=22 reasons={"UNAUTHORIZED":11,"TIMEOUT":11}
```

`latency_ms=1002` 是"卡了多久才降级"的实测值；这条列在 DDL 注释里写的是"本地引擎为 NULL"，
此处刻意保留失败调用的实测耗时，**代价与理由**：排障需要区分"空 Key（0ms，没发请求）"和
"网络卡住（1002ms）"，两者运维处置完全不同。

### 3.3 非 JSON（假端点返回 200 + 非 JSON 内容）

```text
PUT /api/records/27 -> http 200 code=0 status=PENDING
33 | 27 | 28 | 0.8900 | LOCAL_KEYWORD | NON_JSON | latency 1ms | tokens NULL | status IGNORED
counters: llmCalls=33 llmSuccess=0 degraded=33 reasons={"UNAUTHORIZED":11,"TIMEOUT":11,"NON_JSON":11}
```

> 三轮里 `status` 始终是 `IGNORED`：即使走的是 LLM 失败路径，重算也没有把状态翻回 `NEW`
> （与 `D1-idempotency.md` §3.5 同一条库层保证）。

### 3.4 成功路径（假端点返回合法 JSON，**不是真实 DeepSeek**）

```text
PUT /api/records/27 -> http 200 code=0 status=PENDING
33 | 27 | 28 | 0.8800 | DEEPSEEK | NULL | latency 1ms | prompt_tokens 100 | completion_tokens 10 | pair-score-v1 | IGNORED
```

它只证明「解析 → 校验 → 落库」这段是通的（`engine=DEEPSEEK`、tokens 落库、`degraded_reason=NULL`、
prompt_version 写对）。**真实 DeepSeek 调用未执行**，见 `D1-deepseek-live.md`。

## 4. 越界 / 5xx / 熔断（单测，不需要外网）

`server/src/test/java/com/findit/campus/pair/engine/deepseek/DeepSeekPairScorerTest.java`（7 条，本机 `HttpServer`）：

| 用例 | 断言 |
|---|---|
| `success` | `engine=DEEPSEEK`、score∈[0,1]、`latency>0`、`prompt_tokens=321`、`completion_tokens=17`、reason 保留 |
| `emptyKeyNoRequest` | `EMPTY_KEY`、`score=null`、**服务端收到 0 次请求** |
| `unauthorized` | `UNAUTHORIZED`、不抛异常 |
| `timeout` | `TIMEOUT`（read=100ms vs 服务端 600ms） |
| `nonJson` | `NON_JSON` |
| `scoreOutOfRange` | `SCORE_OUT_OF_RANGE`（`{"score":1.5}`） |
| `httpError` | `HTTP_ERROR`（500） |

`LlmCircuitBreakerTest`（2 条，可控时钟，不 sleep）：连续失败到阈值即打开、冷却期内一律拒绝、
到期恢复、成功一次清零。阈值默认 5 次 / 打开 5 分钟（架构 §8.4）。

> 熔断存在的理由：DeepSeek 故障时若继续逐对等待 20 秒超时，一次扫描 500 条 × 10 对 = 5000 次超时，
> 会把后台线程和连接挂死数小时。打开后直接本地降级，把故障隔离在一轮扫描之内。

## 5. 通知与降级的关系（必须说清的边界）

- 通知阈值 `findit.pair.notify_threshold=0.75` 与引擎无关：降级分 ≥0.75 同样发 `PAIR_MATCH`（§2 的 0.91）。
- 但本地关键词分**偏保守**：近似但非完全一致的同一对通常落在 0.81–0.91，随文本差异下降。
  也就是说降级时"联系方式直看档（≥0.90）"很少触发 —— 这是**有意的**：不给弱证据发号码。
- 一旦 key 恢复，已落库的 `LOCAL_KEYWORD` 行会在冷却期（`degraded-retry-minutes=60`）后的扫描中被
  重算为 `DEEPSEEK`。这条路径由 `shouldRetryDegraded` 控制，**只对降级行生效**，
  不会让已成功的 pair 重复计费（见 `D1-idempotency.md` §3.1）。

## 6. 零明文

- 代码/配置/日志/证据里没有任何真实密钥；`application.yml` **没有 api-key 键**，密钥只从
  `@Value("${DEEPSEEK_API_KEY:}")` 读取。
- 日志只打 `engine/degradedReason/latencyMs/promptTokens/completionTokens/pairId/recordId/model`，
  不打 Authorization、prompt、response、号码。
- 本文件与 `D1-idempotency.md`/`D1-deepseek-live.md` 中 `sk-` 与「密钥变量名 = 值」的写法均为 0 命中。
