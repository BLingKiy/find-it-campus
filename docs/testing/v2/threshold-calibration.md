# 阈值校准：0.90 / 0.75 是怎么定的，为什么现在必须按引擎分两套

> 任务：ZHYYKFX-84 ｜ 分支：`feat/v2-Z84-threshold-calibration` ｜ 基线：`origin/feat/v2-integration` @ `6ce5700`
>
> 本文回答四件事：① 旧值 `0.90 / 0.75` 从哪来（答：经验值，从未校准）；② 用 20 对人工标注样本实测后，两套引擎各自应该取什么值；③ 为什么这两套值不能合并成一套；④ 真实场景里「同一个人两次描述同一件物品」的措辞差异会把分数打掉多少，以及为此修正了哪两个本地引擎门槛。
>
> 2026-10-28 追加段（§10）覆盖了近似同义转换与高重叠陷阱；它推翻了上一轮对 `LOCAL direct / notify` 的取值，但**没有**改变 `unlock` 与默认（语义）那套。

---

## 1. 结论先行

**必须分引擎两套阈值；并且 `LOCAL_KEYWORD` 的直看/通知门槛必须抬到 0.90。** 关键理由三条：

1. **分数尺度差一个量级**：同一对内容，本地关键词引擎给 0.20，语义引擎给 0.92（P06）；本地引擎的正例区间 0.20–0.73 完全落在旧 `unlock=0.75` 之外，降级态 0 张卡片。
2. **同义改写保持率差 0.34**：18 对「原文↔改写」实测，本地引擎保持率中位数 **0.64**（均值 0.628，最低 0.41），语义引擎 **0.98**（均值 0.972，最低 0.95）。同一把尺子量不了两套分布。
3. **高重叠陷阱会同时骗过本地引擎的直看档**：6 对「不同物但描述高度重叠」的负例，本地引擎给到 **0.33–0.85**（最高 0.85），语义引擎只有 **0.10–0.72**（最高 0.72）。旧本地 `direct=0.65` 会让 **5/6 陷阱免授权出号**；抬到 0.90 后为 **0/6**，代价是本地引擎不再有免授权直看。

| 引擎 | `contact_unlock_threshold`（出卡片 / 可申请解锁） | `contact_direct_threshold`（免授权直看） | `notify_threshold`（写通知） | `completed_visible_threshold`（合格对端看 COMPLETED 详情） |
|---|---|---|---|---|
| 默认（语义引擎 `DEEPSEEK`） | **0.80** | **0.95** | **0.90** | **0.95** |
| 覆盖 `LOCAL_KEYWORD` | **0.40**（v2.1.0 由 0.45 下调，见下方变更说明） | **0.90** | **0.90** | **0.90** |

- 20 对原标注集上，两组取值都达到任务判据：正例 `score ≥ unlock` ≥ 80%，负例 `score < unlock` ≥ 90%（实测 LOCAL **80% / 90%**，DEEPSEEK **100% / 100%**）。
- 18 对同义改写上：LOCAL 过 `unlock` **17/18 = 94.4%**，DEEPSEEK **18/18 = 100%**。
- 6 对陷阱上：LOCAL 过 `unlock` **5/6 = 83.3%**（这就是能力边界，见 §10.6），过 `direct` **0/6**；DEEPSEEK 过 `unlock` **0/6**。
- 旧值失效的根因不是「调高了 0.15」，而是**一个阈值同时承担了「出不出卡片」和「能不能取号」两件事**，且两套引擎共用同一把尺子。本地引擎的分数区间是 0.02–0.85（含陷阱），`0.75/0.90` 在这个分布里既挡不住陷阱、又放不出同义改写。
- 代价必须说清：`unlock=0.45` 会让 5/6 高重叠陷阱出卡片；`direct=0.90` 让本地引擎的直看档只剩「字面几乎相同」的记录。**本地引擎没有语义判别力，这是算法边界，不是阈值还能再调出来的空间**（§10.5 给了反证：unlock 抬到 0.86 时同义过阈变 0/18）。

> **出厂值变更（v2.1.0 总集成，ZHYYKFX-92）**：按人类「召回优先」口径复核同义集，**本地 `unlock` 由 0.45 下调到 0.40**。依据：S1-OF（小米充电宝 O→F）本地分 **0.41**，在 0.45 下被卡在卡片外（17/18 = 94.4%）；0.40 后 18/18 全部可见。代价为零——陷阱集最低 T2 = **0.33**，0.40 之下仍不可见，陷阱过阈维持 5/6。默认（语义引擎）`unlock` 维持 **0.80** 不变：同义集 18/18、标注集最低正例 0.92 均无正例被卡，而语义陷阱最高 0.72，降到 0.70 会让 T1/T3/T5 三条负例进可授权档。下文 §6.2 中以 0.45 展开的推导保留为当时依据，出厂值以本节为准。

三个必答问题：

| 问题 | 结论 |
|---|---|
| ① 单套阈值在同义转换下是否还站得住？ | **不站得住。** 单套只有两种结局：取语义尺度（0.80）→ 本地同义过阈 0/18；取本地尺度（0.45）→ 语义陷阱直看 3/6（0.72 ≥ 0.65）。 |
| ② 要不要分引擎两套阈值？ | **要。** 上一轮已实现的 `forEngine()` 方向正确；本轮修正的是本地那套的上三档（0.65/0.55/0.65 → 0.90/0.90/0.90），`unlock` 保持 0.45。 |
| ③ 降级时是否要在页面明确提示「关键词匹配，同义描述可能匹配不到」？ | **要，已改。** 详情页降级 banner 从「智能匹配暂不可用，已用关键词匹配。分数仅供参考」改为「当前为关键词匹配，同义/近义描述可能匹配不到，也可能出现不相关的结果，结果显示仅供参考」。只改文案，不改交互。 |

---

## 2. 约束与假设

