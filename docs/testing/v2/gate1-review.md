# 门禁 1 复核 · v2 集成分支（登录态 / 越权 / 遮蔽 + 附加项）

> 任务 [ZHYYKFX-67](https://app.hifox.com/nhnok/task/ZHYYKFX-67)｜复核人：端测测｜门禁条目来源：[ZHYYKFX-63](https://app.hifox.com/nhnok/task/ZHYYKFX-63)
> 复核时间：2026-10-27 21:36–21:53（Asia/Shanghai）
> **本文只做复核留证，不判定门禁 1 是否放行**——放行由人类在 ZHYYKFX-63 签字。

## 0. 基线

| 项 | 值 | 取得方式 |
|---|---|---|
| 集成分支 | `feat/v2-integration` | `git ls-remote origin refs/heads/feat/v2-integration` |
| 分支头 | = 本文件所在的提交（证据提交在 merge commit 之上；交付时点的实测 SHA 见任务评论，避免文档自指导致 SHA 追不上） | `git ls-remote origin refs/heads/feat/v2-integration` |
| merge commit（集成点，稳定） | `b1ecfa3f662a37b60ccf5c25df8bfe64c8a1db6c` | `git log -1 b1ecfa3 --pretty=%H` |
| merge 的两个 parent | `0fff831bb044cdb95069e34de818abc3538404e4`（C1）+ `acf98dd539687642922eced495ad153ddcbd576b`（C2） | `git log -1 --pretty=%P b1ecfa3` |
| merge 方式 | `git merge --no-ff`，**0 冲突**，无人工改动 | `git log --oneline --graph` |
| `origin/main` | `21784f674ca3a5915aaa86dc95edd7a0e55e973d`（提交前后均未动，仍是 21784f6） | `git ls-remote origin refs/heads/main` |

`git ls-remote` 实读的五个功能分支（与任务书基线逐字符一致，无漂移）：

```
9b2ad2557f77e7b23e76d79b5088a32cf85dce7c	refs/heads/feat/v2-B1-data-layer
2553695b524c776216d555b5e5cc201ee796d255	refs/heads/feat/v2-B2-auth
90d06286afeb04e43de2b676710e63f2d92e9a25	refs/heads/feat/v2-B3-frontend-auth
0fff831bb044cdb95069e34de818abc3538404e4	refs/heads/feat/v2-C1-masking
acf98dd539687642922eced495ad153ddcbd576b	refs/heads/feat/v2-C2-frontend-masking
21784f674ca3a5915aaa86dc95edd7a0e55e973d	refs/heads/main
```

合并图（合并后 `git log --oneline --graph -6`）：

```
*   b1ecfa3 Merge remote-tracking branch 'origin/feat/v2-C2-frontend-masking' into feat/v2-integration
|\
| * acf98dd feat(v2-C2): 列表/详情遮蔽占位、本人管理入口、/my 骨架与编辑页
| * 90d0628 feat(v2-B3): 前端登录态（/login、401 统一引导、路由守卫、顶栏学号+退出）
* | 0fff831 feat(v2-C1): 遮蔽单点 + 所有权 403 + 三态状态写入收敛
* | 2553695 feat(v2-B2): 登录/登出/me + 服务端会话 + 401 统一出口（bcrypt cost=10）
* | 9b2ad25 docs(v2-B1): 证据文档任务链接改为 app URL；枚举同步责任收敛到 C1
```

两条线文件面互补（后端 `server/`+`docs/`、前端 `web/`+`docs/testing/`），未出现同名文件冲突，因此不存在「机械整合」与「业务语义取舍」的场景。

## 1. 环境与可复现步骤

| 组件 | 事实 |
|---|---|
| JDK | Temurin 17.0.20.1 |
| MySQL | 8.0.29 @ `127.0.0.1:3306`，root / 空密码 |
| 数据库 | **新建** `findit_gate1_67`（`db/schema.sql` + `db/seed.sql` 原样执行，仅把库名替换，避免覆盖本机遗留库）。7 张表、24 条记录、3 个演示账号、0 条 `t_access_log` |
| server | `java -jar server/target/find-it-campus-0.0.1-SNAPSHOT.jar --server.port=8088`，`FINDIT_DB_URL` 指向上述库 |
| web | `npm run build` 产物 → `vite preview`（5173，`/api` 代理到 8088） |

演示账号（seed 内固定）：`demo01/Demo@123456`(id=1)、`demo02/Demo@123456`(id=2)、`demo03/Demo@123456`(id=3)。

遮蔽类用例需要「文本里真带号码」的记录，seed 的 24 条不含手机号，因此通过**应用自身的 `POST /api/records`** 由 demo01 建了 3 条夹具（#27/#28/#29，内容对应契约 §6.4 的 P1/P2 型），这是业务写入而非改库改码：

| id | location | description | title | contact |
|---|---|---|---|---|
| 27 | `第三教学楼201` | `昨天 14:30 在三楼捡到，联系 13812345678` | `夹具P1测试钱包` | `13812345678` |
| 28 | `东区3号宿舍楼512室` | `QQ 123456789，证件号 110101199001011234` | `夹具P2测试钱包` | `13700001234` |
| 29 | `图书馆` | `标题里带号码的打码验证，地点在图书馆。` | `夹具标题含号13812345678` | `13600001234` |

## 2. 构建与冒烟

### 2.1 server 打包 / 启动

```
$ cd server && mvn -B -DskipTests package
[INFO] --- spring-boot:3.5.16:repackage (repackage) @ find-it-campus ---
[INFO] Replacing main artifact ...\target\find-it-campus-0.0.1-SNAPSHOT.jar with repackaged archive, adding nested dependencies in BOOT-INF/.
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  12.352 s
```

```
2026-10-27 21:36:41.236 INFO ... Tomcat started on port 8088 (http) with context path '/'
2026-10-27 21:36:41.268 INFO ... Started FindItCampusApplication in 6.03 seconds

$ curl -s http://127.0.0.1:8088/api/health
HTTP=200
{"code":0,"message":"ok","data":{"status":"UP","db":"UP","version":"0.0.1-SNAPSHOT","engine":"local"},"requestId":"d8ca27a4b43d42e8"}
```

### 2.2 web build

```
$ cd web && npm run build
dist/assets/index-BkkmkJo7.js                      41.04 kB │ gzip: 16.46 kB
dist/assets/_plugin-vue_export-helper-BMX0JJ1V.js  58.58 kB │ gzip: 22.97 kB
✓ built in 649ms

$ npm run type-check        # vue-tsc --noEmit ×2，无输出即通过
（exit 0）
```

### 2.3 四步冒烟（真实浏览器：Edge 155 headless，CDP 直驱，非 curl 模拟）

`登录 → 列表 → 详情 → /my`，脚本见附录 A，截图见 `docs/testing/v2/gate1-review/`：

```
PASS | 1 登录页渲染 | title="登录"
PASS | 2 列表页有数据 | 卡片链接 19 个，首个 /records/26，标题「启事列表」
PASS | 3 详情页渲染 | path=/records/26 title="启事详情" bodyLen=461
PASS | 4 /my 页渲染 | path=/my title="我的启事" 我的启事链接 0 个
```

截图：`01-login.png`、`02-login-filled.png`、`03-list.png`、`04-detail.png`、`05-my.png`。

> 冒烟附带观察（**不计入门禁 1**）：`/my` 页当前是占位骨架，正文写「你的启事列表正在接入中：接入后将在这里按状态分组展示」，`我的启事链接 0 个`；数据出口 `GET /api/my/records` 本身正常（见 C1 出口 4）。即：接口有、页面未接。C2 任务标题写明交付的是「/my 骨架」，与实现一致，不按缺陷计。

## 3. 13 条逐条结论

| # | 组 | 用例 | 判定 | 证据位置 |
|---|---|---|---|---|
| A1 | 登录态 | 无 cookie 访问需登录接口 → 401 | **PASS** | §3.1 |
| A2 | 登录态 | 错口令 5 次文案一致 | **PASS** | §3.1 |
| A3 | 登录态 | `Set-Cookie` 属性（HttpOnly / SameSite / Path） | **PASS** | §3.1 |
| A4 | 登录态 | 刷新保持登录 | **PASS** | §3.1 |
| A5 | 登录态 | 重启后会话失效 | **PASS** | §3.1 |
| A6 | 登录态 | 退出清除会话 | **PASS** | §3.1 |
| B1 | 越权 | 非所有者 PATCH / PUT / DELETE → 403 且 DB 不变 | **PASS** | §3.2 |
| B2 | 越权 | 看别人 pair 联系方式 → 403 且 `t_access_log` 无新增 | **FAIL**（出口未实现，见 §4.1） | §3.2 |
| B3 | 越权 | 不存在 id → 404 | **PASS** | §3.2 |
| C1 | 遮蔽 | 五个出口（列表/详情/匹配/我的/通知）口径一致 | **PASS** | §3.3 |
| C2 | 遮蔽 | `location` 截断、`description` 打码按契约 | **PASS** | §3.3 |
| C3 | 遮蔽 | 非本人响应体 `1[3-9]\d{9}` 0 命中 | **PASS** | §3.3 |
| C4 | 遮蔽 | DB 内 `contact` 仍明文 | **PASS** | §3.3 |
| D1 | 附加 | 配对打分 / 兜底跑完后 `t_record.status` 与 `updated_at` 不变 | **PASS（打分路径）**，兜底路径无入口（见 §4.2） | §3.4 |

合计：**12 PASS + 1 FAIL**（13 条中）。FAIL 与缺口的性质、影响面见 §4。

### 3.1 A 组 · 登录态（6 条）

**A1 无 cookie → 401。**

```
$ curl -s -o body -w HTTP=%{http_code} GET /api/auth/me
HTTP=401  body={"code":10006,"message":"请先登录","data":null,"requestId":"40a66c29a13b4ede"}
$ ... GET /api/my/records
HTTP=401  body={"code":10006,"message":"请先登录","data":null,"requestId":"45fbba9a4c6d4da9"}
$ ... GET /api/notifications
HTTP=401  body={"code":10006,"message":"请先登录","data":null,"requestId":"70f00ac11f384012"}
$ curl -s -i -X POST /api/records -d '{}'          # 匿名写
HTTP/1.1 401   {"code":10006,"message":"请先登录",...}
```

判定 **PASS**。口径说明：`GET /api/records`、`GET /api/records/{id}`、`GET /api/categories` 匿名访问是 **200**（列表/详情/分类按契约 H3 允许匿名读，只做遮蔽），这是设计而非缺陷；实测 `GET /api/records` 匿名 → 200、返回 `isMine:false`。门禁 A1 的「401」落在私有读（me / 我的 / 通知）与全部写接口上，已逐条实测。

**A2 错口令 5 次文案一致**（外加换账号 2 次、不存在账号 1 次，共 7 次，专门验证无账号枚举差异）：

```
demo01 错口令 #1  HTTP=401 {"code":10006,"message":"学号或密码不正确",...}
demo01 错口令 #2  HTTP=401 {"code":10006,"message":"学号或密码不正确",...}
demo01 错口令 #3  HTTP=401 {"code":10006,"message":"学号或密码不正确",...}
demo02 错口令 #4  HTTP=401 {"code":10006,"message":"学号或密码不正确",...}
demo02 错口令 #5  HTTP=401 {"code":10006,"message":"学号或密码不正确",...}
nobody 错口令 #6  HTTP=401 {"code":10006,"message":"学号或密码不正确",...}
```

7 次响应的 `code`/`message`/`data` 完全一致，仅 `requestId` 不同。判定 **PASS**。

**A3 `Set-Cookie` 属性。**

```
$ curl -s -i -X POST /api/auth/login -d '{"studentNo":"demo01","password":"Demo@123456"}'
Set-Cookie: JSESSIONID=0F11943C90415D4BA3FF2511BDDB64A4; Path=/; HttpOnly; SameSite=Lax
```

逐项：`HttpOnly` ✅、`SameSite=Lax` ✅、`Path=/` ✅；无 `Secure`（`application.yml` 中 `secure: false`，本地 http 下置 true 会被浏览器拒收，一致）；无 `Max-Age`/`Expires`（会话级 Cookie）。
浏览器侧复核 HttpOnly 生效：页面内 `document.cookie` → `""`（JS 读不到 `JSESSIONID`）。判定 **PASS**。

**A4 刷新保持。**

服务端（同一 cookie 连续请求）：

```
$ curl -b jar /api/auth/me   # 第 1 次
HTTP=200 {"code":0,...,"data":{"id":1,"studentNo":"demo01","displayName":"演示甲"},...}
$ curl -b jar /api/auth/me   # 第 2 次
HTTP=200 {"code":0,...,"data":{"id":1,"studentNo":"demo01","displayName":"演示甲"},...}
$ curl -b jar /api/records?pageSize=1
HTTP=200 {"code":0,...,"isMine":true}
```

浏览器侧（`Page.reload` 真实刷新）：

```
A4 刷新前 header: FI 校园失物招领 启事列表 发布启事 我的启事 学号 demo01 退出
A4 刷新后 header: FI 校园失物招领 启事列表 发布启事 我的启事 学号 demo01 退出
A4 判定: PASS 刷新后仍是登录态
```

截图 `06-after-login.png`、`07-after-reload.png`。判定 **PASS**。

**A5 重启失效。**

```
[A5-1] 重启前：登录成功，GET /api/auth/me -> HTTP=200，旧 JSESSIONID=704A07D40307CA74309A230F71915956
[A5-2] taskkill //PID 14116 //F  → 成功: 已终止 PID 为 14116 的进程
       停止后探活：HTTP=000（进程已停）
[A5-3] 重新 `java -jar 同一 jar --server.port=8088` → HTTP=200
       日志：Started FindItCampusApplication ... with PID 14852（21:51:38）

[A5-4] 重启后用旧 cookie：
$ GET /api/auth/me      -b 旧cookie -> HTTP=401 {"code":10006,"message":"请先登录",...}
$ GET /api/my/records   -b 旧cookie -> HTTP=401 {"code":10006,"message":"请先登录",...}
$ GET /api/auth/me      （空 cookie 对照）-> HTTP=401 同上
[A5-5] 重启后重新登录 -> 200，GET /api/auth/me(新cookie) -> HTTP=200
```

判定 **PASS**。会话存在进程内存（`server.servlet.session.timeout: 30m`，无外部 session store），重启即全失效，与 `docs/design/v2-auth-session.md` 及 `deploy/DEPLOY-WINDOWS.md` 记录的「单实例演示形态已知限制」一致。

**A6 退出清除。**

```
$ curl -s -i -b jar -X POST /api/auth/logout
HTTP/1.1 200
Set-Cookie: JSESSIONID=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly; SameSite=Lax
{"code":0,"message":"已退出","data":null,"requestId":"dc843a5aefa143e1"}

$ curl -b jar /api/auth/me   # 退出后继续用旧 cookie
HTTP=401 {"code":10006,"message":"请先登录",...}
```

浏览器侧：

```
A6 退出后 header: FI 校园失物招领 启事列表 发布启事 登录          -> PASS 顶栏回到未登录态
A6 刷新后 header: 同上                                          -> PASS 刷新后仍是未登录态
A6 守卫: 未登录访问 /my -> /login?redirect=%2Fmy                 -> PASS
```

截图 `08-after-logout.png`、`09-logout-reload.png`、`10-guard-redirect.png`。判定 **PASS**。

### 3.2 B 组 · 越权（3 条）

**B1 非所有者 PATCH / PUT / DELETE → 403 且 DB 不变。** 目标记录 `#1`（owner=`demo01`/id=1，`PENDING`，对他人可见），操作人 `demo02`（另加 `demo03` 复核）。每步都在写前写后各取一次 DB 快照：

```
--- 操作前 DB ---
1  PENDING  图书馆丢了黑色钱包  昨天下午在图书馆三楼自习区靠窗…  图书馆三楼自习区  13800000001  李同学  1  NULL  2026-10-20 09:10:00

# PUT（整份合法 body：换 title/description/location/contact/publisherName）
$ curl -b jar02 -X PUT /api/records/1 --data-binary @put1.json
HTTP=403 {"code":10007,"message":"无权操作该资源","data":null,"requestId":"7fbb021a12504544"}
$ 同一请求换 demo03
HTTP=403 {"code":10007,"message":"无权操作该资源","data":null,"requestId":"1c1a813c1c4d4d46"}

# PATCH
$ PATCH /api/records/1/status {"status":"COMPLETED"} (demo02)
HTTP=403 {"code":10007,"message":"无权操作该资源",...}
$ PATCH /api/records/1/status {"status":"CLOSED"} (demo02)
HTTP=403 {"code":10007,"message":"无权操作该资源",...}

# DELETE
$ DELETE /api/records/1 (demo02) -> HTTP=403 {"code":10007,...}
$ DELETE /api/records/1 (demo03) -> HTTP=403 {"code":10007,...}

--- 操作后 DB（与操作前逐字节一致）---
1  PENDING  图书馆丢了黑色钱包  …  图书馆三楼自习区  13800000001  李同学  1  NULL  2026-10-20 09:10:00
```

`status`、`title`、`description`、`location`、`contact`、`publisher_name`、`publisher_id`、`deleted_at`、`updated_at` 九列全部未变。判定 **PASS**。

**B2 看别人 pair 联系方式 → 403 且 `t_access_log` 无新增：FAIL（出口未实现）。**

```
t_access_log 操作前：0
$ 匿名 GET /api/pairs/1/contact                       -> HTTP=401 {"code":10006,"message":"请先登录",...}
$ demo01（pair#1 的失主方）GET /api/pairs/1/contact    -> HTTP=404 {"code":10003,"message":"资源不存在",...}
$ demo02（pair#1 的拾获方）GET /api/pairs/1/contact    -> HTTP=404 {"code":10003,"message":"资源不存在",...}
$ demo03（无关第三方）GET /api/pairs/1/contact         -> HTTP=404 {"code":10003,"message":"资源不存在",...}
t_access_log 操作后：0

$ server 端路由实现：pair 模块 java 文件 5 个，其中 web/ 控制器 0 个
$ grep -rn "t_access_log\|AccessLog" server/src  ->  0 命中（全仓无写入点）
```

即：`GET /api/pairs/{id}/contact` 在本基线**没有路由**，任何身份都是 404（`10003`），因此「403 + 审计无新增」这条断言**没有可测对象**，无法判定为 PASS，按门禁口径记 FAIL。这与 C1 交付说明一致（`docs/testing/v2/C1-masking-evidence.md` §「不在 C1 范围」：`/api/pairs/*` 的 PATCH/contact/unlock 与 `t_access_log` 取号审计属于 E1）。影响面与最小复现见 §4.1。

**B3 不存在 id → 404。**

```
$ 匿名 GET /api/records/999999   -> HTTP=404 {"code":20001,"message":"记录不存在或已删除",...}
$ demo02 GET /api/records/999999 -> HTTP=404 {"code":20001,"message":"记录不存在或已删除",...}
$ 匿名 GET /api/records/0        -> HTTP=404 {"code":20001,...}
$ demo02 GET /api/records/0      -> HTTP=404 {"code":20001,...}
$ GET /api/records/abc           -> HTTP=400 {"code":10001,"data":{"fieldErrors":[{"field":"id","reason":"必须为数字"}]}}
```

合法数字但不存在 → 404（`20001`，与 403 的 `10007` 分流正确）；非数字路径参数 → 400 参数校验，属于入参问题不是可见性问题。判定 **PASS**。

### 3.3 C 组 · 遮蔽（4 条）

**C1 五个出口口径一致。** 观测对象：记录 `#3`（owner=demo01，`location=第三教学楼201`），观察人 `demo02`（登录非所有者）。

| 出口 | 请求 | `location` | `description` | `contact` | `publisherName` | `isMine` |
|---|---|---|---|---|---|---|
| 列表 | `GET /api/records?pageSize=50` 中的 #3 | `第三教学楼…` | **不返回**（响应体无该字段） | 不返回 | 不返回 | `false` |
| 详情 | `GET /api/records/3` | `第三教学楼…` | 原文但无号码（见下） | `null` | `张**` | `false` |
| 匹配 | `POST /api/match` 命中 `#15` | `第三教学楼…` | 不返回 | 不返回 | 不返回 | — |
| 我的 | `GET /api/my/records`（demo02 自己 4 条） | 原值 | 不返回 | **本人原值** | 本人原值 | — |
| 通知 | `GET /api/notifications`（2 条） | 不适用 | 不适用 | 不适用 | 不适用 | — |

原文摘录：

```
列表 #3: {"id":3,"location":"第三教学楼…","title":"学生证和校园卡一起丢了","isMine":false,"hasDescription":false}

详情 #3: {"id":3,...,"location":"第三教学楼…","contact":null,"publisherName":"张**",
         "description":"在今天上午的高等数学课上，装学生证、校园卡和一张银行卡的透明卡套从书包侧袋掉出来了，
                        怀疑落在第三教学楼 201 教室，证件上的姓名是张三，请捡到的同学联系我。",
         "isMine":false,"pairState":null,...}

匹配   : {"matchId":2,"engine":"LOCAL_KEYWORD","total":1,"items":[{"recordId":15,"score":0.74,...,
         "location":"第三教学楼…",...}]}
详情#15: {"id":15,"location":"第三教学楼…","contact":null,"publisherName":"郑**","isMine":false}

我的   : {"total":4,"list":[{"id":6,...,"location":"7号宿舍楼下快递点","contact":"13800000006","publisherName":"孙同学",...},
         {"id":16,...,"contact":"13800000104",...},{"id":24,...},{"id":13,...}]}

通知   : {"unreadCount":1,"total":2,"list":[{"id":4,"type":"PAIR_MATCH","title":"有一条新的匹配结果",
         "body":"…为保护隐私，通知不展示联系方式与完整描述。"},...]}
```

同一记录同一字段在不同出口取值一致：`#3` 的 `location` 在列表与详情都是 `第三教学楼…`；`#15` 的 `location` 在 `/api/match` 结果与详情页都是 `第三教学楼…`；`#3` 的 `contact` 在列表与详情都不可得（列表不返回该字段、详情 `null`）。判定 **PASS**。

**C2 `location` 截断与 `description` 打码按契约（§6.1/§6.2/§6.3）。** 每条都给出「期望（契约原文）→ 实际」：

| 夹具 | 契约期望 | 实际（demo02 视角） | 结果 |
|---|---|---|---|
| `#27` location=`第三教学楼201` | `第三教学楼…`（8 码点不截，末尾 `201` 被数字规则去掉） | `第三教学楼…` | ✅ |
| `#27` description=`昨天 14:30 在三楼捡到，联系 13812345678` | `[时间已隐藏] 在三楼捡到，联系 [手机号已隐藏]`（§6.4 P1） | `[时间已隐藏] 在三楼捡到，联系 [手机号已隐藏]` | ✅ |
| `#27` contact / publisherName | `null` / `李**` | `null` / `李**` | ✅ |
| `#28` location=`东区3号宿舍楼512室` | `东区3号宿舍楼…`（先截 8 码点得 `东区3号宿舍楼5`，再去末尾数字） | `东区3号宿舍楼…` | ✅ |
| `#28` description=`QQ 123456789，证件号 110101199001011234` | `QQ[QQ号已隐藏]，证件号 [证件号已隐藏]`（§6.4 P2） | `QQ[QQ号已隐藏]，证件号 [证件号已隐藏]` | ✅ |
| `#28` contact / publisherName | `null` / `王**` | `null` / `王**` | ✅ |
| `#29` title=`夹具标题含号13812345678` | `夹具标题含号[手机号已隐藏]`（§6.2 标题防御性追加） | `夹具标题含号[手机号已隐藏]` | ✅ |
| `#29` publisherName=`赵`（单码点） | `*` | `*` | ✅ |
| 反例 `#3` description 含 `第三教学楼 201 教室` | 原样（裸房间号不命中，§6.4 N1/N3） | 原样，仅 `location` 被截为 `第三教学楼…` | ✅ |
| 所有者视角 `#27`/`#28` | 四项全原值（§6.4 O1/O2） | `第三教学楼201` / `昨天 14:30 …13812345678` / `13812345678` / `李同学`；`东区3号宿舍楼512室` / `QQ 123456789，证件号 110101199001011234` / `13700001234` / `王小明` | ✅ |

判定 **PASS**。附注：`#23` 的标题在 `/api/match` 中显示为 `已归还:第二教学楼…`（原本是全角冒号 `：`），是 §6.2 规定的归一化（全角冒号→ASCII 冒号）所致，非缺陷。

**C3 非本人响应体 `1[3-9]\d{9}` 0 命中。**

```
列表 /api/records        : 命中 0 处
详情 /api/records/3      : 命中 0 处
详情 /api/records/15     : 命中 0 处
详情 /api/records/27     : 命中 0 处
详情 /api/records/28     : 命中 0 处
详情 /api/records/29     : 命中 0 处
匹配 /api/match          : 命中 0 处
通知 /api/notifications  : 命中 0 处
我的 /api/my/records     : 命中 4 处 -> 13800000006 13800000011 13800000101 13800000104
```

两个必须说清的点：

1. **`我的` 出口的 4 处命中不是泄露。** 按契约 §6.5，`GET /api/my/records` 对「本人」返回 `contact` 原值；命中项归属核对：`6/13/16/24` 的 `publisher_id` 全为 `2`，即全部是 demo02 自己的记录。这是「本人数据回显」，不是「看到别人号码」。
2. 首次扫描时 `详情 #28` 曾报 1 处命中 `16546291848`，回查原文是 `"requestId":"1654629184804031"`（16 位纯数字请求 id）被正则截取前 11 位，属**扫描误报**；已在扫描前剔除 `requestId` 后复测为 0，并额外做了定向比对：

```
记录 #3 的真实 contact=13800000003  -> 非本人响应命中 0 处
记录 #27 的真实 contact=13812345678 -> 非本人响应命中 0 处
记录 #28 的真实 contact=13700001234 -> 非本人响应命中 0 处
记录 #29 的真实 contact=13600001234 -> 非本人响应命中 0 处
```

判定 **PASS**（非本人出口 0 命中；本人出口命中项已逐条归属核对）。

**C4 DB 内 `contact` 仍为明文。**

```
$ mysql ... -e "SELECT id,contact,publisher_name,location FROM t_record WHERE id IN (1,3,13,23,27,28,29) ORDER BY id;"
1   13800000001   李同学   图书馆三楼自习区
3   13800000003   张三     第三教学楼201
13  13800000101   周同学   图书馆三楼自习区
23  13800000111   何同学   第二教学楼楼梯口
27  13812345678   李同学   第三教学楼201
28  13700001234   王小明   东区3号宿舍楼512室
29  13600001234   赵       图书馆
```

`#29` 的 `title` 在库里仍是 `夹具标题含号13812345678`（明文），只有输出层打码。判定 **PASS**：遮蔽是输出层行为（V2-ADR-003），符合 C1 口径。

### 3.4 D 组 · 附加（1 条）

**D1 配对打分 / 兜底跑完后 `t_record.status` 与 `updated_at` 不变。**

```
基线快照（t_record 全表 27 行 id/status/completed_at/updated_at）
t_match_result 行数：5     t_pair_score 行数：14

$ POST /api/match  #1  -> {"matchId":4,...,"items":[{"recordId":13,"score":0.92,...}]}
$ POST /api/match  #2  -> {"matchId":5,...,"items":[{"recordId":23,"score":1.00,"recordStatus":"COMPLETED","detailAccess":"RESTRICTED"...},...]}

复快照后 diff：
PASS  t_record 全表 status / completed_at / updated_at 逐行一致（diff 空）
t_match_result 行数（打分后）：7   # 打分确实落库，只是写在快照表
PASS  t_pair_score 全表（含 status / updated_at）未被打分任务改写
```

判定 **PASS（打分路径）**：自动打分只新增 `t_match_result` 快照，既没有改 `t_record.status/updated_at`，也没有把 `t_pair_score` 的 `status` 翻回去（A1 §4.3 那条「文本 hash 变化不得把 IGNORED 翻回 NEW」的库层落点）。兜底路径的情况见 §4.2。

## 4. FAIL 与缺口

### 4.1 B2 · pair 取号出口在本基线不存在（判定 FAIL）

- **现象**：`GET /api/pairs/{id}/contact` 无路由。匿名 → 401（门禁层先拦），登录后任意身份（失主方 demo01 / 拾获方 demo02 / 第三方 demo03）→ 404 `{"code":10003,"message":"资源不存在"}`。`t_access_log` 表存在但全仓无任何写入点（`grep -rn "t_access_log\|AccessLog" server/src` → 0 命中），0 → 0 无变化。
- **最小复现**：

  ```
  curl -s -i -b <demo02 登录后的 cookie> http://127.0.0.1:8088/api/pairs/1/contact
  # 实际：HTTP 404  {"code":10003,"message":"资源不存在"}
  # 门禁 A/B 期望：HTTP 403 {"code":10007,...}，且 t_access_log 不新增
  ```

- **影响面**：
  - 作为**安全后果**：泄露面并不因此变大——非所有者本来就不该拿到别人号码，现在直接没有取号入口，详情/列表/匹配/通知四个出口的 `contact` 一律 `null` 或不返回（C1/C3 已实测）。**不存在「越权拿到号码」的可行路径**。
  - 作为**功能后果**：配对双方无法通过接口获取对方联系方式，E1 的「取号 + 审计」整块功能在本基线不可用；`t_access_log` 恒为 0，任何依赖审计的合规结论都无法在此基线上取证。
  - 作为**门禁后果**：B2 这条用例无法按字面判 PASS。属于「本期未实现的计划内范围」而非回归缺陷（C1 证据文档已声明 `/api/pairs/*` 归 E1），但它确实挡在门禁 1 的 3 条越权用例里。
- **不在本报告范围**：具体实现与排期，交由 ZHYYKFX-63 的人类验收决定是「按范围豁免」还是「退回 E1 先做」。

### 4.2 D1 的「兜底」路径同样没有入口（覆盖缺口，不单独记 FAIL）

```
$ curl -s -i -X POST /api/ops/pair-scan
HTTP/1.1 404
{"code":10003,"message":"资源不存在","data":null,"requestId":"716330beb2df435d"}

$ grep -rln 'pair-scan' server/src/main/java  -> 只有 auth/web/AuthPaths.java（门禁白名单规则）
   即：路径在规则表里登记了，实现侧 0 个文件
```

`AuthPaths.PUBLIC_WRITE_PATHS` 已为 `/api/ops/pair-scan` 预留白名单（注释写明「D1 落地时按 token 判 401/404」），但控制器未落地。因此 D1 的「兜底跑完后」没有可触发对象，本次只验证了「配对打分」路径的不变性。**这条不影响 D1 已测部分的结论，但意味着「兜底不改 status」这一条在集成分支上仍属未验证**，需要在 D1 实现后用同一套快照/diff 方法补测。

### 4.3 顺带发现（不计入门禁 1，供参考）

用仓库自带的 `vite.config.ts` 预览配置（`preview.port: 4173` + `/api` 代理到后端）跑 `vite preview` 时，浏览器登录会失败：

```
# 失败现场（浏览器）：登录页提示「登录失败 网络请求失败，请稍后再试」
# 服务端日志：POST /api/auth/login -> 403 (29 ms)
# 直接复现（构造同样请求）：
$ curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8088/api/auth/login \
    -H 'Content-Type: application/json' -H 'Origin: http://localhost:4173' \
    -d '{"studentNo":"demo01","password":"Demo@123456"}'
403
```

原因：`WebConfig` 的 `allowedOriginPatterns` 只登记了 `http://localhost:5173` / `http://127.0.0.1:5173`；preview 的代理带 `changeOrigin: true`，后端看到的 `Host` 是 `127.0.0.1:8088`、`Origin` 是 `localhost:4173`，被 Spring 判为跨域而拒绝。**真实部署不受影响**（`deploy/nginx/nginx.conf:62` 用 `proxy_set_header Host $host`，Origin 与 Host 一致）。影响仅限「本地用 preview 演练」这条路；本次冒烟改用 5173 预览即通过。若要消除，二选一：把 preview 端口改成 5173，或把 4173 加进 CORS 白名单——都属于实现侧决定，本报告不动代码。

## 5. 未覆盖 / 风险陈述

- **B2、D1 兜底**：如前，属于本期未实现的接口，本基线无法取证。
- **并发与竞态**：本次为单请求串行复核，未做并发写、并发取号、重复提交的压测。门禁 1 无此要求，但「越权 + 并发」组合未验。
- **越权覆盖面**：只用了 `PENDING` 记录 `#1` 作靶子；`COMPLETED` / `CLOSED` 记录的写越权（应落 404 而非 403）未在本次复核范围内（属 C1 已交付的其他判据）。
- **遮罩出口的穷举**：`pair 卡片` 出口在详情里只对所有者返回 `pairs`，非所有者无该字段（已实测 `pairState: null`），但 pair 卡片的具体字段级遮蔽随 E1 落地后才可测。
- **测试数据落库**：本次在 `findit_gate1_67` 里新增了 3 条夹具记录（#27/#28/#29）与 4 条 `t_match_result` 快照，均为测试库内的业务写入，未改动任何仓库文件与业务代码。
- 复核只降低风险，不代表「零缺陷」；本报告结论仅覆盖上述 13 条与已列出的命令。

## 6. 证据清单与复现

| 内容 | 路径 |
|---|---|
| 本文 | `docs/testing/v2/gate1-review.md` |
| 浏览器截图（10 张） | `docs/testing/v2/gate1-review/` |
| A 组原始输出 | 本机 `deploy-accept/ZHYYKFX-67/ev/A-group.txt`、`A5a.txt`、`A5b.txt`、`auth-browser.txt` |
| B / C / D 组原始输出 | 同目录 `B-group.txt`、`C-group.txt`、`D-group.txt` |
| 构建日志 | 同目录 `build-server.txt`、`build-web.txt` |
| 复现脚本 | 同目录 `run-a.sh`、`run-a5a.sh`、`run-a5b.sh`、`run-b.sh`、`run-c.sh`、`run-d.sh`、`smoke-cdp.mjs`、`auth-cdp.mjs` |

复现顺序建议：建库（`schema.sql` + `seed.sql`，库名自定）→ `mvn package` → 8088 启动 → `run-a.sh` → `run-b.sh` → `run-c.sh` → `run-d.sh` → `run-a5a.sh` + `run-a5b.sh`（会重启进程，放最后）→ `npm run build` + `vite preview --port 5173` + `smoke-cdp.mjs` / `auth-cdp.mjs`。

## 附录 A · 冒烟与浏览器复核脚本要点

- 浏览器：Edge 155 headless，通过 DevTools Protocol 直连（`Runtime.evaluate` 填表 + 点击提交，非 DOM 注入 mock），无第三方依赖。
- 断言点：`#field-studentNo` / `#field-password` / `form.login__form button[type=submit]`（语义 id 与可见文案优先，未用 CSS class 定位列表内容）；路由断言用 `location.pathname`。
- 刷新用 `Page.reload`，退出点击顶栏「退出」按钮，守卫用直接 `Page.navigate` 到 `/my` 后读 `location.pathname + location.search`。
