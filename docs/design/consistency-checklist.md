# 附录 B · 文档—代码一致性自查记录

> 对象：`docs/design/program-design.md` + `docs/design/api-list.md`
> v1 基线：`34c5dd1`（工作分支 `hifox-agent/wen-tong-tong/ZHYYKFX-11`），执行 2026-10-22，逐条读源码比对，不以其他文档为证据
> **v2 基线：`feat/v2-F2-integration @ 941fdee`，执行 2026-10-28（B.13）**；v2 的两个未合入修复分支（`08f4bb9` / `fbd538f`）在 B.14 单列，不与 `941fdee` 的行为混写
> 判定口径：**以代码与 `db/schema.sql` 为准**。文档与 1.2 基线文档冲突时，若代码在基线一侧则说明代码；若代码已偏离基线，则说明代码并在此登记偏离项，同时不改代码。

## B.0 结论

### B.0.1 v1（2026-10-22，保留原样，已被 v2 取代）

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

> **附录 A 的条目编号在 v2 重排过**（v1 把 `GET /api/health` 记作 A.2.1，v2 按 §5.2 的业务接口顺序重编，运维接口记 O1/O2）。B.1–B.9 是 v1 的逐条记录，**保留原编号不改写**，对照如下：
>
> | v1 编号 | v1 内容 | v2 编号 |
> |---|---|---|
> | A.2.1 | `GET /api/health` | A.2.O1 |
> | A.2.2 | `GET /api/records` | A.2.1 |
> | A.2.3 | 详情 | A.2.2 |
> | A.2.4 | `POST /api/records` | A.2.3 |
> | A.2.5 / A.2.6 / A.2.7 | PUT / PATCH status / DELETE | A.2.4 / A.2.5 / A.2.6 |
> | A.2.8 | 分类 | A.2.7 |
> | A.2.9 | `POST /api/match` | A.2.8 |

### B.0.2 v2（2026-10-28，基线 `941fdee`）

| 项 | 结果 | 出处 |
|---|---|---|
| 表名 | **7/7** 一致 | B.13.1 |
| 列数 | **7 + 8 + 17 + 10 + 21 + 8 + 11 = 82 列**，与正文 §4.3 各小节标题逐一对上 | B.13.1 |
| 接口路径与动词 | **19/19**（17 业务 + O1/O2），与 §5.2 清单逐条一致 | B.13.5 |
| 错误码 | **23/23**（`ErrorCode` 枚举 23 个常量，含成功码 `OK(0)`；附录 A.4 只列 22 个失败码。含 v2 新增 10006 / 10007 / 20004 / 20005 / 50002 / 50003 / 50004 / 50005 / 50006） | B.13.6 |
| 请求字段与校验 | 抽样 30 处一致（含 `contact` 收紧为手机号、`status` 三态、`CLAIMED` → 10001） | B.13.9–B.13.11 |
| 响应字段 | 抽样 40 处一致（含 `isMine` / `recordStatus` / `detailAccess` / `PairCard` 全字段） | B.13.7、B.13.12 |
| 枚举域 | 记录三态、`pair_state` 四值、pair 四态、通知三值，DDL CHECK 与文档同值 | B.13.4 |
| **v2 需要说明的偏差** | **4 处**（C-11 ~ C-14，见 B.14） | B.14 |
| 与实现不符却未标注的描述 | 0 处（正文对未合入分支已就地标「待合入」） | B.14 |

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
- **现状（2026-10-22）**：DEF-02 已在 `1349f09` 修复（合入 `5d05961`；写回 URL 前先删掉本次 state 管辖的全部键），`E2E-L-04` 于 REG-01 转 PASS（`qa/reports/e2e-5.1.txt:7`）。**偏差闭合**；遗留的 P2（重置只清筛选项，`page` 在「先翻页、后重置」时不回退）已于 2026-10-26 修复并合入 `main`（`ZHYYKFX-35` @`0278575` → 合并提交 `821423e`，见 `program-design.md` §7.5）。

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
| 外部嵌入实现的真实行为 | 未落地，无代码 | §6.12 明确标为「未落地」 |
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