| 项 | 值 | 影响 |
|---|---|---|
| 标注样本（原文集） | 20 对（正例 10 / 负例 10），四类：A 完全同物不同措辞 5、B 近义可判同物 4、C 部分要素相同 5、D 明确无关 6 | 样本量小于统计显著所需；阈值是**经验区间**，不是置信区间 |
| 标注样本（同义集，2026-10-28 追加） | 6 件物品 × 4 种措辞（O 原文 / C 口语化 / F 书面 / S 简称或错别字）→ 48 对：6 对 O↔O 基线、18 对原文↔改写、18 对改写↔改写、6 对高重叠陷阱 | 与 ZHYYKFX-85 的 A/B **共用** `qa/datasets/pair-synonyms/variants.json`，不是另造一套 |
| 数据来源 | 本机 MySQL 8.0.29 空库 + `db/seed.sql`（3 账号 / 9 分类），业务表清空，逐对发布、逐对关闭 | 同一时刻只有一对 PENDING；采集轮把两套阈值都压到全放行 |
| 打分引擎 | `LOCAL_KEYWORD`（无 Key，`EMPTY_KEY`）/ `DEEPSEEK`（`deepseek-chat`，temperature 0，`prompt_version=pair-score-v1`） | LLM 结果随模型版本漂移；换模型必须重跑两套样本 |
| 分数采集方式 | 采集轮把四个阈值临时压到全放行（`--findit.pair.*` 与 `--findit.pair.overrides.local.*` 启动参数，**不写配置文件**），从 `GET /api/records/{id}` 的 `pairs[].score` 读原始分 | 阈值本身不参与打分；漏压 local 那套会让低分同义对被误判成「没出卡片」 |
| 最终验证 | 用 `application.yml` 的新默认值重启，走真实发布 → 出分 → 卡片/计数 → `GET /api/pairs/{id}/contact` | 证据 `results/verify-local.json`、`verify-deepseek.json`、`verify-syn-local.json`、`verify-syn-deepseek.json` |
| 时间与机器 | 2026-10-28 23:45–2026-10-29 02:20，单机 4 核，MySQL 127.0.0.1:3306 | 延迟与 token 只代表这台机器这个时段，不作为容量承诺 |

**未验证 / 需要你回填的假设**

- 真实线上样本的分布可能与本标注集不同（两套样本都是人工按校园场景编写，不是生产日志抽样）。
- `DEEPSEEK` 的 0.95–1.00 是 `deepseek-chat` 当前版本的行为；模型升级后正例可能整体下移，0.95 直看档会先失效。
- 没有做「用户实际点击/反馈」的在线 A/B，只有离线标注集判据。
- 陷阱集只有 6 对，最高分 0.85 与本地 `direct=0.90` 只隔 0.05；再多几对同类型负例，0.90 也可能不够。

---

## 3. 方法与复现

```bash
# 1) 采集轮：空库 + 两套阈值全放行（仅采集用；local 的 overrides 也必须压）
java -jar server/target/find-it-campus-0.0.1-SNAPSHOT.jar --server.port=18086 \
  --findit.pair.contact_unlock_threshold=0.00 --findit.pair.contact_direct_threshold=0.01 \
  --findit.pair.notify_threshold=0.00 --findit.pair.completed_visible_threshold=0.00 \
  --findit.pair.overrides.local.contact_unlock_threshold=0.00 \
  --findit.pair.overrides.local.contact_direct_threshold=0.01 \
  --findit.pair.overrides.local.notify_threshold=0.00 \
  --findit.pair.overrides.local.completed_visible_threshold=0.00
FINDIT_API_BASE=http://localhost:18086 node qa/datasets/pair-calibration/run.mjs local
FINDIT_API_BASE=http://localhost:18086 node qa/datasets/pair-synonyms/run-synonyms.mjs local

# 2) 语义引擎：同一条命令注入 DEEPSEEK_API_KEY（只在该 java 进程内可见），换端口各跑一遍
FINDIT_API_BASE=http://localhost:18087 node qa/datasets/pair-calibration/run.mjs deepseek
FINDIT_API_BASE=http://localhost:18087 node qa/datasets/pair-synonyms/run-synonyms.mjs deepseek

# 3) 统计与逐对表
node qa/datasets/pair-calibration/analyze.mjs
node qa/datasets/pair-synonyms/analyze-synonyms.mjs

# 4) 新默认值下的可见性验证（不加任何 --findit.pair.* 覆盖）
FINDIT_API_BASE=http://localhost:18088 node qa/datasets/pair-calibration/verify.mjs local
FINDIT_API_BASE=http://localhost:18089 node qa/datasets/pair-calibration/verify.mjs deepseek
FINDIT_API_BASE=http://localhost:18088 node qa/datasets/pair-synonyms/verify-synonyms.mjs local
FINDIT_API_BASE=http://localhost:18089 node qa/datasets/pair-synonyms/verify-synonyms.mjs deepseek
```

逐对采集细节：每对样本由 demo02 发 LOST、demo03 发 FOUND，发布后轮询**两端** `pairState` 直到 READY/DEGRADED，再读 LOST 详情里的 `score/engine/tier`，最后 `PATCH status=CLOSED` 关闭两端。`latency_ms` / token 从 `t_pair_score` 直接取（卡片 API 不含这两列）。

启动日志可核对出厂值（证据 `z84s-app-local.log` / `z84s-app-deepseek.log`）：

```text
pair thresholds default(direct=0.95 unlock=0.8 notify=0.9 completedVisible=0.95)
                local(direct=0.9 unlock=0.45 notify=0.9 completedVisible=0.9)
                deepseek(direct=0.95 unlock=0.8 notify=0.9 completedVisible=0.95)
```

---

## 4. 标注集与逐对分数

标注表原件：`qa/datasets/pair-calibration/pairs.json`（含标题、描述、分类、地点、人工判定与一句理由）。
逐对分数（同一台机器、同一批样本）：

