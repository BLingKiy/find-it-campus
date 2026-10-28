# ADR：AI 发布闸门（审核 + 去隐私后处理 + 双通道文本）

- 任务：ZHYYKFX-85「发布闸门：后处理信息经 AI 审核通过才上架（设计 + 实现）」
- 作者：磐石石（后端架构师）
- 日期：2026-10-28
- 状态：已实现，待人类验收
- 分支：`feat/v2-Z85-review-gate`（基于 `origin/feat/v2-integration @ 6ce5700`，不合并 main）
- 相关契约：`docs/api-contract-v2.md`、`docs/design/v2-architecture.md` §6 / §8 / §9

---

## 1. 结论先行

**推荐方案：审核态与业务三态正交，落 `t_record.review_state`；对外可见性收敛为生成列 `is_visible`；发布/编辑改为「同步落库 + 异步审核」；展示与比对拆成 `display_text` / `match_text` 两条通道；默认 `findit.review.mode=off`，行为与 v2.0 完全一致。**

三个关键理由：

1. **不改三态状态机**：`PENDING/COMPLETED/CLOSED` 已被 45 条级验收证据、PATCH 流转、COMPLETED 保留期绑定；把「审核中」塞进 `status` 会改写既有不变量（例如「公共列表恒 PENDING」）。审核只回答「是否允许对外可见」，用独立列 + 一个生成列即可。
2. **可见性只有一个事实来源**：`is_visible = (review_state='APPROVED' AND deleted_at IS NULL)` 是 MySQL 生成列，列表/匹配/候选池/配对/详情全部引用它。漏一处就是「未审核内容能被看到」，靠人眼 grep 12 处 `status='PENDING'` 不可靠；生成列把「加审核态」变成「加一个条件」，并可用一条结构性测试守住。
3. **双通道是隐私与效果的唯一可行折中**：公示文本必须去身份 + 泛化；比对文本必须保留地点/时间/特征，否则「第三教学楼 201」被泛化成「教学楼」会直接拉低匹配。两条通道分开落库，对外只出 `display_text`，`match_text` 只进打分与 `contentHash`。

代价（不隐藏）：

- 发布从「立即可见」变成「秒级到数十秒后可见」；LLM 不可用时记录停在待审（安全但不可用），需要人工兜底。
- 多一次 LLM 调用（发布路径），成本与 P95 延迟上升；`temperature=0` 只能提高一致性，不能承诺「必然遮住某一类」。
- 新增两套文本与审计表，存储与运维复杂度上升；`raw_*` 只给本人，读取走审计。
- 默认 `off` 意味着演示/回归包不受影响，但「闸门」只有显式打开才生效；这是为了避免改坏既有 45 条证据，不是遗漏。

---

## 2. 约束与假设

### 2.1 已确认的输入（来自任务书 / 人类澄清）

| 约束 | 影响 |
|---|---|
| 审核通过才允许进公共列表、候选池、可配对 | 所有对外可见 SQL 必须加可见谓词；未过审不触发打分 |
| 失败策略：重试 3 次、指数退避，仍失败停 `PENDING` 并转人工，绝不自动上架 | 审核服务必须区分「可重试失败」与「确定性拒绝」 |
| 规则打码在审核之前；不改 `MaskingPolicy` 既有规则 | 规则双检是**追加**的确定性扫描，不放宽既有打码 |
| 不改打分算法、不改三档解锁语义 | `PairScoringService` 的分数计算/阈值/upsert 列清单不动，只换输入文本与新增 reason 落库 |
| 默认 `findit.review.mode=off` | 发布/编辑走原路径；新列默认 `APPROVED` |
| seed 24 条视为已通过 | `t_record.review_state` 默认 `APPROVED` + seed 显式回填 |
| 不合并 main；设计+实现落分支 | 交付分支 + commit，合并与出包等人类点头 |
| 双通道：`match_text` 只剥身份；`display_text` 全脱敏+泛化 | `raw_*` / `match_*` / `title/description/location` 三份文本 |
| 送 LLM：身份类不送，场景类可送 | prompt 输入只来自 `match_*`，并经 `IdentityScrubber` |
| `summary` ≤40 字，含类别+关键特征+模糊地点 | prompt 硬约束 + 长度校验；三要素靠 prompt + 抽样人审，不做不可靠的机器语义判定 |
| 规则双检命中即 `REJECTED`，理由写「规则双检命中」 | 模型 decision 只是第一道筛，不是放行凭证 |

### 2.2 尚未确认、需要人类拍板的假设

1. **失败阈值**：`max-attempts=3`、退避 `300ms * 2^(n-1)`、单次超时 `45s` 是经验值，需按真实 P95 与成本实测调整。
2. **`rewritten` 文本是否默认采用**：当前**默认采用**（`title/description/location` 直接用模型输出），`review_engine` 记录引擎、`review_prompt_version` 记录版本。是否改为「仅在人工确认后采用」需要拍板。
3. **人工审核入口的权限模型**：本单提供 `/api/ops/reviews/*`，沿用现有 ops 接口的访问控制口径；是否要给独立角色/审计员需要产品确认。
4. **`raw_*` 的保留期**：当前随记录逻辑删除保留；是否要在删除后物理清除或单独加密，需要合规确认。
5. **摘要三要素的验收方式**：当前是 prompt 约束 + ≤40 字硬校验 + 抽样人审；若要求机器可判定，需要产品给出「类别/特征/地点」的词表或标注样本。

### 2.3 规模与性能假设

| 项 | 取值 | 来源 |
|---|---|---|
| 单条审核 LLM 调用 | 900 max tokens，1 次/条（重试另计） | 本设计建议，需实测 |
| 发布同步开销 | 只多一次 INSERT，无 LLM | 代码结构决定 |
| 审核并发 | 2 线程、队列 100（独立于打分线程池） | 经验值，需按压测调整 |
| 审核 P95 目标 | ≤ 8s（含 1 次调用）；失败重试不计入发布响应 | **建议值**，需实测；业务未给硬指标 |
| 可见延迟 | 审核完成后 ≤1 个轮询周期（前端不轮询，重新进入「我的启事」即可） | 当前实现 |
| `is_visible` 索引 | `(is_visible, status, deleted_at, created_at)` | 覆盖公共列表/候选池的主过滤顺序 |