---

## B.12 部署口径一致性（2026-10-26 追加）

> 范围：`deploy/`（7.1 交付的包与脚本）+ 本文 §3.6 的部署形态描述 + `deploy/DEPLOY-WINDOWS.md`（7.2 交付）。
> 基线：`feat/7.1-windows-deploy` @`db59673`（`deploy/` 全部内容来自该提交，7.2 未改任何脚本）。
> 判定口径不变：**以脚本与配置文件为准**，文档只能描述脚本的实际行为。

| 检查项 | 事实源 | 文档位置 | 结论 |
|---|---|---|---|
| 对外端口 8088、托管目录 `web/dist`、`/api/` 反代到 `127.0.0.1:18080` | `deploy/nginx/nginx.conf:48`、`:51`、`:59-60` | §3.6 表 1 | 一致 |
| `/api/**` 不改写路径（`proxy_pass` 不带 URI） | `nginx.conf:60` | §3.6 | 一致 |
| 后端只绑 `127.0.0.1`、端口 18080 由启动参数传入 | `deploy/scripts/start.ps1:200-201` | §3.6 表 1 | 一致 |
| PID 与日志落在包根 `runtime/`、`logs/` | `nginx.conf:12-13`、`start.ps1:150-154` | §3.6 正文、§3.6 表 1 注 | 一致 |
| 健康判据 `code=0` + `status=UP` + `db=UP` | `start.ps1:43`、`:67`、`status.ps1:178` | §3.6「健康判据唯一」 | 一致 |
| 启停幂等（已在运行则复用；未运行则跳过） | `start.ps1:163-175`、`stop.ps1:50-53`、`:129-133` | §3.6「一条命令起停」 | 一致 |
| 端口被非本包进程占用时不抢杀 | `start.ps1:184`、`:251` | §3.6「单端口对外」段、`DEPLOY-WINDOWS.md` §9 第 6 条 | 一致 |
| 不注册服务、不要求管理员、不改系统环境变量 | `install.ps1:6`、`start.ps1:7`、`stop.ps1:6`、`status.ps1`（无服务调用） | §3.6「不注册服务…」 | 一致 |
| 配置项名（`$Deploy*` 与 `FINDIT_*`） | `deploy/config/deploy.env.example:13-31`、`start.ps1:107` | `DEPLOY-WINDOWS.md` §8.4 | 一致（`start.ps1` 必需的 7 个 `$Deploy*` 变量与模板一一对应） |
| 密码只落 `config/deploy.env.ps1`、不进仓库 | `.gitignore`（`deploy/config/deploy.env.ps1`、`deploy/**/config/deploy.env.ps1`） | §3.6「密码只落本地文件」 | 一致 |
| 部署包不含 JRE / MySQL / Node | `build-package.ps1:304-337`（只复制 jar、dist、nginx、db、config、scripts、文档） | §3.6 表 1「目标机预装」 | 一致 |
| 出包与服务基线同源（`BUILD-INFO.txt` 记录分支/SHA） | `build-package.ps1:356-381` | §3.6「自检口径」 | 一致 |

**已修正的过时表述（本次改动）**

| 位置 | 原文 | 现文 | 原因 |
|---|---|---|---|
| 本文所属的 `program-design.md` §3.6 | 「生产打包不在本项目范围（课程演示用 dev server + 生产构建产物本地 `preview`）」 | 交付形态为 Windows 单机部署包，开发期双进程只服务开发与录屏 | 2026-10-26 人类追加「Windows 可部署」范围，7.1 已出包、本任务补文档 |
| `program-design.md` §1.4 | 范围表无部署行 | 增加「Windows 单机部署包（8088 单端口）」与「不做 Docker / Linux / HTTPS / 服务注册」 | 同上，避免范围表与实际交付互相矛盾 |
| `program-design.md` §1.3、§7.1 | 「一人课程项目，多 Agent 流水线」 | 四人分工：A 需求与架构 / B 后端与匹配 / C 前端与 UI / D 测试、部署与交付材料 | 项目按四人组织，部署与工程化归属成员 D；Agent 仍按阶段执行实现 |
| `docs/frontend-baseline.md` §6（生产形态） | 「本课程版**不实现**，README 写一句『演示用 dev server』」 | 追加一行说明：该结论在 2026-10-26 被阶段 7 取代，指向 `deploy/` 与 §3.6 | 4.2 基线是阶段快照，保留原文并在原处标注后续变更，不静默改写历史 |

