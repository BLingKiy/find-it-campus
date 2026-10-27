# B2 认证与会话 · 执行记录

- 任务：[ZHYYKFX-50](https://app.hifox.com/nhnok/task/ZHYYKFX-50) · B2 认证与会话 ｜ 磐石石 ｜ dsh
- 分支：`feat/v2-B2-auth`（从 `origin/feat/v2-B1-data-layer` @ `9b2ad25` 切出；不推 main、不合并）
- 契约：`feat/v2-A1-arch-contract` @ `b90270e` —— `docs/api-contract-v2.md` §1.1 / §4.9–§4.11 / §6，`docs/design/v2-architecture.md` §5.3 / V2-ADR-002
- 设计口径：`docs/design/v2-auth-session.md`（会话模型、门禁规则、残余风险）
- 执行日期：2026-10-27
- 环境：Windows 10 Pro for Workstations / Temurin JDK 17.0.20.1 / Maven 3.10.0 / MySQL 8.0.29（独立数据目录 + 3307，避免与 3306 上的其他任务抢库）/ Spring Boot 3.5.16

## 结论

七条验收逐条通过（①无 cookie 401、②连续 5 次失败文案一致且哈希不变、③登录 Set-Cookie 属性与 `/me`、
④重启后会话失效、⑤退出清 Cookie、⑥`t_user` 全 bcrypt cost=10、⑦无注册接口）。
`server/` 单元 + 切片测试 **85 通过 / 0 失败**（其中 B2 新增 30 条），构建产物可启动，
真机请求/响应原文见下（Cookie 与 session id 已截断）。

没有改表结构、没有加列；账号仍来自 `db/seed.sql` 的三个演示账号。

## 0. 复现步骤

```bash
# 1) 起库（本次用独立数据目录 + 3307）
mysqld --no-defaults --datadir=<workspace>/mysql-b1-data --port=3307 --bind-address=127.0.0.1
mysql --no-defaults --default-character-set=utf8mb4 -u root -h 127.0.0.1 -P 3307 < db/schema.sql
mysql --no-defaults --default-character-set=utf8mb4 -u root -h 127.0.0.1 -P 3307 < db/seed.sql

# 2) 构建与测试
cd server
mvn -o -B test                 # 85 tests, 0 failures
mvn -o -B -DskipTests package  # target/find-it-campus-0.0.1-SNAPSHOT.jar

# 3) 启动（DB 指向 3307；仓库里不出现任何真实口令）
FINDIT_DB_URL='jdbc:mysql://127.0.0.1:3307/find_it_campus?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8' \
FINDIT_DB_USER=root FINDIT_DB_PASSWORD= \
java -jar target/find-it-campus-0.0.1-SNAPSHOT.jar --server.port=18080

# 4) 冒烟
curl -i http://127.0.0.1:18080/api/health
```

启动日志：`Tomcat started on port 18080` / `Started FindItCampusApplication in 6.865 seconds`。

> **Cookie / session id 截断口径**：下文所有 `Set-Cookie` 的 `JSESSIONID` 值统一替换为
> `<32 位十六进制，已截断>`；除此之外的响应头、响应体、日志均为原文。证据里不出现完整会话 id。

---

## 1. 验收① 无 cookie → 401 + 统一错误体（不是 500、不是空列表）

```text
> GET http://127.0.0.1:18080/api/my/records          （无 Cookie）
HTTP/1.1 401
X-Request-Id: 7c50d41665e74a86
Content-Type: application/json;charset=UTF-8

{"code":10006,"message":"请先登录","data":null,"requestId":"7c50d41665e74a86"}
```

```text
> POST http://127.0.0.1:18080/api/records            （无 Cookie，body {"type":"LOST","title":"x"}）
HTTP/1.1 401
X-Request-Id: 962f89e4333a42b6
Content-Type: application/json;charset=UTF-8

{"code":10006,"message":"请先登录","data":null,"requestId":"962f89e4333a42b6"}
```

```text
> GET http://127.0.0.1:18080/api/auth/me             （无 Cookie）
HTTP/1.1 401
X-Request-Id: a98fe28a94934bef
Content-Type: application/json;charset=UTF-8

{"code":10006,"message":"请先登录","data":null,"requestId":"a98fe28a94934bef"}
```

要点：body 形状与成功响应一致（`code/message/data/requestId`），`data=null`；HTTP 状态是 401 而不是 500；
`requestId` 与响应头 `X-Request-Id`、日志三处一致。

`GET /api/my/records` 在 B2 交付时还没有 handler（属于 C1），401 由门禁在**路由之前**给出 —— 这正是把门禁放在
Servlet Filter 而不是 MVC 拦截器的原因：未登录者连"这个路由存不存在"都不应该知道。同一个路径带会话访问则是 404
（路由确实还没落地）：

```text
> GET http://127.0.0.1:18080/api/my/records          （带有效会话）
HTTP/1.1 404
X-Request-Id: e16bd12249eb45f4

{"code":10003,"message":"资源不存在","data":null,"requestId":"e16bd12249eb45f4"}
```

读接口的匿名可读没有被误伤：

```text
> GET http://127.0.0.1:18080/api/records?pageSize=1  （无 Cookie）
http=200
{"code":0,"message":"ok","data":{"page":1,"pageSize":1,"total":21,"list":[{"id":26,"type":"FOUND","status":"PENDING","title":"捡到一张浅蓝色卡片",...
```

---

## 2. 验收② 连续 5 次错误口令 → 始终 401、文案一致、口令哈希不变

请求体固定为 `{"studentNo":"demo01","password":"WrongPass#1"}`，逐次记录：

```text
hash_before_sha256=de1862b87e8adb3414596f495935297281f1c64dd09b88a6bdae4101446ecf9e
attempt=1 http=401 body={"code":10006,"message":"学号或密码不正确","data":null,"requestId":"c16e761884e346f5"}
attempt=2 http=401 body={"code":10006,"message":"学号或密码不正确","data":null,"requestId":"fa043495f4b249ee"}
attempt=3 http=401 body={"code":10006,"message":"学号或密码不正确","data":null,"requestId":"f1333a2c674f45f5"}
attempt=4 http=401 body={"code":10006,"message":"学号或密码不正确","data":null,"requestId":"e795396a9b424ba7"}
attempt=5 http=401 body={"code":10006,"message":"学号或密码不正确","data":null,"requestId":"80aed22e97524c18"}
hash_after_sha256=de1862b87e8adb3414596f495935297281f1c64dd09b88a6bdae4101446ecf9e
hash_unchanged=True
```

`t_user.password_hash` 前后 SHA-256 相同 ⇒ 失败路径没有任何写操作（`UserMapper` 本身也只有 SELECT，
见 `auth/mapper/UserMapper.java`）。学号不存在、口令错、账号停用三种情况返回完全相同的 status + code + message
（切片测试 `AuthServiceTest#unknownStudentIsUniformFailure` / `#inactiveUserIsUniformFailure` 覆盖），
服务端日志里才区分原因：

```text
2026-10-27 19:49:55.100 WARN [http-nio-18080-exec-6] c.f.c.a.service.LoginAttemptTracker - [0f317914094340cf] 登录连续失败达到阈值（仅记录，不锁定账号）: key=demo01|127.0.0.1 count=5 windowMinutes=15
2026-10-27 19:49:55.100 WARN [http-nio-18080-exec-6] c.f.campus.auth.service.AuthService - [0f317914094340cf] 登录失败 studentNo=demo01 ip=127.0.0.1 reason=PASSWORD_MISMATCH consecutiveFailures=5
2026-10-27 19:49:55.101 WARN [http-nio-18080-exec-6] c.f.c.c.error.GlobalExceptionHandler - [0f317914094340cf] 业务异常 code=10006: 学号或密码不正确
2026-10-27 19:49:55.102 INFO [http-nio-18080-exec-6] c.f.c.common.config.RequestIdFilter - [0f317914094340cf] POST /api/auth/login -> 401 (90 ms)
```

阈值只产生这一条 WARN：不锁账号、不返回 429、不改响应（契约 §4.9 与验收口径一致）。

---

## 3. 验收③ 正确口令登录 → Set-Cookie 属性 + `/api/auth/me` 返回学号与 id

```text
> POST http://127.0.0.1:18080/api/auth/login
> body {"studentNo":"demo01","password":"Demo@123456"}
HTTP/1.1 200
X-Request-Id: 968a98ecaba647bc
Set-Cookie: JSESSIONID=<32 位十六进制，已截断>; Path=/; HttpOnly; SameSite=Lax
Content-Type: application/json

{"code":0,"message":"登录成功","data":{"id":1,"studentNo":"demo01","displayName":"演示甲"},"requestId":"968a98ecaba647bc"}
```

```text
> GET http://127.0.0.1:18080/api/auth/me             （带上面的会话 Cookie）
HTTP/1.1 200
X-Request-Id: d8f50fb345684903
Content-Type: application/json

{"code":0,"message":"ok","data":{"id":1,"studentNo":"demo01","displayName":"演示甲"},"requestId":"d8f50fb345684903"}
```

`Set-Cookie` 三个属性都在：`Path=/`、`HttpOnly`、`SameSite=Lax`（HTTP 部署不带 `Secure`；HTTPS 反代下需把
`server.servlet.session.cookie.secure` 置 `true`，见 `deploy/DEPLOY-WINDOWS.md` §11.1）。
登录响应里只有 `id/studentNo/displayName`，不含 `passwordHash`（出参 DTO 只有三个字段）。

---

## 4. 验收④ 重启服务 → 已登录会话的 `/api/auth/me` 返回 401

重启前用同一份 Cookie（`cookies.txt`）拿到过 200（见 §3），然后把进程杀掉、用同一条启动命令重新拉起：

```text
app_restarted_ready=True

> GET http://127.0.0.1:18080/api/auth/me             （带重启前签发的同一 Cookie）
HTTP/1.1 401
X-Request-Id: 0e088ade289348c2
Content-Type: application/json;charset=UTF-8

{"code":10006,"message":"请先登录","data":null,"requestId":"0e088ade289348c2"}
```

原因：会话在 JVM 内存里，进程退出即消失。这是**已写进文档的已知限制**，不是缺陷：

- `deploy/DEPLOY-WINDOWS.md` §11.1「重启即失效」行：升级/重启后所有在线用户需重新登录；
- `docs/design/v2-auth-session.md` §3 会话模型与 §7 残余风险；
- 实现：`server/src/main/resources/application.yml` 的 `server.servlet.session`（30 分钟超时、Cookie 属性）；
- README「登录与会话（v2 B2 起）」一节（面向开发者的一句话说明）。

---

## 5. 验收⑤ 退出 → `/api/auth/me` 401 且 Cookie 被清除

```text
> POST http://127.0.0.1:18080/api/auth/login         （重新登录拿新会话）
http=200 body={"code":0,"message":"登录成功","data":{"id":1,"studentNo":"demo01","displayName":"演示甲"},"requestId":"b6d8e291d57c46bb"}
me_before_logout_http=200 body={"code":0,"message":"ok","data":{"id":1,"studentNo":"demo01","displayName":"演示甲"},"requestId":"a3472da82b1b4989"}
```

```text
> POST http://127.0.0.1:18080/api/auth/logout        （带会话 Cookie）
HTTP/1.1 200
X-Request-Id: 6814c935595a4023
Set-Cookie: JSESSIONID=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly; SameSite=Lax
Content-Type: application/json

{"code":0,"message":"已退出","data":null,"requestId":"6814c935595a4023"}
```

```text
> GET http://127.0.0.1:18080/api/auth/me             （退出后继续带旧 Cookie）
HTTP/1.1 401
X-Request-Id: ee94f912d2b64964
Content-Type: application/json;charset=UTF-8

{"code":10006,"message":"请先登录","data":null,"requestId":"ee94f912d2b64964"}
```

幂等（契约 §4.10）：未登录再调一次 logout 仍然 200：

```text
http=200 body={"code":0,"message":"已退出","data":null,"requestId":"4befd5dd8feb4a77"}
```

---

## 6. 验收⑥ 数据库 `t_user`：口令列无明文，全部 bcrypt 且 cost ≥ 10

```sql
SELECT id, student_no, LEFT(password_hash,7) AS prefix, CHAR_LENGTH(password_hash) AS len,
       (password_hash LIKE '$2a$%' OR password_hash LIKE '$2b$%') AS bcrypt_prefix,
       CAST(SUBSTRING(password_hash,5,2) AS UNSIGNED) AS cost,
       (password_hash = 'Demo@123456') AS is_plaintext
FROM find_it_campus.t_user ORDER BY id;
```

```text
1	demo01	$2a$10$	60	1	10	0
2	demo02	$2a$10$	60	1	10	0
3	demo03	$2a$10$	60	1	10	0
```

```sql
SELECT COUNT(*) AS total,
       SUM(password_hash LIKE '$2a$%' OR password_hash LIKE '$2b$%') AS bcrypt_rows,
       MIN(CAST(SUBSTRING(password_hash,5,2) AS UNSIGNED)) AS min_cost,
       MAX(CAST(SUBSTRING(password_hash,5,2) AS UNSIGNED)) AS max_cost
FROM find_it_campus.t_user;
```

```text
3	3	10	10
```

读法：3 行全部 `$2a$` 前缀、长度 60（bcrypt 定长）、cost 均 10（契约下限 10）、`is_plaintext` 全 0。
校验用的 `BCryptPasswordEncoder` cost 也是 10（`auth/config/AuthConfig#BCRYPT_COST`），与 seed 一致。

---

## 7. 验收⑦ 路由清单：不存在注册接口

`auth` 包里的全部 Mapping 注解（`AuthController` 是 B2 唯一的 auth 路由来源）：

```text
@RequestMapping("/api/auth")
@PostMapping("/login")
@PostMapping("/logout")
@GetMapping("/me")
```

主源码里 `register` 出现次数：

```text
register_hits=0
```

真机请求（已登录，能通过门禁）：

```text
> POST http://127.0.0.1:18080/api/auth/register      （带有效会话）
HTTP/1.1 404
X-Request-Id: fadcc881303e4c93
Content-Type: application/json

{"code":10003,"message":"资源不存在","data":null,"requestId":"fadcc881303e4c93"}
```

未登录访问同一路径同样是"路由不存在"（GET 不在私有读清单内，不被门禁拦）：

```text
> GET http://127.0.0.1:18080/api/auth/register
HTTP/1.1 404
X-Request-Id: 3da4d02b050446eb
Content-Type: application/json

{"code":10003,"message":"资源不存在","data":null,"requestId":"3da4d02b050446eb"}
```

---

## 8. 自动化测试

```text
mvn -o -B test
Tests run: 85, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

B2 新增 30 条：

| 测试类 | 条数 | 覆盖 |
|---|---|---|
| `auth/service/AuthServiceTest` | 8 | 正确口令登录、会话 id 轮换、口令错/学号不存在/停用三种一致失败、5 次失败哈希不写、坏哈希不 500、成功清计数 |
| `auth/service/LoginAttemptTrackerTest` | 5 | 阈值累计、key 独立、15 分钟窗口过期、成功清零、内存上界 |
| `auth/web/AuthApiTest` | 6 | 登录成功/失败形状、参数校验、`/me` 未登录 401 / 登录 200、登出幂等 + 清 Cookie 头 |
| `auth/web/AuthPathsRulesTest` | 7 | 写默认拦（fail-closed）、白名单、读默认放、私有读、前缀不误伤、OPTIONS、context path |
| `authprobe/MyRecordsGateProbeTest` | 2 | `GET /api/my/records` 无会话 401 / 有会话放行（C1 接口未落地前的探针） |
| `record/web/RecordApiValidationTest`（新增 2 条） | 2 | `POST /api/records` 无会话 401 + 统一 body；`GET /api/records` 仍匿名 200 |

探针说明：`authprobe/MyRecordsProbeController` 是 **test-only** 的占位路由（与仓库既有的 `validationprobe` 同手法），
只用于验证门禁规则；不进主源码、不出现在任何路由清单里。C1 落地后用真接口替换这两条断言。

---

## 9. 顺手修掉的一处基线缺陷（test-only）

在基线 `9b2ad25` 上跑全量测试：`Tests run: 17, Failures: 0, Errors: 17`，全部是
`SeedSamples` 初始化失败（`db/seed.sql 解析出 0 条记录，期望 24 条`）。

原因：B1 把 seed 改成 v2 口径（`status` 三态 `PENDING/COMPLETED/CLOSED`，`t_record` 在 `status` 与 `title` 之间
多了一列 `completed_at`），而 v1 时代的测试夹具 `SeedSamples` 仍在用 `CLAIMED` 与旧列序的正则 —— 静默解析出 0 条。

本分支只改测试夹具（`SeedSamples` 正则 + 一处过时的显示名/断言文案），**不改生产代码、不改 seed、不改表结构**：

```text
Tests run: 85, Failures: 0, Errors: 0, Skipped: 0   （修复后）
```

这一步是 B1 遗留，谁先基于 B1 做构建都会撞上；如果上游已经有别的修复，合并时以文件为准即可。

---

## 10. 未覆盖 / 明确不做的项

| 项 | 状态 | 说明 |
|---|---|---|
| 30 分钟超时到期 | 未实测（等 30 分钟） | 配置为 `server.servlet.session.timeout=30m`；"会话消失 → 401"的行为已由 §4 重启失效覆盖同一代码路径。做真实验证需要把超时改成 1 分钟再等，本次未做 |
| 多实例部署 | 不支持 | 内存会话不共享；多实例必须换 Redis/DB 会话并给配对任务加分布式锁（设计文档 §10 演进路线） |
| HTTPS + `Secure` Cookie | 未实测 | 本地/演示是 HTTP；生产改一处配置即可（部署说明 §11.1），本次不构造 TLS 环境 |
| 账号停用后的既有会话 | 已知残余风险 | `is_active=0` 只拦新登录；已存在会话在超时前有效。v2 无停用入口，加管理端时在门禁补一次复核（设计文档 §7） |
| 登录限流 | 应用层不做 | 契约要求"连续 5 次始终 401、文案一致"；限流应下沉到 Nginx/IP 层 |
| 指标导出（actuator/micrometer） | 未做 | 没有引入 actuator，登录成功/失败/阈值只落日志（设计文档 §8） |
| 前端登录态（B3） | 不在本条范围 | 前端 `credentials:'include'`、401 跳登录由 B3 交付；后端已把 CORS `allowCredentials` 改为 `true` |
| `/api/my/records` 业务实现（C1） | 不在本条范围 | 本条的只有"登录门禁"；探针测试覆盖规则，真接口由 C1 交付 |
