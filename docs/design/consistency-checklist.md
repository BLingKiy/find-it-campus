# 附录 B · 文档—代码一致性自查记录

> 对象：`docs/design/program-design.md` + `docs/design/api-list.md`
> 基线：`34c5dd1`（工作分支 `hifox-agent/wen-tong-tong/ZHYYKFX-11`）
> 执行：2026-10-22，逐条读源码比对，不以其他文档为证据
> 判定口径：**以代码与 `db/schema.sql` 为准**。文档与 1.2 基线文档冲突时，若代码在基线一侧则说明代码；若代码已偏离基线，则说明代码并在此登记偏离项，同时不改代码。

## B.0 结论

| 项 | 结果 |
|---|---|
| 表名 | 3/3 一致 |
| 列名与类型 | 31/31 一致（3 张表合计） |
| 接口路径与动词 | 9/9 一致 |
| 请求字段（含校验） | 24/24 一致 |
| 响应字段 | 54/54 一致 |
| 错误码 | 13/13 一致 |
| 配置项 | 7/7 一致 |
| **需要说明的偏差** | **10 处**（C-01 ~ C-10），其中 3 处为已登记缺陷、2 处为基线文档与实现漂移、5 处为需求假设与实现差异 |
| 与实现不符却未标注的描述 | 0 处 |

---

## B.1 表与列

`db/schema.sql` 是唯一事实源；文档 §4.3 的三张字段表逐列来自这里。

### B.1.1 表名与建表位置

| 文档中的表 | `db/schema.sql` | 结论 |
|---|---|---|
| `t_category` | 第 30–43 行 | 一致 |
| `t_record` | 第 55–88 行 | 一致 |
| `t_match_result` | 第 98–122 行 | 一致 |

全库仅此三张 `CREATE TABLE`（`grep -c "CREATE TABLE" db/schema.sql` = 3），与「三张表」约束一致。

### B.1.2 列名、类型、默认值

| 表 | 列 | `schema.sql` 行 | 文档位置 |
|---|---|---|---|
| `t_category` | `id` / `code` / `name` / `keywords` | 31 / 32 / 33 / 34 | §4.3 |
| | `sort_order` / `is_active` / `created_at` / `updated_at` | 35 / 36 / 37 / 38 | §4.3 |
| `t_record` | `id` / `type` / `status` | 56 / 57 / 58 | §4.3 |
| | `title` / `description` / `category_id` | 60 / 61 / 62 | §4.3 |
| | `location` / `occurred_at` / `contact` | 63 / 64 / 65 | §4.3 |
| | `publisher_name` / `deleted_at` / `created_at` / `updated_at` | 66 / 67 / 68 / 69 | §4.3 |
| `t_match_result` | `id` / `input_text` | 99 / 100 | §4.3 |
| | `input_type` / `input_category_id` | 104 / 105 | §4.3 |
| | `impl` / `top_n` / `min_score` / `candidates` | 106 / 107 / 108 / 109 | §4.3 |
| | `cost_ms` / `created_at` | 110 / 111 | §4.3 |

### B.1.3 索引与约束

| 文档描述 | `schema.sql` | 结论 |
|---|---|---|
| `idx_record_list (type, status, deleted_at, created_at)` | 第 73 行 | 一致 |
| `idx_record_category (category_id, deleted_at, created_at)` | 第 75 行 | 一致 |
| `ft_record_search` FULLTEXT + ngram | 第 77 行 | 一致 |
| `uk_category_code`、`idx_category_active_sort` | 第 40–41 行 | 一致 |
| `idx_match_result_impl_created`、`idx_match_result_created` | 第 113–114 行 | 一致 |
| 两条外键的 `RESTRICT` / `SET NULL` | 第 78–79、115–116 行 | 一致 |
| `input_type` 可空 + CHECK 允许 NULL | 第 104、117 行 | 一致（改动记录见 `db/schema.sql` 附 2） |

---

## B.2 接口路径与动词

| 文档中的路径 | 注解位置 | 结论 |
|---|---|---|
| `GET /api/health` | `common/web/HealthController.java:38`（类 `@RequestMapping("/api")`）+ `:55`（`@GetMapping("/health")`） | 一致 |
| `GET /api/records` | `record/web/RecordController.java:37` + `:47` | 一致 |
| `GET /api/records/{id}` | `RecordController.java:53` | 一致 |
| `POST /api/records` | `RecordController.java:59` | 一致 |
| `PUT /api/records/{id}` | `RecordController.java:65` | 一致 |
| `PATCH /api/records/{id}/status` | `RecordController.java:72` | 一致 |
| `DELETE /api/records/{id}` | `RecordController.java:83` | 一致 |
| `GET /api/categories` | `category/web/CategoryController.java:21` + `:34` | 一致 |
| `POST /api/match` | `match/web/MatchController.java:28` + `:37` | 一致 |

