# 阈值校准：0.90 / 0.75 是怎么定的，为什么现在必须按引擎分两套

> 任务：ZHYYKFX-84 ｜ 分支：`feat/v2-Z84-threshold-calibration` ｜ 基线：`origin/feat/v2-integration` @ `6ce5700`
>
> 本文回答三件事：① 旧值 `0.90 / 0.75` 从哪来（答：经验值，从未校准）；② 用 20 对人工标注样本实测后，两套引擎各自应该取什么值；③ 为什么这两套值不能合并成一套。

---

## 1. 结论先行

**必须分引擎两套阈值。** 同一对内容，`LOCAL_KEYWORD` 与 `DEEPSEEK` 的分数差 0.72（P06：0.20 vs 0.92），共用一套阈值必然导致「一个引擎看得到、另一个引擎永远看不到」。

| 引擎 | `contact_unlock_threshold`（出卡片 / 可申请解锁） | `contact_direct_threshold`（免授权直看） | `notify_threshold`（写通知） | `completed_visible_threshold`（合格对端看 COMPLETED 详情） |
|---|---|---|---|---|
| 默认（语义引擎 `DEEPSEEK`） | **0.80** | **0.95** | **0.90** | **0.95** |
| 覆盖 `LOCAL_KEYWORD` | **0.45** | **0.65** | **0.55** | **0.65** |

- 在 20 对标注样本上，两组取值都达到任务判据：正例 `score ≥ unlock` ≥ 80%，负例 `score < unlock` ≥ 90%（实测 LOCAL **80% / 90%**，DEEPSEEK **100% / 100%**）。
- 旧值失效的根因不是「调高了 0.15」，而是**一个阈值同时承担了「出不出卡片」和「能不能取号」两件事**，且两套引擎共用同一把尺子。本地引擎的分数区间是 0.02–0.73，0.75 在它的分布**之外** —— 所以降级态下 0 张卡片，用户以为系统没匹配。
- 代价必须说清：LOCAL 的 0.45 落在经验空隙里，距最近负例（0.46）与正例（0.49）都只有 0.01–0.04 余量，**20 对样本撑不起永久常数**，必须随新样本重校（§9）。

---

## 2. 约束与假设

| 项 | 值 | 影响 |
|---|---|---|
| 标注样本 | 20 对（正例 10 / 负例 10），四类：A 完全同物不同措辞 5、B 近义可判同物 4、C 部分要素相同 5、D 明确无关 6 | 样本量小于统计显著所需；下面的阈值是**经验区间**，不是置信区间 |
| 数据来源 | 本机 MySQL 8.0.29 空库 + `db/seed.sql`（3 账号 / 9 分类），业务表清空，逐对发布、逐对关闭 | 同一时刻只有一对 PENDING，`lowConfidenceCount` 不被别的样本污染 |
| 打分引擎 | `LOCAL_KEYWORD`（无 Key，`EMPTY_KEY`）/ `DEEPSEEK`（`deepseek-chat`，temperature 0，`prompt_version=pair-score-v1`） | LLM 结果随模型版本漂移；换模型必须重跑本标注集 |
| 分数采集方式 | 采集轮把四个阈值临时压到全放行（`--findit.pair.*` 启动参数，**不写配置文件**），从 `GET /api/records/{id}` 的 `pairs[].score` 读原始分 | 阈值本身不参与打分，压到 0 只影响可见性，不影响分数 |
| 最终验证 | 用 `application.yml` 的新默认值重启，走真实发布 → 出分 → 卡片/计数 | 证据 `results/verify-local.json` / `verify-deepseek.json` |
| 时间与机器 | 2026-10-28 23:45–23:58，单机 4 核，MySQL 127.0.0.1:3307 | 延迟与 token 只代表这台机器这个时段，不作为容量承诺 |

**未验证 / 需要你回填的假设**

- 真实线上样本的分布可能与本标注集不同（本集由人工按校园场景编写，不是生产日志抽样）。
- `DEEPSEEK` 的 0.92–0.98 是 `deepseek-chat` 当前版本的行为；模型升级后正例可能整体下移，0.95 直看档会先失效。
- 没有做「用户实际点击/反馈」的在线 A/B，只有离线标注集判据。

---