---

## 3. 与现状的差距（影响面清单）

### 3.1 现状问题

1. `POST /api/records` 同步 INSERT 后立即 `PENDING`，立刻出现在公共列表与候选池，并立刻发 `PairScoringRequested`。
2. 可见性谓词散落：`RecordMapper.xml` 的 `listJoins` / `matchablePredicate` / `selectPairScanTargets`，`PairFactsMapper.xml` 的 5 条事实查询。加审核态若逐处补 `review_state='APPROVED'`，漏一处就是隐私事故。
3. `PairPrompt` 只用 `MaskingPolicy` 打码，地点被截断到 8 码点并去掉末尾定位；时间被相对时间规则抹掉。**同一份文本既用于展示又用于打分**，无法同时满足「公示去身份」与「比对保留区分度」。
4. `PairScoreResult.reason` 已解析但 `PairScoringService` 没有落库，模型依据被丢弃。

### 3.2 需要改的 SQL（逐条）

| 文件 | 语句 | 改动 | 理由 |
|---|---|---|---|
| `mapper/record/RecordMapper.xml` | `listJoins`（`countByCondition` / `selectSummaries` 共用） | `+ r.is_visible = 1` | 公共列表只出已审核 |
| 同上 | `matchablePredicate`（`selectCandidatePool` / `selectSummariesByIds` 共用） | `+ r.is_visible = 1 AND (...)` | 候选池与 `/api/match` 回表都只出已审核 |
| 同上 | `selectPairScanTargets` | `+ r.is_visible = 1` | 未审核记录不花 LLM 额度打分 |
| 同上 | `selectById` / `selectByIds` | 不加快照过滤，新增审核/双通道列 | 本人详情要能读 `PENDING/REJECTED`；打分要能读到「刚被拒绝」的事实再跳过 |
| 同上 | `selectMyRecords` / `countByPublisher` | 不加快照过滤，新增审核字段 | 「我的启事」必须显示审核态与理由 |
| 同上 | `insert` / `updateContent` | 新增 `review_*` / `raw_*` / `match_*` / `summary` 列 | 三通道落库 + 编辑即重新待审 |
| 同上 | 新增 `markReviewAttempt` / `approveReview` / `rejectReview` / `markReviewManual` / `markReviewApprovedManually` / `markReviewRejectedManually` / `selectReviewCandidates` / `countReviewState` | 全部带 `review_state='PENDING'` 条件 | 并发只有一个结论；人工与 AI 不互相覆盖 |
| `mapper/pair/PairFactsMapper.xml` | `countValidMatched` | `+ l.is_visible=1 AND f.is_visible=1` | 两端都过审才算有效配对 |
| 同上 | `countQualifyingPairs` / `countLowConfidence` | `+ other.is_visible=1` | 未过审的对端不能被计数/探出 |
| 同上 | `countByRecordIds` | `+ l/f/other.is_visible=1` | `/my` 三计数不分叉 |
| 同上 | `selectCardsByRecordIds` | `+ l/f.is_visible=1`；`+ p.reason` | 卡片不出现未过审对端；打分依据可见 |
| `mapper/pair/PairScoreMapper.xml` | 4 条 SELECT + `upsertScore` | `+ reason` 列 | 修复 v2.0「reason 被丢弃」 |
| `mapper/review/ReviewAuditMapper.xml` | 新增 | 只 INSERT / SELECT | 审计 append-only |
| `db/schema.sql` / `db/migration/v2.1-review-gate-up.sql` | `t_record` / `t_pair_score` / `t_review_audit` | 新列 + 生成列 + 索引 + CHECK | 结构与回滚见 §5 |
| `db/seed.sql` | 24 条 seed | `review_state='APPROVED'`、`summary=LEFT(title,40)` | 切 `ai` 后演示不全空 |

### 3.3 需要改的 Java 文件

| 文件 | 改动 |
|---|---|
| `record/domain/Record.java` | 审核/双通道字段 + `isApproved()` + `matchXxxOrDefault()` / `rawXxxOrDefault()` |
| `record/domain/RecordReviewState.java` | 新增枚举 |
| `record/domain/RecordSummary.java` / `MyRecordRow.java` | 审核/摘要读模型字段 |
| `record/mapper/RecordMapper.java` | 审核写方法 |
| `record/service/RecordService.java` | 发布/编辑三通道落位；AI 模式发审核事件、不打分；本人详情写 `RAW_VIEW` 审计 |
| `record/service/RecordVisibilityPolicy.java` | 非所有者 + 非 APPROVED → 404 |
| `record/service/MyRecordService.java`（间接） | 读模型带审核字段 |
| `record/web/dto/RecordDetailResponse.java` | `reviewState/reviewReason/reviewManualRequired/reviewedAt/summary/raw*`（raw 仅本人） |
| `record/web/dto/MyRecordListItemResponse.java` | 审核态、理由、人工标记、摘要 |
| `record/web/dto/RecordListItemResponse.java` | `summary` |
| `record/web/dto/PairCard.java` / `PairCardFacts.java` | `scoreReason`（打码后出） |
| `pair/domain/PairSide.java`（语义） / `PairRecallService.java` | 打分快照改取 `match_*` |
| `pair/engine/PairPrompt.java` | 身份剥离替代全量打码；`prompt_version` → `pair-score-v2` |
| `pair/engine/PairContentHasher.java`（间接） | hash 输入随 `PairSide` 变为 `match_*` |
| `pair/service/PairScoringService.java` | 未过审直接跳过；`reason` 落库 |
| `pair/service/PairAccessPolicy.java` | 任一端非 APPROVED → pair 不可用 |
| `pair/domain/PairScore.java` / `PairCardRow.java` | `reason` |
| `review/**` | 新增：配置、事件、监听、LLM 客户端、prompt、身份剥离、规则双检、审核编排、审计、兜底调度 |
| `web/**` | 「我的启事」审核态/理由/摘要；详情页「已提交，审核通过后可见」+ 原文折叠；pair 卡片打分依据 |