九条路径与文档 §5.2 的九个接口一一对应，无多、无缺。

---

## B.3 响应字段

| 文档中的字段组 | DTO 与行号 | 结论 |
|---|---|---|
| 列表项 9 字段 | `record/web/dto/RecordListItemResponse.java:22-34`：`id` `type` `status` `title` `categoryId` `categoryName` `location` `occurredAt` `createdAt` | 一致 |
| 详情 14 字段 | `RecordDetailResponse.java:22-42`：`id` `type` `status` `title` `description` `categoryId` `categoryName` `location` `occurredAt` `contact` `publisherName` `allowedStatuses` `createdAt` `updatedAt` | 一致 |
| 匹配结果 8 字段 | `match/web/dto/MatchResponse.java:16-23`：`matchId` `engine` `inputType` `topN` `minScore` `tookMs` `total` `items` | 一致 |
| 匹配项 11 字段 | `MatchItemResponse.java:29-43`：`recordId` `score` `scorePercent` `reason` `type` `status` `title` `categoryName` `location` `occurredAt` `createdAt` | 一致 |
| 分类 5 字段 | `category/web/dto/CategoryResponse.java:15-19`：`id` `code` `name` `sortOrder` `active` | 一致 |
| 健康检查 4 字段 | `common/web/HealthController.java:55-71`：`status` `db` `version` `engine` | 一致 |
| 状态流转响应 3 字段 | `record/web/dto/StatusUpdateResponse.java`：`id` `status` `allowedStatuses` | 一致 |

**反向核对（文档说「不返回」的字段，代码确实不返回）**：列表 DTO 中无 `description` / `contact` / `publisherName`；分类 DTO 中无 `keywords`。

---

## B.4 请求字段与校验

| 接口 | 字段 | DTO 行号 | 文档写的校验 | 结论 |
|---|---|---|---|---|
| `GET /api/records` | `type` | `RecordQuery.java:30` | `LOST` / `FOUND` | 一致 |
| | `status` | `:34` | 默认 `PENDING`，含 `ALL` | 一致 |
| | `categoryId` | `:36` | 存在且启用 | 一致 |
| | `keyword` | `:39` | ≤ 64 | 一致 |
| | `page` | `:42` | ≥ 1 | 一致 |
| | `pageSize` | `:46` | 1–50 | 一致 |
| | `sortBy` | `:49` | `createdAt` / `occurredAt` | 一致 |
| | `sortOrder` | `:52` | `asc` / `desc` | 一致 |
| `POST`/`PUT` records | `type` | `RecordCreateRequest.java:33` | `LOST` / `FOUND`，必填 | 一致 |
| | `title` | `:37` | 2–64 | 一致 |
| | `description` | `:41` | 2–2000 | 一致 |
| | `categoryId` | `:44` | 可空，存在且启用 | 一致 |
| | `location` | `:48` | 1–128 | 一致 |
| | `occurredAt` | `:53` | 格式 + 不晚于 now+5min | 一致（校验器 `common/validation/NotFutureMinutesValidator`） |
| | `contact` | `:57` | ≤ 64，字符集限制 | 一致 |
| | `publisherName` | `:61` | ≤ 32 | 一致 |
| `PATCH .../status` | `status` | `StatusUpdateRequest.java:17` | 三值之一 | 一致 |
| `GET /api/categories` | `onlyActive` | `CategoryController.java:36` | 默认 `true` | 一致 |
| `POST /api/match` | `text` | `MatchRequest.java:27` | 2–2000，越界 30001 | 一致（在 `MatchService.requireText` 校验，不在 DTO） |
| | `type` | `:31` | `LOST` / `FOUND` | 一致 |
| | `categoryId` | `:33` | 存在 | 一致 |
| | `topN` | `:37` | 默认 5，1–20 | 一致 |
| | `minScore` | `:41` | 默认 0.3，0–1 | 一致 |
| | `excludeRecordId` | `:44` | 可空 | 一致 |

