# AI 语义匹配模块：接口定义与可替换实现方案（1.2 交付物 3）

> 服务对象：3.2（实现引擎）、3.1（`/api/match` 编排）、5.1（效果评估）、6.1（架构章节）。
> 目标验收：「把实现从本地算法换成外部模型时，只需替换实现类，接口签名与调用方代码不变」——本文用**类型边界 + 装配方式 + 反例清单**三件事把它钉死。

---

## 1. 边界划分：谁负责什么

```
MatchController ──► MatchService ──► MatchEngine (接口) ──► KeywordMatchEngine（默认，本地）
                        │                    ▲            └► ExternalEmbeddingMatchEngine（可替换）
                        │                    │
                        ├─► MatchCandidatePoolPort ──► RecordMapper（候选池取数，只读）
                        ├─► RecordMapper（回表补展示字段 + 过滤非法候选）
                        └─► MatchResultMapper（写 t_match_result 快照）
```

三条边界规则，违反任何一条就等于"接口独立"没做到：

1. **引擎不碰数据库连接**（不注入 Mapper、不写 SQL 之外的表），它只消费 `MatchCandidatePoolPort` 递到手里的一池候选文本。
   挡住的变化：换实现时不需要理解 MySQL 表结构；外部实现可以被单测覆盖（无需起库）。
2. **引擎只返回 `recordId + score + reason`**，返回展示字段（title/type/status/…）是非法的。
   挡住的变化：候选合法性（未删除、状态可展示、不越权）由 `MatchService` 统一保证，不由算法各自实现一遍。
3. **引擎不知道 HTTP**：没有 `HttpStatus`、没有 `ApiResponse`、不接收 DTO。异常只有 `MatchEngineException` 一种。

---

## 2. 接口签名（3.2 直接照此建文件，勿改签名）

包：`com.findit.campus.match.engine`

```java
/** 语义匹配引擎的唯一抽象。实现类必须无状态、线程安全。 */
public interface MatchEngine {

    /** 实现标识，值域与 t_match_result.impl 的 CHECK 约束一致。 */
    MatchEngineKind kind();

    /** 是否可用（外部实现用于自检 Key/网络是否就绪）。默认 true。 */
    default boolean available() { return true; }

    /**
     * 输入一段描述，返回按分数倒序、已过滤 topN/minScore 的候选。
     * 契约：结果非 null（无命中返回空列表）；recordId 不重复；不含 query.excludeRecordId；
     *      score ∈ [0,1] 且 ≥ query.minScore；size ≤ query.topN。
     * @throws MatchEngineException 引擎自身不可用/超时/内部异常；业务性拒绝（参数不合法）
     *         应在 MatchService 里挡在调用之前，不由引擎抛。
     */
    List<MatchCandidate> match(MatchQuery query) throws MatchEngineException;
}
```

```java
/** 引擎标识。新增实现必须同步新增枚举值 + schema.sql 的 CHECK 值域 + 本文件 §5 的装配分支。 */
public enum MatchEngineKind {
    LOCAL_KEYWORD, EXTERNAL_EMBEDDING;
}
```

```java
/** 一次匹配请求的引擎视图（与 HTTP DTO 解耦：MatchController 的 DTO 由 MatchService 转换过来）。 */
public record MatchQuery(
        String text,                 // 已校验，非空白，长度 2..2000
        RecordType type,             // 可为 null = 不限类型
        Long categoryId,             // 可为 null
        int topN,                    // 1..20
        BigDecimal minScore,         // 0..1
        Long excludeRecordId         // 可为 null
) {}
```

```java
/** 引擎的唯一输出单元。刻意不含展示字段（见 §1 规则 2）。 */
public record MatchCandidate(
        Long recordId,
        BigDecimal score,            // 0..1，scale=2，HALF_UP
        String reason                // 非 null；前端只展示不解析
) {}
```