## 3. 方法与复现

```bash
# 1) 采集轮：空库 + 全放行阈值（仅采集用）
java -jar server/target/find-it-campus-0.0.1-SNAPSHOT.jar --server.port=18086 \
  --findit.pair.contact_unlock_threshold=0.00 --findit.pair.contact_direct_threshold=0.01 \
  --findit.pair.notify_threshold=0.00 --findit.pair.completed_visible_threshold=0.00
FINDIT_API_BASE=http://localhost:18086 node qa/datasets/pair-calibration/run.mjs local

# 2) 语义引擎：同一条命令注入 DEEPSEEK_API_KEY（只在该 java 进程内可见）
FINDIT_API_BASE=http://localhost:18087 node qa/datasets/pair-calibration/run.mjs deepseek

# 3) 统计与逐对表
node qa/datasets/pair-calibration/analyze.mjs

# 4) 新默认值下的可见性验证（不加任何 --findit.pair.* 覆盖）
FINDIT_API_BASE=http://localhost:18088 node qa/datasets/pair-calibration/verify.mjs local
FINDIT_API_BASE=http://localhost:18089 node qa/datasets/pair-calibration/verify.mjs deepseek
```

逐对采集细节：每对样本由 demo02 发 LOST、demo03 发 FOUND，发布后轮询**两端** `pairState` 直到 READY/DEGRADED，再读 LOST 详情里的 `score/engine/tier`，最后 `PATCH status=CLOSED` 关闭两端。`latency_ms` / token 从 `t_pair_score` 直接取（卡片 API 不含这两列）。

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

> 「档位/出卡片」按上面的建议值计算（LOCAL 0.65/0.45，DEEPSEEK 0.95/0.80）；低分只进 `lowConfidenceCount`，不渲染卡片。
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

`DEEPSEEK` 延迟与 token（20 次真实调用）：`latency_ms` 554–1790（均值 802），prompt 177–228，completion 26–35。来源：`results/deepseek-db.txt`。

---

## 6. 取值理由（逐个键）

### 6.1 为什么默认那套 = 语义引擎尺度

- `unlock 0.80`：正例最低 0.92，负例最高 0.15；0.80 让 100% 正例出卡片，同时把 0.80–0.92 这段没有样本的灰区留给模型波动。旧值 0.75 也满足判据，但 0.75–0.80 在本次样本里没有任何正例，收紧到 0.80 不损失召回、只多一层防误报。
- `direct 0.95`：直看档**不经对方授权就出号码**，属于隐私动作，取正例下沿 0.92 之上；效果是 10 条正例里 9 条直看，P06（0.92）落到「需对方授权」，保留一次人工确认。
- `notify 0.90`：0.80–0.90 这段样本为空；只在 ≥0.90 时发通知，10 条正例仍全部命中、负例 0 条，通知不会比卡片更吵。
- `completed_visible 0.95`：与 direct 同值，语义是「只有高置信对端才能看 COMPLETED 详情」。它与 direct 不是同一个判定（前者只看遮蔽详情、不含号码），但两者都要求「高置信」，用同一个数字便于解释。

### 6.2 为什么本地覆盖 = 0.65 / 0.45 / 0.55

- `unlock 0.45`：落入唯一可行区间 `[0.44, 0.49]`，取偏下沿的位置。实测 8/10 正例出卡片（80%，达判据）、9/10 负例不出（90%，达判据）。
  - **代价 1**：P06（移动电源 ↔ 充电宝，0.20）与 P09（0.36）仍然看不到 —— 这两条正是「同物不同措辞」，本地关键词引擎无法命中，属于能力边界。
  - **代价 2**：P14（iPhone ↔ 华为，0.46）会被当成「可能相关」出卡片。同类别同地点不同物，关键词分不清；用户点进后需对方授权，影响是「多一次无效点击」，不是号码泄露。
  - **风险**：0.45 距 P14（0.46）只有 0.01，距 P13（0.43）只有 0.02。**小样本给出的 0.05 宽窗口，不能当成稳定常数。**
