# C1 遮蔽与所有权 · 执行记录

- 任务：[ZHYYKFX-52](https://app.hifox.com/nhnok/task/ZHYYKFX-52) · C1 遮蔽与所有权 ｜ 磐石石 ｜ dsh
- 分支：`feat/v2-C1-masking`（从 `origin/feat/v2-B2-auth` @ `2553695` 切出；不推 main、不合并）
- 契约：`feat/v2-A1-arch-contract` @ `b90270e` —— `docs/design/v2-architecture.md` §6 / §9.2 / §9.3、`docs/api-contract-v2.md` §1.2 / §4.1–§4.6 / §4.8 / §4.12 / §4.16
- 执行日期：2026-10-27
- 环境：Windows 10 Pro for Workstations / Temurin JDK 17.0.20.1 / Maven 3.10.0 / MySQL 8.0.29（独立数据目录 + 3311，避免与 3306/3307 上的其他任务抢库）/ Spring Boot 3.5.16
- 证据脚本：`c1-scratch/run-evidence.ps1`（本地临时脚本，不入库）；原始输出已随证据提交：
  `docs/testing/v2/C1-masking-evidence-results.txt`

## 结论

范围 6 项 + 终态口径 vF + 补充 #2 + A1 文档标注全部落地。**真机 54 项断言全过（54/54）**，
`server/` 单元 + 切片测试 **127 通过 / 0 失败**（C1 新增 43 条）。

- 遮蔽只有一个实现：`common/masking/MaskingPolicy`；五个出口（列表 / 详情 / `/api/match` / 我的启事 / 通知）
  全部调用它，没有第二份正则。
- 所有权只有一条判据：`publisher_id == session.userId`；非所有者写操作 403/10007 且 DB 行逐字段不变。
- `t_record.status` 只有三条写语句：`insert` 初始化 `PENDING`、`markCompleted`、`close`；
  系统任务没有任何写 status 的 SQL。
- 数据库 `contact` 保持明文（输出层遮蔽，V2-ADR-003），直查证据见 §7。

所有手机号、证件号、QQ 号均为假号（`138xxxx` / `139xxxx` / `123456789` / `110101…`），
不含真实个人信息；会话 Cookie 未写入证据。

## 0. 复现步骤

```bash
# 1) 起库（独立数据目录 + 3311）
mysqld --no-defaults --datadir=<workspace>/mysql-b1-data --port=3311 --bind-address=127.0.0.1
mysql --no-defaults --default-character-set=utf8mb4 -u root -h 127.0.0.1 -P 3311 < db/schema.sql
mysql --no-defaults --default-character-set=utf8mb4 -u root -h 127.0.0.1 -P 3311 < db/seed.sql

# 2) 测试与构建
cd server
mvn -o -B test                 # 127 tests, 0 failures
mvn -o -B -DskipTests package  # target/find-it-campus-0.0.1-SNAPSHOT.jar

# 3) 启动（仓库里不出现任何真实口令）
FINDIT_DB_URL='jdbc:mysql://127.0.0.1:3311/find_it_campus?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8' \
FINDIT_DB_USER=root FINDIT_DB_PASSWORD= \
java -jar target/find-it-campus-0.0.1-SNAPSHOT.jar --server.port=18081
```

演示账号来自 `db/seed.sql`：`demo01` / `demo02` / `demo03`，口令见 README（证据里不引用口令明文）。

证据夹具：以 `demo01`（user 1）新发一条记录 X（本次运行 id=**27**，`type=LOST`、`status=PENDING`）：

```text
title       = C1 证据：第三教学楼丢了黑色钱包
description = 昨天 14:30 在第三教学楼 201 捡到，联系 13900001111，QQ 123456789，证件 110101199001011234
location    = 第三教学楼201
contact     = 13900001111
```

这条记录同时覆盖四类正则与地点截断；`demo02`（user 2）作为非所有者，`demo03`（user 3）作为
seed #23 的所有者。运行后的 `t_record.id=27` 由 `db/schema.sql` 重建 + `seed.sql` 后自增得到。

---

## 1. 遮蔽单点与身份维度

### 1.1 单点实现

| 出口 | 装配点 | 调用 |
|---|---|---|
| 公共列表 | `RecordListItemResponse.from(summary, viewerId)` | `MaskingPolicy.maskDescription/maskLocation` |
| 详情 | `RecordDetailResponse.from(record)` / `ownerView(...)` | 同上 + `maskPublisherName`；非所有者 `contact=null` |
| `/api/match` | `MatchItemResponse.of(..., ownerView)` | title/location/reason 同一套 |
| 我的启事 | `MyRecordListItemResponse.of(row, counts)` | **本人数据不遮蔽**（契约 §4.12） |
| 通知 | `NotificationItemResponse.of(notification)` | title/body 输出再打一次（纵深防御） |

`MaskingPolicy` 是纯函数、只依赖 JDK；`RecordDetailResponse.from(Record)` 与
`RecordListItemResponse.from(RecordSummary)` 都是 **fail-closed 默认（遮蔽）**，
本人视角必须显式传 `viewerId` / 调 `ownerView`，新调用点忘记判身份时最坏是多打码，不会漏号码。

### 1.2 正例 / 反例 / 所有者原样（单测 `MaskingPolicyTest`，10 条）

| # | 输入 location | 输出 location | 输入 description 片段 | 输出 description 片段 |
|---|---|---|---|---|
| P1 | `第三教学楼201` | `第三教学楼…` | `昨天 14:30 … 13812345678` | `[时间已隐藏] … [手机号已隐藏]` |
| P2 | `东区3号宿舍楼512室` | `东区3号宿舍楼…` | `QQ 123456789，证件号 110101199001011234` | `QQ[QQ号已隐藏]，证件号 [证件号已隐藏]` |
| P3 | `图书馆三楼自习区A12座` | `图书馆三楼自习区…` | `上周五 09:15 … 3 号楼 201 … 13900001111` | `[时间已隐藏] … 3 号楼 201 … [手机号已隐藏]` |
| N1 | `图书馆` | `图书馆` | `3 号楼 201 教室，无联系方式` | 原样 |
| N2 | `大学生活动中心` | 原样 | `12345` / `12345678` / `13:30`（无 QQ、无相对日期） | 原样 |
| N3 | `操场` | 原样 | `2026-10-21 13:30 … 卡号 201` | 原样 |
| O1–O3 | 同 P1–P3，查看人为所有者 | 四项全部原值 | — | — |

`maskPublisherName`：`李同学 → 李**`、`赵 → *`、空 → `匿名同学`。
正例/反例与 A1 §6.4 逐字一致；裸房间号（201）、楼层（3 号楼）、卡号（12345）均不打码。

### 1.3 地点截断的边界

`第二教学楼楼梯口`（8 个码点，结尾不是定位片段）在非所有者详情里**原样保留**——
截断的目标是"去掉可定位到房间/座位的精度"，不是把所有地点都变成不可读（§6.1 的设计取舍）。

---

## 2. 五出口逐一比对（同一记录 X=27，同一身份）

### 2.1 匿名 / 登录非所有者（demo02）：四处一致

```text
anon   GET /api/records/27
{"id":27,"status":"PENDING",
 "title":"C1 证据:第三教学楼丢了黑色钱包",
 "description":"[时间已隐藏] 在第三教学楼 201 捡到，联系 [手机号已隐藏]，QQ[QQ号已隐藏]，证件 [证件号已隐藏]",
 "location":"第三教学楼…","contact":null,"publisherName":"李**",
 "isMine":false,"allowedStatuses":[],"hasMatchedPair":false,"matchedPairCount":0}

demo02 GET /api/records/27
{"id":27,"status":"PENDING",
 "title":"C1 证据:第三教学楼丢了黑色钱包",
 "description":"[时间已隐藏] 在第三教学楼 201 捡到，联系 [手机号已隐藏]，QQ[QQ号已隐藏]，证件 [证件号已隐藏]",
 "location":"第三教学楼…","contact":null,"publisherName":"李**",
 "isMine":false,"allowedStatuses":[],"hasMatchedPair":false,"matchedPairCount":0}
```

| 出口 | 请求 | title | location | description | contact | publisherName |
|---|---|---|---|---|---|---|
| 列表 | `GET /api/records?pageSize=50`（匿名） | 打码（无号码，故原文） | `第三教学楼…` | 不返回 | 不返回 | 不返回 |
| 详情 | `GET /api/records/27`（匿名/demo02） | 打码 | `第三教学楼…` | 已打码 | `null` | `李**` |
| 匹配 | `POST /api/match`（demo02） | 打码 | `第三教学楼…` | 不返回（`reason` 已打码） | 不返回 | 不返回 |
| 我的 | `GET /api/my/records`（demo02） | — | — | — | — | X 不在 demo02 的集合里（按 publisher_id 过滤） |
| 通知 | `GET /api/notifications`（demo02） | 通知标题/正文走同一打码器；不含记录标题（A1 已收敛为通用提示） | — | — | — | — |

断言原文：`PASS anon and non-owner see identical masked fields (5-exit consistency)`。
列表与详情对同一身份给出**逐字相同**的 title/location；匹配项同样打码（见 §4）。

### 2.2 所有者（demo01）：原文回显

```text
demo01 GET /api/records/27
{"id":27,"status":"PENDING","title":"C1 证据：第三教学楼丢了黑色钱包",
 "description":"昨天 14:30 在第三教学楼 201 捡到，联系 13900001111，QQ 123456789，证件 110101199001011234",
 "location":"第三教学楼201","contact":"13900001111","publisherName":"李同学",
 "isMine":true,"allowedStatuses":["CLOSED"],"hasMatchedPair":false,"matchedPairCount":0}

demo01 GET /api/my/records?pageSize=50 → 同一记录的 list 行
{"id":27,"status":"PENDING","title":"C1 证据：第三教学楼丢了黑色钱包","location":"第三教学楼201",
 "contact":"13900001111","publisherName":"李同学","detailVisible":true,"isMine":true, ...}
```

公共列表的所有者行同样 `isMine=true`、location 原文（`x_in_owner_list`），
与详情/我的启事一致。**非所有者列表/详情/match 的响应体手机号命中数 = 0**（§3）。

### 2.3 通知出口（防御性夹具）

seed 的 5 条通知正文本身不含号码。为了证明"通知出口也调用同一套打码器"，DB 直插一条
**防御性夹具**（正常写入路径 D1 会写已打码的值；这里模拟历史/脏数据）：

```sql
INSERT INTO t_notification (user_id, pair_id, type, title, body, read_at, created_at)
VALUES (1, NULL, 'SYSTEM', 'C1 防御性夹具 13900001111', '正文含 13900001111 与 QQ 123456789', NULL, NOW());
```

```text
GET /api/notifications?pageSize=50 （demo01）
{"id":9,"type":"SYSTEM","pairId":null,
 "title":"C1 防御性夹具 [手机号已隐藏]",
 "body":"正文含 [手机号已隐藏] 与 QQ[QQ号已隐藏]",
 "read":false,"createdAt":"2026-10-27 20:16:50"}
body 手机号命中数 = 0
```

---

## 3. 响应体全局手机号正则扫描

对**非所有者 / 匿名**的每个响应体取 UTF-8 原文，跑 `1[3-9]\d{9}` 计数：

| 响应体 | 命中数 |
|---|---|
| 匿名详情 `x-anon-detail.json` | **0** |
| 非所有者详情 `x-demo02-detail.json` | **0** |
| 匿名公共列表 `list-anon.json`（50 条） | **0** |
| 非所有者 `/api/match` `match-x.json`（20 条） | **0** |
| 含手机号的 `/api/match` `match-phone.json` | **0** |
| 通知（夹具前/后） | **0 / 0** |
| 终态 404 body（X/23/24/25） | **0** |

所有者自己的详情/我的启事按 C1 验收要求回显本人号码（`my-demo01.json` 命中 18 次 = 数据库里本人记录的明文号码），
这是**本人数据回显**，不是跨用户泄露（契约 §1 备注、A1 §6.3 末段）。

---

## 4. 匹配快照写入前脱敏（H8）

以 `demo02` 发起：

```text
POST /api/match {"text":"昨天 14:30 联系 13900001111，QQ 123456789","minScore":0,"topN":5}
http=200，响应体手机号命中数 0
```

同一次调用写入的 `t_match_result`（最新一行）：

```text
input_text = [时间已隐藏] 联系 [手机号已隐藏]，QQ[QQ号已隐藏]
candidates = [{"score":0.78,"reason":"关键词命中:[手机号已隐藏]qq[QQ号已隐藏]、1430、昨天、联系","recordId":27},
              {"score":0.33,"reason":"关键词命中:昨天、联系","recordId":1}, ...]
```

`input_text` 与 `candidates[].reason` 都在 `INSERT` 之前过了 `MaskingPolicy.maskDescription`；
SQL 里没有明文号码。`/api/match` 命中 COMPLETED（seed #23）时：

```text
{"recordId":23,"status":"COMPLETED","recordStatus":"COMPLETED","detailAccess":"RESTRICTED",
 "title":"已归还:第二教学楼捡到的蓝色挂件钥匙","location":"第二教学楼楼梯口", ...}
```

`detailAccess=RESTRICTED` 表示"命中 ≠ 详情授权"——点进去仍由详情门槛判定（§6）。
候选池 `MATCHABLE` 由 `RecordMapper.xml#matchablePredicate` 统一定义：
`PENDING` 或保留期内的 `COMPLETED`，`CLOSED` 一律不召回。

---

## 5. 所有权：403 + DB 逐字段不变

记录 X=27（owner=demo01），`demo02` 逐项尝试：

### 5.1 PATCH /api/records/27/status

```text
> PATCH /api/records/27/status   {"status":"CLOSED"}     （demo02 的会话）
HTTP/1.1 403
{"code":10007,"message":"无权操作该资源","data":null,"requestId":"1cb37d4ed1014851"}

SELECT status, completed_at, updated_at, deleted_at FROM t_record WHERE id=27;
调用前：PENDING	NULL	2026-10-27 20:16:48	NULL
调用后：PENDING	NULL	2026-10-27 20:16:48	NULL
t_access_log：0 -> 0
```

### 5.2 DELETE /api/records/27

```text
HTTP/1.1 403
{"code":10007,"message":"无权操作该资源","data":null,"requestId":"1a0cc15897c445d0"}

SELECT deleted_at FROM t_record WHERE id=27;  → NULL
```

### 5.3 PUT /api/records/27

```text
HTTP/1.1 403
{"code":10007,"message":"无权操作该资源","data":null,"requestId":"08cb5b62c63c421b"}

title 调用前后均为：C1 证据：第三教学楼丢了黑色钱包
status/completed_at/updated_at/deleted_at 均不变
```

三个写方法的判定顺序统一为：**不存在/不可见 → 404，可见但非所有者 → 403，所有者但状态不允许 → 409**。
因此 CLOSED 记录不会先命中 20003 而把"存在且已关闭"泄露给非所有者。

`t_access_log` 在 C1 的所有读/写 404/403 路径上都不新增（C1 不写审计；号码取号审计属于 E1）。

---

## 6. 三态与终态可见性

### 6.1 「标记完成」前置：409/20005 + 零写入

```text
> PATCH /api/records/27/status  {"status":"COMPLETED"}    （demo01，X 尚无任何 pair）
HTTP/1.1 409
{"code":20005,"message":"需双方确认匹配后才能标记完成",
 "data":{"currentStatus":"PENDING","required":"MATCHED_PAIR","matchedPairCount":0},
 "requestId":"efee4aad994342e6"}

调用前：PENDING	NULL	2026-10-27 20:16:48	NULL
调用后：PENDING	NULL	2026-10-27 20:16:48	NULL
t_access_log：0 -> 0
```

实现上 `RecordStatusPolicy.requireCompletable(cnt)` 在 `UPDATE` 之前调用，失败分支没有任何 DML；
`t_record.completed_at` 保持 NULL。**不是**"先写后判再回滚"。

用 DB 直插造数（不等待 D1）：`(lost_id=27, found_id=13, score=0.9500, status=MATCHED, 两端 confirmed_at=NOW())`，
再以 demo01 调一次：

```text
HTTP/1.1 200
{"code":0,"message":"已标记完成",
 "data":{"id":27,"status":"COMPLETED","completedAt":"2026-10-27 20:16:54",
         "updatedAt":"2026-10-27 20:16:54","allowedStatuses":[],
         "hasMatchedPair":true,"matchedPairCount":1}}

SELECT status, completed_at, updated_at FROM t_record WHERE id=27;
COMPLETED	2026-10-27 20:16:54	2026-10-27 20:16:54
```

status 与 completed_at 由**同一条 UPDATE** 写入（`RecordMapper.xml#markCompleted`），
满足 `ck_record_completed_at`；不存在中途崩溃的脏行。

### 6.2 详情出口 × 身份 × 状态（实际状态码）

| 记录 | 状态 | 匿名 | demo01 | demo02 | demo03 |
|---|---|---|---|---|---|
| X=27（配对 27↔13，0.95 MATCHED） | COMPLETED（3 秒前） | ❌ 404/20001 | ❌ 404/20001（所有者） | ✅ 200（合格对端，遮蔽） | ❌ 404/20001 |
| #23（配对 10↔23 0.95 MATCHED；6↔23 0.93 **IGNORED**） | COMPLETED（3 天前） | ❌ 404/20001 | ✅ 200（合格对端，遮蔽） | ❌ 404/20001（唯一关联 pair 为 IGNORED） | ❌ 404/20001（所有者） |
| #24 | CLOSED | ❌ 404/20001 | ❌ 404/20001 | ❌ 404/20001（所有者） | — |
| #25（completed_at 31 天前，超出 30 天保留期） | COMPLETED（已到期） | — | ❌ 404/20001（所有者） | ❌ 404/20001 | — |

404 body 统一为：

```json
{"code":20001,"message":"记录不存在或已删除","data":null,"requestId":"..."}
```

不含标题、联系方式、完成时间；`demo02` 对 #23 的 404 与"完全没有 pair"同码同文案
（`PASS seed 23 IGNORED-only pair 0.93 -> 404/20001 same code as no pair`）。
所有者看自己的 COMPLETED 同样 404（A9 字面），X=27 与 #23 两处都验证。
COMPLETED 的 200 视图 `contact=null`、`completedAt=null`、`isMine=false`、`pairs` 省略、`lowConfidenceCount=0`。

### 6.3 列表出口

```text
GET /api/records?status=COMPLETED&pageSize=50  → 200, total=0
GET /api/records?status=CLOSED&pageSize=50     → 200, total=0
GET /api/records?status=ALL&pageSize=50        → 200, 仍只含 PENDING
GET /api/records?pageSize=50 的 id 集合：27,26,22,15,3,7,18,6,19,4,1,9,16,8,14,2,20,17,21,5,10,13
（不含 23/24/25）
```

SQL 恒加 `r.status='PENDING'`（`listJoins`），请求里的 `COMPLETED/CLOSED/ALL` 只影响"确认终态 0 命中"，
不能通过参数改变可见集合。

### 6.4 `/api/my/records` 的三态摘要

```text
demo03 GET /api/my/records?pageSize=50 中的 #23：
{"id":23,"status":"COMPLETED","title":"已归还：第二教学楼捡到的蓝色挂件钥匙",
 "location":"第二教学楼楼梯口","completedAt":"2026-10-24 20:16:36",
 "contact":"13800000111","publisherName":"何同学","pairState":"READY",
 "pairCount":2,"relatedCount":2,"lowConfidenceCount":0,
 "hasMatchedPair":true,"matchedPairCount":1,"detailVisible":false, ...}
```

本人数据原文回显（含 contact），`detailVisible=false` 与详情 404 的口径一致；
计数只算"有效 pair"（两端未删/未 CLOSED/未过期），`pairCount` 含低分、`relatedCount` 只算
`score ≥ contact_unlock_threshold`、`matchedPairCount` 只算有效 MATCHED。

---

## 7. DB 直查：contact 仍为明文

```sql
SELECT id, contact FROM t_record WHERE id IN (27,13,23) ORDER BY id;
```

```text
13	13800000101
23	13800000111
27	13900001111
```

`masking is output-only`：应用层没有任何"写库前把 contact 清空/加密"的逻辑，
`t_record.contact` 的 UPDATE 仍按用户提交的号码落库；遮蔽只发生在 DTO 装配。

---

## 8. 状态写入路径收敛

### 8.1 静态检查

```text
Select-String server/src/main/resources/mapper/record/RecordMapper.xml -Pattern 'SET\s+status'
218:  SET status       = 'COMPLETED',
226:  SET status       = 'CLOSED',

主源码 CLAIMED 引用：6 处（均为"已作废"说明/校验文案）；代码引用（RecordStatus.CLAIMED / 枚举声明）0 处
```

`t_record.status` 的全部写语句：
1. `insert`：插入时初始化 `PENDING`（唯一初始化点）；
2. `markCompleted`：`UPDATE ... SET status='COMPLETED', completed_at=#{at}, updated_at=#{at} WHERE status='PENDING'`；
3. `close`：`UPDATE ... SET status='CLOSED', completed_at=NULL, updated_at=#{at} WHERE status='PENDING'`。

`updateContent` 不接收 status，`markDeleted` 只写 `deleted_at/updated_at`。
代码里没有 scheduler / async worker 写 `t_record.status` 或 `completed_at` 的路径（D1 尚未落地；
D1 落地时只能读 `MATCHABLE` 谓词，不能新增 status 写入）。

### 8.2 「任务跑一轮」实测

跑一轮 `/api/match`（引擎打分 + 写 `t_match_result` 快照，含降级/兜底路径）前后：

```text
record 13: PENDING  NULL  2026-09-15 15:30:00  NULL  ->  同左
record 1 : PENDING  NULL  2026-10-20 09:10:00  NULL  ->  同左
```

`status / completed_at / updated_at` 三个值完全不变，证明快照写入与打分不碰记录主表状态。
（D1 的每日扫描尚未落地，本条用"现有唯一的系统写入链路"验证；D1 落地时应复用同一断言。）

---

## 9. 配置集中（A1 §7.1）

唯一配置类：`com.findit.campus.pair.config.PairPolicyProperties`（`@ConfigurationProperties(prefix="findit.pair")`），
`@PostConstruct` 校验并 fail-fast：

| YAML 键 | 默认值 | 校验 |
|---|---|---|
| `findit.pair.contact_direct_threshold` | `0.90` | `0..1` |
| `findit.pair.contact_unlock_threshold` | `0.75` | `0..1` 且 `< contact_direct_threshold` |
| `findit.pair.completed_visible_threshold` | `0.90` | `0..1`；详情门槛/`/my`/match 共用 |
| `findit.pair.completed_retention_days` | `30` | `1..3650`；`completedCutoff(now)` 唯一入口 |
| `findit.pair.notify_threshold` | `0.75` | `0..1` 且 `≥ contact_unlock_threshold` |

`PairPolicyPropertiesTest` 6 条：默认值、`unlock≥direct` 失败、分数越界、保留期边界、
`notify<unlock` 失败、`cutoff = now-30d`。服务启动时配置非法直接失败，不带病运行。

---

## 10. A1 文档标注（#11 连带）

- `docs/design/v2-architecture.md` §4.6 标题下新增一行「不适用」说明：v1 无真实历史数据，
  B1 直接重建 `schema.sql` + `seed.sql`，不提供 v1→v2 迁移脚本；
- 第 155 行原「并入 `schema.sql` 与 v1→v2 迁移脚本」改为只留 `schema.sql`；
- §4.6 升级步骤的小标题改为「本方案已作废，以下为原始设计记录」，正文未删除（保留历史结论）。

验收（`docs/` 递归扫描 v1→v2 迁移脚本的文件名，即带 `db/` 前缀的那个 `.sql` 名称）：

```text
count=1，唯一命中是 docs/design/v2-architecture.md 第 449 行的「不适用」说明
```

扫描命令与原始输出见 `docs/testing/v2/C1-masking-evidence-results.txt` 的
`POSTRUN docs_recursive_grep` 行（同时清理了 B1 证据里一处指向该不存在文件的旧文件名，
只改措辞、不改结论）。

---

## 11. 自动化测试

```text
mvn -o -B test
Tests run: 127, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

C1 新增/更新的测试：

| 测试类 | 条数 | 覆盖 |
|---|---|---|
| `common/masking/MaskingPolicyTest` | 10 | A1 §6.4 正例 P1–P3、反例 N1–N3、所有者原样、全角归一、空值兜底、手机号全局正则、列表/详情身份维度 |
| `record/service/RecordStatusPolicyTest` | 8 | 三态流转、终态、20002、20005（数据体）、`allowedStatuses` 过滤 |
| `record/service/RecordVisibilityPolicyTest` | 7 | 三态 × 三类身份 × 保留期边界 × 合格对端；MATCHABLE 谓词 |
| `record/service/RecordServiceAccessTest` | 13 | 非所有者 403 + 零写；20005 零写；200 完成；CLOSED/COMPLETED/到期 404；IGNORED 404；PENDING 遮蔽/明文；create 取会话身份 |
| `record/web/MyRecordsApiTest` | 3 | 匿名 401；有会话 200 + 本人字段原文；CLAIMED → 400 |
| `pair/config/PairPolicyPropertiesTest` | 6 | 阈值/保留期 fail-fast |
| `match/service/MatchServiceTest`（新增 2 条） | 12 | 快照输入与 reason 写入前脱敏；COMPLETED 候选 RESTRICTED |
| `record/web/RecordApiValidationTest`（更新） | 17 | 遮蔽视角 contact=null、isMine、allowedStatuses 有/无 MATCHED |

**说明**：B2 的 test-only `authprobe/MyRecordsProbeController` 与 `MyRecordsGateProbeTest` 已删除，
门禁断言由 `MyRecordsApiTest` 打真接口（B2 证据 §8 的约定）。

---

## 12. 代码清单

```text
server/src/main/java/com/findit/campus/
  common/masking/MaskingPolicy.java                 新增：遮蔽单点（location/description/publisherName）
  common/api/ErrorCode.java                         新增 10007 FORBIDDEN、20005 PAIR_REQUIRED_FOR_COMPLETE
  record/domain/RecordStatus.java                   三态；CLAIMED 删除
  record/domain/Record.java                         新增 publisherId/completedAt/pairState
  record/domain/RecordSummary.java                  新增 publisherId/completedAt
  record/domain/MyRecordRow.java                    新增：我的启事读模型
  record/domain/MyPairCounts.java                   新增：四个计数
  record/service/RecordStatusPolicy.java            三态流转 + requireCompletable（20005）
  record/service/RecordVisibilityPolicy.java        新增：详情三档可见性 + MATCHABLE
  record/service/RecordService.java                 身份/所有权/终态/唯一 status 写路径
  record/service/MyRecordService.java               新增：我的启事（三态）
  record/service/PairFactsPort.java                 新增：record 对 pair 事实的只读依赖
  record/web/RecordController.java                  从会话取身份；写接口 requireUser
  record/web/MyRecordsController.java               新增：GET /api/my/records
  record/web/dto/RecordDetailResponse.java          身份维度 + completedAt/hasMatchedPair/计数
  record/web/dto/RecordListItemResponse.java        isMine + 按身份遮蔽
  record/web/dto/StatusUpdateRequest.java           只接受 COMPLETED/CLOSED
  record/web/dto/StatusUpdateResponse.java          completedAt/updatedAt/hasMatchedPair/matchedPairCount
  record/web/dto/MyRecordsQuery.java                新增
  record/web/dto/MyRecordListItemResponse.java      新增
  record/mapper/RecordMapper.java/.xml              MATCHABLE、markCompleted/close、我的启事、publisher_id
  record/mapper/MyRecordCondition.java              新增
  pair/config/PairPolicyProperties.java             新增：唯一配置类
  pair/mapper/PairFactsMapper.java/.xml             新增：只读 pair 事实
  pair/service/PairFactsAdapter.java                新增：实现 record 的只读端口
  match/service/MatchService.java                    遮蔽 + MATCHABLE + 快照写入前脱敏
  match/service/RecordCandidatePoolAdapter.java      MATCHABLE + 保留期 cutoff
  match/web/MatchController.java                     传 viewerId
  match/web/dto/MatchItemResponse.java               打码 + recordStatus/detailAccess
  notification/**                                   新增：通知列表读取 + 输出遮蔽
server/src/main/resources/application.yml           新增 findit.pair.*
docs/design/v2-architecture.md                      §4.6 / 第 155 行标注
docs/testing/v2/C1-masking-evidence.md              本文件
```

---

## 13. 未覆盖 / 明确不做

| 项 | 状态 | 说明 |
|---|---|---|
| `PairCard`（详情/我的启事内嵌 `pairs[]`） | 不在 C1 | 依赖 E1 的 pair 四态/授权/取号语义；C1 只给 `hasMatchedPair/matchedPairCount/pairCount/relatedCount/lowConfidenceCount`，避免拼一个半成品卡片再被 E1 改结构 |
| 通知写入（PAIR_MATCH/PAIR_UNLOCK/SYSTEM） | 不在 C1 | D1 负责；C1 只做读取 + 输出遮蔽。唯一键去重、阈值、失效 pair 等写入语义不属于本条 |
| `PATCH /api/notifications/{id}/read` | 不在 C1 | E1（通知与 pair 联动） |
| 每日扫描 / 到期清扫定时器 | 不在 C1 | D1；本条只保证"到期是读时窗口，任何任务都不写 status" |
| `/api/pairs/*`（PATCH/contact/unlock） | 不在 C1 | E1；`t_access_log` 的取号审计也属于 E1 |
| COMPLETED 到期后 pair 的派生清理 | 不在 C1 | D1；C1 的 `PairFactsPort` 已按 `completedCutoff` 把到期端视为无效 |
| 多实例 / 分布式会话 | 不支持 | 沿用 B2 的内存会话；多实例需 Redis/DB 会话与分布式锁（设计文档 §10） |
| 通知防刷屏 | D1 | 唯一键 `(user_id,pair_id,type)` 已在库层，写入方负责 |

### 已知行为（不隐藏）

- **全角冒号归一**：A1 §6.2 规定把 `：` 映射为 `:`，因此遮蔽视图里的标题/描述会把中文全角冒号
  显示成半角（例如 `C1 证据:第三…`）；所有者视图保留原文。无隐私影响，属于规则的直接结果。
- **COMPLETED 的 200 视图不返回 completedAt**：契约 §4.2 明确"非所有者看不到完成时间"，
  合格对端也是非所有者，因此为 `null`。
- **列表关键词语义**：LIKE 匹配的是**原始** title/description（库内明文），不是打码后的文本；
  这是"输出层遮蔽"的必然结果。搜索词不会因为记录里含号码而命中号码（用户搜索的是文本片段），
  但若用户搜索"13900001111"恰好命中明文描述，列表仍会返回该行（返回体已打码）—— 这属于
  "输出不可见、检索可命中"的残余面，生产需评估是否需要检索词脱敏（超出 C1 范围）。
- **`/api/match` 引擎输入**：当前 `findit.match.engine=local`（本地关键词，不出网）；若将来切
  external embedding，C1 只保证"落库快照已打码 + 输出已打码"，**不**对引擎入参做额外脱敏。
  D1 的 pair 打分 prompt 必须只用打码文本（A1 §8），那条属于 D1 的交付口径。
- **C1 的 404 与 403 分流**：CLOSED/到期/非合格对端一律 404，避免用 403 探出存在性；
  这是与"非所有者 PENDING 403"并列的第二档，两者不能合并（契约 §6 明确 10007/20001 分流）。

---

## 14. 验收对照

| 验收标准 | 证据 | 结果 |
|---|---|---|
| 非所有者详情 200、contact=null、publisherName 脱敏、location 截断、description 打码；全局正则 0 命中 | §2.1、§3 | ✅ |
| 所有者详情 contact 与 publisherName 原样 | §2.2 | ✅ |
| 非所有者 PATCH 403，status/updated_at 不变，t_access_log 无新增 | §5.1 | ✅ |
| 非所有者 DELETE 403，deleted_at 仍 NULL | §5.2 | ✅ |
| 五出口逐一请求，同一身份呈现一致 | §2、§2.3 | ✅ |
| DB 直查 contact 仍明文 | §7 | ✅ |
| 任务运行一轮 status 与 updated_at 不变 | §8.2 | ✅ |
| 终态：CLOSED 任何身份 404；COMPLETED 仅合格对端 200、所有者/其余 404 | §6.2 | ✅ |
| 标记完成入口：非所有者 403；无 MATCHED → 409，状态不变 | §5.1、§6.1 | ✅ |
| IGNORED 且 score 0.95 → 非所有者与所有者均 404、响应不含标题与联系方式、t_access_log 不新增 | §6.2（seed #23 demo02/demo03） | ✅ |
| `docs/` 递归扫描 v1→v2 迁移脚本名 只剩「不适用」一处 | §10 | ✅ |
| 证据里手机号一律假号 | 全文 | ✅ |

`SUMMARY pass=54 fail=0`