### 3.4 对既有 45 条验收证据的影响

| 证据组 | 默认 `mode=off` | `mode=ai`（显式切换） |
|---|---|---|
| 三态流转 / 409 零写入 / COMPLETED 保留期 | 不变 | 不变（审核不写 `status/completed_at/updated_at`） |
| 公共列表恒 PENDING、终态 0 命中 | 不变 | 追加「未过审 0 命中」；已过审的 PENDING 仍按原规则 |
| `/api/match` 候选与回表 | 不变 | 未过审不进池、不进回表 |
| 三档解锁 / pair 四态 / 通知 | 不变 | 未过审的 pair 不参与计数/取号/确认；已过审不变 |
| 遮蔽（MASK 组） | 不变 | 展示文本先经 LLM + 规则双检，出口仍再走 `MaskingPolicy`（纵深防御） |
| 我的启事三计数 | 不变 | 未过审期间计数为 0（pair 不可见），过审后恢复 |
| 发布响应 `pairs=[]` | 不变 | 不变；额外返回 `reviewState=PENDING` |
| 幂等 / 并发 / 降级 | 不变 | 审核失败停 PENDING 是**新增**路径，不改变既有接口语义 |
| 证据采集脚本（`qa/run-all.mjs` 等） | 默认包可原样复跑 | 需要新增 `review` 场景；本单提供独立脚本与证据，不改旧脚本断言 |

**结论**：默认 `off` 时旧证据应全部保持；`ai` 模式的旧证据需要追加「未审核 0 命中」的新断言，不能直接宣称「45 条全绿」——本单在 `docs/testing/v2/Z85-review-gate/` 给出增量证据。

---

## 4. 架构总览

### 4.1 架构风格

模块化单体（不拆微服务）。审核是发布路径上的一个应用服务，不引入消息中间件：
- 事务内只写记录 + 发 Spring 事件；`@TransactionalEventListener(AFTER_COMMIT)` + `@Async` 在独立线程池调 LLM。
- 失败/重投由 `ReviewReconcileJob` 兜底；单实例用 `AtomicBoolean` 单飞，多实例需命名锁/任务表（见 §12）。
- 不为审核单独建库/建服务：团队规模与流量（校园演示 + 课程验收）不支持微服务的运维成本。

### 4.2 发布流水线

```
POST/PUT
  │ 事务内
  ├─ 规则打码前的原文 → raw_*（仅本人）
  ├─ IdentityScrubber(原文) → match_*（身份类剥离，地点/时间/特征保留）
  ├─ match_* 暂存 title/description/location（展示占位，已剥身份）
  ├─ review_state=PENDING
  └─ INSERT/UPDATE + 发 RecordSubmittedForReview
        │ AFTER_COMMIT + @Async
        ▼
  ReviewGateService
  ├─ 送 LLM：IdentityScrubber(match_*) + 类型/分类/时间（身份类不送）
  ├─ 一次调用返回 title/description/location/summary/decision/reasons
  ├─ 规则双检（手机/证件/邮箱/QQ/学号/姓名/精准定位）
  ├─ APPROVE → 事务：display_text + summary + review_* + APPROVED + 审计
  │             └─ AFTER_COMMIT → PairScoringRequested → 打分（用 match_*）
  ├─ REJECT  → 事务：REJECTED + reasons + 审计（不触发打分）
  └─ 连续失败 → PENDING + manual_required=1 + 审计（绝不自动上架）
```

### 4.3 双通道定义

| 通道 | 落库列 | 内容 | 用途 | 对外 |
|---|---|---|---|---|
| `raw_text` | `raw_title/raw_description/raw_location` | 用户原文 | 本人回看、争议追溯 | **仅本人详情**，读取写 `RAW_VIEW` 审计 |
| `match_text` | `match_title/match_description/match_location` | 原文仅剥离身份类（姓名/学号/手机/QQ/微信/邮箱）；地点细节、时间、物品特征完整保留 | 出分、`contentHash`、送 LLM 的输入 | **任何响应都不出** |
| `display_text` | `title/description/location` | 全脱敏 + LLM 泛化；定位只到楼栋/楼层/区域 | 列表/详情/通知/匹配卡片 | 所有对外接口只出这一份 |

历史行 / `mode=off`：`match_*` 为 NULL，读取侧用 `COALESCE(match_*, display_*)` 回退；打分前仍过 `IdentityScrubber`，手机号不会因回退外泄。

### 4.4 状态迁移

```
业务 status（不变）：   PENDING ──本人完成──▶ COMPLETED ──到期──▶(读时 404)
                          └──本人关闭──▶ CLOSED

审核 review_state（新增，正交）：
  POST/PUT(mode=ai) ──▶ PENDING ──AI APPROVE──▶ APPROVED ──▶ 可打分/可配对/可对外
                          │
                          ├──AI REJECT / 规则双检命中──▶ REJECTED（理由对本人可见）
                          │
                          └──连续失败──▶ PENDING + review_manual_required=1
                                          ├──人工 APPROVE ──▶ APPROVED
                                          └──人工 REJECT  ──▶ REJECTED

  mode=off：POST/PUT 直接 APPROVED（review_engine='OFF'），行为与 v2.0 一致
  编辑：任何 status=PENDING 的内容变更 → review_state 回 PENDING（旧结论不复用）
```

不允许的迁移：
- `APPROVED → PENDING`（除非内容变更或人工重新送审；当前只有编辑触发）；
- `REJECTED → APPROVED` 只能走人工接口，AI 不会自动翻案；
- 任何系统任务写 `status/completed_at`（保持不变）。

---

## 5. 数据设计