> `text` 的校验位置值得注意：契约要求它返回 `30001` 而不是通用 `10001`，因此没有挂在 `@Size` 上，而是在 `MatchService` 里显式判断（`MatchService.java:150-162`）。文档 §5.2 的「错误码」列已按此写。

---

## B.5 错误码

| 文档 code | `ErrorCode.java` 行 | HTTP | 结论 |
|---|---|---|---|
| 10001 | 20 | 400 | 一致 |
| 10002 | 21 | 413 | 一致 |
| 10003 | 22 | 404 | 一致 |
| 10004 | 23 | 405 | 一致 |
| 10005 | 24 | 500（DB DOWN 由 `HealthController` 覆写 503） | 一致（`HealthController.java:64-71`） |
| 20001 | 27 | 404 | 一致 |
| 20002 | 28 | 409 | 一致 |
| 20003 | 29 | 409 | 一致 |
| 30001 | 32 | 400 | 一致 |
| 30002 | 33 | 503 | 一致 |
| 30003 | 34 | 504 | 一致 |
| 40001 | 38 | 404 | 一致 |
| 40002 | 39 | 409 | 一致 |

---

## B.6 状态机与匹配模块

| 文档描述 | 代码位置 | 结论 |
|---|---|---|
| `PENDING → CLAIMED / CLOSED`，`CLAIMED → CLOSED`，`CLOSED` 终态 | `record/service/RecordStatusPolicy.java:29-31` | 一致 |
| 20002 错误体 `{current, allowed}`，空时 `["(无)"]` | `RecordStatusPolicy.java:78-85` | 一致 |
| `MatchEngine` 两个方法：`String impl()`、`match(candidates, query)` | `match/engine/MatchEngine.java:19`、`:29` | 一致（与 1.2 文档不同，见 C-04） |
| `MatchQuery(inputText, inputType, categoryId, topN, minScore)` | `match/engine/MatchQuery.java:19-24` | 一致 |
| `MatchCandidate(recordId, score, reason)` | `match/engine/MatchCandidate.java:16` | 一致 |
| `MatchCandidateSource(recordId, title, description, categoryId, location)` | `match/engine/MatchCandidateSource.java:16-21` | 一致 |
| `MatchCandidatePoolPort.load(targetType, categoryId, limit)` | `MatchCandidatePoolPort.java:33` | 一致 |
| 引擎装配 `@ConditionalOnProperty(prefix="findit.match", name="engine")` | `common/config/MatchEngineConfig.java:63-65` | 一致 |
| 「容器内唯一 `MatchEngine` bean」 | `MatchEngineConfig` 仅一个 `@Bean`；`MatchService` 用 `ObjectProvider.getIfAvailable()`（`MatchService.java:111`） | 一致 |
| 分数权重 1.0 / 0.45 / 1.4 × IDF；0.78 + 0.22 + 0.07 + 0.25；scale 2 | `match/engine/local/KeywordMatchEngine.java:62-73`、`:164-166`、`:242-243` | 一致 |
| 候选池上限 500 | `application.yml`（`findit.match.pool-size: 500`）+ `MatchService.java:91` 默认值 | 一致 |
| 快照写失败只 warn，`matchId=null` | `MatchService.java:236-251` | 一致 |
| 未装配引擎 → 503 + 30002 + `data.engine` | `MatchService.java:111-116` | 一致 |

---

## B.7 配置项

| 文档中的配置 | 文件与行 | 结论 |
|---|---|---|
| `server.port: 18080` | `application.yml:16` | 一致 |
| `findit.match.engine`（默认 `local`） | `application.yml:63` | 一致 |
| `findit.match.pool-size: 500` | `application.yml:65` | 一致 |
| `findit.match.external.timeout-ms: 3000` | `application.yml:69` | 一致（**只此一个超时项**，无文档早期草案里的 connect/read 分开配置） |
| Vite 代理 `/api → http://localhost:18080` | `web/vite.config.ts:17-18` | 一致 |
| CORS 白名单 5173、`allowCredentials=false` | `common/config/WebConfig.java:21-26` | 一致 |
| `map-underscore-to-camel-case: true` | `application.yml:58` | 一致 |

---

## B.8 文中数字的出处