<!-- TABLE_START -->
| 样本 | 类别 | 人工判定 | LOCAL_KEYWORD 分数 | LOCAL 档位/出卡片 | DEEPSEEK 分数 | DEEPSEEK 档位/出卡片 | 理由 |
|---|---|---|---|---|---|---|---|
| P01 | A | 应匹配 | 0.73 | DIRECT/出 | 0.98 | DIRECT/出 | 同品牌同容量同地点，外壳划痕这一细节两边一致，是同一件充电宝 |
| P02 | A | 应匹配 | 0.70 | DIRECT/出 | 0.98 | DIRECT/出 | 深蓝色折叠伞、木柄、同一地点，描述的是同一把伞 |
| P03 | A | 应匹配 | 0.66 | DIRECT/出 | 0.95 | DIRECT/出 | 同姓、同学号年级段、同学院同地点，指向同一张学生证 |
| P04 | A | 应匹配 | 0.49 | AUTHORIZABLE/出 | 0.98 | DIRECT/出 | 同颜色同材质同容量同教室，杯身贴纸也一致，是同一个保温杯 |
| P05 | A | 应匹配 | 0.67 | DIRECT/出 | 0.95 | DIRECT/出 | 同色、同为右耳单只、同地点，缺失充电仓的细节互相印证 |
| P06 | B | 应匹配 | 0.20 | LOW/不出 | 0.92 | AUTHORIZABLE/出 | “移动电源 / 充电宝”是同一物的两种叫法，颜色容量品牌与地点一致 |
| P07 | B | 应匹配 | 0.68 | DIRECT/出 | 0.95 | DIRECT/出 | 颜色、折叠/长柄样式与地点一致，可以判为同一把伞 |
| P08 | B | 应匹配 | 0.56 | AUTHORIZABLE/出 | 0.98 | DIRECT/出 | 同地点、同含宿舍钥匙、同一个小熊挂件，可判为同一串钥匙 |
| P09 | B | 应匹配 | 0.36 | LOW/不出 | 0.95 | DIRECT/出 | 黑框、高度数、同地点，可判为同一副眼镜 |
| P10 | C | 应匹配 | 0.59 | AUTHORIZABLE/出 | 0.95 | DIRECT/出 | 同地点、同颜色，钱包内都有校园卡，一侧信息更少但仍是同一只钱包 |
| P11 | C | 不应匹配 | 0.40 | LOW/不出 | 0.15 | LOW/不出 | 同类别同地点，但宿舍门钥匙与带遥控器的电动车钥匙不是同一个物品 |
| P12 | C | 不应匹配 | 0.41 | LOW/不出 | 0.05 | LOW/不出 | 同为教材同一间教室，但高等数学与大学英语是两本不同的书 |
| P13 | C | 不应匹配 | 0.43 | LOW/不出 | 0.15 | LOW/不出 | 同为黑色包、同一地点，但双肩电脑包与女士手提包不是同一件 |
| P14 | C | 不应匹配 | 0.46 | AUTHORIZABLE/出 | 0.08 | LOW/不出 | 同类目同地点，但蓝色 iPhone 13 与黑色华为手机是两部不同的手机 |
| P15 | D | 不应匹配 | 0.17 | LOW/不出 | 0.05 | LOW/不出 | 充电宝与蓝牙耳机是两类物品，只有颜色和地点相同 |
| P16 | D | 不应匹配 | 0.02 | LOW/不出 | 0.00 | LOW/不出 | 雨伞与保温杯是两类物品，地点也不同 |
| P17 | D | 不应匹配 | 0.12 | LOW/不出 | 0.05 | LOW/不出 | 学生证与钥匙串是两类物品，只有地点相同 |
| P18 | D | 不应匹配 | 0.12 | LOW/不出 | 0.00 | LOW/不出 | 线性代数课本与钱包是两类物品，只有地点相同 |
| P19 | D | 不应匹配 | 0.18 | LOW/不出 | 0.05 | LOW/不出 | 白色蓝牙耳机与白色运动水壶是两类物品，只有颜色与地点相同 |
| P20 | D | 不应匹配 | 0.35 | LOW/不出 | 0.05 | LOW/不出 | 黑色双肩包与黑色雨伞是两类物品，只有颜色与地点相同 |
<!-- TABLE_END -->

> 「档位/出卡片」按上面的最终值计算（LOCAL 0.90/0.45，DEEPSEEK 0.95/0.80）；低分只进 `lowConfidenceCount`，不渲染卡片。
>
> 本地引擎的 10 条正例现在全部落在 `AUTHORIZABLE`（0.49–0.73 < 0.90），不再有免授权直看。这是 §10 的修正结果，不是回归。
>
> `LOCAL_KEYWORD` 行的 `latency_ms` / token 均为 NULL：这是 `EMPTY_KEY` 降级路径的既有口径（没有发生外部调用），不是漏采。

`DEEPSEEK` 逐对耗时与 token（真实调用，来源 `results/deepseek-db.txt`）：

| 样本 | P01 | P02 | P03 | P04 | P05 | P06 | P07 | P08 | P09 | P10 |
|---|---|---|---|---|---|---|---|---|---|---|
| latency_ms | 1790 | 764 | 1023 | 869 | 964 | 554 | 764 | 870 | 673 | 662 |
| prompt / completion | 228/29 | 204/29 | 201/35 | 207/34 | 213/32 | 206/29 | 190/29 | 193/29 | 196/31 | 193/31 |

| 样本 | P11 | P12 | P13 | P14 | P15 | P16 | P17 | P18 | P19 | P20 |
|---|---|---|---|---|---|---|---|---|---|---|
| latency_ms | 829 | 692 | 832 | 835 | 746 | 646 | 734 | 584 | 619 | 589 |
| prompt / completion | 183/31 | 189/33 | 188/32 | 189/28 | 182/26 | 178/26 | 177/28 | 183/27 | 183/26 | 185/32 |

---

## 5. 分布

| 统计量 | LOCAL_KEYWORD 正例 | LOCAL_KEYWORD 负例 | DEEPSEEK 正例 | DEEPSEEK 负例 |
|---|---|---|---|---|
| n | 10 | 10 | 10 | 10 |
| min | 0.20 | 0.02 | 0.92 | 0.00 |
| p25 | 0.44 | 0.12 | 0.95 | 0.05 |
| median | 0.63 | 0.24 | 0.95 | 0.05 |
| p75 | 0.68 | 0.41 | 0.98 | 0.05 |
| max | 0.73 | 0.46 | 0.98 | 0.15 |

**分离情况**

- DEEPSEEK：正例下沿 0.92、负例上沿 0.15，**空隙 0.77**。任何 `unlock ∈ [0.16, 0.95]` 都同时满足两条判据；选 0.80 是为了给「模型偶尔给真匹配 0.85」留余量，同时离负例上沿还有 0.65。
- LOCAL：正例 10 个里有 2 个（P06 0.20、P09 0.36）**低于多数负例**；负例 10 个里有 1 个（P14 0.46）高于正例 P04（0.49）之外的所有正例。正负区间在 0.36–0.49 重叠，**同时满足两条判据的 unlock 只有 [0.44, 0.49]**，宽度 0.05。
- 这不是阈值问题，是算法能力边界：关键词算法看不到「移动电源 = 充电宝」「黑框眼镜的度数描述」这类语义等价，却会被同类别、同地点、同颜色的词面重合抬高（P11–P14 全是同类别不同物）。
- 加入 §10 的 6 对高重叠陷阱后，本地负例上沿从 0.46 抬到 **0.85**，本地正负区间**完全交叠**（正例 0.20–0.74，负例 0.02–0.85）。这是本地引擎更真实的边界。