### 5.1 `t_record` 新增列

| 列 | 类型 | 约束/默认 | 语义与建索引理由 |
|---|---|---|---|
| `review_state` | `VARCHAR(16)` | NOT NULL DEFAULT `'APPROVED'`，CHECK 三值 | 审核态；默认 APPROVED 保证 v2.0 兼容 |
| `reviewed_at` | `DATETIME` | NULL | 最后一次结论时间 |
| `review_reason` | `VARCHAR(500)` | NULL | ≤3 条中文理由，分号分隔；不含原文 |
| `review_engine` | `VARCHAR(32)` | NULL | `DEEPSEEK` / `RULE_DOUBLE_CHECK` / `MANUAL` / `OFF` |
| `review_model` | `VARCHAR(64)` | NULL | 模型名，不落 key |
| `review_prompt_version` | `VARCHAR(32)` | NULL | `pair-postprocess-v1` |
| `review_latency_ms` | `INT` | NULL, CHECK ≥0 | 单次调用实测耗时 |
| `review_tokens` | `INT` | NULL | prompt+completion；缺失为 NULL，不估算 |
| `review_attempts` | `INT` | NOT NULL DEFAULT 0, CHECK ≥0 | 已开始的尝试次数；先写次数再调 LLM，崩溃不会无限重试 |
| `review_manual_required` | `TINYINT(1)` | NOT NULL DEFAULT 0 | 1=待人工；**该行永不被自动重试/自动上架** |
| `raw_title/raw_description/raw_location` | `VARCHAR(64)/TEXT/VARCHAR(128)` | NULL | 原文，仅本人 |
| `match_title/match_description/match_location` | 同上 | NULL | 比对文本，任何响应不出 |
| `summary` | `VARCHAR(64)` | NULL | 展示摘要 ≤40 字 |
| `is_visible` | `TINYINT(1)` GENERATED STORED | `(review_state='APPROVED' AND deleted_at IS NULL)` | 对外可见唯一谓词；可索引 |

新增索引：
- `idx_record_visible (is_visible, status, deleted_at, created_at)`：公共列表/候选池的主过滤顺序，避免全表扫描后回表。
- `idx_record_review (review_state, review_manual_required, created_at)`：人工审核队列与兜底调度。

为什么用生成列而不是视图/Java 判断：
- 视图要改所有表名与 MyBatis 映射，回滚成本高；
- Java 判断只覆盖 service 入口，SQL 里的列表/候选池仍会漏；
- 生成列由 MySQL 保证与 `review_state/deleted_at` 同步，任何写入路径都改不了它，索引可直接命中。

### 5.2 `t_review_audit`（新增，append-only）

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT UNSIGNED PK | |
| `record_id` | BIGINT UNSIGNED NOT NULL | FK → `t_record` |
| `action` | VARCHAR(24) | `REVIEW` / `MANUAL_APPROVE` / `MANUAL_REJECT` / `RAW_VIEW` |
| `decision` | VARCHAR(16) NULL | `APPROVE/REJECT/PENDING/ERROR`；RAW_VIEW 为 NULL |
| `review_state` | VARCHAR(16) NULL | 动作完成后的态 |
| `reason` | VARCHAR(500) NULL | 判定理由，不含原文 |
| `engine/model/prompt_version` | VARCHAR | 模型与版本 |
| `latency_ms/prompt_tokens/completion_tokens` | INT | 证据 |
| `actor_type/actor_id` | VARCHAR(8)/BIGINT | AI / HUMAN / SYSTEM |
| `request_id` | VARCHAR(64) | 与响应体同值 |
| `created_at` | DATETIME | |

索引：`(record_id, created_at)`、`(action, created_at)`。**没有原文列**：审计不复制正文，避免变成第二份未脱敏语料库。

### 5.3 `t_pair_score` 新增 `reason`

`VARCHAR(200) NULL`：模型打分依据。v2.0 解析后丢弃，v2.1 落库并由 `PairCard.scoreReason` 打码输出。`ON DUPLICATE KEY UPDATE` 列清单加入 `reason`，仍**不碰** `status/授权/确认` 列（`PairScoreUpsertSqlTest` 继续守住）。

### 5.4 迁移与回滚

- 空库：`db/schema.sql`（八张表）。
- 已有库：`db/migration/v2.1-review-gate-up.sql`（DDL + 审计表 + 回填审核态），`down.sql` 有损回滚。
- 向后兼容：新列可空/有默认；`review_state` 默认 APPROVED；`is_visible` 由既有列派生。
- 灰度：先 `mode=off` 发版（只加列，不改行为）→ 验证旧 45 条 → 小流量 `mode=ai` → 观察审核失败率/延迟 → 再放大。
- 回滚：停应用 → 执行 down → 回退 jar；`off` 模式下即使不回滚结构也不会改变行为。

### 5.5 接口契约增量

**`POST /api/records` / `PUT /api/records/{id}` 响应（与详情同构）新增：**

| 字段 | 类型 | 出现条件 | 语义 |
|---|---|---|---|
| `reviewState` | `PENDING/APPROVED/REJECTED` | 恒有 | 审核态；发布响应在 ai 模式为 PENDING，off 模式为 APPROVED |
| `reviewManualRequired` | boolean | 仅本人 | 是否待人工 |
| `reviewReason` | string \| null | 仅本人 | 拒绝/失败/人工理由 |
| `reviewedAt` | datetime \| null | 仅本人 | 结论时间 |
| `summary` | string \| null | 恒有 | ≤40 字展示摘要 |
| `rawTitle/rawDescription/rawLocation` | string | **仅本人且 AI 通道发布的记录** | 原文；其他视角/off 模式整个键省略 |

**`GET /api/records` 列表项新增**：`summary`（可为 null）。

**`GET /api/my/records` 列表项新增**：`reviewState`、`reviewManualRequired`、`reviewReason`、`reviewedAt`、`summary`。

**pair 卡片新增**：`scoreReason`（模型打分依据，已打码；可为 null）。

