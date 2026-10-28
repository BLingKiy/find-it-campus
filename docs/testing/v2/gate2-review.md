# 门禁 2 复核 · 幂等 / 降级 / 分档 / 多对 / 终态 / B2 会话 / 真实 DeepSeek

> 任务 [ZHYYKFX-64](https://app.hifox.com/nhnok/task/ZHYYKFX-64)｜复核人：端测测｜复核时间：2026-10-28 01:10–01:35（Asia/Shanghai）
> **第 7 组重跑：2026-10-28 07:33–07:38**（人类给出本地密钥文件路径后，按派工单「第 7 组重跑」一节补测；其余六组结论不变）
> **本文只做复核留证，不判定门禁 2 是否放行**——放行由人类在ZHYYKFX-64 签字。
> 所有结论都对应 `raw/` 下的原始输出；本文里的每段引用都能在对应 raw 文件里逐字找到。

## 0. 结论一览

| 组 | 判据 | 结论 |
|---|---|---|
| 1 幂等 | 同 pair 触发 3 次仍 1 行 / LLM 计数不变；改标题只重算相关 pair；20 并发不双行不 500 | **PASS**（3/3） |
| 2 降级 | 空 Key / 401 / 超时 / 非 JSON 四种：发布 200、`engine=LOCAL_KEYWORD`、有分数、页面提示可见；恢复后可重算；P99 < 500ms | **PASS**（判据 4/4）；「真实调用成功一次」一格已在第 7 组补齐（§8） |
| 3 分档 | 0.92 双端可看；0.80 未授权/授权/撤回/附言清空；<0.75 一律 403；改阈值档位随之变化 | **PASS**（4/4） |
| 4 多对 pair | 一条招领×两条寻物 1:1×2；一个 CONTACTED、另一个仍 NEW 且可解锁；招领仍在候选池 | **PASS**（3/3） |
| 5 终态 | 未 MATCHED 标完成 409；COMPLETED 列表 0/匹配命中且 <0.90 详情 404；CLOSED 双 0 且取号 403；到期从匹配消失 | **PASS**（4/4） |
| 6 B2 会话 | 无 session 401/10006；非参与方 403/10007 且审计无成功行；异常 id 404 不 500；他人通知 403 | **PASS**（4/4） |
| 7 真实 DeepSeek | 至少 1 行 `engine=DEEPSEEK` 且 latency/tokens/score/prompt_version 达标 | **PASS**（0/1 → **1/1**，9 行真实调用，见 §8） |

**七组均为实跑，无推测格、无「不可判定」格。** 第 7 组上一轮记为「不可判定 · 运行时无法读取密钥」，根因是只查了环境变量、没有在启动命令里显式注入；本轮按人类给出的本地密钥文件路径，在同一条 shell 内注入后重启实测通过，§8 已改写。

## 1. 基线与环境

| 项 | 值 | 取得方式 |
|---|---|---|
| 集成分支 | `feat/v2-integration` | `git ls-remote origin refs/heads/feat/v2-integration` |
| 基线 SHA | `78b96be175925cab81fa436666509b14d00a7de2` | 与派工单逐字符一致 |
| 复核分支 | `feat/v2-gate2-evidence`（从该 SHA 切出） | `git checkout -B` |
| 五个 merge commit（`--first-parent`） | `b41db4f`(D1) → `5658194`(E1) → `abc3c31`(E1b) → `a7968a4`(E2) → `78b96be`(D2) | `git log --oneline --first-parent` |
| 五个源分支 tip 是否为 HEAD 祖先 | `77596f7`/`17dcc9a7`/`3138f8b2`/`0866bae`/`440d668` 全部 ANCESTOR-OK | `git merge-base --is-ancestor` |
| `origin/main` | `21784f674ca3a5915aaa86dc95edd7a0e55e973d`（复核前后未动） | `git ls-remote origin refs/heads/main` |

构建与静态检查（本机实跑，不是采信执行侧自述）：

```
server: mvn -B -DskipTests package  -> BUILD SUCCESS (31.6s)
web   : npm run build               -> ✓ built in 1.16s
web   : npm run type-check          -> exit 0（两个 tsconfig 均通过）
```

服务端启动口径（每组不同，逐组在正文标注）：

| 代号 | 端口 | 密钥 | LLM 端点 | 其他 |
|---|---|---|---|---|
| A | 8088 / 18080 | 未设置 | 默认 `api.deepseek.com` | 默认阈值 |
| C | 8088 | 未设置 | 同 A | `contact_direct_threshold=0.85`、`contact_unlock_threshold=0.40` |
| D | 18080 | 本机假密钥 `sk-local-stub-not-a-real-key` | 本机假端点 `127.0.0.1:18099/v1` | `read-timeout=1000`、`total-timeout=2500`、`circuit-failure-threshold=10000`、`degraded-retry-minutes=1` |
| E | 18082 | 本地密钥文件注入（同一条 shell，`key-length=35`） | 默认 `api.deepseek.com/v1` | 新库 `findit_gate2_64_g7`（schema+seed 后清空业务表） |
| F | 18082 | 未设置（`unset`，`len=0`） | 同 E | 同库重启，用与 E 相同的正文复跑 |

数据库为**新建** `findit_gate2_64`（`db/schema.sql` + `db/seed.sql` 原样执行，仅替换库名），初始 7 表 / 24 条记录 / 14 条 pair / 3 个账号（`demo01/02/03`，口令 `Demo@123456`）。
证据全部来自**真实服务端**：没有任何 `page.route` 注入、没有手写响应体冒充返回、没有假卡片；浏览器只用 CDP 点真实按钮。

### 1.1 两类夹具（如实声明，不藏）

1. **业务写入**：所有被测记录都通过 `POST /api/records` / `PUT /api/records/{id}` 真实创建，没有直接 INSERT `t_record`。
2. **状态/时间夹具**（仅 4 处，均在正文标注）：把 `t_pair_score.score` 不需要改——三个分档用种子自带（0.96 / 0.86 / 0.42）；只对 `t_record.completed_at`（模拟到期）、`t_pair_score.updated_at`（模拟冷却窗口已过）做过 UPDATE。**没有改过任何 score、engine、degraded_reason**。

## 2. 第 1 组 · 幂等

配置 D（假端点稳定返回合法 JSON，`score=0.87`）。目标 pair = `lost#33 ↔ found#34`（pairId 116）。

### 1.1 同 pair 重复触发 3 次 → 仍 1 行、LLM 计数不变

| 时点 | 触发动作 | `llmCalls` | `skippedUnchanged` | pair 116 行数 | pair 116 `updated_at` |
|---|---|---|---|---|---|
| 基线（预热扫描后） | — | 91 | 14 | 1 | 2026-10-28 01:26:48 |
| 触发 1 | `PUT /api/records/33` 提交**完全相同**内容（hash 未变） | — | — | 1 | 01:26:48（未变） |
| 触发 2 | `POST /api/ops/pair-scan` | **91** | 25 | 1 | 01:26:48（未变） |
| 触发 3 | `POST /api/ops/pair-scan` | **91** | 25 | 1 | 01:26:48（未变） |

`llmCalls` 三次读数完全相同（91 → 91 → 91），`pairsWritten` 也停在 91：**没有重复调用 LLM，也没有重复写行**。PUT 返回 200 且 `pairState=PENDING`，说明编辑接口不等打分、也不因内容未变而重算。
原始输出：`raw/G1-idempotency.txt` §1.1。

### 1.2 改标题只重算相关 pair

`PUT /api/records/30` 把标题改成「丢失一把黄铜色口琴（改标题探针）」，随后对比**全表** 105 行的 `updated_at`：

```
diff（改前 → 改后）
45,53c45,53      13:26:38 → 01:27:10  …（45–53 全部是 lost_id=30 的 9 行）
55c55            01:26:30 → 01:27:12  （55 = 30↔27）
73c73            01:26:40 → 01:27:13  （73 = 30↔32）
```

- 变动行**全部**是 `lost_id=30` 的 11 行（含改后新进池的 `30↔27`、`30↔32`）。
- 控制行 `6`(6↔18, CONTACTED) / `12`(10↔23, MATCHED) / `14`(25↔13, MATCHED) 的 `updated_at` **逐字节未变**。
- 105 行 → 105 行，没有新增重复行。

原始输出：`raw/G1-idempotency.txt` §1.2。

### 1.3 20 并发发布：不双行、不 500、发布 P99 < 500ms

单账号（demo02）每轮 20 并发 `POST /api/records`，先 15 次串行 + 1 轮并发预热，再连跑 4 轮：

| 轮次 | ok | fail | p50 | p95 | **p99** | max |
|---|---|---|---|---|---|---|
| 第 1 轮（首次并发） | 20/20 | 0 | 234ms | 278ms | **287ms** | 287ms |
| 第 2 轮（稳态） | 20/20 | 0 | 196ms | 218ms | **221ms** | 221ms |
| 第 3 轮（稳态） | 20/20 | 0 | 145ms | 176ms | **182ms** | 182ms |
| 第 4 轮（稳态） | 20/20 | 0 | 473ms | 485ms | **487ms** | 487ms |

- 80 次发布**全部 200**，0 个 5xx，0 个业务错误码。
- 并发后数据库：`t_pair_score` 共 312 行，`group by (lost_id,found_id) having count(*)>1` = **0 行**，`score is null` = 0 行。
- 稳态 P99 最大值 **487ms < 500ms**，判 PASS；**但余量只有 13ms**，见 §9 风险。

原始输出：`raw/G1-concurrency.txt`（含每轮 SQL 一致性检查与 80 个记录 id）。

## 3. 第 2 组 · 降级

### 2.1 空 Key（配置 A，进程内 `DEEPSEEK_API_KEY` 未设置）

发布一对（demo02 LOST / demo03 FOUND）：

```
POST /api/records -> HTTP 200 / 0.140630s      (LOST  id=136)
POST /api/records -> HTTP 200 / 0.092790s      (FOUND id=137)
落库：1523 | 136 | 137 | 0.7200 | LOCAL_KEYWORD | EMPTY_KEY | NULL | NULL | NULL | pair-score-v1 | NEW
```

判据逐条：发布 200 ✓（不等打分，0.09–0.14s）；`engine=LOCAL_KEYWORD` ✓；`score=0.7200 ∈[0,1]` ✓；`latency_ms/prompt_tokens/completion_tokens` 全 NULL（没发请求，**不估算**）✓；记录侧 `pairState=DEGRADED`、`pairDegraded=true` ✓。
原始输出：`raw/G2-empty-key.txt`。

### 2.2 401 / 超时 / 非 JSON（配置 D，本机假端点，走同一条 `DeepSeekPairScorer` 代码路径）

每轮先把假端点切模式，再发布 1 条 + 改 1 条标题强制重算（pair 116）：

| 轮次 | 假端点行为 | 发布 | 改标题 PUT | 落库（pair 116） | 受影响 14 对的引擎分布 |
|---|---|---|---|---|---|
| 401 | 返回 401 | **200** / 0.24s | 200 | `LOCAL_KEYWORD` / `UNAUTHORIZED` / `latency_ms=3` | `LOCAL_KEYWORD × 14, reason=UNAUTHORIZED` |
| 超时 | sleep 3s（客户端 read=1000ms） | **200** / 0.46s | 200 | `LOCAL_KEYWORD` / `TIMEOUT` / `latency_ms=1010` | `LOCAL_KEYWORD × 14, reason=TIMEOUT` |
| 非 JSON | 200 + HTML | **200** / 0.018s | 200 | `LOCAL_KEYWORD` / `NON_JSON` / `latency_ms=1` | `LOCAL_KEYWORD × 14, reason=NON_JSON` |

三种故障下：发布接口始终 200、永远有分数（0.71）、`degraded_reason` 精确区分三种原因、`tokens` 保持 NULL 不估算、`pair.status` 保持 NEW。
原始输出：`raw/G2-degradation.txt` §2.2。
**关于配置 D 的假密钥串**：`sk-local-stub-not-a-real-key`（§1 表格第 47 行）是上一轮本机假端点 `127.0.0.1:18099/v1` 的固定占位值，不是凭据、对任何真实端点无效；它含连字符，因此不匹配零明文扫描的形状 `sk-[A-Za-z0-9]{24,}`。本轮为保留上一轮证据原文，**不改写**这一处，只在此单独说明。

### 2.3 恢复后兜底可重算

假端点切回 200 + 合法 JSON；把 45 条降级行的 `updated_at` 回拨 2 小时（模拟已过 `degraded-retry-minutes=1` 冷却窗口，**这是唯一的时间夹具**），触发 `POST /api/ops/pair-scan`：

```
scan: {"scanned":4,"rescored":44,"degraded":0,"skipped":0,"expired":1,"elapsedMs":10062,
       "counters":{"llmCalls":360,"llmSuccess":288,"degraded":72,"pairsWritten":360,
       "degradedByReason":{"UNAUTHORIZED":24,"TIMEOUT":24,"NON_JSON":24}}}
目标 pair 重算后：116 | 0.8700 | DEEPSEEK | NULL | 1 | 213 | 19
全表降级行：45 -> 1
```

判据：降级行被兜底扫描重算为 `engine=DEEPSEEK`、`degraded_reason=NULL`、tokens 落库（213/19）✓；剩余 1 行是另一条 `EMPTY_KEY` 记录（本次运行期间进程无密钥的那一段产生的），属预期。
原始输出：`raw/G2-degradation.txt` §2.3。

### 2.4 页面提示可见（真实浏览器，真实后端）

`vite preview`（生产构建产物）→ `/api` 代理到 18080，Edge headless 用 CDP 真实点击登录：

```
2.2b /my 降级徽标数量 = 2
2.2c /my 含「已降级」= true
2.2d /my 含「智能匹配暂不可用，已用关键词匹配」= true
2.2e 详情页 URL = /records/28
2.2f 详情页含「已降级」= true
2.2g 详情页含降级说明 = true
```

截图：`gate2-review/g2-01-my-degraded.png`（列表卡片徽标 + 整段说明）、`gate2-review/g2-02-detail-degraded.png`（详情页 pair 面板顶部提示）。

### 2.5 发布 P99

见 §2 的 1.3 表：稳态 P99 221 / 182 / 487ms，首次并发 287ms，全部 **< 500ms**。

## 4. 第 3 组 · 分档

配置 A（默认阈值 `contact_direct_threshold=0.90` / `contact_unlock_threshold=0.75`）。**三档夹具全部来自 B1 种子**，未改 score。

### 3.1 ≥0.90 直看档：双端可看

| pair | 组成 | score | 调用方 | 结果 |
|---|---|---|---|---|
| 1 | LOST#1(demo01) ↔ FOUND#13(demo02) | 0.9600 | demo01（失主端） | 200 `tier=DIRECT` `available=true` `contact=13800000101` |
| 1 | 同上 | 0.9600 | demo02（拾获端） | 200 `tier=DIRECT` `available=true` `contact=13800000001` |
| 3 | LOST#3 ↔ FOUND#15（两端同属 demo01） | **0.9200** | demo01 | 200 `tier=DIRECT` `available=true`，`viewerSide` 固定 `LOST`（契约 §4.14 的确定性规则） |

「双端可看」= 两端各自拿到**对方**的号码，且互不相同（1 ≠ 13 的号），不是同一串回显。
原始输出：`raw/G3-tiers.txt` §3.1。

### 3.2 0.75–0.90 授权档：未授权 → 授权 → 撤回

pair 4 = LOST#4(demo01) ↔ FOUND#16(demo02)，score **0.8600**，按顺序实跑：

| 步骤 | 请求 | 结果 |
|---|---|---|
| 清场 | demo02 `REVOKE` | 200 `public=false`；库里 `found_unlocked_at`/`found_unlock_note` 同时置 NULL |
| 未授权 | demo01 `GET /pairs/4/contact` | 200 `tier=AUTHORIZABLE` `available=false` `reason=NOT_PUBLIC` `message="对方尚未公开"` `contact=null` |
| 授权 | demo02 `PUBLISH` + `note="…请联系我 13800000999"` | 200 `public=true`；附言**打码后**入库：`[手机号已隐藏]` |
| 已授权 | demo01 `GET contact` | 200 `available=true` `contact=13800000104` `unlockedAt=2026-10-28 01:18:26` `note=...打码附言` |
| 对端视角 | demo02 `GET contact` | 200 `available=false` `message="对方尚未公开"`（只公开自己这一端，对端仍拿不到） |
| 撤回 | demo02 `REVOKE` | 200 `public=false`；库里两列同时 NULL（**附言随撤回清空**） |
| 撤回后 | demo01 `GET contact` | 200 `available=false` `reason=NOT_PUBLIC` `note=null`（回退到未授权态） |

另做幂等对照：同一 note 重复 `PUBLISH` 两次，`unlockedAt` 都是 `01:18:30`（不刷新、不重复通知）。
原始输出：`raw/G3-tiers.txt` §3.2、§3.4。

### 3.3 <0.75：所有入口一律 403/50002

| pair | score | 取号 GET | 公开 POST unlock | 联系 PATCH |
|---|---|---|---|---|
| 10 | 0.4200 | 403/50002 | 403/50002 | 403/50002 |
| 8 | 0.3400 | 403/50002 | 403/50002 | 403/50002 |

`t_access_log` 对应新增两行 `result=DENIED_LOW_SCORE`（score 0.4200 / 0.3400），说明拒绝发生在**档位判定**、且留了痕；对照 0.86 的 pair 4 同期能 `PUBLISH` 200，排除「权限整体坏掉」。
原始输出：`raw/G3-tiers.txt` §3.3、审计尾部。

### 3.4 阈值改配置 → 档位随之变化

配置 C 启动（`--findit.pair.contact_direct_threshold=0.85 --findit.pair.contact_unlock_threshold=0.40`）：

| pair | score | 改前（默认阈值） | 改后 | 判定 |
|---|---|---|---|---|
| 4 | 0.8600 | `AUTHORIZABLE`（需对方授权） | `tier=DIRECT` `available=true` 直接出号 | 档位随阈值上移 |
| 10 | 0.4200 | 403/50002 | 200 `tier=AUTHORIZABLE` `reason=NOT_PUBLIC`；`PUBLISH` 200 | 从拒绝变成可授权 |
| 8 | 0.3400 | 403/50002 | 仍 403/50002 | 新阈值之下依旧拒绝 |
| 3 | 0.9200 | `DIRECT` | 仍 `DIRECT` | 高分档未被改坏 |

原始输出：`raw/G3b-threshold.txt`。改动仅通过**启动参数**注入，未改配置文件、未改代码；测完已复原默认阈值进程。

## 5. 第 4 组 · 多对 pair

夹具（全部业务写入）：FOUND#27（demo03，「图书馆三楼捡到黑色充电宝」）+ LOST#28（demo01）+ LOST#29（demo02），三条标题/描述/地点完全相同，故分数 1.0000。

| 判据 | 实测 |
|---|---|
| 一条招领 × 两条寻物各成一个 pair（1:1×2） | `pair 35 = 28↔27`、`pair 44 = 29↔27`，各 1 行；`found_id=27` 共 12 行（2 行来自本夹具 score=1.0，其余 10 行是种子池的召回，score ≤0.18，未合并成一行的「多对多」） |
| 把其中一个置 CONTACTED | `PATCH /pairs/35 {action:CONTACT}` → 200 `已联系`，db `35 CONTACTED`；同一时刻 `44 NEW` |
| 另一个仍为 NEW 且仍可解锁 | `PATCH /pairs/44 {action:CONTACT}` → 200；`PUBLISH /pairs/44/contact-unlock` → 200 `public=true` |
| 两对互不影响、各自可取号 | demo01 取 pair 35 → 200 `contact=13900002222`；demo02 取 pair 44 → 200 `contact=13900002222`；demo03（招领方）取 44 → 200 `contact=13900004444` + 附言 |
| 该招领仍在候选池 | `POST /api/match` 命中 `recordId=27`，`recordStatus=PENDING`、`detailAccess=OPEN` |
| pair 进展不改记录状态 | 27/28/29 三条 `t_record.status` 全部仍 `PENDING` |

原始输出：`raw/G4-multi-pair.txt`（造数与 4.1–4.6）、`raw/G4b-multi-pair.txt`（4.7–4.10，用正确参与方账号复核四条视角）。

## 6. 第 5 组 · 终态

### 5.1 未达 MATCHED 标完成 → 409，status 不变

`record 30`（孤立口琴，无 pair 命中）：

```
改前：30  PENDING  completed_at=NULL   updated_at=2026-10-28 01:21:18
PATCH /api/records/30/status {"status":"COMPLETED"} -> HTTP 409
  {"code":20005,"message":"需双方确认匹配后才能标记完成",
   "data":{"currentStatus":"PENDING","required":"MATCHED_PAIR","matchedPairCount":0}}
改后：30  PENDING  completed_at=NULL   updated_at=2026-10-28 01:21:18   ← 三列逐字符一致
```

### 5.2 COMPLETED：列表 0 命中、匹配命中；score < 0.90 直连详情 404

夹具：LOST#31(demo01) ↔ FOUND#32(demo02)，**score 0.8000**（0.75–0.90 区间，靠「同描述同地点、不同标题、同分类 + 关键词」落在 0.80）。双方 `CONFIRM` 后 `MATCHED`，再标完成：

```
pair 74: 0.8000 MATCHED  lost_confirmed 01:22:06  found_confirmed 01:22:07
PATCH /api/records/31/status COMPLETED -> 200 {"status":"COMPLETED","matchedPairCount":1}
```

| 判据 | 实测 |
|---|---|
| 公共列表 0 命中 | `GET /api/records?pageSize=50`（total=26）中 `recordId=31` 出现 **0** 次 |
| 匹配仍命中 | `POST /api/match` → `recordId=31, score=0.91, status=COMPLETED, recordStatus=COMPLETED, detailAccess=RESTRICTED`（保留期内仍在候选池，且**不返回号码**） |
| score < 0.90 直连详情 404 | demo02（对端，pair 0.80）`GET /api/records/31` → **404/20001**；demo03（无关）→ 404 |
| 对照 1：score ≥ 0.90 的对端 → 200 | `record 23`（COMPLETED，保留期内）对 demo01（pair 12，0.9500 MATCHED）→ **200 遮蔽视图** |
| 对照 2：IGNORED 的 0.93 不算合格对端 | demo02（pair 11，0.9300 IGNORED）→ 404 |
| 对照 3：所有者 → 404（契约 §4.2 第 4 条明文），走 `/my` | demo03（record 23 所有者）→ 404；`/api/my/records` 中 record 23 出现 1 次；demo01 的 `/my` 中 record 31 出现 1 次 |

> 所有者看自己 COMPLETED 的详情返回 404 是**契约明文**（「按架构 A9 字面；所有者走 /my」），不是缺陷。复核时先按「403/404 都是错」的直觉踩了一次，已回查 §4.2 更正。

原始输出：`raw/G5-terminal-1..5.txt`。

### 5.3 CLOSED：列表与匹配双 0 命中；已有 pair 取号 403

`record 24`（LOST / CLOSED）：

```
公共列表 total=26，recordId=24 出现 0 次
POST /api/match（"第一食堂一楼门口 黑框眼镜…"）-> recordId=24 出现 0 次（同文本的 22 命中 0.53）
pair 13（24↔22，0.8800 CONTACTED）
GET  /api/pairs/13/contact      -> 403 {"code":50005,"message":"该配对已失效"}
PATCH /api/pairs/13 {CONTACT}   -> 403 {"code":50005,...}
```

### 5.4 到期后从匹配消失

`record 23`（COMPLETED，`completed_at=3 天前`，保留期内）：

```
到期前：POST /api/match("第二教学楼楼梯口 蓝色挂件 钥匙") -> recordId=23 命中 1 次（score 1.00, RESTRICTED）
夹具：UPDATE t_record SET completed_at = now() - 31 day WHERE id=23   （只改时间，不改 status）
到期后：同一请求 -> recordId=23 命中 0 次；t_record 仍是 23 / COMPLETED（系统不改写状态）
```

原始输出：`raw/G5-terminal-3.txt` §5.4。

## 7. 第 6 组 · B2 会话（门禁 1 转挂）

| 格 | 判据 | 实测 |
|---|---|---|
| 6.1 无 session | 四个接口 401/10006 | `GET /pairs/1/contact`、`POST /pairs/1/contact-unlock`、`PATCH /pairs/1`、`PATCH /notifications/2/read` **全部 401 / code 10006 / "请先登录"** |
| 6.2 非本 pair 参与方 | 四个接口 403/10007，且审计无「成功」行新增 | demo03（pair 1 两端是 demo01/demo02）四个接口**全部 403 / 10007**；`t_access_log` 总行数 0→1，**新增行是 `DENIED_NOT_OWNER`，`GRANTED` 行 0** |
| 6.3 不存在 / 超界 / 非数字 id | 404 不 500 | pair 接口 `999999` / `9223372036854775807` / `99999999999999999999`（超 bigint）/ `abc` / `-1` / `0` 共 18 次调用**全部 404 / 20004**；notification 接口 `999999` / `abc` / `-1` **全部 404 / 50004**；无一 500 |
| 6.4 标记他人通知已读 | 403/10007 | demo01 改 notif 2（属 demo02）→ **403/10007**；`t_notification.read_at` 仍为 NULL |
| 反向对照 | 合法参与方不被误拦 | demo01 取 pair 1 → 200 `DIRECT`；`PATCH pair 1 CONTACT` → 200；改**自己**的 notif 1 → 200 `readAt=2026-10-28 01:16:07` |

原始输出：`raw/G6-b2-session.txt`。

## 8. 第 7 组 · 真实 DeepSeek 调用 → PASS

**结论：PASS（1/1）。** 2026-10-28 07:33–07:35 在真 key 环境下跑完 `D1-deepseek-live.md` 的 5 步，9 行真实调用全部达标；07:36–07:38 去掉密钥重启做同内容对照，降级行为不变。

### 8.1 密钥与注入（只写路径，不写值）

| 项 | 值 |
|---|---|
| 来源 | `%USERPROFILE%\.hifox-secrets\deepseek_api_key.txt` |
| 可读性 | `test -s` 通过，`wc -c` = **35**，注入后 `key-length=35`（`raw/g7-key-read.txt`） |
| 注入 | 与该次 `java -jar … --server.port=18082` **同一条 shell**：`export DEEPSEEK_API_KEY="$(cat <路径>)"`，不换会话、不用 `Start-Process`/后台守护 |
| 端点 / 模型 | 默认 `https://api.deepseek.com/v1` / `deepseek-chat`（`findit.llm.*` 配置值） |
| 库 | 新建 `findit_gate2_64_g7`（schema + seed 后清空业务表：3 账号 / 9 分类 / 0 记录 / 0 pair） |

上一轮记为「不可判定 · 运行时无法读取密钥」，根因是**只查了环境变量**（dsh / codebuddy 运行时不继承 agent 级环境变量），不是密钥无效。本轮显式注入后 `/api/health` → `status=UP / db=UP`，同一批样本 9 对全部走真实端点。

### 8.2 三档结果

| 档位 | model | 时间（Asia/Shanghai） | latency_ms | prompt_tokens | completion_tokens | score | engine |
|---|---|---|---|---|---|---|---|
| 高相似（充电宝：同标题同描述） | deepseek-chat | 2026-10-28 07:35:38 | 1459 | 245 | 29 | 1.0000 | DEEPSEEK |
| 中相似（雨伞：同物不同措辞） | deepseek-chat | 2026-10-28 07:35:39 | 600 | 206 | 28 | 0.8500 | DEEPSEEK |
| 低相似（保温杯 ↔ 蓝牙耳机） | deepseek-chat | 2026-10-28 07:35:42 | 775 | 215 | 28 | 0.0000 | DEEPSEEK |

`model` 列填配置值：打分器不把响应里的 `model` 落库，这里不臆造回显值。
9 行全量（3 LOST × 3 FOUND）：`latency_ms` 457–1459、prompt 206–245、completion 25–33、`score` 0.0000–1.0000。

判据逐条（SQL 聚合，原始输出同一文件）：

```
deepseek_rows  latency_pos  prompt_pos  completion_pos  score_in_range  reason_null  prompt_ver_ok
9              9            9           9               9               9            9
```

### 8.3 与 LOCAL_KEYWORD 的同内容对照

停进程、`unset DEEPSEEK_API_KEY`（实测 `len=0`）后同 jar / 同库 / 同端口重启，用**完全相同的三份 LOST 正文**再发一次，新记录与阶段 A 的同一条 FOUND 成 pair：

| 档位 | DEEPSEEK（真 key） | LOCAL_KEYWORD（无 key） | 说明 |
|---|---|---|---|
| 高相似 | 1.0000 / 1459ms / 245+29 | **1.0000** / `EMPTY_KEY` / NULL | 同标题同描述，关键词路径也能满分 |
| 中相似 | **0.8500** / 600ms / 206+28 | **0.1600** / `EMPTY_KEY` / NULL | 差距 0.69：模型识别「同一把折叠伞」，关键词只共享「伞 / 体育馆」 |
| 低相似 | 0.0000 / 775ms / 215+28 | 0.0100 / `EMPTY_KEY` / NULL | 均判无关 |

### 8.4 去掉密钥后：发布仍 200、仍降级

```
高相似 LOST  → HTTP 200 / 0.167s   id=7
中相似 LOST  → HTTP 200 / 0.054s   id=8
低相似 LOST  → HTTP 200 / 0.061s   id=9
阶段 B 新行：9/9 engine=LOCAL_KEYWORD，9/9 degraded_reason=EMPTY_KEY
             latency_ms / prompt_tokens / completion_tokens 全 NULL（不估算）
记录侧：record 7/8/9 均 pairState=DEGRADED、pairDegraded=true
```

§0 第 2 组里「真实调用成功一次（model / latency / tokens）」这一格**已由上表补齐**；原有 §2.2 的 401 / 超时 / 非 JSON 三种降级结论**不变**（仍是本机假端点、同一条 `DeepSeekPairScorer` 路径），本轮不重跑、交叉引用即可。

### 8.5 原始输出与零明文

- `raw/g7-live-deepseek.txt`：阶段 A（发布 → 等待 → 全量行 → 判据聚合 → 含两端标题的明细）
- `raw/g7-no-key-degraded.txt`：阶段 B（无 key 重启 → 同内容重发 → 同内容并列对比 → 引擎分布 → 记录侧标志）
- `raw/g7-key-read.txt`：`ls -l` / `test -s` / `wc -c` / `key-length`
- `raw/g7-secret-scan.txt`：零明文扫描的命令与原始输出。口径分三层——真实密钥形态 `sk-[A-Za-z0-9]{24,}` 对**全库**（tracked + 未跟踪）扫描 **0 命中**（`git grep` 与 `grep -rn` 各一次，均 exit=1）；宽松前缀 `sk-` 对 `raw/g7-*.txt` 四个数据文件 **0 命中**；对本文与 `D1-deepseek-live.md` 的 `sk-` 命中只有 §1 配置 D 占位串与本文/文档的自述引用，均非凭据（见 §3 说明行）。

## 9. 未覆盖 / 风险陈述

1. **真实 DeepSeek 的样本面很窄**：§8 只有 3 对（9 行），三档结论只说明「同物同描述 / 同物不同措辞 / 完全不同」能被区分。长描述、模糊表述、同名不同物、跨语言、带图片的样本都没测；也不能由此推断线上分数分布。
2. **降级的 401/超时/非 JSON 用本机假端点**：走的是同一条 `DeepSeekPairScorer` 代码路径，但不是供应商真实行为；真实网络抖动、429 限流、IPv6/代理问题未覆盖。
3. **发布 P99 余量小**：稳态最好 182ms、最差 **487ms**（阈值 500ms）。第 4 轮变慢发生在配对表从 165 行涨到 312 行、且后台仍在补算的过程中，属「数据量增长 + 后台打分争用」的综合结果，**不建议把这台机器的 487ms 当成容量承诺**；冷启动/更大数据量需重测。
4. **并发维度只测了「发布」**：没有测并发取号、并发 `CONFIRM`、并发 `PUBLISH/REVOKE` 同一条 pair。`PairLockService`/命名锁对这些路径的保护本次未验证。
5. **分档边界值**：实跑覆盖 0.96 / 0.92 / 0.86 / 0.42 / 0.34，**没有**测 0.7499 / 0.75 / 0.8999 / 0.90 这种恰好落在阈值上的小数（`decimal(5,4)` 四舍五入到 2 位的边界未单独构造）。
6. **到期只测了 COMPLETED**：CLOSED 恒 0 命中已测；`completed_retention_days` 改配置后的边界（29 天 / 31 天）未重复验，只验了 3 天 / 31 天。
7. **多对 pair 的召回面**：一条招领会与候选池 top10 各成一行（本次 12 行），这是设计（`top-n=10`），但也意味着「1:1×2」只在**夹具两条**上成立；如果期望「一条招领只和两条寻物成对」，那是产品口径问题，不是本次能判定的缺陷。
8. **UI 只验了降级的可见性**：三档的 UI 表现（E2 已交付）本次没有重跑，只在 API 层验了档位；`/my` 卡片上出现了两个「已降级」徽标（`data-testid="my-record-degraded"` + 文案重复），属观感问题，不计入本门禁。
9. **测试库状态**：`findit_gate2_64` 经过多轮夹具与并发压测（最终 135 条记录、696 行 pair、49 行 `t_access_log`），**不是**干净种子库。本文每组都标注了当时的配置与库状态；要逐格复现，建议按 §10 顺序从空库重跑。
10. 复核只降低风险，不代表「零缺陷」。以上结论只覆盖列出的 7 组判据。

## 10. 证据清单与复现

| 内容 | 路径 |
|---|---|
| 本文 | `docs/testing/v2/gate2-review.md` |
| 浏览器截图（2 张） | `docs/testing/v2/gate2-review/g2-01-my-degraded.png`、`g2-02-detail-degraded.png` |
| 第 1 组原始输出 | `docs/testing/v2/gate2-review/raw/G1-idempotency.txt`、`G1-concurrency.txt` |
| 第 2 组原始输出 | `raw/G2-empty-key.txt`、`raw/G2-degradation.txt` |
| 第 3 组原始输出 | `raw/G3-tiers.txt`、`raw/G3b-threshold.txt` |
| 第 4 组原始输出 | `raw/G4-multi-pair.txt`、`raw/G4b-multi-pair.txt` |
| 第 5 组原始输出 | `raw/G5-terminal-1.txt` … `raw/G5-terminal-5.txt` |
| 第 6 组原始输出 | `raw/G6-b2-session.txt` |
| 第 7 组原始输出 | `raw/g7-live-deepseek.txt`（真 key）、`raw/g7-no-key-degraded.txt`（去 key 对照）、`raw/g7-key-read.txt`（密钥可读性）、`raw/g7-secret-scan.txt`（零明文扫描：密钥形态全库 0 命中） |

复现顺序（本机，Git Bash + PowerShell 混用；库名自定）：

1. 建库：`db/schema.sql` + `db/seed.sql`（仅替换库名），7 表 / 24 记录 / 14 pair。
2. `cd server && mvn -B -DskipTests package`，`cd web && npm run build && npm run type-check`。
3. 配置 A 起服务（端口 18080，`FINDIT_DB_URL` 指向该库，`OPS_SCAN_TOKEN` 自定，不设 `DEEPSEEK_API_KEY`）→ 跑第 3、4、5、6 组与第 2 组 2.1。
4. 配置 C（追加 `--findit.pair.contact_direct_threshold=0.85 --findit.pair.contact_unlock_threshold=0.40`）→ 跑 3.4，跑完复原。
5. 配置 D（假密钥 + `FINDIT_LLM_BASE_URL=http://127.0.0.1:18099/v1` + 三个 timeout/熔断参数）→ 跑第 1 组、第 2 组 2.2/2.3；假端点模式用文件切换（`ok`/`401`/`timeout`/`nonjson`）。
6. 第 7 组（配置 E/F）：新库 `findit_gate2_64_g7` → 同一条 shell 内 `export DEEPSEEK_API_KEY="$(cat %USERPROFILE%\.hifox-secrets\deepseek_api_key.txt)"` 后 `java -jar … --server.port=18082` → 发布 3 对样本（`raw/g7-live-deepseek.txt`）；停进程、`unset` 后同端口重启 → 同正文再发 3 条 LOST（`raw/g7-no-key-degraded.txt`）。判据与表格见 `D1-deepseek-live.md`。

> 本次复核未改任何业务代码、未改 `db/`、未推 `main`、未合并 `main`；本分支只新增 `docs/testing/v2/gate2-review.md` 与 `docs/testing/v2/gate2-review/` 下的证据文件。