- `direct 0.65`：负例上沿 0.46 之上 0.19 的位置，只放行 5/10 正例（P01/P02/P03/P05/P07）。直看档会免授权出号，宁可少放。
- `notify 0.55`：比 unlock 高 0.10，避开 0.40–0.46 那批「同类别不同物」；6/10 正例发通知，负例 0 条。
- `completed_visible 0.65`：与 direct 对齐。0.45–0.65 的本地 pair 能出卡片但不能让对端看 COMPLETED 遮蔽详情，避免「低置信也能读已完成记录」。

### 6.3 关系约束（启动期 fail-fast，两套都校验）

- `unlock < direct`：否则直看档与可授权档重叠。
- `notify ≥ unlock`：否则「低于通知阈值但高于展示阈值」的 pair 永远收不到通知。
- 四个值都必须在 `[0, 1]`；保留期 1–3650 天。

实测两条失败路径（完整日志：`results/failfast-default.txt`、`results/failfast-local.txt`）：

```text
Caused by: java.lang.IllegalStateException: findit.pair.contact_unlock_threshold(0.99) 必须小于 findit.pair.contact_direct_threshold(0.95)
Caused by: java.lang.IllegalStateException: findit.pair.overrides.local.contact_unlock_threshold(0.99) 必须小于 findit.pair.overrides.local.contact_direct_threshold(0.65)
```

---

## 7. 本地 / 语义 A/B（同一对内容，分数与可见性）

| 样本 | LOCAL | DEEPSEEK | 差值 | 用旧值（0.75/0.90）谁看不到 | 用新值谁看不到 |
|---|---|---|---|---|---|
| P01 充电宝（同物，细节一致） | 0.73 | 0.98 | +0.25 | **LOCAL 看不到** | 都看得到 |
| P06 移动电源 ↔ 充电宝（同物异名） | 0.20 | 0.92 | +0.72 | LOCAL 看不到 | LOCAL 仍看不到（能力边界） |
| P09 黑框眼镜（部分措辞重合） | 0.36 | 0.95 | +0.59 | LOCAL 看不到 | LOCAL 仍看不到（能力边界） |
| P14 iPhone ↔ 华为（同类不同物） | 0.46 | 0.08 | −0.38 | 都看不到 | **LOCAL 会出卡片** |
| P16 雨伞 ↔ 保温杯 | 0.02 | 0.00 | −0.02 | 都看不到 | 都看不到 |

结论：**同一个阈值不可能同时适配两个引擎**。旧值 0.75 对语义引擎尚可，对本地引擎等于关闭展示；把旧值直接降到 0.45，语义引擎会把 P14 这类 0.46 的负例放进直看档（0.45 < 0.90 的老直看值），仍是一套值服务两个尺度。分引擎后两套各自在自己的分布内取值。

---

## 8. 实现与可见性改动

### 8.1 配置与代码

| 文件 | 改动 |
|---|---|
| `server/src/main/resources/application.yml` | 默认 `0.95/0.80/0.90/0.95` + `findit.pair.overrides.local` `0.65/0.45/0.55/0.65`；注释写明每个数字的来源与代价 |
| `pair/config/PairThresholds.java` | 新增：一套已解析阈值（direct/unlock/notify/completedVisible）的不可变载体 |
| `pair/config/PairPolicyProperties.java` | 新增 `overrides.local/deepseek`（未配置键回退默认）、`forEngine(engine)`、`defaultThresholds()`、`localThresholds()`；启动期对**每一套**做范围与关系校验，错误信息指到具体键；启动日志打印三套解析结果 |
| `pair/mapper/PairFactsMapper.java` + XML | 卡片、低分计数、批量计数、合格对端四条 SQL 改为按 `p.engine` 在 `#{localThreshold}` / `#{threshold}` 之间二选一（共享 `score_at_or_above` 片段，低分计数用它的补集） |
| `record/service/PairFactsPort.java` | 去掉阈值入参：由 pair 侧的适配器按每行 `engine` 选阈值，record 不再知道「本地 0.45 ≈ 语义 0.80」 |
| `pair/service/PairFactsAdapter.java` | 注入 `PairPolicyProperties`；卡片 `tier` 与 SQL 过滤使用同一套引擎阈值 |
| `pair/service/PairContactService.java` / `PairUnlockService.java` / `PairActionService.java` | 三档判定、PUBLISH、PATCH 的分数门槛改为 `forEngine(pair.engine)` |
| `pair/service/PairScoringService.java` | 通知阈值按**实际产出分数的引擎**取（含降级重算时用既有行的 engine） |
| `web/src/components/match/PairPanel.vue` | 「匹配度低于 75%」→「匹配度未达到展示门槛」：阈值现在按引擎不同且可配置，写死百分比会在下次调值时变成假话 |