| 文档中的数字 | 出处 | 说明 |
|---|---|---|
| 种子数据 24 条记录 | `db/seed.sql:9`（注释）+ `:43` 起的 `INSERT` | 与文档 §5.3.2 一致 |
| 默认列表 `total=21` | `db/seed.sql:13` 的推导 + 5.1 接口用例 `ENV-01` | 实测通过 |
| 分类 9 条（8 启用 + 1 停用） | `db/seed.sql:28-38` | 文档 §3.2「三张表」不涉及数量，`api-list.md` A.2.8 示例取了其中 2 条 |
| 接口用例 64 / 通过 63（首次跑）→ 64 / 64（REG-01） | `docs/testing/5.1-test-report.md:12`（首次跑）与 `:30`（§1.1 复测）；`qa/reports/api-5.1.txt:2` | 一致 |
| 走查 19 / 18（首次跑）→ 19 / 19（REG-01），一致性 9/9、落库 10/10 | 同上 §1 与 §1.1；`qa/reports/e2e-5.1.txt:2` | 一致 |
| 匹配 top1 10/10（P1）、6/10（P2）、top3 10/10 | `docs/testing/5.1-match-evaluation.md:70-77` | 一致 |
| `tookMs` 0/0/0 与 0.2/1/1 | 同上第 76 行 | 一致 |
| 后端单测 32 个（3.1）/ 51 个（3.2） | 3.1 交付评论、3.2 交付评论 | 一致 |
| 构建产物 5.54 kB / 6.59 kB | 阶段 2 复审评论的构建对照 | 一致 |
| 缺陷 DEF-01/02/03、观察项 OBS-01..04 | `docs/testing/5.1-defects.md` | 一致 |

---

## B.9 偏差清单（必须说明的 10 处；C-01 / C-02 已随 DEF-01 / DEF-02 修复闭合，条目保留）

### C-01 `occurredAt` 非法日历值 → 500，契约要求 400

- **实现**：`RecordCreateRequest.occurredAtAsDateTime()`（`:129`）里 `LocalDateTime.parse` 抛 `DateTimeParseException`，无对应出口，落到兜底 `10005`。
- **文档处理**：§5.3.4 与 `api-list.md` A.2.4 按**契约**写 `400/10001`，并显式标注「已知实现偏差：DEF-01」。
- **为什么不改文档迁就实现**：契约是事实源，缺陷不该被文档合法化；修复后文档无需再改。
- **现状（2026-10-22）**：DEF-01 已在 `3548a27` 修复（合入 `d21fe78`；新增 `@StrictDateTime` + `ResolverStyle.STRICT`），`API-C-08` 于 REG-01 转 PASS（`qa/reports/api-5.1.txt:35`）。**实现已与契约一致，偏差闭合**；`api-list.md` A.2.4 的偏差标注同步改为「已修复」。

### C-02 重置筛选后 URL 残留（DEF-02）

- **实现**：`web/src/composables/useRouteQueryState.ts` 写回时 `{...route.query, ...prune(next)}` 只删值不删键。
- **文档处理**：文档未声称「重置筛选会清空 URL」。§2.1 R-06 只要求空态与失败提示；§5.4 未涉及。**无冲突**，但读者若按「URL 即状态」推断需知道此缺陷，故登记。
- **现状（2026-10-22）**：DEF-02 已在 `1349f09` 修复（合入 `5d05961`；写回 URL 前先删掉本次 state 管辖的全部键），`E2E-L-04` 于 REG-01 转 PASS（`qa/reports/e2e-5.1.txt:7`）。**偏差闭合**；仍遗留一处 P2：重置只清筛选项，`page` 在「先翻页、后重置」时不回退（见 `program-design.md` §7.4 节末）。

### C-03 匹配 503 文案重复（DEF-03）

- **实现**：`MatchPanel` 的标题与 `describeError()` 返回同一句。
- **文档处理**：文档只描述「匹配服务暂不可用」的降级行为，未描述文案层级。**无冲突**。

### C-04 `MatchEngine` 签名与 1.2 文档不同

- **基线文档**（`docs/ai-match-module.md` §2）：`MatchEngineKind kind()` + `match(MatchQuery query)`，并写「勿改签名」。
- **实现**：`String impl()`（`MatchEngine.java:19`）+ `match(List<MatchCandidateSource>, MatchQuery)`（`:29`）。
- **原因**：3.1 把候选池改为由 `MatchService` 递入，可见性规则不必在引擎里复制一遍。
- **文档处理**：§6.2 按**代码**写，并在文中注明「初版提案在实现时做了调整」。建议后续同步基线文档，本次不改他人文档（超出 `docs/` 新增范围以外的最小改动原则）。

### C-05 「先过滤后截断」与实现的顺序不同