```java
/** 引擎取候选池的唯一出口（依赖倒置：接口在 engine 包，实现类是 record.mapper.RecordMapper）。 */
public interface MatchCandidatePoolPort {
    /** 返回候选记录（未删除、status=PENDING、type 匹配、按 createdAt 倒序、最多 poolSize 条）。 */
    List<PoolItem> loadPool(RecordType typeOrNull, int poolSize);

    record PoolItem(Long recordId, String title, String description,
                    Long categoryId, String location, LocalDateTime occurredAt) {}
}
```

```java
/** 引擎侧唯一的异常类型。MatchService 捕获它 → 映射 30002/30003。 */
public class MatchEngineException extends RuntimeException {
    public enum Reason { UNAVAILABLE, TIMEOUT, INTERNAL }
    public MatchEngineException(Reason reason, String message, Throwable cause) { … }
    public Reason reason();
}
```

**不需要**再加 `MatchEngineRegistry`、`AbstractMatchEngine`、`MatchEngineChain` 这类东西——只有一个注入点、两个实现，加一层"注册表"挡不住任何真实变化，只增加阅读成本。

---

## 3. 两个实现：接入点与代价

### 3.1 `KeywordMatchEngine`（本地，默认，包 `engine.local`）

- 依赖：仅 `MatchCandidatePoolPort` + `CategoryMapper`（读 `t_category.keywords`）+ `MatchProperties`。
- 算法基线（供 3.2 参考，不属接口契约，可自由迭代）：中文 bigram 切分 → 标题/描述 TF-IDF 或词频 Jaccard → 分类一致加分 → 地点字符串相似加分 → `occurredAt` 与 now 的天数衰减 → 归一到 `[0,1]`。
- 优点：**零外部依赖、可离线演示、结果确定可复现**（5.1 的正/负例断言不会飘）；`db/seed.sql` 末尾的 S1–N4 样例可直接当单测。
- 代价：语义泛化弱（"充电宝"↔"移动电源"命中不了）；答辩讲"语义"时要诚实说明当前是关键词+加权，改进方向是换实现（这恰好是把"可替换"讲成加分点的地方）。
- 失效场景：候选池外的记录召回为 0（池上限 500）；同义词/拼音/错别字输入效果断崖下降；数据量大时打分成本线性上升。

### 3.2 `ExternalEmbeddingMatchEngine`（外部模型，包 `engine.remote`，默认不启用）

- 依赖：`MatchCandidatePoolPort` + `EmbeddingClient`（**本包内的接口**，把 HTTP/SDK/JSON 细节挡在这里）+ `MatchProperties`。
- 流程：输入与候选池各取 embedding → 余弦相似度 → 归一 → topN。
- **实现约束（重要）**：候选池 embedding 只在请求内计算（N 次批量调用，N ≤ poolSize），或放进程内 Caffeine/简单 LRU。**不在三张表里加 `embedding` 列，也不建第四张表** —— 需要持久化向量时先报人类（ADR-002 触发条件②）。
- 代价：配额与费用、网络抖动、结果不可完全复现（评估需注明）、Key 管理、隐私（描述文本会发给第三方 —— **这条要在答辩里主动说，并配 ADR 记录**）。
- 失效场景：断网/配额耗尽 ⇒ `available()=false` 或调用抛 `UNAVAILABLE` → 契约返回 503/30002。

### 3.3 换实现的成本表（这就是验收要证的点）

| 变更 | 需要动的文件 | 不需要动 |
|---|---|---|
| 本地 → 外部 | `application.yml`（`engine: external`）+ 环境变量；如需新枚举值则加 `MatchEngineKind` 与 `schema.sql` CHECK | `MatchEngine` / `MatchQuery` / `MatchCandidate` / `MatchService` / `MatchController` / 请求响应 DTO / 前端全部代码 |
| 外部换供应商 | `EmbeddingClient` 的实现类 + 3 个配置项 | 其余同上 |
| 换算法（本地内部改进） | `KeywordMatchEngine` 内部 | 接口、调用方、契约 |

---

## 4. 依赖注入与切换方式

配置（前缀 `findit.match`，绑定到 `MatchProperties` 记录类，`@Validated` 启动即校验）：