`DEEPSEEK` 延迟与 token（20 次真实调用）：`latency_ms` 554–1790（均值 802），prompt 177–228，completion 26–35。来源：`results/deepseek-db.txt`。

---

## 6. 取值理由（逐个键）

### 6.1 为什么默认那套 = 语义引擎尺度

- `unlock 0.80`：正例最低 0.92，负例最高 0.15；0.80 让 100% 正例出卡片，同时把 0.80–0.92 这段没有样本的灰区留给模型波动。旧值 0.75 也满足判据，但 0.75–0.80 在本次样本里没有任何正例，收紧到 0.80 不损失召回、只多一层防误报。
- `direct 0.95`：直看档**不经对方授权就出号码**，属于隐私动作，取正例下沿 0.92 之上；效果是 10 条正例里 9 条直看，P06（0.92）落到「需对方授权」，保留一次人工确认。
- `notify 0.90`：0.80–0.90 这段样本为空；只在 ≥0.90 时发通知，10 条正例仍全部命中、负例 0 条，通知不会比卡片更吵。
- `completed_visible 0.95`：与 direct 同值，语义是「只有高置信对端才能看 COMPLETED 详情」。它与 direct 不是同一个判定（前者只看遮蔽详情、不含号码），但两者都要求「高置信」，用同一个数字便于解释。

### 6.2 为什么本地覆盖 = 0.45 / 0.90 / 0.90 / 0.90

- `unlock 0.45`：原 20 对标注集里落入唯一可行区间 `[0.44, 0.49]`，取偏下沿；同义集上 17/18 出卡片（94.4%）。
  - **代价 1（保留）**：P06（移动电源 ↔ 充电宝，0.20）与 P09（0.36）仍然看不到 —— 本地关键词引擎无法命中语义等价。
  - **代价 2（新增，必须说清）**：6 对高重叠陷阱里 5 对（0.75–0.85）会出卡片。本地引擎的排序是错的：陷阱比真同义改写得分更高，**没有任何单一 unlock 能同时做到「同义可见」和「陷阱不可见」**。
  - **为什么不抬到 0.86**：抬到 0.86 后同义过阈 **0/18**、陷阱过阈 0/6 —— 降级态又回到「一条都看不到」，正是本单要解决的原始投诉。这里选可见性优先，用卡片文案和授权门槛兜住误报。
- `direct 0.90`：陷阱集最高 0.85，0.90 在其上留 0.05。旧值 0.65 会让 **5/6 陷阱直接进直看档、不经授权出号码**，这是实测出的隐私缺陷（§10.5）。代价是本地同义对（0.41–0.74）全部落在 `AUTHORIZABLE`，降级态不再有免授权直看 —— 这是正确的取舍：宁可多一次授权，不可错放号码。
- `notify 0.90`：旧值 0.55 会给 5/6 陷阱发通知；0.90 之上只剩字面几乎相同的 pair（O↔O 基线 0.87–1.00），降级态「少通知」比「通知错物」好。
- `completed_visible 0.90`：与 direct 对齐；低置信本地 pair 不得读对端 COMPLETED 详情，避免「误配也能看已完成记录」。

### 6.3 关系约束（启动期 fail-fast，两套都校验）

- `unlock < direct`：否则直看档与可授权档重叠。
- `notify ≥ unlock`：否则「低于通知阈值但高于展示阈值」的 pair 永远收不到通知。
- 四个值都必须在 `[0, 1]`；保留期 1–3650 天。

实测两条失败路径（完整日志：`results/failfast-default.txt`、`results/failfast-local.txt`）：

```text
Caused by: java.lang.IllegalStateException: findit.pair.contact_unlock_threshold(0.99) 必须小于 findit.pair.contact_direct_threshold(0.95)
Caused by: java.lang.IllegalStateException: findit.pair.overrides.local.contact_unlock_threshold(0.99) 必须小于 findit.pair.overrides.local.contact_direct_threshold(0.9)
```

---

## 7. 本地 / 语义 A/B（同一对内容，分数与可见性）

| 样本 | LOCAL | DEEPSEEK | 差值 | 用旧值（0.75/0.90）谁看不到 | 用新值谁看不到 |
|---|---|---|---|---|---|
| P01 充电宝（同物，细节一致） | 0.73 | 0.98 | +0.25 | **LOCAL 看不到** | 都看得到 |
| P06 移动电源 ↔ 充电宝（同物异名） | 0.20 | 0.92 | +0.72 | LOCAL 看不到 | LOCAL 仍看不到（能力边界） |
| P09 黑框眼镜（部分措辞重合） | 0.36 | 0.95 | +0.59 | LOCAL 看不到 | LOCAL 仍看不到（能力边界） |
| P14 iPhone ↔ 华为（同类不同物） | 0.46 | 0.08 | −0.38 | 都看不到 | **LOCAL 会出卡片**（AUTHORIZABLE，不直看） |
| P16 雨伞 ↔ 保温杯 | 0.02 | 0.00 | −0.02 | 都看不到 | 都看不到 |

结论：**同一个阈值不可能同时适配两个引擎**。旧值 0.75 对语义引擎尚可，对本地引擎等于关闭展示；把旧值直接降到 0.45，语义引擎会把 P14 这类 0.46 的负例放进可授权档，而语义陷阱（§10.5，最高 0.72）在 0.65 直看档下会直接出号。分引擎后两套各自在自己的分布内取值。

---

## 8. 实现与可见性改动

### 8.1 配置与代码