- **基线文档**（`ai-match-module.md` §5 步骤 5、6）：先回表过滤非法候选，再截断到 `topN`。
- **实现**（`MatchService.java:126-132`）：先在内存里 `minScore` 过滤 → 去重 → 排序 → 截断 `topN`，再回表取展示字段；回表取不到的候选被丢弃且**不补齐**。
- **行为差异**：`topN=1` 时若唯一候选恰好在取池与回表之间被删除，实现返回 0 条，基线设想会返回次优候选。契约只约束 `size ≤ topN` 与「不含已删除记录」，两者都不违反。
- **文档处理**：§6 的时序图与正文按**实现**描述（截断 → 回表），并保留「不补齐」的理由。

### C-06 表单必填性与字段集

- **1.1（R-10/R-11）**：六字段（类型/标题/描述/地点/发生时间/联系方式）全必填。
- **实现**：`contact`、`publisherName` 可选；另增 `categoryId`、`publisherName`，共 8 字段。
- **文档处理**：§2.3 的 D-1 登记；§5.3.4 与 `api-list.md` A.2.4 按实现写（与契约 §5.1 一致）。

### C-07 `topN` 默认值

- **1.1（R-23）**：默认 10、上限 20。
- **实现与契约**：默认 5、上限 20（`MatchRequest.java:23-25`）。
- **文档处理**：§2.3 的 D-2 登记；`api-list.md` A.2.9 按 5 写。

### C-08 匹配入口位置

- **1.1（R-13/R-28b）**：提交成功后结果页展示候选 + 详情页「找相似」。
- **实现**：只在详情页提供「开始匹配」（`MatchPanel`），提交后跳详情但不自动匹配。
- **文档处理**：§2.3 的 D-3 登记；§1.2 的能力表按实现写。

### C-09 删除与下架的语义

- **1.1（R-17）**：删除采用下架语义，物理行保留。
- **实现**：拆成两件事——`CLOSED` 状态（下架，`status=ALL` 时仍可见）与 `deleted_at`（删除，所有读接口不可见，`RecordService.delete` 写该列）。
- **文档处理**：§2.3 的 D-5 登记；§4.2 与 §5.5 按实现写。两者都不物理删除，与 1.1 的「物理行仍存在」不冲突。

### C-10 排序是否可切换

- **1.1（R-05）**：假设不提供排序切换，固定倒序。
- **实现**：支持 `sortBy ∈ {createdAt, occurredAt}` 与 `sortOrder`，前端默认 `createdAt DESC`。
- **文档处理**：§2.3 的 D-4 登记；§5.1 与 `api-list.md` A.2.2 按实现写。

> 另有 4 条观察项（OBS-01..04）不构成文档偏差：文档未对其涉及的行为作正向描述（如未声称性能指标、未声称 E2E 选择器的稳定性）。

---

## B.10 未核对 / 无法核对的部分

| 项 | 原因 | 处理 |
|---|---|---|
| 前端页面的像素级视觉 | 本任务不改前端、不启动浏览器；视觉结论取自 4.2 的实测记录与 5.1 的一致性断言 | 文档只引用其结论，不自行描述像素值 |
| 外部嵌入实现的真实行为 | 未落地，无代码 | §6.6 明确标为「未落地」 |
| 性能与并发数字 | 本期未测 | 文中只出现 5.1 记录的实测耗时，并注明「不是性能结论」 |
| `qa/` 脚本的逐行逻辑 | 只需引用其结果，未逐行审读 | 引用其报告文件，不转述脚本实现细节 |
| 3 条缺陷的修复可行性 | 修复不属本任务 | 只登记现象、定位与建议方向 |

---

## B.11 复核方式

```bash
# 表与列
grep -n "CREATE TABLE\|^  \`" db/schema.sql

# 接口路径
grep -rn "RequestMapping\|GetMapping\|PostMapping\|PutMapping\|PatchMapping\|DeleteMapping" \
  server/src/main/java/com/findit/campus/*/web/*.java

# 字段
sed -n '20,45p' server/src/main/java/com/findit/campus/record/web/dto/RecordListItemResponse.java

# 错误码
grep -n "[0-9]\{5\}, HttpStatus" server/src/main/java/com/findit/campus/common/api/ErrorCode.java

# 配置
grep -n "port\|engine\|pool-size\|timeout-ms" server/src/main/resources/application.yml
```

本文档若与代码再次出现不一致，按上一节的口径处理：**先确认哪一侧是事实源，再把差异写进 B.9，而不是悄悄改文档**。
