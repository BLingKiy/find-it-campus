# E1 联系方式解锁与访问审计 · 证据

任务：[ZHYYKFX-56](https://app.hifox.com/nhnok/task/ZHYYKFX-56) · 分支 `feat/v2-E1-contact-unlock` · 基线 `origin/feat/v2-D1-pair-scoring @ 77596f7`

> 分支头以**本文件所在提交**为准（不写死自身 SHA，避免文档与提交互相引用）。基线用
> `git ls-remote` 实读为 `77596f7bb7a0456b0e51af00013855d46d54b342`，与派工单一致。
> 本文所有数字都标了来源（命令/环境）；手机号全部为假号 `139/138000000xx`。

## 1. 环境与结果

| 项 | 值 |
|---|---|
| JDK / Maven | Temurin 17.0.20.1 / Maven 3.9.11（本地仓库 `panshishi/m2repo`） |
| 构建 | `mvn -DskipTests package` → `find-it-campus-0.0.1-SNAPSHOT.jar` |
| 单测 | `mvn test` → **Tests run: 201, Failures: 0, Errors: 0**（基线 155 + 本条新增 46） |
| 数据库 | MySQL 8.0.29，隔离实例 `127.0.0.1:3307`；`db/schema.sql` + `db/seed.sql` + E1 夹具全量重建 |
| 应用 | `java -jar ... --server.port=18083`；`DEEPSEEK_API_KEY` 未设置（本条不调 LLM） |
| 本轮 DDL | **无**：`t_access_log` 已由 B1 建好；`t_pair_score` 已含授权/确认列 |

新增 7 个测试类、46 个用例：

| 测试类 | 用例 |
|---|---:|
| `PairAccessPolicyTest` | 2 |
| `PairContactServiceTest` | 10 |
| `PairUnlockServiceTest` | 7 |
| `PairActionServiceTest` | 13 |
| `NotificationServiceTest` | 5 |
| `PairApiTest` | 6 |
| `NotificationApiTest` | 3 |

端到端脚本（本机执行，不随仓库分发）`evidence-run.ps1` 共 **68 条断言，0 失败**；下面的请求/响应均由该脚本落盘。

## 2. 实现落点

| 文件 | 职责 |
|---|---|
| `pair/web/PairController.java` | `GET /api/pairs/{id}/contact`、`POST .../contact-unlock`、`PATCH /api/pairs/{id}`；path id 手工解析，非数字/超界 → 404 |
| `pair/service/PairContactService.java` | 三档 × 逐端授权的取号判定；每次调用写审计，`GRANTED` 先落审计再出号 |
| `pair/service/PairUnlockService.java` | 逐端 PUBLISH / REVOKE；附言打码与 200 字上限；撤回清空附言；`PAIR_UNLOCK` 通知 |
| `pair/service/PairActionService.java` | CONTACT / CONFIRM / IGNORE 状态机；同 action 幂等；终态 409；不联动 `t_record` |
| `pair/service/PairAccessPolicy.java` | 三档与 pair 可用性的纯函数（复用 `RecordVisibilityPolicy` 的保留期判定） |
| `pair/service/PairLookupService.java` | pair 一行 + 两端记录装配；记录缺失只解释为 404/20004 |
| `pair/service/AccessLogService.java` | `t_access_log` 唯一写点，append-only，失败不放号 |
| `notification/service/NotificationService#markRead` | 已读幂等、`readAt` 不刷新、返回更新后总未读数 |
| `common/api/ErrorCode.java` | 新增 `20004 / 50002 / 50003 / 50004 / 50005` |

**判定顺序（三个接口共用，不调换）：**

1. pair 不存在 / 任一端记录已删 → `404/20004`
2. 非参与方 → `403/10007`
3. pair 失效（任一端 CLOSED，或 COMPLETED 已过保留期）→ `403/50005`
4. `score < contact_unlock_threshold` → `403/50002`（联系方式 / PATCH；REVOKE 不检查）
5. 按三档与对方授权返回 200，或按 action/状态机执行

**配置不硬编码**：0.90 / 0.75 / 30 天全部来自 `PairPolicyProperties`（`findit.pair.*`，见 `application.yml`）。

## 3. 三档 × 授权前后 × 撤回

### 3.1 0.80：对方未公开 → 200 + `available=false`（不是 403）

```http
GET /api/pairs/15/contact
Cookie: demo01
```

```json
{"code":0,"message":"ok","data":{"pairId":15,"score":0.8000,"scorePercent":80,"tier":"AUTHORIZABLE","viewerSide":"LOST","targetSide":"FOUND","available":false,"contact":null,"reason":"NOT_PUBLIC","message":"对方尚未公开","unlockedAt":null,"note":null},"requestId":"4ae044de8df64e52"}
```

审计结果 `DENIED_NOT_PUBLIC`，不是 403 —— 「对方尚未公开」是业务状态，不是权限错误（契约 §6 末段）。

### 3.2 本端 PUBLISH：附言落库前打码

```http
POST /api/pairs/15/contact-unlock
Cookie: demo01
{"action":"PUBLISH","note":"周六下午图书馆见 13800000027"}
```

```json
{"code":0,"message":"ok","data":{"pairId":15,"side":"LOST","unlockedAt":"2026-10-27 23:33:49","note":"周六下午图书馆见 [手机号已隐藏]","public":true},"requestId":"bd591eedc46946b4"}
```

- 响应与库中附言均不含原文 `13800000027`；库中为 `周六下午图书馆见 [手机号已隐藏]`。
- 只写 LOST 端；FOUND 端授权与附言不变。

### 3.3 对端取号：0.80 + 对方已授权 → 200 + 号码 + 附言

```http
GET /api/pairs/15/contact
Cookie: demo02
```

```json
{"code":0,"message":"ok","data":{"pairId":15,"score":0.8000,"scorePercent":80,"tier":"AUTHORIZABLE","viewerSide":"FOUND","targetSide":"LOST","available":true,"contact":"13900000027","reason":null,"message":"对方已公开联系方式","unlockedAt":"2026-10-27 23:33:49","note":"周六下午图书馆见 [手机号已隐藏]"},"requestId":"b43f48e96c1841d4"}
```

### 3.4 只授权一端，另一端仍不可见

demo01 自己再取号时目标仍是 FOUND 端；FOUND 未公开，所以仍是 `NOT_PUBLIC`：

```json
{"code":0,"message":"ok","data":{"pairId":15,"score":0.8000,"tier":"AUTHORIZABLE","viewerSide":"LOST","targetSide":"FOUND","available":false,"contact":null,"reason":"NOT_PUBLIC","message":"对方尚未公开","unlockedAt":null,"note":null},"requestId":"6f1310ca4d3242ce"}
```

demo02 PUBLISH FOUND 端后，demo01 才拿到 `13900000029`：

```json
{"code":0,"message":"ok","data":{"pairId":15,"side":"FOUND","unlockedAt":"2026-10-27 23:33:51","note":"我在图书馆前台","public":true},"requestId":"5643f5f0a0a34311"}
```

```json
{"code":0,"message":"ok","data":{"pairId":15,"score":0.8000,"scorePercent":80,"tier":"AUTHORIZABLE","viewerSide":"LOST","targetSide":"FOUND","available":true,"contact":"13900000029","reason":null,"message":"对方已公开联系方式","unlockedAt":"2026-10-27 23:33:51","note":"我在图书馆前台"},"requestId":"2a9f3218ee784ddb"}
```

### 3.5 撤回：对端回到「对方尚未公开」，附言同时清空

```http
POST /api/pairs/15/contact-unlock
Cookie: demo01
{"action":"REVOKE"}
```

```json
{"code":0,"message":"ok","data":{"pairId":15,"side":"LOST","unlockedAt":null,"note":null,"public":false},"requestId":"49398aef553542bf"}
```

demo02 再取号：

```json
{"code":0,"message":"ok","data":{"pairId":15,"score":0.8000,"scorePercent":80,"tier":"AUTHORIZABLE","viewerSide":"FOUND","targetSide":"LOST","available":false,"contact":null,"reason":"NOT_PUBLIC","message":"对方尚未公开","unlockedAt":null,"note":null},"requestId":"82a87ea697524c77"}
```

库中当时的状态（脚本在撤回后立即查询）：

```text
NULL|NULL        -- lost_unlocked_at | lost_unlock_note
```

> 契约 §4.15 原有一句「附言随 pair 行保留供审计」与任务书「撤回时清空附言」冲突；
> 按人类 2026-10-27 拍板口径实现为**清空**，并已把 §4.15 那句话改掉。撤回后 `PAIR_UNLOCK`
> 历史通知不删除（通知是已发生的投递事实），但附言与授权时间已不可见。

### 3.6 直看档 0.96：双端无需授权

```json
{"code":0,"message":"ok","data":{"pairId":1,"score":0.9600,"scorePercent":96,"tier":"DIRECT","viewerSide":"LOST","targetSide":"FOUND","available":true,"contact":"13800000101","reason":null,"message":"匹配度较高，可直接查看","unlockedAt":null,"note":null},"requestId":"5f6b18a9a55249e4"}
```

```json
{"code":0,"message":"ok","data":{"pairId":1,"score":0.9600,"scorePercent":96,"tier":"DIRECT","viewerSide":"FOUND","targetSide":"LOST","available":true,"contact":"13800000001","reason":null,"message":"匹配度较高，可直接查看","unlockedAt":null,"note":null},"requestId":"1078309c39434eb6"}
```

### 3.7 低分 / 失效 / 越权 / 不存在

| 场景 | pair | 请求者 | HTTP + code | 响应体 |
|---|---|---|---|---|
| `<0.75` | 10（0.42） | demo01 | `403/50002` | `{"code":50002,"message":"匹配度不足，无法查看联系方式","data":null,...}` |
| CLOSED 记录 | 13（0.88，lost 24 CLOSED） | demo02 | `403/50005` | `{"code":50005,"message":"该配对已失效","data":null,...}` |
| COMPLETED 到期 | 14（0.90，lost 25 31 天前） | demo01 | `403/50005` | 同上 |
| 非参与方 | 1 | demo03 | `403/10007` | `{"code":10007,"message":"无权操作该资源","data":null,...}` |
| pair 不存在 | 999999 | demo01 | `404/20004` | `{"code":20004,"message":"配对不存在或任一端记录已删除","data":null,...}` |
| 非数字 id | `abc` | demo01 | `404/20004` | 同上 |
| 超界 id | 24 位数字 | demo01 | `404/20004` | 同上 |

所有 403/404 响应体都不含 `1[3-9]\d{9}` 号码片段。

### 3.8 附言长度

```json
{"code":10001,"message":"请求参数校验失败","data":{"fieldErrors":[{"field":"note","reason":"长度需在 0 到 200 之间"}]},"requestId":"786dcfe1aefb43b0"}
```

201 字 → `400/10001`；199 字 → 200，`data.note.length = 199`（不截断）。长度按 Unicode 码点算，
emoji 不会被算成两个字符；打码后若变长再做 200 码点防御性截断，避免撞 `VARCHAR(200)`。

## 4. 访问审计（`t_access_log`）

每次 `GET /api/pairs/{id}/contact` 都写一条；未授权、403、404（记录已删）也写，只是 `result` 不同。
表内同时写 `created_at`（何时）、`ip` / `user_agent`（来源），`request_id` 与响应体同值。

```sql
SELECT id, pair_id, viewer_id, viewer_side, target_side, score, result, request_id
FROM t_access_log ORDER BY id;
```

```text
+----+---------+-----------+-------------+-------------+--------+---------------------+------------------+
| id | pair_id | viewer_id | viewer_side | target_side | score  | result              | request_id       |
+----+---------+-----------+-------------+-------------+--------+---------------------+------------------+
|  1 |      15 |         1 | LOST        | FOUND       | 0.8000 | DENIED_NOT_PUBLIC   | 4ae044de8df64e52 |
|  2 |      15 |         2 | FOUND       | LOST        | 0.8000 | GRANTED             | b43f48e96c1841d4 |
|  3 |      15 |         1 | LOST        | FOUND       | 0.8000 | DENIED_NOT_PUBLIC   | 6f1310ca4d3242ce |
|  4 |      15 |         1 | LOST        | FOUND       | 0.8000 | GRANTED             | 2a9f3218ee784ddb |
|  5 |      15 |         2 | FOUND       | LOST        | 0.8000 | DENIED_NOT_PUBLIC   | 82a87ea697524c77 |
|  6 |      16 |         1 | LOST        | FOUND       | 0.8000 | DENIED_NOT_PUBLIC   | 62eef451b1f8441c |
|  7 |       1 |         1 | LOST        | FOUND       | 0.9600 | GRANTED             | 5f6b18a9a55249e4 |
|  8 |       1 |         2 | FOUND       | LOST        | 0.9600 | GRANTED             | 1078309c39434eb6 |
|  9 |      10 |         1 | LOST        | FOUND       | 0.4200 | DENIED_LOW_SCORE    | 1ce7c89b12704dcc |
| 10 |      13 |         2 | LOST        | FOUND       | 0.8800 | DENIED_PAIR_INVALID | 2154e0ded70f418b |
| 11 |      14 |         1 | LOST        | FOUND       | 0.9000 | DENIED_PAIR_INVALID | 38985db040334666 |
| 12 |       1 |         3 | LOST        | FOUND       | 0.9600 | DENIED_NOT_OWNER    | cb5449b82e284460 |
+----+---------+-----------+-------------+-------------+--------+---------------------+------------------+
```

日志摘录（等价截图；`request_id` 与上表一一对应）：

```text
2026-10-27T23:33:49.018+08:00  INFO ... AccessLogService : contact access audited pairId=15 viewerId=1 viewerSide=LOST targetSide=FOUND result=DENIED_NOT_PUBLIC
2026-10-27T23:33:50.129+08:00  INFO ... AccessLogService : contact access audited pairId=15 viewerId=2 viewerSide=FOUND targetSide=LOST result=GRANTED
2026-10-27T23:33:50.711+08:00  INFO ... AccessLogService : contact access audited pairId=15 viewerId=1 viewerSide=LOST targetSide=FOUND result=DENIED_NOT_PUBLIC
2026-10-27T23:33:51.504+08:00  INFO ... AccessLogService : contact access audited pairId=15 viewerId=1 viewerSide=LOST targetSide=FOUND result=GRANTED
2026-10-27T23:33:52.778+08:00  INFO ... AccessLogService : contact access audited pairId=15 viewerId=2 viewerSide=FOUND targetSide=LOST result=DENIED_NOT_PUBLIC
2026-10-27T23:33:52.967+08:00  INFO ... AccessLogService : contact access audited pairId=16 viewerId=1 viewerSide=LOST targetSide=FOUND result=DENIED_NOT_PUBLIC
2026-10-27T23:33:53.474+08:00  INFO ... AccessLogService : contact access audited pairId=1 viewerId=1 viewerSide=LOST targetSide=FOUND result=GRANTED
2026-10-27T23:33:53.904+08:00  INFO ... AccessLogService : contact access audited pairId=1 viewerId=2 viewerSide=FOUND targetSide=LOST result=GRANTED
2026-10-27T23:33:54.317+08:00  INFO ... AccessLogService : contact access audited pairId=10 viewerId=1 viewerSide=LOST targetSide=FOUND result=DENIED_LOW_SCORE
2026-10-27T23:33:54.872+08:00  INFO ... AccessLogService : contact access audited pairId=13 viewerId=2 viewerSide=LOST targetSide=FOUND result=DENIED_PAIR_INVALID
2026-10-27T23:33:55.038+08:00  INFO ... AccessLogService : contact access audited pairId=14 viewerId=1 viewerSide=LOST targetSide=FOUND result=DENIED_PAIR_INVALID
2026-10-27T23:33:55.449+08:00  INFO ... AccessLogService : contact access audited pairId=1 viewerId=3 viewerSide=LOST targetSide=FOUND result=DENIED_NOT_OWNER
```

**fail-closed**：`AccessLogService.record(...)` 在返回号码之前执行；INSERT 抛异常时请求以 500 结束，
响应体不含号码。`pair` 行本身不存在时不写审计：`fk_access_pair` 要求 `pair_id` 指向真实行，
没有可指的 id；此时 404 也不泄露资源存在性。

## 5. 通知已读（`PATCH /api/notifications/{id}/read`）

demo01 PUBLISH 后给 demo02 写 `PAIR_UNLOCK`（唯一键幂等），通知 id=9。

第一次：

```json
{"code":0,"message":"ok","data":{"id":9,"read":true,"readAt":"2026-10-27 23:33:59","unreadCount":1},"requestId":"7e1e0566a393490a"}
```

第二次（同一 id）：

```json
{"code":0,"message":"ok","data":{"id":9,"read":true,"readAt":"2026-10-27 23:33:59","unreadCount":1},"requestId":"47f5427fec7d4a9d"}
```

`readAt` 不变，`unreadCount` 不重复递减。越权/不存在：

| 场景 | HTTP + code |
|---|---|
| demo03 标记 demo02 的通知 | `403/10007` |
| id=999999 | `404/50004` |
| id=`abc` | `404/50004` |

## 6. `PATCH /api/pairs/{id}`：状态机、幂等、不联动

| 步骤 | 请求 | 结果 |
|---|---|---|
| NEW + CONTACT | demo01 | 200 `status=CONTACTED`, `message=已联系` |
| CONTACTED + CONTACT | demo01 | 200，`updatedAt` 与第一次相同（不写库） |
| CONTACTED + CONFIRM（单端） | demo01 | 200 `status=CONTACTED`, `myConfirmed=true`, `message=已确认，等待对方确认` |
| CONTACTED + CONFIRM（另一端） | demo02 | 200 `status=MATCHED`, `matched=true`，两端 `confirmedAt` 非空 |
| MATCHED + CONTACT | demo01 | `409/50003` |
| 请求体带 `status` | demo01 | `400/10001`，`field=status` |
| 非法 action | demo01 | `400/10001`，`field=action` |
| 非参与方 | demo03 | `403/10007` |
| `<0.75` | pair 10 | `403/50002` |
| CLOSED | pair 13 | `403/50005` |
| 不存在 / 非数字 id | 999999 / `abc` | `404/20004` |

关键响应：

```json
{"code":0,"message":"已确认，等待对方确认","data":{"pairId":15,"status":"CONTACTED","matched":false,"mySide":"LOST","myConfirmed":true,"otherConfirmed":false,"lostConfirmedAt":"2026-10-27 23:33:57","foundConfirmedAt":null,"updatedAt":"2026-10-27 23:33:57"},"requestId":"f53899dd8bf94e9b"}
```

```json
{"code":0,"message":"已完成双方确认","data":{"pairId":15,"status":"MATCHED","matched":true,"mySide":"FOUND","myConfirmed":true,"otherConfirmed":true,"lostConfirmedAt":"2026-10-27 23:33:57","foundConfirmedAt":"2026-10-27 23:33:58","updatedAt":"2026-10-27 23:33:57"},"requestId":"12572860d544437e"}
```

**不联动记录**：MATCHED 前后 `t_record` 的 27 / 29 行：

```text
+----+---------+--------------+---------------------+
| id | status  | completed_at | updated_at          |
+----+---------+--------------+---------------------+
| 27 | PENDING | NULL         | 2026-10-26 09:10:00 |
| 29 | PENDING | NULL         | 2026-10-26 10:10:00 |
+----+---------+--------------+---------------------+
```

接口不写 `t_record.status/completed_at/updated_at`；`MATCHED` 只由两端 `confirmed_at` 派生。
`PATCH` 也不写 `t_access_log`（审计只覆盖联系方式查看），不发通知。

### 6.1 并发双端 CONFIRM（补充）

两个请求同时打同一 pair（lost=demo01 / found=demo02），均 200，最终一行 `MATCHED`，无 CHECK 违约：

```text
"status":"CONTACTED","myConfirmed":true,"otherConfirmed":false,...,"lostConfirmedAt":"2026-10-27 23:34:06"
"status":"MATCHED","myConfirmed":true,"otherConfirmed":true,...,"lostConfirmedAt":"2026-10-27 23:34:06","foundConfirmedAt":"2026-10-27 23:34:06"

+----+---------+---------------------+---------------------+
| id | status  | lost_confirmed_at   | found_confirmed_at  |
+----+---------+---------------------+---------------------+
| 16 | MATCHED | 2026-10-27 23:34:06 | 2026-10-27 23:34:06 |
+----+---------+---------------------+---------------------+
```

并发下先返回的响应可能仍是 `CONTACTED`（读快照早于另一端提交），以库里的最终行为准；
`ck_pair_matched` 没有被触发。

## 7. 1:1 隔离：A 的授权不影响 B

夹具：同一招领 `found_id=29` 同时匹配两条寻物 `lost_id=27`（A，pair 15）与 `lost_id=28`（B，pair 16），
两条 pair 均 0.80、NEW。A 完成「PUBLISH → 对端取号 → 撤回」后，B 的整行在操作前后逐字段相同：

```text
B before: NEW|NULL|NULL|NULL|NULL
B after : NEW|NULL|NULL|NULL|NULL
```

B 的取号仍按自身状态返回：

```json
{"code":0,"message":"ok","data":{"pairId":16,"score":0.8000,"tier":"AUTHORIZABLE","viewerSide":"LOST","targetSide":"FOUND","available":false,"contact":null,"reason":"NOT_PUBLIC","message":"对方尚未公开","unlockedAt":null,"note":null},"requestId":"62eef451b1f8441c"}
```

没有占用/锁定语义：`found_id=29` 同时参与两条 pair，A 的 MATCHED 不影响 B；`t_record` 也没有被置为
COMPLETED/CLOSED。

## 8. 全局号码扫描

扫描命令（响应体逐字符正则 `1[3-9]\d{9}`）：

```powershell
Get-Content $response -Raw | Select-String '1[3-9]\d{9}'
```

| 出口 | 身份 | 命中 |
|---|---|---:|
| `GET /api/records?pageSize=50` | 匿名 | **0** |
| `GET /api/records/13`（他人 PENDING 详情） | 匿名 | **0** |
| `POST /api/match` | demo01 | **0** |
| `GET /api/notifications?pageSize=50` | demo02 | **0** |

说明：`/api/my/records` 是**本人**列表，按契约 §4.2 所有者明文回显自己的号码（实测命中 19 处），
不属于「对方联系方式」泄露；四个对外出口的 0 命中是本次验收口径。号码只从
`GET /api/pairs/{id}/contact` 出。

## 9. 边界与代价

- **仅参与方可取号**：`publisher_id` 来自会话，不读请求参数；非参与方 403 且写 `DENIED_NOT_OWNER`。
- **CLOSED/到期优先于分数**：CLOSED 的 0.88 pair 返回 50005 而不是 50002/404，避免「分数够就能看已关闭记录」。
- **撤回清附言是任务书口径**，与契约 §4.15 原文有一句冲突，已按拍板改文档；若未来要「保留附言供审计」，
  必须同时改任务口径与 `revokeLost/revokeFound` 两条 SQL，不能只改一处。
- **pair 行不存在不写审计**：外键约束决定，不是漏写；记录已删（pair 行还在）会写 `DENIED_PAIR_INVALID`。
- **PUBLISH 与通知同事务**：通知写失败则授权回滚，避免「已公开但对方没收到」；代价是通知表故障会放大成取号授权失败。
- **未闭环项**：`t_access_log` 只增不删，容量与归档策略未在本条实现（B1 的索引足够支撑当前量级；
  十万级以上需要按 `created_at` 分区/归档，另行评估）。
- **未验证项**：真实代理链路的 `X-Forwarded-For` 可信边界未在本机验证；当前实现取 XFF 首段并截断，
  生产必须由可信反代覆写该头。

## 10. 复现命令

```powershell
# 1) 单测
mvn -s m2-settings-online.xml test        # Tests run: 201, Failures: 0, Errors: 0

# 2) 起库（隔离数据目录，端口 3307）
mysqld --datadir=work-v2-E1\mysql-data --port=3307 --bind-address=127.0.0.1

# 3) 建库 + 种子 + E1 夹具
mysql --host=127.0.0.1 --port=3307 -uroot -e "source db/schema.sql"
mysql --host=127.0.0.1 --port=3307 -uroot -e "source db/seed.sql"
mysql --host=127.0.0.1 --port=3307 -uroot -e "source work-v2-E1/fixtures.sql"

# 4) 起应用
java -jar server/target/find-it-campus-0.0.1-SNAPSHOT.jar --server.port=18083

# 5) 端到端断言（本机脚本，不随仓库分发）
powershell -ExecutionPolicy Bypass -File work-v2-E1/evidence-run.ps1
# RESULT: PASS=68 FAIL=0
```