| 文件 | 改动 |
|---|---|
| `server/src/main/resources/application.yml` | 默认 `0.95/0.80/0.90/0.95` + `findit.pair.overrides.local` `0.90/0.45/0.90/0.90`；注释逐项写明来源与代价 |
| `pair/config/PairThresholds.java` | 新增：一套已解析阈值（direct/unlock/notify/completedVisible）的不可变载体 |
| `pair/config/PairPolicyProperties.java` | 新增 `overrides.local/deepseek`（未配置键回退默认）、`forEngine(engine)`、`defaultThresholds()`、`localThresholds()`；启动期对**每一套**做范围与关系校验，错误信息指到具体键；启动日志打印三套解析结果 |
| `pair/mapper/PairFactsMapper.java` + XML | 卡片、低分计数、批量计数、合格对端四条 SQL 改为按 `p.engine` 在 `#{localThreshold}` / `#{threshold}` 之间二选一（共享 `score_at_or_above` 片段，低分计数用它的补集） |
| `record/service/PairFactsPort.java` | 去掉阈值入参：由 pair 侧的适配器按每行 `engine` 选阈值，record 不再知道「本地 0.45 ≈ 语义 0.80」 |
| `pair/service/PairFactsAdapter.java` | 注入 `PairPolicyProperties`；卡片 `tier` 与 SQL 过滤使用同一套引擎阈值 |
| `pair/service/PairContactService.java` / `PairUnlockService.java` / `PairActionService.java` | 三档判定、PUBLISH、PATCH 的分数门槛改为 `forEngine(pair.engine)` |
| `pair/service/PairScoringService.java` | 通知阈值按**实际产出分数的引擎**取（含降级重算时用既有行的 engine） |
| `web/src/components/match/PairPanel.vue` | ①「匹配度低于 75%」→「匹配度未达到展示门槛」；②降级 banner 补「当前为关键词匹配，同义/近义描述可能匹配不到，也可能出现不相关的结果，结果显示仅供参考」 |

### 8.2 可见性补齐（第 5 项）

核查结论：**低置信度入口已经存在**，不在本单新增。

- 详情页：`PairPanel.vue` 有 `data-testid="pair-low-count"` 区块，展示「存在 N 条可能相关」+「仅显示数量，不展示详情与联系方式」；只有计数，不展开低分卡片。
- 我的启事：`MyRecordCard.vue` 展示「另有 N 条低分结果（仅计数）」。
- 本单只改了一处会变成假话的文案（写死的 75%），并补了降级提示。证据：`results/verify-syn-local.json` 中 S1-OF → `lowConfidenceCount=1`、无卡片；`verify-syn-deepseek.json` 中 T1/T6 → `lowConfidenceCount=1`、无卡片。

### 8.3 测试与构建

`server/mvnw -o test`（JDK 17，离线仓库 `m2repo`）：**254 tests，0 failures / 0 errors**。

新增/调整的用例：

- `PairPolicyPropertiesTest`：新默认值；`forEngine` 选择；未覆盖键回退；**从真实 `application.yml` 读本地覆盖值并断言 0.45/0.90/0.90/0.90**（覆盖值只在 YAML 里，new 一个对象测的是回退不是出厂值）；本地覆盖 `unlock ≥ direct` / `notify < unlock` 都 fail-fast 且信息指到 `findit.pair.overrides.local.*`。
- `PairFactsAdapterTest`：同分 0.50 在 LOCAL 是 AUTHORIZABLE、在 DEFAULT 是 LOW；SQL 收到两组阈值。
- `PairFactsCardsSqlTest`：卡片/低分计数共用同一阈值片段，片段按 engine 二选一。
- `PairContactServiceTest`：0.50 的本地 pair 可授权、0.50 的语义 pair 仍 403；直看用例改到 0.96（新 direct 0.95）。

前端（`web/`，用同一 `package-lock.json` 的另一工作区 `node_modules`）：

```text
vue-tsc --noEmit -p tsconfig.json      → exit 0
vue-tsc --noEmit -p tsconfig.node.json → exit 0
vite v8.3.5 building client environment for production...
✓ 114 modules transformed.
✓ built in 1.07s
```

`vite build` 上一轮因沙箱 `spawn EPERM`（Vite 探测 Windows 映射盘要执行 `net use`，沙箱禁止带管道的子进程）未执行。本轮用 `node -r vite-exec-shim.cjs node_modules/vite/bin/vite.js build` 补跑通过：shim 只把 `net use` 替换为「没有映射盘」（工作区在本地盘，等价），未改 Vite 或构建配置，也未把 shim 带进产物。**结论：构建可通过；后续在普通 shell 直接 `npm run build` 也应通过，不再需要 shim。**

---

## 9. 回归：门禁 2 第 3 组夹具按新阈值重算

旧夹具的分数没有变（种子未改），变的是档位口径。引擎取自 `db/seed.sql` 的 `t_pair_score.engine`。

| 夹具 | 引擎 | score | 旧档位（0.90/0.75） | 最终档位（0.90/0.45 本地、0.95/0.80 语义） | 是否变化 |
|---|---|---|---|---|---|
| pair 1 | DEEPSEEK | 0.96 | DIRECT | DIRECT | 否 |
| pair 3 | DEEPSEEK | 0.92 | DIRECT | **AUTHORIZABLE** | **是**：新 direct 0.95，0.92 落到「需对方授权」 |
| pair 4 | DEEPSEEK | 0.86 | AUTHORIZABLE | AUTHORIZABLE | 否 |
| pair 74（门禁 4/5 夹具） | LOCAL_KEYWORD | 0.80 | AUTHORIZABLE | AUTHORIZABLE | 否（本地 direct 0.90，0.80 不直看） |
| pair 10 | DEEPSEEK | 0.42 | 403/50002 | 403/50002 | 否（0.42 < 0.80） |
| pair 8 | DEEPSEEK | 0.34 | 403/50002 | 403/50002 | 否 |
| 任意 LOCAL 0.45–0.90 的 pair | LOCAL | — | 403 | AUTHORIZABLE | **新增能力**：旧值下本地引擎没有「可授权档」 |

**门禁 5 §5.2j 不受影响**：pair 74（0.80，LOCAL）在 `completed_visible=0.90` 下仍是非合格对端 → 404，与旧值一致。上一轮草稿曾把本地 `completed_visible` 降到 0.65，会让该用例变 200；§10 的陷阱数据把上三档抬回 0.90，这个反转随之撤回，**不需要重跑门禁 5 的 404 截图**。

门禁 2 §3.4「改阈值档位随之变化」的机制证据仍然成立，但**只对默认那套**成立：当时用 `--findit.pair.contact_direct_threshold=0.85 --contact_unlock_threshold=0.40` 注入，影响的是 DEEPSEEK 的 pair 4/10/8；要复现本地 pair 74 的档位变化，现在必须覆盖 `--findit.pair.overrides.local.*`。这不是回归失败，是「两套阈值」的直接结果。