**错误码**：不新增错误码。
- 未过审的他人详情复用 `20001`（404，不泄露存在性）；
- 人工审核对非 PENDING 记录复用 `20003`（409）；
- ops 入口未配置/传错 token 复用 `10003`（404，隐藏入口）。

**幂等性**：`POST /api/records` 仍是「每次创建一条」，没有新增幂等键；审核异步可重投，状态更新以 `record_id + review_state='PENDING'` 为条件，重复处理不会产生第二个结论。

---

## 6. 关键链路（按调用顺序）

### 6.1 发布（`mode=ai`）

1. `POST /api/records` → `RecordController.create` → `RecordService.create`（`@Transactional`）。
2. `categoryService.requireSelectable`：失败 400/40001/40002；不写库。
3. `applySubmittedText`：
   - 原文 → `raw_*`；
   - `IdentityScrubber` → `match_*`；
   - `match_*` → `title/description/location`（占位）；
   - `review_state=PENDING`、`summary=NULL`。
4. `recordMapper.insert`：DB 不可用 → 500/10005；事务回滚，用户可重试（无副作用）。
5. 发 `RecordSubmittedForReview`：事件总线同步，不失败。
6. 事务提交 → 监听器 `AFTER_COMMIT` + `@Async` 启动审核；**发布响应立即返回** `200 + id + reviewState=PENDING + pairs=[]`。
7. 审核线程：`selectById` → 若已非 PENDING（人工抢先）直接返回；否则 `markReviewAttempt(n)` → `client.process`。
8. 失败：`auditFailure` → 退避 → 下一轮；3 次后 `markReviewManual`（状态仍 PENDING）→ 审计 `PENDING`。
9. 成功 APPROVE：规则双检 → `approveReview`（条件 `review_state='PENDING'`）→ 审计 → 发 `PairScoringRequested`（AFTER_COMMIT）→ 打分。
10. 成功 REJECT：`rejectReview` → 审计；不触发打分。
11. 打分：`PairScoringService.scoreRecord` 再校验 `review_state=APPROVED`；`PairRecallService.toSide` 取 `match_*`；`PairPrompt` 再 `IdentityScrubber`；`PairContentHasher` 用 `match_*`。

失败点与兜底：

| 失败点 | 表现 | 兜底 |
|---|---|---|
| DB INSERT 失败 | 500，事务回滚 | 客户端重试；无记录 |
| 事件队列满 | 审核任务丢弃 | 记录 PENDING；`ReviewReconcileJob` 5 分钟后重投 |
| LLM 超时/5xx/非 JSON | 重试 | 3 次后转人工，记录不消失 |
| LLM 401/空 key | 重试无意义但按策略计数 | 快速转人工；日志只记原因，不记 key |
| 模型 APPROVE 但规则命中 | 立即 REJECTED | 理由「规则双检命中：…」；不重试 |
| 模型输出超长/缺字段 | 重试 | 3 次后转人工 |
| 审核与人工并发 | 条件 UPDATE 0 行 | 先到者生效，后到者不写审计、不打分 |
| 审核通过后打分失败 | pair 分数缺失 | 保持现有降级/扫描机制；不影响已上架事实 |

### 6.2 对外读取

- 公共列表：`listJoins` 带 `is_visible=1 AND status='PENDING'` → 未过审 0 命中。
- 详情：`selectById` 不加可见过滤（本人/合格对端要读），`RecordVisibilityPolicy` 对非所有者 `review_state != APPROVED` → 404；本人 `PENDING/REJECTED` 可见审核态与 `raw_*`。
- `/api/match`：候选池 `matchablePredicate` → 未过审不进池；回表 `selectSummariesByIds` 再校验。
- 配对：`PairFactsMapper` 所有查询两端/对端 `is_visible=1`；`PairAccessPolicy` 纯函数再兜一层。
- 「我的启事」：不按审核过滤（本人数据），返回审核态/理由/人工标记/摘要；未过审期间 pair 计数为 0。

---

## 7. 失败策略、重试与幂等

| 维度 | 策略 | 理由 |
|---|---|---|
| 重试次数 | 3 次（含首次） | 任务书建议；`max-attempts` 可配 |
| 退避 | 300ms, 600ms, 1200ms（指数，封顶 4 次移位） | 避开瞬时抖动，不把线程占满 |
| 不可重试 | 401/403、空 key、明确 REJECT、规则命中 | 重试不会改变结果，只会浪费额度 |
| 幂等键 | `record_id + review_state='PENDING'` 条件更新 | 同一记录并发/重投只有一个结论 |
| 尝试计数 | 调 LLM **之前**写 `review_attempts` | 进程崩溃不会无限重试 |
| 绝不自动上架 | 失败只写 `manual_required=1`，不写 APPROVED | 与「经审核才上架」一致 |
| 人工兜底 | `POST /api/ops/reviews/{id}/approve|reject` | 人工是最终裁决；AI 不自动翻案 |
| 兜底调度 | 5 分钟冷却、`attempts < max`、`manual=0` | 队列丢任务/进程重启可恢复；不碰人工行 |
| 与人工并发 | 所有 UPDATE 带 `review_state='PENDING'` | 0 行 = 已被别人定稿，不覆盖 |

**代价**：失败时发布延迟从秒级变成「永不自动可见」，必须有人工队列；这是安全侧的选择，不是性能选择。

---

## 8. 身份剥离、规则双检与 LLM 外发边界

### 8.1 `IdentityScrubber`（比对通道）

删除：
- 手机号（含空格/短横/逗号/全角/`+86` 变体，复用 `MaskingPolicy.maskIdentity`）；
- 18 位证件号、手机号、QQ、邮箱；
- 「姓名/名字/联系人/我叫」标签后的 2–4 字；
- 「学号/工号/学籍号」标签后的 4–20 位；
- 「微信/QQ」标签后的账号；
- 发布者展示名原文（≥2 字）。

保留：颜色、品牌、材质、独特标记、类别、**精准地点、时间**。