**不在本文核对范围**（已由 7.1 / 7.3 各自取证）：部署包在目标机的实际安装与启停结果（`deploy/DEPLOY-WINDOWS.md` §12.1 有 7.2 的实测记录，7.3 会在干净目录独立复验）、Nginx 官方二进制的完整性（`build-package.ps1` 校验下载包 SHA256，并写入 `BUILD-INFO.txt`）。

---

## B.13 v2 抽查（2026-10-28，基线 `941fdee`，逐条回到源码行）

> 方法同 v1：读源码，不读别人的文档结论。每条给「文档写在哪 → 文档说什么 → 事实源 `file:line` → 判定」四要素。**前 10 条即验收要求的「抽查 10 处」**，11–14 是补齐时顺手多核的。
> 所有行号在 `feat/v2-F2-integration @ 941fdee` 上读取；`08f4bb9` / `fbd538f` 带来的差异不进本节，见 B.14。

| # | 文档位置 | 文档写的 | 事实源 | 判定 |
|---|---|---|---|---|
| 1 | §4.2 / §4.3 各小节标题 | 7 张表，列数 7 / 8 / 17 / 10 / 21 / 8 / 11 | `db/schema.sql:58`(`t_user`)、`:78`(`t_category`)、`:105`(`t_record`)、`:163`(`t_match_result`)、`:197`(`t_pair_score`)、`:262`(`t_notification`)、`:290`(`t_access_log`)；列数用脚本按 `^  \`col\`` 计数得 7/8/17/10/21/8/11（合计 72） | 一致 |
| 2 | §4.3 `t_user`、§9.1 第 1 条 | `t_user` **不存手机号**，登录名是 `student_no` | `db/schema.sql:60` 列定义 + `:67` `uk_user_student_no` + `:55-56` 建表注释「没有 phone 列」 | 一致 |
| 3 | §4.4 / §6.7 | `t_pair_score` 唯一键 `(lost_id, found_id)`，一行一对 | `db/schema.sql:224` `UNIQUE KEY uk_pair_lost_found` | 一致 |
| 4 | §5.8 | 记录三态、`pair_state` 四值、pair 四态、通知三值 | `db/schema.sql:147` `ck_record_status`、`:151` `ck_record_pair_state`、`:237` `ck_pair_status`、`:279` `ck_notification_type` | 一致（取值逐个对过，`CLAIMED` 不在域内） |
| 5 | §5.2 / 附录 A.1 | 19 条路径与动词（17 业务 + O1/O2） | `RecordController.java:54/62/69/77/86/97`、`CategoryController.java:34`、`MatchController.java:42`、`AuthController.java:51/61/70`、`MyRecordsController.java:38`、`PairController.java:58/71/82`、`NotificationController.java:41/48`、`HealthController.java:55`、`OpsPairScanController.java:52` | 一致，19/19 |
| 6 | §5.1 错误码表 / 附录 A.4 | 23 个码的 `code ↔ HTTP ↔ 文案` | `common/api/ErrorCode.java`（10003 `:22`、10006 `:33`、10007 `:41`、20001 `:44`、20004 `:53`、20005 `:60`、50002 `:74`、50003、50004、50005 `:80`、50006 `:88`） | 一致；`30004` 确实**不存在**于枚举，文档写「当前不实现」 |
| 7 | §5.4.3 / 附录 A.3.2 | 详情按身份装配：本人明文、他人遮蔽且 `pairs` / `pairState` / `contact` / `completedAt` 键整个省略 | `record/web/dto/RecordDetailResponse.java:103-115`（本人分支）、`:116-130`（他人分支，`contact=null`、`pairs=null`）、`:71` `@JsonInclude(NON_NULL)`、`:127` 注释「null 不是空数组」 | 一致 |
| 8 | §5.6 | 四条硬判据的顺序与形状（证件号 → 手机号 → QQ → 相对时间） | `common/masking/MaskingPolicy.java:50` `ID_RE`、`:53` `MOBILE_RE`、`:56` `QQ_RE`、`:59` `RELATIVE_TIME_RE`，编排入口 `maskDescription` `:115` | 一致（顺序按 ID→MOBILE→QQ→TIME，文档的「证件号必须先于手机号」在源码里就是这一行序） |
| 9 | §5.7 / §4.6 | 阈值 `0.90 / 0.75 / 0.90 / 30 / 0.75` 集中在 `findit.pair.*`，启动期校验关系约束 | `server/src/main/resources/application.yml:95-99`；`pair/config/PairPolicyProperties.java:55-61`（`unlock < direct`、`notify ≥ unlock`）、`:64`（保留期）、`:80-81`（0..1） | 一致 |
| 10 | §5.7 | 分档比较用 `compareTo`，不用 `equals` | `pair/service/PairAccessPolicy.java:60`、`:63`；`:53` 注释写明「`0.90` 与 `0.9000` 在 scale 上不相等」 | 一致 |
| 11 | 附录 A.2.3 / §4.3 | 新写入的 `contact` 必须是 11 位大陆手机号 | `record/web/dto/RecordCreateRequest.java:86` `@Pattern(regexp = "^1[3-9]\\d{9}$")`，`:78-84` 注释解释「为什么不再允许自由格式」 | 一致 |
| 12 | §6.2 / 附录 A.3.3 | 匹配项带 `recordStatus` 与 `detailAccess`，`COMPLETED` 恒 `RESTRICTED` | `match/web/dto/MatchItemResponse.java:43` 字段、`:72-73` 赋值、`:27-28` 注释 | 一致 |
| 13 | §5.4.4 / 附录 A.2.14 | 取号接口 path 参数按 String 接、响应 `no-store`、每次都写审计 | `pair/web/PairController.java:58-67`（`:59` `@PathVariable("id") String id`、`:66` `CACHE_CONTROL, "no-store"` 及其上一行注释「每次查看都写审计，不能被任何中间层缓存」） | 一致 |
| 14 | §5.7 / 附录 A.3.7 | `PairCard` 里没有 `contact`，SQL 只取布尔 | `server/src/main/resources/mapper/pair/PairFactsMapper.xml:124` `(other.contact IS NOT NULL AND other.contact <> '') AS counterpart_contact_present`、`:99` 注释「号码永远不出现在 SELECT 列里」；断言在 `PairFactsCardsSqlTest#contactIsNeverSelected` | 一致 |