```yaml
findit:
  match:
    engine: local          # local | external —— 唯一的切换开关
    pool-size: 500         # 候选池上限（1..2000）
    external:
      base-url: ${FINDIT_EMBED_BASE_URL:}      # engine=external 时必须非空
      api-key: ${FINDIT_EMBED_API_KEY:}        # 同上
      model: ${FINDIT_EMBED_MODEL:text-embedding-v3}
      connect-timeout: 1s
      read-timeout: 3s
```

装配（`common.config.MatchEngineConfig`）：

```java
@Bean
@ConditionalOnProperty(prefix = "findit.match", name = "engine",
                       havingValue = "local", matchIfMissing = true)
MatchEngine keywordMatchEngine(MatchCandidatePoolPort pool, CategoryMapper categories, MatchProperties props) {
    return new KeywordMatchEngine(pool, categories, props);
}

@Bean
@ConditionalOnProperty(prefix = "findit.match", name = "engine", havingValue = "external")
MatchEngine externalMatchEngine(MatchCandidatePoolPort pool, MatchProperties props) {
    return new ExternalEmbeddingMatchEngine(new HttpEmbeddingClient(props.external()), pool, props);
}
```

```java
@Service
class MatchService {
    MatchService(MatchEngine engine, …) { this.engine = engine; }   // 只依赖接口
}
```

要点：
- `matchIfMissing = true` ⇒ 不配任何东西时是**本地实现**，演示环境零配置可跑（可靠性优先）。
- 容器里**恒只有一个 `MatchEngine` bean** ⇒ 注入不需要 `@Qualifier`、不需要 `List<MatchEngine>`，也就不存在"两个实现谁生效"的歧义。
- 启动日志固定打一行：`match engine = LOCAL_KEYWORD (poolSize=500)`。排障和录屏都靠它。
- **不做自动回退到本地**（ADR-009）：外部挂了就是 503。理由是评分诚信——静默降级会让用户以为拿的是语义分数，评估数据（`impl` 列）也会被污染。
  放弃的东西：可用性。若人类要求"外部失败自动降级本地"，实现方式是把两个引擎 bean 都注册 + `MatchService` 注入 `Map<String, MatchEngine>`，代价是分数口径混池，且 5.1 的评估必须按 `impl` 分组重新解读 → **Q-10 待拍板**。

---

## 5. `MatchService` 编排顺序（写给 3.1，别写成"调引擎然后原样吐出"）

1. 校验 `text`（空白/长度）→ `30001`；校验 `topN`/`minScore`/`type` → `10001`；`categoryId` 存在性 → `40001`。
2. `engine.available()` 为 false → `30002`（快速失败，不做无谓计算）。
3. `pool = poolPort.loadPool(query.type, props.poolSize())`，剔除 `excludeRecordId`。
4. `candidates = engine.match(query)` —— 捕获 `MatchEngineException`：`TIMEOUT`→30003，其余→30002；其他异常→记 error 后抛 10005（**绝不裸抛给前端**，这是 3.2 的验收点）。
5. 回表：`recordMapper.selectDisplayByIds(ids)` → `WHERE deleted_at IS NULL AND status = 'PENDING'`；丢弃库里已不存在的候选（合法性过滤）。用 `status='PENDING'` 而不是 `<> 'CLOSED'`：池取数与回表用同一个口径，免得"CLAIMED 到底进不进结果"这件事在两处写法不一致。
6. 截断到 `topN`（过滤后不足就少给，**不许二次补齐**——补齐会引入未过滤的候选）。
7. 写快照（`try/catch` 包裹，失败仅 warn），返回 DTO。

步骤 5 与 6 的顺序很重要：先过滤再截断，否则 `topN=1` 时可能返回一条已删除记录。

---

## 6. 怎么验证"接口独立、实现可替换"（把验收做成可执行的检查）