### 8.2 可见性补齐（第 5 项）

核查结论：**低置信度入口已经存在**，不在本单新增。

- 详情页：`PairPanel.vue` 有 `data-testid="pair-low-count"` 区块，展示「存在 N 条可能相关」+「仅显示数量，不展示详情与联系方式」；只有计数，不展开低分卡片。
- 我的启事：`MyRecordCard.vue` 展示「另有 N 条低分结果（仅计数）」。
- 本单只修了一处会变成假话的文案（写死的 75%），证据：`results/verify-local.json` 中 P13 → `lowConfidenceCount=1`；`results/verify-deepseek.json` 同。

### 8.3 测试

`server/mvnw -o test`：**253 tests，0 failures / 0 errors**。

新增/调整的用例：

- `PairPolicyPropertiesTest`：新默认值；`forEngine` 选择；未覆盖键回退；本地覆盖 `unlock ≥ direct` / `notify < unlock` 都 fail-fast 且信息指到 `findit.pair.overrides.local.*`。
- `PairFactsAdapterTest`：同分 0.50 在 LOCAL 是 AUTHORIZABLE、在 DEFAULT 是 LOW；SQL 收到两组阈值。
- `PairFactsCardsSqlTest`：卡片/低分计数共用同一阈值片段，片段按 engine 二选一。
- `PairContactServiceTest`：0.50 的本地 pair 可授权、0.50 的语义 pair 仍 403；直看用例改到 0.96（新 direct 0.95）。
- `PairContactServiceTest` 等旧断言中「0.92 必 DIRECT」已按新默认值修正。

前端：`vue-tsc --noEmit -p tsconfig.json` **通过**（用同一 `package-lock.json` 的另一工作区 `node_modules` 运行）。`vite build` 在本 agent 沙箱内因 `spawn EPERM`（Windows 下 Vite 探测 realpath 需要起子进程）无法执行，**未在本轮验证**；该改动只涉及一行模板文案，但合并前应在普通 shell 里跑一次 `npm run build` 补上这条证据。

---

## 9. 回归：门禁 2 第 3 组夹具按新阈值重算

旧夹具的分数没有变（种子未改），变的是档位口径。引擎取自 `db/seed.sql` 的 `t_pair_score.engine`。

| 夹具 | 引擎 | score | 旧档位（0.90/0.75） | 新档位 | 是否变化 |
|---|---|---|---|---|---|
| pair 1 | DEEPSEEK | 0.96 | DIRECT | DIRECT | 否 |
| pair 3 | DEEPSEEK | 0.92 | DIRECT | **AUTHORIZABLE** | **是**：新 direct 0.95，0.92 落到「需对方授权」 |
| pair 4 | DEEPSEEK | 0.86 | AUTHORIZABLE | AUTHORIZABLE | 否 |
| pair 74（门禁 4/5 夹具） | LOCAL_KEYWORD | 0.80 | AUTHORIZABLE | **DIRECT** | **是**：本地 direct 0.65 |
| pair 10 | DEEPSEEK | 0.42 | 403/50002 | 403/50002 | 否（0.42 < 0.80） |
| pair 8 | DEEPSEEK | 0.34 | 403/50002 | 403/50002 | 否 |
| 任意 LOCAL 0.45–0.65 的 pair | LOCAL | — | 403 | AUTHORIZABLE | **新增能力**：旧值下本地引擎没有「可授权档」 |

**门禁 5 §5.2j 的结论会反转**：pair 74（0.80，LOCAL）在旧 `completed_visible=0.90` 下是非合格对端 → 404；新本地 `completed_visible=0.65` → 0.80 ≥ 0.65，合格对端 → 200 遮蔽视图。这是**有意的行为变化**（本地引擎的 0.80 已经是高分），但必须重跑该用例并更新预期，不能沿用旧 404 的截图。