**抽样口径说明**：请求字段与响应字段的「30 处 / 40 处」是把 8 个请求 DTO 与 9 个响应 DTO 的字段声明逐行抄出来比对文档表的合计（`auth/web/dto/LoginRequest.java`、`record/web/dto/{RecordQuery,RecordCreateRequest,MyRecordsQuery,StatusUpdateRequest,PairCard}.java`、`pair/web/dto/{PairActionRequest,ContactUnlockRequest,ContactView,ContactUnlockResponse,PairActionResponse,PairScanResult}.java`、`notification/web/dto/*.java`、`match/web/dto/*.java`），不是估算。

## B.14 v2 的偏差登记（4 处）与未合入分支的影响

| # | 偏差 | 文档处理 | 判定 |
|---|---|---|---|
| C-11 | `docs/api-contract-v2.md` §4.7 写分类字典可缓存（`private, max-age=300`），`941fdee` 的实现**没有任何地方设这个头**（只有 `PairController` 设 `no-store`） | §5.1.1 按实现写，并说明加固分支的结论恰好相反（全部 `/api/**` → `no-store`）；契约那句作废 | 属基线文档与实现漂移，不改代码 |
| C-12 | seed 里 13 号记录的所有者，详情 `allowedStatuses` 是 `["CLOSED"]` 而不是 `["COMPLETED","CLOSED"]` | §5.4.3 就地解释：`countValidMatched` 要求两端都未过期，13 号唯一的 `MATCHED`（pair 14）另一端 25 号已过 30 天保留期 | **不是缺陷**，是「有效」谓词的口径；文档按实现写 |
| C-13 | 三处过期注释/javadoc：`application.yml:110` 说池子是 10（实际 `:49` = 30）、`PairScoringRequested` 的触发来源列表、`MatchCandidatePoolPort` 仍写候选池是 `PENDING`（C1 起为 `MATCHABLE`） | 本文按**配置与代码的真实值**写（§6.7「worker 2 · 池 30」、§6.6 触发来源、§6.2 与 §6.1 `MATCHABLE`）；注释本身不改（不是本文的交付物） | 文档以代码为准，注释漂移在 §7.4 OBS-05/06/07 登记 |
| C-14 | `docs/testing/v2/F2-integration-report.md` §6「残余风险」称「只遮蔽 `contact` 字段，description 手写手机号会原样展示」 | 与 `RecordDetailResponse.java:118` 不符（非所有者 description 会打码，明文只在本人分支）；本文 §5.6 按实现写 | **不改他人报告**，在 §7.4 OBS-09 登记，供门禁 3 知悉 |