**能力边界**：不是 NER；正文里无标签自述真名不保证剥离。验收口径是「规则双检 + 抽样人审」，不承诺 100%（与任务书 §风险 3 一致）。

### 8.2 规则双检（展示通道）

对 `title/description/location/summary` 逐个扫描：
- 手机/证件/邮箱/QQ：与 `MaskingPolicy.maskIdentity` 差分判定（同一份检测）；
- 学号/工号：带标签，或 10–12 位独立长数字（偏保守）；
- 带标签姓名、发布者展示名；
- 精准定位：门牌/房间/座位/工位/床位/柜位，或「图书馆 301」类楼栋+数字；楼栋/楼层/区域一级允许。

命中 → **无论模型 decision 为何**一律 `REJECTED`，理由 `规则双检命中：<类别>`。这是硬约束，不可配置关闭。

### 8.3 送 LLM 的边界（数据外发面）

| 数据 | 是否送 LLM | 说明 |
|---|---|---|
| 姓名/学号/手机/QQ/微信/邮箱/`contact` | **否** | `PostprocessCommand` 类型里没有 contact/publisherId；文本先过 `IdentityScrubber` |
| 物品特征、类别、地点（含楼栋/楼层/区域） | 是 | 已知外发面；不送精准门牌（scrub 不删，但 prompt 要求模型删除并泛化） |
| 时间 | 是 | 场景类；比对通道保留 |
| `raw_*` 原文 | **否** | 只读给本人；不参与审核输入 |

答辩口径：**用户身份信息没有出服务器**；场景类文本（物品/楼层/时间）会出服务器，这是为「去隐私改写 + 审核」支付的已知代价。

---

## 9. 可靠性与安全

### 9.1 认证授权

- 发布/编辑/删除/我的启事：沿用现有 session + 所有权校验，无新增绕过。
- 详情：非所有者未过审 → 404（与不存在同码，不泄露「审核中」）。
- 人工审核接口：沿用 ops 路由的访问控制（现有 `OpsPairScanController` 同口径）；**建议**后续加独立审核员角色，本单未自造权限模型。
- `raw_*`：仅本人详情；每次读取写 `RAW_VIEW` 审计（失败只告警，不阻断本人查看自己的内容）。

### 9.2 传输/静态/密钥

- 传输：沿用现有 HTTP/反代；生产 HTTPS（既有部署文档）。
- 静态：`raw_*` 与 `match_*` 在库内明文（与 `contact` 同口径）；靠 DB 访问控制与部署隔离，未引入列级加密。**这是已知取舍**，合规要求更高时需加密/脱敏存储。
- 密钥：只从 `DEEPSEEK_API_KEY` 读；配置类无 key 字段；日志/审计/响应均不含 key 或 prompt 原文。

### 9.3 审计

- 每次 AI 尝试（成功/失败/非法输出）一条 `t_review_audit`；
- 人工通过/驳回各一条；
- 每次本人读原文一条 `RAW_VIEW`；
- 审计不含原文；`reason` 只存模型理由/规则标签/人工理由。

### 9.4 拒绝服务与背压

- 审核线程池 2 线程、队列 100；满则丢弃并日志，记录停 PENDING，由兜底调度重投。
- 兜底批大小 20/轮，单飞；多实例需命名锁（未实现，列入演进）。
- LLM 单次 45s 超时；重试 3 次最坏 ~135s 占用 1 个审核线程，队列容量按发布峰值评估。

---

## 10. 可观测性

### 10.1 指标/日志字段

| 事件 | 字段 | 用途 |
|---|---|---|
| `review llm ok` | `recordId, decision, engine, latencyMs, promptTokens, completionTokens, model` | 成功率、P95、成本 |
| `review llm unavailable` | `recordId, reason, latencyMs, httpStatus` | 失败分类与告警 |
| `review retry` | `recordId, attempt/max, reason` | 重试率 |
| `review approved/rejected` | `recordId, attempts, reason` | 结论分布 |
| `review manual required` | `recordId, attempts, lastReason` | 人工队列增长 |
| `review reconcile start` | `candidates, maxAttempts` | 兜底任务健康 |
| `review.enqueue.rejected` | `queueSize, activeCount` | 队列容量告警 |

**不打**：prompt/响应原文、手机号、姓名、key、Cookie。

### 10.2 告警建议（阈值来源：经验值，需实测校准）

- `review_manual_required=1` 增速 > 10 条/10 分钟 → P2；
- 审核失败率（UNAVAILABLE/总数）> 20% 持续 10 分钟 → P1；
- `reviewExecutor` 队列 > 80 → P2；
- 审核 P95 > 30s 持续 10 分钟 → P2；
- `RAW_VIEW` 审计写入失败 > 0 → P2（隐私审计缺口）。

### 10.3 排查入口

- 记录：`t_record.review_state/review_reason/review_engine/review_model/review_prompt_version/review_attempts`。
- 审计：`t_review_audit` 按 `record_id` 查全历史。
- 日志：按 `requestId` 串联「发布 → 审核 → 打分」；异步审核从事件带入 requestId。
- 人工队列 SQL：`SELECT id, review_reason, review_attempts FROM t_record WHERE review_state='PENDING' AND review_manual_required=1 ORDER BY created_at;`

---

## 11. 测试方案与验收映射

### 11.1 单测（JUnit）

| 用例 | 断言 |
|---|---|
| `IdentityScrubberTest` | 手机/证件/邮箱/QQ/学号/姓名被剥离；颜色/品牌/地点/时间保留；幂等 |
| `SensitiveTextScannerTest` | 手机/学号/邮箱/门牌命中；楼栋/楼层不误伤 |
| `ReviewGateServiceTest`（mock client） | APPROVE 落库+打分；REJECT 不落库；规则命中覆盖模型 APPROVE；连续失败停 PENDING；attempts 先落 |
| `RecordVisibilityPolicyTest` | 非所有者 + PENDING/REJECTED → 404；所有者可见；APPROVED 走原三态 |
| `RecordMapperSqlTest` | 列表/候选池/扫描 SQL 含 `is_visible=1`；审核写 SQL 带 `review_state='PENDING'` |
| `PairFactsMapperSqlTest` | 5 条事实查询都含 `is_visible` |
| `PairPromptTest` | prompt 不含手机号/姓名；地点/时间保留；版本 `pair-score-v2` |
| `PairScoringServiceTest` | `reason` 落库；未过审跳过 |
| `RecordServiceAccessTest` | `mode=off` 三通道回退原文；AI 模式发审核事件不打分；raw 读取审计 |