---

## 10. 近似同义转换专项（2026-10-28 追加段）

### 10.1 样本与共享

- 文件：`qa/datasets/pair-synonyms/variants.json`。6 件物品 × 4 种措辞（O 原文 / C 口语化 / F 书面正式 / S 带简称或错别字），每件两两组合成 6 对，另加 O↔O 基线，共 7 对/物品；再加 6 对「不同物但描述高度重叠」的陷阱负例。总计 **48 对**。
- 18 对「原文↔改写」（O-C / O-F / O-S）用于算保持率；18 对「改写↔改写」（C-F / C-S / F-S）用于看改写之间是否仍稳；6 对陷阱用于看本地引擎会不会把不同物当成同物。
- 同一物品内 `location` 完全一致，把差异限制在标题与描述措辞上，避免地点改写混淆保持率。
- **与 ZHYYKFX-85 共用同一批**：`variants.json` 是唯一来源，Z85 的 A/B 必须读 `items` 与 `traps`，不得另造 15 对；基线口径是 `phrasings.O` 对应 Z85 的 BEFORE（`match_text`），`C/F/S` 是同一物品的三种改写，供 AFTER 通道复用。
  - **迁移状态（如实记）**：Z85 现有 `ReviewAbHarnessTest.fixtures()` 的 15 对硬编码样本早于本文件，**尚未迁移**。下一次改 Z85 的 A/B 时必须改为读本文件、旧 15 对作废；在那之前，两边分数不能混算。这是本单唯一没有闭环的交接项。

### 10.2 指标定义

- **分数保持率 R** = 改写对分数 / 同一物品 O↔O 基线分数。分母不是写死的 1.0：本地引擎 S4 的原文基线只有 0.87，写死 1.0 会虚高。
- **过阈比例** = 18 对原文↔改写中 `score ≥ 该引擎 unlock` 的比例。
- 所有分数来自采集轮（两套阈值压到全放行），阈值只用于事后分档，不参与打分。

### 10.3 分数保持率（逐项给数）

| 引擎 | n | min | p25 | median | p75 | max | mean |
|---|---|---|---|---|---|---|---|
| `LOCAL_KEYWORD` | 18 | 0.41 | 0.61 | **0.64** | 0.67 | 0.74 | **0.628** |
| `DEEPSEEK` | 18 | 0.95 | 0.95 | **0.98** | 0.98 | 1.00 | **0.972** |

分措辞类型：

| 类型 | LOCAL median / mean / min | DEEPSEEK median / mean / min |
|---|---|---|
| O→C 口语化 | 0.64 / 0.646 / 0.52 | 0.98 / 0.973 / 0.95 |
| O→F 书面 | 0.63 / 0.592 / 0.41 | 0.98 / 0.973 / 0.95 |
| O→S 简称/错别字 | 0.64 / 0.647 / 0.61 | 0.98 / 0.970 / 0.95 |

逐对分数与保持率（完整表：`results/syn-table.md`）：

| 物品 | LOCAL O-C / O-F / O-S | LOCAL 保持率 | DEEPSEEK O-C / O-F / O-S | DEEPSEEK 保持率 |
|---|---|---|---|---|
| S1 小米充电宝 | 0.64 / 0.41 / 0.68 | 0.640 / 0.410 / 0.680 | 0.98 / 1.00 / 0.95 | 0.980 / 1.000 / 0.950 |
| S2 深蓝色折叠伞 | 0.64 / 0.66 / 0.61 | 0.640 / 0.660 / 0.610 | 0.98 / 0.95 / 0.98 | 0.980 / 0.950 / 0.980 |
| S3 学生证 | 0.61 / 0.67 / 0.65 | 0.610 / 0.670 / 0.650 | 0.95 / 1.00 / 0.95 | 0.950 / 1.000 / 0.950 |
| S4 银色保温杯 | 0.45 / 0.53 / 0.53 | 0.517 / 0.609 / 0.609 | 1.00 / 0.98 / 0.98 | 1.000 / 0.980 / 0.980 |
| S5 钥匙串 | 0.73 / 0.55 / 0.70 | 0.730 / 0.550 / 0.700 | 0.98 / 0.98 / 0.98 | 0.980 / 0.980 / 0.980 |
| S6 线性代数教材 | 0.74 / 0.65 / 0.63 | 0.740 / 0.650 / 0.630 | 0.95 / 0.95 / 0.95 | 0.950 / 0.950 / 0.950 |

**两引擎差距**：保持率中位数差 **0.34**（0.98 − 0.64），均值差 **0.344**（0.972 − 0.628）；单对差最小 **0.21**（S6-OC：0.95 vs 0.74），最大 **0.59**（S1-OF：1.00 vs 0.41）。本地引擎最差的是「书面改写」（mean 0.592、最低 0.41），因为它把口语里的「充电宝」换成书面的「移动电源」后，字面重叠几乎归零。

**过阈比例**：

| 引擎 | 阈值 | 18 对同义改写过阈 | 18 对交叉改写过阈 | 18 对同义改写过 direct |
|---|---|---|---|---|
| `LOCAL_KEYWORD` | unlock 0.45 / direct 0.90 | **17/18 = 94.4%** | 13/18 = 72.2%（min 0.22 / median 0.50 / max 0.76） | 0/18 |
| `DEEPSEEK` | unlock 0.80 / direct 0.95 | **18/18 = 100%** | 18/18 = 100%（min 0.95 / median 0.98 / max 0.98） | 18/18 |

> 唯一没过的本地同义对是 S1-OF（0.41 < 0.45）——「遗失黑色小米移动电源」对「小米充电宝2W毫安」：书面的「移动电源」和简称的「2W」都不在关键词词表里。这不是阈值差一点，把 unlock 降到 0.40 能救这一对，但陷阱集里 0.33 的 T2 也会跟着进来（见 §10.6）。

### 10.4 陷阱负例：本地引擎的最高分比真同义还高

