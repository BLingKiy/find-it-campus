# E1b 详情 / 我的启事 `pairs[]` · 证据

任务：[ZHYYKFX-69](https://app.hifox.com/nhnok/task/ZHYYKFX-69) · 分支 `feat/v2-E1b-record-pairs` · 基线 `origin/feat/v2-E1-contact-unlock @ 17dcc9a77`

> 分支头以**本文件所在提交**为准（不写死自身 SHA，避免文档与提交互相引用）。基线用
> `git ls-remote` 实读为 `17dcc9a771f7dc6afa4c59f0b638a0affcc178ff`，与派工单一致；`origin/main`
> 仍是 v1 的 `21784f674ca3a5915aaa86dc95edd7a0e55e973d`，未从 main 切。本文所有号码均为假号
> `139000000xx`。

## 1. 环境与结果

| 项 | 值（来源） |
|---|---|
| JDK / Maven | Temurin 17.0.20.1 / Maven 3.10.0（`java -version` / `mvn -v`） |
| 单测 | `mvn test` → **Tests run: 220, Failures: 0, Errors: 0, Skipped: 0**（基线 E1 为 201，本条 +19） |
| 端到端 | 本机脚本 `evidence-run.ps1` → **PASS=71 FAIL=0**，请求/响应逐条落盘 |
| 数据库 | MySQL 8.0.29，隔离实例 `127.0.0.1:3307`；`db/schema.sql` + `db/seed.sql` + E1b 夹具全量重建 |
| 应用 | 同一 jar，`--server.port=18084`；`DEEPSEEK_API_KEY` 未设置（本条不调 LLM） |
| 本轮 DDL | **无**：`git diff db/schema.sql` 为空，`t_pair_score` / `t_record` 已含所需列 |
| 接口 | **无新增**：只在既有 `GET /api/records/{id}` 与 `GET /api/my/records` 的响应里补 `pairs[]` |

新增 5 个测试类、17 个用例，另在既有 `PairAccessPolicyTest` / `RecordServiceAccessTest` 各补 1 个，
合计 +19：

| 测试类 | 用例 | 覆盖 |
|---|---:|---|
| `PairCardTest` | 3 | §5.1 全字段映射、出口打码、JSON 无 `contact` 键 |
| `PairFactsAdapterTest` | 6 | 分组保序、逐端确认/授权独立、`otherNote` 门控、三档与可用性 |
| `PairFactsCardsSqlTest` | 4 | 卡片 SQL 的有效性谓词、定序、`contact` 只以布尔出现、无 `${}` |
| `MyRecordServiceTest` | 2 | 一页 3 条各自正确；计数/卡片各只查一次（N+1 防线） |
| `RecordPairsApiTest` | 2 | 所有者 `pairs[]` 序列化；非所有者 `pairs` 键不存在 |
| `PairAccessPolicyTest` | +1 | `contactAvailable` / `contactHint` 四选一 / `scorePercent` 四舍五入 |
| `RecordServiceAccessTest` | +1 | 详情三视角 + COMPLETED 合格对端 `pairs=null` + 发布成功 `pairs=[]` |

## 2. 实现落点

| 文件 | 职责 |
|---|---|
| `pair/mapper/PairFactsMapper#selectCardsByRecordIds` + `mapper/pair/PairFactsMapper.xml` | **一条 SQL 取整页**卡片事实；低分、两端删除/CLOSED、COMPLETED 到期全部在 SQL 过滤；`ORDER BY score DESC, pair_id ASC` |
| `pair/domain/PairCardRow` | SQL 行事实。**不含对方号码**，只有 `counterpart_contact_present` 布尔 |
| `record/domain/PairCardFacts` | record 侧声明的只读卡片形状（依赖倒置：接口在 record，实现在 pair） |
| `pair/service/PairFactsAdapter#cardsOf` | 按 `recordId` 分组并保序；三档、百分比、逐端标志、`contactAvailable/contactHint` 在这里用 pair 口径算好 |
| `pair/service/PairAccessPolicy` | `contactAvailable` / `contactHint` / `scorePercent` 的**唯一实现**，§4.14 与本条共用 |
| `record/web/dto/PairCard` | §5.1 字段搬运 + 出口打码（复用 C1 `MaskingPolicy`）；**类型上没有 `contact` 字段** |
| `record/service/RecordService#pairCards` | 详情/更新响应装配；只给 `OWNER_PLAINTEXT` 视角 |
| `record/service/MyRecordService` | `/my` 每行内嵌 `pairs`；整页 id 一次性传给 `cardsOf` |
| `record/web/dto/RecordDetailResponse` | `pairs` 上 `@JsonInclude(NON_NULL)`：`null` = 字段省略，`[]` = 所有者确实没有卡片 |
| `record/web/dto/MyRecordListItemResponse` | `pairs` 恒存在（`/my` 全是所有者视角），无卡片时 `[]` |

三条口径约束（写进代码也写进测试）：

1. **`PairCard` 永远不含对方号码**：本类没有 `contact` 字段，`PairCardFacts` 也没有，SQL 只 SELECT 一个
   `other.contact IS NOT NULL AND other.contact <> ''` 的布尔。想泄露得先改类型。
2. **卡片与计数同源**：`selectCardsByRecordIds` 的「有效」谓词与 `countByRecordIds` 逐条相同
   （两端 `deleted_at IS NULL`、`status <> 'CLOSED'`、COMPLETED 端 `completed_at > cutoff`），
   避免出现「卡片 1 张、`relatedCount` 说 2 张」。
3. **序号由服务端定**：`ORDER BY t.record_id, p.score DESC, p.id ASC`；适配器用 `LinkedHashMap` 保序，
   前端不再排。

## 3. 0.92 直看档：`DIRECT` / `scorePercent=92` / 无号码

请求（demo01 是所有者；本人 `contact` 故意为空，响应里若出现号码只可能来自卡片）：

```http
GET /api/records/31
Cookie: JSESSIONID=<demo01>
```

响应（`data` 全文，`pairs[0]` 即契约 §5.1 全字段）：

```json
{"code":0,"message":"ok","data":{"id":31,"type":"LOST","status":"PENDING","title":"E1b 直看档丢失的黑色钱包","description":"E1b fixture: direct tier owner record. No phone in this text.","categoryId":1,"categoryName":"钱包卡证","location":"图书馆三楼自习区","occurredAt":"2026-10-26 08:00:00","completedAt":null,"contact":null,"publisherName":"E1b-A","allowedStatuses":["CLOSED"],"hasMatchedPair":false,"matchedPairCount":0,"isMine":true,"pairState":"READY","lowConfidenceCount":0,"pairs":[{"pairId":21,"score":0.9200,"scorePercent":92,"tier":"DIRECT","status":"NEW","matched":false,"myConfirmed":false,"otherConfirmed":false,"lostConfirmedAt":null,"foundConfirmedAt":null,"engine":"DEEPSEEK","degraded":false,"counterpart":{"recordId":32,"type":"FOUND","title":"E1b 直看档拾到的黑色钱包","location":"图书馆三楼自习区","occurredAt":"2026-10-26 08:30:00","createdAt":"2026-10-26 09:05:00","status":"PENDING"},"mySide":"LOST","myUnlocked":false,"otherUnlocked":false,"myNote":null,"otherNote":null,"contactAvailable":true,"contactHint":"匹配度较高，可直接查看"}],"createdAt":"2026-10-26 09:00:00","updatedAt":"2026-10-26 09:00:00"},"requestId":"d02f8483185e48b8"}
```

断言结果：

```text
PASS  HTTP 200 / isMine=true
PASS  owner view 有 pairs 键
PASS  pairs 长度 1
PASS  pairId=21
PASS  tier=DIRECT / scorePercent=92
PASS  contactAvailable=true / hint=匹配度较高，可直接查看
PASS  PairCard 无 contact 键
PASS  响应体号码正则 0 命中
```

> `counterpart.location` 保留「图书馆三楼自习区」是因为它不超过 8 个码点、末尾也不命中精确定位片段；
> 同一套 `MaskingPolicy.maskLocation` 在 0.80 用例里把「图书馆二楼」截成「图书馆…」，见下节。

## 4. 0.80 可授权档：授权前 / 公开后 / 撤回后（不缓存）

同一 `pair 22`（record 33 LOST × record 34 FOUND，`score=0.8000`），三次请求详情、两次对方动作。

### 4.1 对方未公开：`AUTHORIZABLE` / 不可点 / `lowConfidenceCount=1`

```json
"pairs":[{"pairId":22,"score":0.8000,"scorePercent":80,"tier":"AUTHORIZABLE","status":"NEW","matched":false,"myConfirmed":false,"otherConfirmed":false,"lostConfirmedAt":null,"foundConfirmedAt":null,"engine":"DEEPSEEK","degraded":false,"counterpart":{"recordId":34,"type":"FOUND","title":"E1b 授权档拾到的蓝色水杯","location":"图书馆…","occurredAt":"2026-10-26 09:10:00","createdAt":"2026-10-26 10:05:00","status":"PENDING"},"mySide":"LOST","myUnlocked":false,"otherUnlocked":false,"myNote":null,"otherNote":null,"contactAvailable":false,"contactHint":"对方尚未公开"}]
```

同一响应里 `lowConfidenceCount=1`：record 33 还有一条 0.60 的 pair 23，**只计数、不出卡片**。

### 4.2 对方 PUBLISH 后：`contactAvailable=true` / `对方已公开联系方式` / `otherNote` 打码

```http
POST /api/pairs/22/contact-unlock
Cookie: JSESSIONID=<demo02>
{"action":"PUBLISH","note":"E1b 对方附言 13900000047"}
```

```json
{"code":0,"message":"ok","data":{"pairId":22,"side":"FOUND","unlockedAt":"2026-10-28 00:20:50","note":"E1b 对方附言 [手机号已隐藏]","public":true},"requestId":"..."}
```

再取详情（节选 `pairs[0]`）：

```json
{"pairId":22,"score":0.8000,"scorePercent":80,"tier":"AUTHORIZABLE","status":"NEW","matched":false,"myConfirmed":false,"otherConfirmed":false,"lostConfirmedAt":null,"foundConfirmedAt":null,"engine":"DEEPSEEK","degraded":false,"counterpart":{"recordId":34,"type":"FOUND","title":"E1b 授权档拾到的蓝色水杯","location":"图书馆…","occurredAt":"2026-10-26 09:10:00","createdAt":"2026-10-26 10:05:00","status":"PENDING"},"mySide":"LOST","myUnlocked":false,"otherUnlocked":true,"myNote":null,"otherNote":"E1b 对方附言 [手机号已隐藏]","contactAvailable":true,"contactHint":"对方已公开联系方式"}
```

- 号码原文 `13900000047` 只出现在 PUBLISH 的写库前文本里，响应和详情都是 `[手机号已隐藏]`。
- 详情响应体整份扫描 `1[3-9]\d{9}` = **0 命中**；`contactAvailable` 只是按钮状态，号码仍只从 §4.14 取。

### 4.3 对方 REVOKE 后再取：立刻回到 `false`（没有缓存）

```http
POST /api/pairs/22/contact-unlock
Cookie: JSESSIONID=<demo02>
{"action":"REVOKE"}
```

```text
PASS  REVOKE 200 / note=null
PASS  otherUnlocked=false / contactAvailable=false
PASS  hint=对方尚未公开 / otherNote=null
```

> 判定在每次请求的 SQL + 适配器里现算，没有进程内/HTTP 缓存；撤回后下一次请求立即看不到「已公开」。

## 5. 0.60 低分：不出卡片，只进 `lowConfidenceCount`

record 33 的详情 `pairs` 只有 pair 22，**没有 pair 23**（0.60）：

```text
PASS  只有 pair 22（0.60 的 pair 23 不出卡）
PASS  lowConfidenceCount=1
```

下界来自 `PairPolicyProperties.contactUnlockThreshold=0.75`（`application.yml` 的
`findit.pair.contact_unlock_threshold`），SQL 用 `#{threshold}` 传参，Java/SQL 都没有硬编码 0.75。

## 6. 同一条记录两对：降序 + 逐端状态独立 + MATCHED 带两端确认时间

record 36（LOST）有 pair 24（0.92，MATCHED）与 pair 25（0.80，CONTACTED）。详情节选：

```json
"pairs":[{"pairId":24,"score":0.9200,"scorePercent":92,"tier":"DIRECT","status":"MATCHED","matched":true,"myConfirmed":true,"otherConfirmed":true,"lostConfirmedAt":"2026-10-26 00:20:41","foundConfirmedAt":"2026-10-26 00:20:41","engine":"DEEPSEEK","degraded":false,"counterpart":{...},"mySide":"LOST","myUnlocked":false,"otherUnlocked":false,"myNote":null,"otherNote":null,"contactAvailable":true,"contactHint":"匹配度较高，可直接查看"},{"pairId":25,"score":0.8000,"scorePercent":80,"tier":"AUTHORIZABLE","status":"CONTACTED","matched":false,"myConfirmed":false,"otherConfirmed":false,"lostConfirmedAt":null,"foundConfirmedAt":null,"engine":"DEEPSEEK","degraded":false,"counterpart":{...},"mySide":"LOST","myUnlocked":true,"otherUnlocked":false,"myNote":"E1b 本端附言 [手机号已隐藏]","otherNote":null,"contactAvailable":false,"contactHint":"对方尚未公开"}]
```

```text
PASS  pairs 长度 2
PASS  顺序 24(0.92) → 25(0.80)
PASS  score 降序
PASS  pairs[0] MATCHED / matched=true
PASS  pairs[0] 两端 confirmed
PASS  pairs[0] 两端确认时间非空
PASS  pairs[1] CONTACTED / myConfirmed=false
PASS  pairs[1] 仅本端已授权
PASS  pairs[1] myNote 已打码
PASS  hasMatchedPair=true / matchedPairCount=1
PASS  t_record 36 仍 PENDING（不自动置位）
```

`t_record` 实测：`SELECT CONCAT_WS('|', status, IFNULL(completed_at,'NULL')) WHERE id=36` → `PENDING|NULL`。
`MATCHED` 只是 pair 的进展，不改记录状态（E1 口径）。

## 7. 失效 pair 剔除：CLOSED / COMPLETED 到期 / 已删除

record 39 库内有 5 条 pair；详情只返回 2 条（29、30）。三条被剔除的库内事实：

```text
pair 26  score=0.9100  lost=PENDING  found=CLOSED      deleted=NULL          completed_window=in-window
pair 27  score=0.9000  lost=PENDING  found=COMPLETED   deleted=NULL          completed_window=expired
pair 28  score=0.8900  lost=PENDING  found=PENDING     deleted=2026-10-28…   completed_window=in-window
pair 29  score=0.8800  lost=PENDING  found=PENDING     deleted=NULL          completed_window=in-window
pair 30  score=0.8500  lost=PENDING  found=PENDING     deleted=NULL          completed_window=in-window
```

> 26 是「对端 CLOSED」，27 是「对端 COMPLETED 且 `completed_at` 早于 now-30d」，28 是「对端
> `deleted_at` 非空」。三个条件都在 `selectCardsByRecordIds` 的 WHERE 里，不依赖 Java 过滤。
> 30 是 `IGNORED`，**照常出卡**（忽略只描述这一对的进展，不隐藏卡片）。

详情 `pairs`（节选）：

```json
[{"pairId":29,"score":0.8800,"tier":"AUTHORIZABLE","status":"NEW","matched":false,"contactAvailable":false,"contactHint":"对方尚未公开",...},
 {"pairId":30,"score":0.8500,"tier":"AUTHORIZABLE","status":"IGNORED","matched":false,"engine":"LOCAL_KEYWORD","degraded":true,"contactAvailable":false,"contactHint":"对方尚未公开",...}]
```

```text
PASS  pairs 长度 2
PASS  只剩 pair 29 / 30（26/27/28 被剔除）
PASS  CLOSED/到期/已删对端均未出卡
PASS  IGNORED 卡片照常出现
PASS  lowConfidenceCount=0
```

## 8. 非所有者 / 匿名 / COMPLETED 合格对端：`pairs` **键不存在**

匿名（无 Cookie）看 record 31：

```json
{"code":0,"message":"ok","data":{"id":31,"type":"LOST","status":"PENDING","title":"E1b 直看档丢失的黑色钱包","description":"E1b fixture: direct tier owner record. No phone in this text.","categoryId":1,"categoryName":"钱包卡证","location":"图书馆三楼自习区","occurredAt":"2026-10-26 08:00:00","completedAt":null,"contact":null,"publisherName":"E**","allowedStatuses":[],"hasMatchedPair":false,"matchedPairCount":0,"isMine":false,"pairState":null,"lowConfidenceCount":0,"createdAt":"2026-10-26 09:00:00","updatedAt":"2026-10-26 09:00:00"},"requestId":"0ef4df715fe04000"}
```

```text
PASS  匿名 200 / isMine=false / contact=null
PASS  匿名响应无 pairs 键
PASS  匿名 lowConfidenceCount=0
PASS  demo02 看他人 PENDING 200 / isMine=false
PASS  非所有者响应无 pairs 键
PASS  非所有者 lowConfidenceCount=0
PASS  demo01 作为合格对端看 COMPLETED 23 → 200 遮蔽
PASS  COMPLETED 合格对端响应无 pairs 键
PASS  COMPLETED 合格对端 low=0 / contact=null
```

> 这是 `@JsonInclude(NON_NULL)` 的直接结果：DTO 里 `pairs` 为 `null` 时整个键不出现。前端用
> `isMine` 判断视角；「字段省略」与「查过确实没有」在语义上不同，不能合并成 `[]`。
> COMPLETED 23 的合格对端判定沿用 C1（`pairUsable` 且 `score ≥ completed_visible_threshold`）。

## 9. `/api/my/records`：一页 3 条、逐条 `pairs[]`、失效不计入计数

请求：`GET /api/my/records?pageSize=3`（demo01）。返回 3 条（45 / 39 / 36）：

| 记录 | `pairCount` | `relatedCount` | `lowConfidenceCount` | `matchedPairCount` | `pairs` |
|---|---:|---:|---:|---:|---|
| 45（无 pair） | 0 | 0 | 0 | 0 | `[]` |
| 39（3 条失效 + 2 条有效） | 2 | 2 | 0 | 0 | `[29 NEW, 30 IGNORED]` |
| 36（MATCHED + CONTACTED） | 2 | 2 | 0 | 1 | `[24 MATCHED, 25 CONTACTED]` |

响应节选（保留三行的 `pairs` 与四个计数；完整响应见落盘 `evidence/my-page-3.json`）：

```json
{"id":45,"status":"PENDING","pairCount":0,"relatedCount":0,"lowConfidenceCount":0,"hasMatchedPair":false,"matchedPairCount":0,"detailVisible":true,"pairs":[],...}
{"id":39,"status":"PENDING","pairCount":2,"relatedCount":2,"lowConfidenceCount":0,"hasMatchedPair":false,"matchedPairCount":0,"pairs":[{"pairId":29,"score":0.8800,"status":"NEW",...},{"pairId":30,"score":0.8500,"status":"IGNORED",...}],...}
{"id":36,"status":"PENDING","pairCount":2,"relatedCount":2,"lowConfidenceCount":0,"hasMatchedPair":true,"matchedPairCount":1,"pairs":[{"pairId":24,"score":0.9200,"status":"MATCHED",...},{"pairId":25,"score":0.8000,"status":"CONTACTED",...}],...}
```

```text
PASS  HTTP 200 / 3 条
PASS  顺序 45,39,36（createdAt desc）
PASS  45：pairs=[]（存在且为空）
PASS  45：三个计数均为 0
PASS  39：pairs=[29,30]
PASS  39：pairCount=2 / relatedCount=2（三条失效不计）
PASS  39：IGNORED 卡片 status 保留
PASS  36：pairs=[24,25]
PASS  36：pairCount=2 / relatedCount=2 / matched=1
PASS  /my 前三条响应体号码正则 0 命中（三条本人 contact 均为空）
```

### 9.1 查询条数与页大小无关（MySQL `general_log` 实测）

对同一条 `/my` 请求打开 `general_log` 后统计：**引用 `t_pair_score` 的 SQL 恰好 2 条** ——
`countByRecordIds`（计数）与 `selectCardsByRecordIds`（卡片）各 1 条，与页内记录数无关。

```text
Query  SELECT t.record_id AS record_id, COUNT(*) AS total, SUM(CASE WHEN p.score >= ? ...      -- 计数
Query  SELECT t.record_id, p.id AS pair_id, p.score, p.status, ... counterpart_contact_present  -- 卡片
```

```text
PASS  pageSize=3：卡片查询 1 条
PASS  pageSize=3：pair 计数查询 1 条
PASS  pageSize=10 返回 10 条
PASS  pageSize=10：卡片查询仍是 1 条（不随页大小线性增长）
PASS  pageSize=10：pair 计数查询仍是 1 条
```

> pageSize 3→10 时，卡片 SQL 的 `IN (?, ?, …)` 参数变多，但**语句条数**不变；占用的是
> `idx_pair_lost_score` / `idx_pair_found_score` 的索引范围扫描，候选集就是页内记录的直接 pair 行。
> `MyRecordServiceTest` 用 Mockito `verify` 钉住了这一点：整页 id 一次传入，方法只调用一次。

## 10. 全局号码扫描

对卡片相关响应体逐份跑正则 `1[3-9]\d{9}`：

| 响应 | 身份 | 命中 |
|---|---|---:|
| `GET /api/records/31`（0.92 所有者） | demo01 | **0** |
| `GET /api/records/33`（0.80 公开后） | demo01 | **0** |
| `GET /api/records/36`（多 pair + 打码附言） | demo01 | **0** |
| `GET /api/records/39`（失效剔除 + IGNORED） | demo01 | **0** |
| `GET /api/my/records?pageSize=3` | demo01 | **0** |

说明：`/my` 是**本人**列表，按契约本人 `contact` 原样回显；本夹具三条记录的本人 `contact` 为空，
所以 0 命中只证明**卡片没有把对方号码带出来**，不是「本人号码也打码」。

## 11. 门禁与范围

- `mvn test`：**220 / 0 / 0**（含新增 17 个用例 + 既有类补 2 个）。
- 端到端：**71 条断言，0 失败**；请求/响应落盘在 `evidence/`（本机，不随仓库分发）。
- 未改 `db/schema.sql`；未改 `web/`；未新增接口；未改 C1 的可见性判定（只补 `pairs[]`）。
- 未推 main、未合并 main；分支从 `origin/feat/v2-E1-contact-unlock @ 17dcc9a77` 切出。

## 12. 权衡与代价

| 决策 | 备选 | 放弃原因 / 代价 |
|---|---|---|
| 计数与卡片**各一条批量 SQL** | 一条 SQL 同时出明细与计数 | 一条 SQL 要用窗口函数/重复聚合，口径更难读；当前 2 条都是 `IN (页内 id)` 的索引查询，代价可接受。若 `/my` p95 不达标，再合并。 |
| 每次请求**现算** `tier` / `contactAvailable` | 缓存卡片结果 | 授权/撤回/确认/忽略/重打分都会改卡片；缓存要在所有 pair 写路径上失效，漏一条就是「对方已撤回但按钮还能点」。契约也明确「不缓存」。 |
| 卡片字段在 **pair 上下文**算好，record 只搬运+打码 | record 侧直接调 `PairAccessPolicy` | 会让 record 反向依赖 pair 的枚举与判定；`PairFactsPort` 依赖倒置已经把方向定死，两处实现必然漂移。 |
| **类型上**杜绝对方号码（`PairCard` 无 `contact` 字段） | 约定「不许填 contact」 | 靠注释约束不住后来的人；SQL 只 SELECT 布尔，Java 对象里根本没有号码可填。 |
| 失效 pair **在 SQL 剔除** | 取回后在 Java 过滤 | Java 过滤会让 `pairCount/relatedCount` 与 `pairs` 两套口径分叉；SQL 是唯一事实来源。 |
| `pairs` 在非所有者视角**省略键** | 返回 `[]` | `[]` 会被前端读成「查过了、确实没有」；`isMine` + 键存在性才能区分视角。 |

已知边界（不是本条的缺陷，是明确的容量/运维问题）：

- **每页 2 条 pair 查询**：`/my` 一页 50 条时，候选 pair 行数取决于这些记录参与了多少对；当前
  B1 索引足够，但**十万级 `t_pair_score` 下没有实测**。需要压测时先看 `EXPLAIN` 是否仍走
  `idx_pair_lost_score` / `idx_pair_found_score`，不要在未验证前承诺 p95。
- **无缓存**：撤回/公开后下一次请求实时生效，代价是高 QPS 下 DB 读放大；要加缓存必须先设计
  失效路径（pair 写路径全部覆盖），本条不做。
- **`lowConfidenceCount` 与卡片是两次独立查询**：两处共用同一个 `cutoff`（`MyRecordService` 里
  只算一次 `now`），但极端情况下两次查询之间发生写入，仍可能出现「计数与卡片差 1」的瞬时不一致；
  读接口可接受，若要强一致需放进同一事务/同一 SQL。
- **`PairCardFacts` 是 27 分量 record**：新增一个卡片字段要同时改 SQL、`PairCardRow`、
  `PairCardFacts`、`PairCard` 四处；这是「卡片字段属于契约」的显式成本，不打算用反射/Map 绕过。

## 13. 复现命令

```powershell
# 1) 单测
mvn -s m2-settings-online.xml test          # Tests run: 220, Failures: 0, Errors: 0

# 2) 起库（隔离数据目录，端口 3307）
mysqld --datadir=work-v2-E1b\mysql-data --port=3307 --bind-address=127.0.0.1

# 3) 建库 + 种子 + E1b 夹具
mysql --host=127.0.0.1 --port=3307 -uroot -e "source db/schema.sql"
mysql --host=127.0.0.1 --port=3307 -uroot -e "source db/seed.sql"
mysql --host=127.0.0.1 --port=3307 -uroot -e "source work-v2-E1b/fixtures.sql"

# 4) 起应用（jar 已在 server/target）
java -jar server/target/find-it-campus-0.0.1-SNAPSHOT.jar --server.port=18084

# 5) 端到端断言（本机脚本，不随仓库分发）
powershell -ExecutionPolicy Bypass -File work-v2-E1b/evidence-run.ps1
# RESULT: PASS=71 FAIL=0
```

> 夹具文件与脚本和 E1 一样只在本机保留，仓库里只提交实现、测试与本证据文档；复现所需的 SQL
> 结构（记录 31/33/36/39/45 + pair 21~30）已在第 3~9 节的响应与库内事实中逐条给出。