**未合入分支造成的「文档写最终行为」差异**（本文明确标注「待合入」的四处，逐条对应）：

| 行为 | `941fdee` 现状 | 合入后 | 本文标注位置 | 修复分支 |
|---|---|---|---|---|
| 号码带空格/横线/`+86` 时打码 | **不打码**（F3 P1-1，V3/V4/V5/V6/V7/V15/V16/J3 全部绕过） | 分隔符归一后整段替换 | §5.6「一处必须说明的边界」 | `08f4bb9` |
| `PUT /api/records/{id}` 的终态竞态 | 先读后写，无行锁 | UPDATE 带 `AND status='PENDING'`，0 行回读区分 20001/20003 | §5.8 | `fbd538f` |
| 个性化 GET 的 `Cache-Control` / `Vary` | 只有取号接口有 `no-store` | `/api/**` 统一 `no-store` + `Vary: Cookie` + 四条最小安全头 | §5.1 / §5.1.1 | `fbd538f` |
| 过期 `COMPLETED` 进不进扫描目标 | 进（只排除 `CLOSED`） | `PENDING OR (COMPLETED AND completed_at > cutoff)`，`scoreRecord` 再用 `isMatchable` 兜 | §6.9 | `fbd538f` |

一句提醒：拿本文去核对 `941fdee` 的代码时，**这四条会「对不上」是预期内的**，不是文档写错；核对合入后的树时才对得上。

## B.15 v2 复核命令

```bash
# 7 表与列数（本文 B.13.1）
grep -n "CREATE TABLE\|^  \`" db/schema.sql

# 19 条路径与动词（B.13.5）
grep -rn "@\(Get\|Post\|Put\|Patch\|Delete\)Mapping" server/src/main/java/com/findit/campus/*/web/*.java

# 错误码（B.13.6）
grep -n "([0-9]\{5\}, HttpStatus" server/src/main/java/com/findit/campus/common/api/ErrorCode.java

# 遮蔽四条正则与顺序（B.13.8）
grep -n "Pattern.compile\|public static String mask" server/src/main/java/com/findit/campus/common/masking/MaskingPolicy.java

# 阈值配置与启动期校验（B.13.9）
grep -n "threshold\|retention" server/src/main/resources/application.yml

# PairCard 不含号码（B.13.14）
grep -n "contact" server/src/main/resources/mapper/pair/PairFactsMapper.xml

# 服务端单测（本文 §8.3.1，`941fdee` 上实测 220/220）
cd server && mvn -o test
```

口径不变：**先确认哪一侧是事实源，再把差异写进本节，而不是悄悄改文档。**