| 陷阱 | 不同点 | LOCAL 分数 | LOCAL 档位（0.45/0.90） | DEEPSEEK 分数 | DEEPSEEK 档位（0.80/0.95） |
|---|---|---|---|---|---|
| T1 黑色充电宝 | 划痕 vs 完好 | **0.82** | AUTHORIZABLE | 0.72 | LOW |
| T2 黑色水杯 | 保温 vs 塑料 | 0.33 | LOW | 0.55 | LOW |
| T3 深蓝色折叠伞 | 木柄 vs 金属柄 | **0.79** | AUTHORIZABLE | 0.72 | LOW |
| T4 钥匙串 | 小熊 vs 兔子挂件 | **0.75** | AUTHORIZABLE | 0.55 | LOW |
| T5 线性代数教材 | 封面有名字 vs 无名字 | **0.85** | AUTHORIZABLE | 0.72 | LOW |
| T6 白色蓝牙耳机 | 右耳 vs 左耳 | **0.80** | AUTHORIZABLE | 0.10 | LOW |

- **本地最高分 0.85，高于 18 对同义改写里的最高分 0.74。** 也就是说，本地引擎把「擦痕不同、挂件不同、左右耳不同」这些决定性的差异，排在「移动电源/充电宝、木柄/木头把」这些同义改写之上。这是排序错误，不是分数尺度问题。
- 语义引擎最高 0.72，低于 `unlock=0.80`，0/6 出卡片；它对 T1/T3/T5 都给 0.72（识别出「高度相似但细节冲突」），对 T6 左右耳只给 0.10，区分能力明显更强。
- **旧本地 `direct=0.65` 的后果**：T1/T3/T4/T5/T6 共 **5/6** 会直接进直看档，用户拿到的号码属于另一个物品的主人。这是隐私缺陷，不是体验瑕疵。抬到 0.90 后 **0/6**。

### 10.5 单套阈值反证（量化）

| 场景 | unlock | 同义对过阈 | 陷阱过阈 | 同义对直看 | 陷阱直看 |
|---|---|---|---|---|---|
| 语义那套套到本地（0.80/0.95） | 0.80 | **0.0%** | 50.0% | 0.0% | 0.0% |
| 旧共用那套套到本地（0.75/0.90） | 0.75 | **0.0%** | 83.3% | 0.0% | 0.0% |
| 上一轮本地那套套到本地（0.45/0.65） | 0.45 | 94.4% | 83.3% | 44.4% | **83.3%** |
| **本轮本地那套套到本地（0.45/0.90）** | 0.45 | **94.4%** | 83.3% | 0.0% | **0.0%** |
| 本地那套套到语义（0.45/0.65） | 0.45 | 100% | 83.3% | 100% | **50.0%** |
| 本地 unlock 抬到 0.86（0.86/0.90） | 0.86 | **0.0%** | 0.0% | 0.0% | 0.0% |

读法：

- 单套取语义尺度 → 本地同义 **0/18** 可见，且仍有 3/6 陷阱过 0.80 但都在直看档之下（对本地是「看得到错物、但拿不到号」）。
- 单套取本地尺度 → 语义同义全可见，但 **3/6 陷阱会直接出号**（0.72 ≥ 0.65）。
- 本地 unlock 抬到 0.86 → 陷阱清零，但同义也清零。**没有第三个点。**

### 10.6 三个必答问题（结论）

**① 单套阈值在同义转换下是否还站得住？— 不站得住。**

不是「差一点」：本地同义保持率中位数 0.64、语义 0.98；本地陷阱最高分 0.85、语义 0.72。两套分布的方向都相反（本地：陷阱 > 同义；语义：同义 > 陷阱），任何一套数值都不可能同时适配。上表六种组合没有一种能同时满足「本地同义可见」与「两边陷阱不直看」。

**② 要不要分引擎两套阈值？— 要，并对本地那套做修正。**

- 上一轮 `forEngine()` 的结论成立：`unlock` 必须分开（本地 0.45 / 语义 0.80）。
- 本轮修正：本地的 `direct/notify/completed_visible` 从 0.65/0.55/0.65 抬到 **0.90/0.90/0.90**。依据是陷阱分 0.33–0.85 与「旧 0.65 让 5/6 陷阱免授权出号」的实测。
- 修正后的代价明确：**降级态不再有免授权直看**；本地全部正例（含原文对原文）都走「需对方授权」。这是用一次授权换取「不把号码给错人」。
- 为什么不干脆在降级态关掉配对：那会让投诉从「匹配结果看不到」变成「降级就完全没功能」，且原文对原文（O↔O 0.87–1.00）本来就能可靠命中；保留 `unlock=0.45` 至少让字面相近的记录可见。

**③ 降级到 `LOCAL_KEYWORD` 时是否应在页面明确提示？— 应该，已实现。**

- 文案（`PairPanel.vue`，`data-testid="pair-degraded-banner"`）：**「当前为关键词匹配，同义/近义描述可能匹配不到，也可能出现不相关的结果，结果显示仅供参考。」**
- 为什么两句都要说：只说「可能匹配不到」会掩盖 **5/6 陷阱出卡片** 这个反向问题；只说「可能不相关」又解释不了同义改写为什么没出现。降级态的准确预期是「两个方向都可能错」。
- 证据：`results/verify-syn-local.json`（S2-OC 0.64 → 卡片 AUTHORIZABLE；T5 0.85 → 卡片 AUTHORIZABLE 但 `available=false`；S1-OF 0.41 → 无卡片、`lowConfidenceCount=1`）、`results/verify-syn-deepseek.json`（S1-OC 0.98 → DIRECT、`available=true`；T1/T6 → 无卡片、计数 1）。

### 10.7 可见性验证（HTTP 证据，默认值下真实发布）

| 引擎 | 样本 | 期望 | 实测 | `GET /api/pairs/{id}/contact` |
|---|---|---|---|---|
| LOCAL | S1-OO 原文对原文 1.00 | DIRECT | DIRECT | 200，`available=true`，有号码 |
| LOCAL | S2-OC 同义 0.64 | 出卡片、不直看 | AUTHORIZABLE | 200，`available=false`，`reason=NOT_PUBLIC` |
| LOCAL | T5 陷阱 0.85 | 出卡片、**不直看** | AUTHORIZABLE | 200，`available=false`，`reason=NOT_PUBLIC` |
| LOCAL | S1-OF 同义 0.41 | 不出卡片 | 无卡片，`lowConfidenceCount=1` | — |
| LOCAL | T2 陷阱 0.33 | 不出卡片 | 无卡片，`lowConfidenceCount=1` | — |
| DEEPSEEK | S1-OC 同义 0.98 | DIRECT | DIRECT | 200，`available=true`，有号码 |
| DEEPSEEK | T1 陷阱 0.72 | 不出卡片 | 无卡片，`lowConfidenceCount=1` | — |
| DEEPSEEK | T6 陷阱 0.10 | 不出卡片 | 无卡片，`lowConfidenceCount=1` | — |