### 11.2 真包验证（见 `docs/testing/v2/Z85-review-gate/`）

1. `mode=off`：发布 → 立即可见、触发打分；响应与 v2.0 同构（只多 `reviewState=APPROVED`）。
2. `mode=ai` + 可用 LLM：
   - 发布 → `PENDING`；公共列表 `/api/match` 候选池 `/api/my/records` 之外的他人详情四面 0 命中；
   - 审核通过 → 公共列表出现、候选池可召回、触发打分；
   - 审核拒绝 → `REJECTED` + `review_reason`，四面仍 0 命中；
   - `raw_*` 仅本人详情可见；他人响应无 `raw_*`/`match_*`。
3. `mode=ai` + LLM 不可用（空 key/假端点）：
   - 发布 200；记录停在 `PENDING` + `review_manual_required=1`；
   - 等待 > 重试窗口后仍不上架；日志/审计有 3 次尝试。
4. 规则双检：用本地 stub 返回含手机号的 APPROVE JSON → 必须 `REJECTED`，理由含「规则双检命中」。
5. 摘要：`summary` ≤40 字；断言含类别/特征/地点（脚本按夹具词表检查）。
6. 并发：同一记录同时人工 approve + AI 回调 → 只有一个结论，审计不双写。

### 11.3 验收标准逐条映射

| 验收标准 | 落点 |
|---|---|
| PENDING 在公共列表/`/api/match`/候选池/他人详情 0 命中 | `is_visible` + `RecordVisibilityPolicy`；真包四面脚本 |
| APPROVED 进池并触发打分 | `approveReview` → `PairScoringRequested`；`is_visible=1` |
| REJECTED 有 `review_reason`，前端可见 | `rejectReview` + `MyRecordListItemResponse.reviewReason` + `MyRecordCard` |
| AI 连续失败停 PENDING + 待人工，不自动上架 | `markReviewManual` + manual 调度排除；真包空 key 场景 |
| `mode=off` 行为与当前完全一致 | 默认配置 + `applySubmittedText` 回退 + 全量 266 测试（1 条 A/B 无 key 时 skip） |
| 审计可追溯判定/模型/耗时/token，不含原文 | `t_review_audit` + 列清单无正文 |
| 含手机号/姓名/门牌 → display 无三者，raw 仅本人 | LLM prompt + 规则双检 + `raw_*` DTO 仅 owner；真包扫描 |
| summary 含三要素、≤40 字 | prompt + 长度校验 + 抽样 |
| LLM 输出含手机号 → 无论 decision 一律 REJECTED | `SensitiveTextScanner` 在 `decide` 中先于 approve |
| `match_text` 无姓名/手机、地点细节完整；display 三者皆无且泛化；响应只出 display | 双通道落库 + DTO 无 match 字段 + 真包 SQL/响应扫描 |
| A/B 一致率/分数偏移 | 见 §12（实测数据） |

---

## 12. A/B 评测（改写对匹配的影响）

### 12.1 口径

- 样本：≥15 对（同物品正例 + 不同物品负例），覆盖钱包/手机/证件/雨伞/水杯等类别。
- 引擎：`DEEPSEEK` 与 `LOCAL_KEYWORD` 各一份；后者预期更差，如实记录，用于决定降级时是否关闭改写。
- 指标：
  1. 正例（同一物品）：改写前后分数差 ≤0.10，且改写后仍 ≥ 解锁阈值；一致率 ≥90%。
  2. 负例（不同物品）：统计改写后**误配**为匹配的比例，要求不高于改写前；升高需说明并调 prompt。
  3. 排序质量：top1 命中率下降不超过 5 个百分点。
- 判定：`score ≥ 0.75` 记为「匹配」，`< 0.75` 记为「不匹配」；人工标签由样本设计给定（同物品/不同物品），不是模型自评。

### 12.2 实测数据

> 数据来源：真实 `deepseek-chat`（`temperature=0`、`pair-postprocess-v1`、`pair-score-v2`），2026-10-29 实测。
> 脚本：`server/src/test/java/com/findit/campus/review/ab/ReviewAbHarnessTest.java`；原始数据：
> `docs/testing/v2/Z85-review-gate/ab/ab-result.json`、`ab-samples.csv`。**没有使用 stub 冒充**。
>
> 口径：`BEFORE = match_text`（身份剥离后的原文，**生产出分实际使用的通道**）；
> `AFTER = display_text`（LLM 改写后的公示文本，**反事实：如果单通道会怎样**）。
> 因此生产环境的真实改写分数差按构造为 **0**；AFTER 只用来量化「单通道风险」。

| 引擎 | 通道 | 正例一致率 | 负例误配率 | top1 命中率 |
|---|---|---|---|---|
| DEEPSEEK | BEFORE（生产） | 1.0000（10/10） | 0.0000（0/5） | 1.0000（5/5） |
| DEEPSEEK | AFTER（反事实） | 1.0000（10/10） | **0.2000（1/5，N04）** | 1.0000（5/5） |
| LOCAL_KEYWORD | BEFORE（生产兜底） | 0.2000（2/10） | 0.0000（0/5） | 1.0000（5/5） |
| LOCAL_KEYWORD | AFTER（反事实） | 0.0000（0/10） | 0.0000（0/5） | 1.0000（5/5） |

正例逐对分数（DEEPSEEK，BEFORE → AFTER）：