| # | 检查 | 判定 |
|---|---|---|
| V1 | 编译期：在 `MatchController`/`MatchService` 里全文搜索 `KeywordMatchEngine`、`ExternalEmbedding` | **0 命中**（只允许出现在 `MatchEngineConfig`） |
| V2 | 把配置改成 `engine: external`（不配 Key）后启动并调 `/api/match` | 返回 `503` + `code 30002`，且**没有任何调用方代码被修改**（`git diff` 只含 yml） |
| V3 | 单测：`MatchEngineTest` 用假实现 `FakeEngine` 注入 `MatchService` | 断言响应 items 与 `FakeEngine` 输出一致 ⇒ 证明契约不绑定具体实现 |
| V4 | `MatchEngineContractTest`（参数化，跑两个实现 + Fake） | 两者都满足 §2 的 6 条契约（倒序、≤topN、≥minScore、去重、排除自身、score∈[0,1]）——**换实现时唯一需要跑绿的测试** |
| V5 | ArchUnit（可选，Q-14） | `match.web`/`match.service` 不依赖 `match.engine.local`/`.remote`；`engine.*` 不依赖 `match.web` |
| V6 | 样例集 S1–N4（`db/seed.sql` 末段） | 每条正例目标 `recordId` 出现在结果且排第一；N2 断言无高分误报；N4 断言 `CLAIMED`(23) 与已删除(25) 记录不出现在任何结果里 |

V4 是这套设计的价值核心：它把"可替换"从一句 PPT 话术变成一条会失败的测试。建议 5.1 直接把它列进测试报告。

---

## 7. 明确不做的东西（写下来挡住顺手加）

- 不做流式/SSE 返回、不做异步任务轮询（匹配是同步计算，输入 2000 字内、池 500 条，同步可接受；**耗时需实测**）。
- 不引入向量数据库（Milvus/ES-knn/pgvector）：与三张表 + 单机的基线冲突，且无规模依据。
- 不做模型训练/微调、不做 embedding 持久化表。
- 不做匹配结果的缓存层（重复输入直接命中缓存会绕过快照，评估样本会被吞掉）。
- 不做多路召回融合（BM25 + 向量加权）——先证明单实现效果不足再谈。

---

## 8. 3.2 落地记录（2026-10-22）

**实现**：`server/src/main/java/com/findit/campus/match/engine/local/KeywordMatchEngine.java`
**装配**：`common/config/MatchEngineConfig#keywordMatchEngine`，`@ConditionalOnProperty(prefix="findit.match", name="engine", havingValue="local", matchIfMissing=true)`，容器内唯一 `MatchEngine` bean。

- **依赖**：只注入 `CategoryMapper`，在首次匹配时读一次 `t_category.keywords` 并在进程内缓存；不注入 `RecordMapper`、不读 `t_record`、不写库、不认识 HTTP。
- **打分**：中文 unigram + bigram、英文/数字整词；权重 = 基础权重 × 本次候选池 IDF；文本覆盖 0.78 + 标题覆盖 0.22 + 地点 bigram 覆盖 0.07 + 类目词表命中 0.25；`setScale(2, HALF_UP)` 后 clamp 到 `[0,1]`。
- **契约**：引擎自身保证非 null、`size ≤ topN`、`score ≥ minScore`、去重、降序（同分按 recordId 升序）；`excludeRecordId` 由 `MatchService` 在调用引擎前剔除（3.1 已实现，引擎入参里没有这个字段）。
- **样例结果**：S1–S6 top1 = 13/14/15/16/17/18；N1 top1 = 7 且 13–22 不出现；N2 空；N3 为 4/16 且 18 不出现；N4 top1 = 18，23（CLAIMED）/25（已删除）不进候选池。
- **换 external 的改动面**：新增 `engine/remote/ExternalEmbeddingMatchEngine` + 包内 `EmbeddingClient`；在 `MatchEngineConfig` 加一个 `havingValue="external"` 的 `@Bean`；`application.yml` 设 `findit.match.engine=external` 并给 `base-url/api-key/timeout`。`MatchEngine` / `MatchQuery` / `MatchCandidate` / `MatchService` / `MatchController` / DTO / 前端均不改。
- **代价与边界**：同义词（充电宝↔移动电源）、拼音、错别字召回弱；候选池外记录召回为 0；单次成本 O(候选池 × 查询词数)，池上限 500。需要语义泛化时按 §3.2 换实现。