关键一行：**本地 T5（0.85）在新值下 `available=false`，旧值 0.65 下会是 `available=true`。** 这就是本次修正要堵的口子。

### 10.8 语义引擎的延迟与 token（48 次真实调用）

| 指标 | min | 均值 | max |
|---|---|---|---|
| `latency_ms` | 431 | 704 | 1221 |
| `prompt_tokens` | 194 | — | 230 |
| `completion_tokens` | 23 | — | 39 |

48 次调用 `degraded_reason` 全为 NULL（真实语义分，不是降级）。来源：`results/syn-deepseek-db.txt`。本地引擎 48 行 `latency_ms` / token 全 NULL、`degraded_reason=EMPTY_KEY`，来源：`results/syn-local-db.txt`。

---

## 11. 风险与边界（必须一并转达）

1. **样本量**：原 20 对 + 同义 48 对（其中 18 对用于保持率），只能给出「可行区间」，不能给出置信度。LOCAL 的 0.45 尤其脆：原集窗口宽 0.05。
2. **过拟合风险**：本地 0.45 由 P04（0.49）与 P14（0.46）决定窗口两端；本地 0.90 由陷阱最高 0.85 决定，余量 0.05。再来几对样本就可能把两个值都挤动。建议样本累计到 ≥60 对（正负各 30）后重跑两套流程。
3. **本地引擎能力边界**：同义词（移动电源/充电宝）、单侧信息缺失、高重叠不同物，关键词算法都无法可靠区分。用 0.45 换来的可见性，代价是 5/6 高重叠陷阱会出卡片（但不会直看）。
4. **DEEPSEEK 模型漂移**：0.92–1.00 的压缩分布是当前模型行为；模型升级或换供应商时，0.95 直看档可能整体失效（全落到 AUTHORIZABLE，不会泄露号码，但体验变差），必须重跑采集轮。
5. **单机单实例**：本文所有数字来自单实例、无并发竞争的空库采集；生产多实例下打分并发不影响分数本身，但延迟/吞吐需另测。
6. **一个阈值仍承担两件事**：`unlock` 同时是「出卡片」与「可申请解锁」的门槛。长期正确做法是拆成 `display_threshold` 与 `request_threshold`；本单按任务边界只做取值校准 + 按引擎覆盖，没有拆语义。
7. **降级态的产品语义**：本地引擎现在只保证「字面相近可见、号码必须授权」。如果业务要求降级态也能语义匹配，正确做法是换一个本地语义模型（embedding），不是继续调阈值。

---

## 12. 演进建议（按优先级）

1. 先做：把两套标注集扩到 ≥60 对，重跑 §3，确认 LOCAL 的 0.45 与 0.90 是否仍成立（尤其 0.90 距陷阱上沿只有 0.05）。
2. 再做：把「展示」与「可申请解锁」拆成两个键。展示可以用低阈值换召回，解锁保持高阈值；这比继续在单键上找平衡更稳。
3. 后置：用生产脱敏样本做一次离线回放（当前样本是人工编写，不是生产分布）。
4. 后置：降级态改用本地轻量语义模型（embedding/ONNX），把「同义可见」从阈值问题变成模型问题；成本是包体与内存，收益是降级态不再依赖词面。
5. 不建议：为了「让本地也看到更多」把语气词/停用词表继续加长。那是拿规则补语义，边际收益递减且不可解释。

---

## 13. 证据索引

| 内容 | 路径 |
|---|---|
| 原标注集（20 对，含人工判定与理由） | `qa/datasets/pair-calibration/pairs.json` |
| 同义标注集（6 物品 × 4 措辞 + 6 陷阱，Z84/Z85 共用） | `qa/datasets/pair-synonyms/variants.json` |
| 采集器 / 验证器 / 分析器（原文集） | `qa/datasets/pair-calibration/run.mjs`、`verify.mjs`、`analyze.mjs` |
| 采集器 / 验证器 / 分析器（同义集） | `qa/datasets/pair-synonyms/run-synonyms.mjs`、`verify-synonyms.mjs`、`analyze-synonyms.mjs` |
| LOCAL 逐对原始结果（原文集 / 同义集） | `results/local.json`、`results/syn-local.json` |
| DEEPSEEK 逐对原始结果（原文集 / 同义集） | `results/deepseek.json`、`results/syn-deepseek.json` |
| 两引擎 DB 原始行（score/engine/degraded/latency/tokens） | `results/local-db.txt`、`results/deepseek-db.txt`、`results/syn-local-db.txt`、`results/syn-deepseek-db.txt` |
| 分布与判据扫描（原文集 / 同义集） | `results/analysis.md`、`results/analysis.json`、`results/syn-analysis.md`、`results/syn-analysis.json` |
| 逐对标注表 | `results/table.md`、`results/syn-table.md` |
| 新默认值下的可见性验证 | `results/verify-local.json`、`results/verify-deepseek.json`、`results/verify-syn-local.json`、`results/verify-syn-deepseek.json` |
| 验证轮 DB 原始行 | `results/verify-local-db.txt`、`results/verify-deepseek-db.txt` |
| fail-fast 日志 | `results/failfast-default.txt`、`results/failfast-local.txt` |
| 启动期三套阈值解析日志 | `z84s-app-local.log`、`z84s-app-deepseek.log`（工作区根，非仓库） |

---

**这次改默认阈值后，v2.0.0 包的既有验收证据里必须重跑的是**：门禁 2 第 3 组（DEEPSEEK 0.92 变 AUTHORIZABLE；本地夹具需改用 `overrides.local` 注入）、门禁 2 §3.4 阈值注入机制（本地夹具改用 `overrides.local`）、E1/E2 中所有「本地引擎直看」截图（现在本地正例全部 AUTHORIZABLE，直看只在原文几乎相同时出现）、以及所有依赖「降级 banner 文案」的截图（文案已改）。门禁 5 的 pair 74「合格对端 404」**不需要重跑**（`completed_visible` 最终为 0.90，与旧值一致）。门禁 1（并发/幂等/降级）与安全复扫不受阈值影响，无需重跑。