| 对 | P01 | P02 | P03 | P04 | P05 | P06 | P07 | P08 | P09 | P10 |
|---|---|---|---|---|---|---|---|---|---|---|
| BEFORE | 0.85 | 0.98 | 0.98 | 0.98 | 0.95 | 1.00 | 0.95 | 0.98 | 0.98 | 0.98 |
| AFTER | 0.98 | 1.00 | 1.00 | 1.00 | 0.95 | 1.00 | 1.00 | 0.98 | 0.98 | 1.00 |
| 差值 | **+0.13** | +0.02 | +0.02 | +0.02 | 0 | 0 | +0.05 | 0 | 0 | +0.02 |

**结论**：

1. **生产口径（BEFORE）全部达标**：正例一致率 100%、负例误配 0%、top1 100%。改写不影响生产出分，因为出分读的是 `match_text`。
2. **反事实单通道出现两类风险**：P01 分数差 +0.13（超过 0.10 阈值）；N04（学生证 vs 校园卡）被改写后误配到 0.85（负例误配 0%→20%）。这直接证明双通道不是过度设计——若把 display 文本拿去打分，会同时出现「事实被改写」和「负例误配升高」。
3. **LOCAL_KEYWORD 明显更差**：BEFORE 正例一致率仅 20%，AFTER 为 0%；负例误配为 0（因分数普遍低于 0.75）。结论是**降级到本地关键词时不应依赖改写文本**；当前生产已经用 `match_text`，本地分不受改写影响。是否在降级时关闭「展示改写」仍需产品决定（展示改写与出分无关，关闭它只会让展示更接近原文，不会改善匹配）。
4. **top1 在样本子集上无下降**：5 个查询、每个 1 正例 + 2 负例，BEFORE/AFTER 都是 5/5。样本量小，不能外推为「排序稳定」；只作为回归下限。

**调 prompt 记录**：本轮未因 A/B 调 prompt（生产口径已达标）。若未来把 display 用于出分，需先把 P01 的「黑色皮质→深色」类事实改写和 N04 的「证件类别泛化」修掉，再重跑本脚本；在修掉之前，双通道是硬约束。

---

## 13. 权衡记录

| 决策 | 备选 | 放弃原因 |
|---|---|---|
| 审核态独立列 + 生成列 | 新增第四个业务状态 | 会改写三态状态机与既有 45 条证据；`status` 语义被污染 |
| 生成列 `is_visible` | 每处 SQL 手写 `review_state='APPROVED'` | 12+ 处必漏；生成列单一事实来源、可索引 |
| 异步闸门 | 同步审核后再返回 | 发布 P99 被 LLM 拖到秒级以上；任务书要求仍返回 200 + id |
| 失败停 PENDING + 人工 | 失败自动放行 / 失败删除 | 自动放行违背「经审核才上架」；删除会让用户内容消失 |
| 双通道 | 单通道（展示文本也用于打分） | 泛化地点/删特征会拉低匹配；原文进展示会泄露身份 |
| `display` 先写身份剥离占位 | 审核前写原文 | 若某处谓词漏了，漏出去的是已剥身份版本（纵深防御） |
| `raw_*` 明文落库 | 不存原文 / 加密 | 需要本人回看与争议追溯；加密留作合规升级项 |
| 审计独立表 | 只写 `t_record` 列 | 需要逐次尝试历史与 `RAW_VIEW`；单行列会被覆盖 |
| `reason` 落 `t_pair_score` | 只写日志 | 前端要展示依据；日志不可按记录查询 |
| 默认 `off` | 默认 `ai` | 不能改坏既有验收；演示包需要可复现的 v2.0 基线 |
| 单实例兜底 | 引入分布式调度 | 课程/演示单实例；多实例需要任务表/命名锁，列入演进 |

---

## 14. 演进路线（每阶段可独立回滚）

| 阶段 | 内容 | 验收 | 回滚 |
|---|---|---|---|
| P0（本单） | 结构 + 代码 + 单测；默认 `off` | 266 全绿（0 失败，1 skip）；`off` 真包行为同 v2.0 | 回退分支/jar；结构可保留 |
| P1 | 部署库执行 up 迁移；`off` 发版 | 旧 45 条证据复跑通过 | down 迁移 + 旧 jar |
| P2 | `mode=ai` + 真实 key，内部账号灰度 | 未审 0 命中；通过/拒绝/失败三链路；审计可查 | 改回 `off` 立即恢复 |
| P3 | 人工审核队列接入 ops 权限/角色 | 待人工不积压；人工结论可追溯 | 关闭 ops 路由 |
| P4 | A/B 调 prompt；决定是否关闭降级引擎的改写 | 指标达标；负例误配不升高 | 回退 prompt 版本 |
| P5 | 多实例化：命名锁/任务表；审计归档；`raw_*` 加密 | 压测与合规评审 | 按阶段回退 |

**做了会后悔的事**（明确不做）：
- 把审核塞进 `status`；
- 在 12 处 SQL 各写一份 `review_state` 条件；
- 用消息队列当事务（本设计用本地事务 + AFTER_COMMIT 事件，Outbox 留作多实例升级）；
- 让 LLM 失败时自动放行；
- 把 `raw_*` 放进任何对外 DTO。

---

## 15. 开放项（需要人类拍板）

1. **默认值与开关**：`findit.review.mode` 保持默认 `off` 还是演示环境默认 `ai`？
2. **失败策略**：3 次/300ms 退避/45s 超时是否接受？是否需要更长的重试窗口？
3. **是否采用 `rewritten`**：当前默认采用模型改写文本；是否改为「仅人工确认后采用」？
4. **人工审核权限**：`/api/ops/reviews/*` 是否沿用现有 ops 口径，还是新增审核员角色？
5. **`raw_*` 保留与加密**：逻辑删除后是否物理清除/加密存储？
6. **A/B 阈值**：正例 ≤0.10、负例不升高、top1 下降 ≤5pp 是否作为上线硬门槛？