门禁 2 §3.4「改阈值档位随之变化」的机制证据仍然成立，但**只对默认那套**成立：当时用 `--findit.pair.contact_direct_threshold=0.85 --contact_unlock_threshold=0.40` 注入，影响的是 DEEPSEEK 的 pair 4/10/8；要复现本地 pair 74 的档位变化，现在必须覆盖 `--findit.pair.overrides.local.*`。这不是回归失败，是「两套阈值」的直接结果。

---

## 10. 风险与边界（必须一并转达）

1. **样本量**：20 对、10 正 10 负，只能给出「可行区间」，不能给出置信度。LOCAL 的 0.45 尤其脆：窗口宽 0.05。
2. **过拟合风险**：P04（0.49）与 P14（0.46）决定了本地窗口的两端；再来一对样本就可能把窗口挤没。建议样本累计到 ≥60 对（正负各 30）后重跑本流程；重跑命令在 §3。
3. **本地引擎能力边界**：同义词（移动电源/充电宝）、单侧信息缺失、同类别同地点不同物，关键词算法都无法可靠区分。用 0.45 换来的可见性，代价是约 10–45% 的可见卡片是误报（本集 1/10 负例出卡片；同类别负例更多时会更差）。
4. **DEEPSEEK 模型漂移**：0.92–0.98 的压缩分布是当前模型行为；模型升级或换供应商时，0.95 直看档可能整体失效（全落到 AUTHORIZABLE，不会泄露号码，但用户体验变差），必须重跑采集轮。
5. **单机单实例**：本文所有数字来自单实例、无并发竞争的空库采集；生产多实例下打分并发不影响分数本身，但延迟/吞吐需另测。
6. **一个阈值仍承担两件事**：`unlock` 同时是「出卡片」与「可申请解锁」的门槛。长期正确做法是拆成 `display_threshold` 与 `request_threshold`；本单按任务边界只做取值校准 + 按引擎覆盖，没有拆语义。

---

## 11. 演进建议（按优先级）

1. 先做：把本标注集扩到 ≥60 对，重跑 §3，确认 LOCAL 的 0.45 是否仍成立。
2. 再做：把「展示」与「可申请解锁」拆成两个键。展示可以用低阈值换召回，解锁保持高阈值；这比继续在单键上找平衡更稳。
3. 后置：用生产脱敏样本做一次离线回放（当前 20 对是人工编写，不是生产分布）。
4. 不建议：为了「让本地也看到更多」把语气词/停用词表继续加长。那是拿规则补语义，边际收益递减且不可解释。

---

## 12. 证据索引

| 内容 | 路径 |
|---|---|
| 标注集（20 对，含人工判定与理由） | `qa/datasets/pair-calibration/pairs.json` |
| 采集器 / 验证器 / 分析器 | `qa/datasets/pair-calibration/run.mjs`、`verify.mjs`、`analyze.mjs` |
| LOCAL 逐对原始结果 | `qa/datasets/pair-calibration/results/local.json` |
| DEEPSEEK 逐对原始结果 | `qa/datasets/pair-calibration/results/deepseek.json` |
| 两引擎 DB 原始行（score/engine/degraded/latency/tokens） | `results/local-db.txt`、`results/deepseek-db.txt` |
| 分布与判据扫描 | `results/analysis.md`、`results/analysis.json` |
| 逐对标注表（本文 §4 的数据源） | `results/table.md` |
| 新默认值下的可见性验证 | `results/verify-local.json`、`results/verify-deepseek.json` |
| 验证轮 DB 原始行 | `results/verify-local-db.txt`、`results/verify-deepseek-db.txt` |
| fail-fast 日志 | `results/failfast-default.txt`、`results/failfast-local.txt` |

---

**这次改默认阈值后，v2.0.0 包的既有验收证据里必须重跑的是**：门禁 2 第 3 组（0.92 变 AUTHORIZABLE、LOCAL 0.80 变 DIRECT）、门禁 4/5 中依赖 pair 74 及 `completed_visible=0.90` 的「合格对端 404」用例、门禁 2 §3.4 阈值注入机制（本地夹具需改用 `overrides.local`）、以及 E2/E1 的所有三档截图（文案与档位边界已变）；门禁 1（并发/幂等/降级）与安全复扫不受阈值影响，无需重跑。
