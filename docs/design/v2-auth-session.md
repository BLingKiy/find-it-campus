# v2 认证与会话设计（B2）

- 任务：ZHYYKFX-50 · B2 认证与会话
- 契约依据：`feat/v2-A1-arch-contract` @ `b90270e`（`docs/api-contract-v2.md` §1.1 / §4.9–§4.11 / §6，`docs/design/v2-architecture.md` §5.3 / V2-ADR-002）
- 本文回答三件事：登录态放在哪里、未登录怎么被拦、会话失效时谁受影响。
- 本文不含代码细节（代码在 `server/src/main/java/com/findit/campus/auth/`），只记口径与取舍。

---

## 1. 结论

**服务端 `HttpSession`（进程内存）+ `JSESSIONID` Cookie（`HttpOnly` / `SameSite=Lax` / `Path=/`，30 分钟超时），
门禁用 Servlet Filter 在路由之前拦截，401 统一为 `code=10006`。**

三个理由：

1. **可吊销**：登出、服务端失效立即生效。JWT 签发即生效到过期，改权限/封号要另建吊销名单，复杂度更高（V2-ADR-002）。
2. **不引整套安全框架**：只引 `spring-security-crypto` 用 bcrypt，不引 `spring-boot-starter-security` 的过滤器链 ——
   后者会带默认表单登录、HTTP Basic 与 CSRF token，与本项目"不开放注册、写操作只用 POST/PUT/PATCH/DELETE"的口径冲突。
3. **代价可接受且已隔离**：单实例、重启失效。这两条在 v2 的部署形态（一个 JVM + 一个 MySQL）下本来就成立，
   把代价写进部署说明，而不是假装没有。

---

## 2. 约束与假设

| 项 | 取值 | 来源 |
|---|---|---|
| 部署形态 | 单实例（一个 Spring Boot 进程 + 一个 MySQL） | A1 A2，现有部署脚本 |
| 用户规模 | 课程演示量级；会话在内存里，无共享存储需求 | 经验判断（未压测） |
| 账号来源 | `db/seed.sql` 的演示账号；**不开放注册** | B1 seed + 任务书 |
| 口令存储 | bcrypt，cost ≥ 10，`$2a$/$2b$` 前缀，60 字符 | 契约 §4.2.1 |
| 超时 | 30 分钟无活动 | 契约 §1.1 |
| 传输 | 开发/演示为明文 HTTP；生产应在 HTTPS 反代后并把 Cookie 设为 `Secure` | 部署说明 §11 |

未确认项（需要人类拍板才动）：**多实例部署**（必须换外置会话）、**账号停用入口**（见 §7 残余风险）、
**登录限流是否下沉到 Nginx/IP 层**（应用层刻意不做，避免和"始终 401、文案一致"的验收冲突）。

---

## 3. 会话模型

| 要素 | 取值 | 说明 |
|---|---|---|
| 存储 | Tomcat 内存 `HttpSession` | 进程退出即消失；不做持久化、不做跨实例复制 |
| Cookie | `JSESSIONID` | `Path=/`、`HttpOnly`、`SameSite=Lax`；HTTPS 部署追加 `Secure` |
| 超时 | 30 分钟（`server.servlet.session.timeout`） | 无活动即过期；过期后的请求按未登录处理 |
| 会话内容 | `{id, studentNo, displayName}` | 只放判定与回显所需字段；**不放口令哈希、不放开权限位** |
| 登录时 | `getSession(true)` → `changeSessionId()` → 写身份 | `changeSessionId()` 是会话固定（session fixation）防护 |
| 登出时 | `invalidate()` + 下发 `Max-Age=0` 的同名 Cookie | 容器失效会话时不会删浏览器那头，必须显式下发 |
| 轮换 | 已登录再次登录 → 200 + 换新 session id | 契约 §4.9「已登录再登录返回 200 并轮换 session id」 |

**为什么会话里只放快照、不每请求回查 `t_user`**：每个受保护请求多一次 PK 查询，而 v2 没有"停用账号"入口，
这次查询买不到任何行为差异。代价写在 §7：一旦有了停用入口，就必须在门禁里补一次 `is_active` 复核或引入会话版本号。

---

## 4. 谁需要登录

规则表在 `auth/web/AuthPaths`，执行点在 `auth/web/AuthGateFilter`。

| 类别 | 规则 | 接口 |
|---|---|---|
| 写（非 GET/HEAD） | **默认要求登录**，白名单例外 | 发布/编辑/改状态/删除/匹配；`/api/my/*`、`/api/notifications/*`、`/api/pairs/*` |
| 写白名单 | 匿名可调用 | `POST /api/auth/login`、`POST /api/auth/logout`（幂等）、`POST /api/ops/pair-scan`（走 `X-Ops-Token`） |
| 读（GET） | **默认匿名**，私有读显式登记 | 公开：`/api/records`、`/api/records/{id}`、`/api/categories`、`/api/health`；需登录：`/api/auth/me`、`/api/my`、`/api/notifications`、`/api/pairs` |

**为什么写默认拦（fail-closed）**：新增写接口忘了登记时，"匿名可写"是安全事故，"匿名只读"只是产品缺陷。
两种错误的代价不对称，所以默认值必须站在安全那一侧。

**为什么门禁是 Filter 而不是 MVC 拦截器**：拦截器只对"已经映射到 handler"的请求生效。
`/api/my/records`（C1 的接口）在 B2 交付时还不存在，用拦截器匿名访问会先得到 404 —— 既让 B2 的验收项不成立，
也把"这个路由存不存在"泄露给未登录者。Filter 在 `DispatcherServlet` 之前判路径，天然满足这两点。
401 的 body 仍由 `ApiResponse.fail(ErrorCode.UNAUTHORIZED)` 构造，与 `GlobalExceptionHandler` 共用同一套
code / 文案 / requestId —— 是"第二个渲染点"，不是"第二张错误码表"。

**未被拦的**：`OPTIONS` 预检（浏览器预检不带 Cookie）、非 `/api/**` 路径。

---

## 5. 401 统一出口

| 场景 | HTTP | code | message |
|---|---|---|---|
| 未登录 / 会话过期 / 重启后 | 401 | 10006 | `请先登录` |
| 登录失败（学号不存在 / 口令错 / 账号停用） | 401 | 10006 | `学号或密码不正确` |

Body 恒为 `{code,message,data,requestId}`，`data=null`，`requestId` 与响应头 `X-Request-Id`、日志里的值一致。

- **不返回空列表、不返回 302、不返回 Whitelabel**：前端拦截器按 `code === 0` 判成功，其它形状会掉进"未知错误"分支。
- **同一个 code 两个文案**：`ErrorCode.UNAUTHORIZED` 只有一条默认文案（`请先登录`），登录失败通过
  `BizException.withPublicMessage` 显式覆盖。这样 10006 的含义只有一条，不因为文案不同裂成两个枚举值。
- **失败文案不区分原因**是防账号枚举的硬要求；服务端日志里区分（`STUDENT_NO_NOT_FOUND` / `USER_INACTIVE` / `PASSWORD_MISMATCH`），
  日志只给运维看。

---

## 6. 口令与登录失败

| 项 | 做法 | 理由 |
|---|---|---|
| 哈希 | `BCryptPasswordEncoder(10)` | 契约 cost ≥ 10；与 seed 里的 `$2a$10$` 一致 |
| 校验 | 用户不存在/停用时，仍对随机 `dummyHash` 跑一次 bcrypt | 否则"账号不存在"几毫秒返回、"存在"约 100ms 返回，响应时间差本身就是枚举旁路 |
| 坏数据 | `password_hash` 不是 bcrypt 形态 → 按"不匹配"处理 + ERROR 日志 | 不能让一行坏数据变成 500，也不能让它比"口令错"多泄露信息 |
| 失败计数 | 按 `学号 + 客户端 IP`，15 分钟窗口，阈值 5 | 只写 WARN；**不锁定、不返回 429**（契约 §4.9） |
| 计数内存 | key 上限 10 000，超限先清过期、仍满则整体清空 | key 由攻击者可控（随便填学号），无上限的 Map 就是远程内存放大器；计数只用于告警，清空是可接受的降级 |
| 客户端 IP | 只取连接对端地址，**不读 `X-Forwarded-For`** | 该头客户端可伪造，采信等于把计数 key 交给攻击者 |

---

## 7. 安全面与残余风险

| 面 | 现状 | 残余风险 |
|---|---|---|
| 认证 | 服务端会话，登录轮换 session id | — |
| 授权 | B2 只判"是否登录"；资源归属（`publisher_id == session.userId`）由 C1/C2/E1 在 service 层判定 | 越权判定不在 B2，别把"登录了"当成"有权限" |
| 传输 | 本地/演示 HTTP | 明文链路可被嗅探；生产必须 HTTPS + `secure` Cookie |
| 静态 | 口令 bcrypt；`t_user` 无手机号 | 数据库备份里仍有口令哈希，按敏感数据保管 |
| CSRF | `SameSite=Lax` + 写操作只用非 GET 方法；**不引入 CSRF token** | 同站 XSS 仍可代发请求，靠前端转义与不用 `v-html` 控制 |
| 会话固定 | 登录时 `changeSessionId()` | — |
| 账号停用 | `is_active=0` 只拦新登录 | **已存在会话在超时前仍有效**（≤30 分钟）；v2 无停用入口，加管理端时必须在门禁补 `is_active` 复核 |
| 会话吊销 | 只有登出与超时 | 没有"踢出所有会话"的管理能力；需要时先做会话版本号/外置会话 |
| 多实例 | 不支持 | 多实例下会话不共享，必须换外置会话（Redis/DB）+ 配对任务分布式锁 |
| 暴力破解 | 只记日志 | 应用层不限流（契约要求"始终 401、文案一致"）；限流放反向代理/IP 层 |
| 注册 | 不开放，无 `/api/auth/register` | 账号只能由 seed/运维产生；加注册要先定义"谁能注册、怎么证明是本校学生" |

---

## 8. 可观测性

| 项 | 现状 |
|---|---|
| 访问日志 | 每次请求一行：方法、路径、状态、耗时（`RequestIdFilter`），带 `requestId` |
| 登录成功 | `INFO 登录成功 userId=<id> ip=<ip>`（不打印学号与口令） |
| 登录失败 | `WARN 登录失败 studentNo=<no> ip=<ip> reason=<分类> consecutiveFailures=<n>` |
| 阈值提示 | `WARN 登录连续失败达到阈值（仅记录，不锁定账号）: key=<学号|IP> count=5 windowMinutes=15` |
| 计数字表上限 | `WARN 登录失败计数表达到上限 10000，整体清空…` |
| 未做 | 没有引入 actuator/micrometer，`findit_auth_login_total`、`findit_session_active` 两个指标**尚未导出**（A1 §11 规划项）。需要时再加依赖，现阶段靠日志 grep |

---

## 9. 权衡记录

| 决策 | 选择 | 放弃的方案与原因 |
|---|---|---|
| 会话形态 | 服务端 `HttpSession` | 放弃 JWT：签发即生效到过期，封号/改权限不生效，要维护吊销名单；放弃 OAuth2/统一认证：超出课程范围 |
| 安全依赖 | 只引 `spring-security-crypto` | 放弃 `spring-boot-starter-security`：默认表单登录/HTTP Basic/CSRF token 与本项目口径冲突，且会引入一套没人维护的过滤器链 |
| 门禁位置 | Servlet Filter（路由之前） | 放弃 HandlerInterceptor：只能拦已映射的 handler，未实现的受保护路径会 404 并泄露路由是否存在 |
| 门禁默认值 | 写默认拦、读默认放 | 放弃"全部默认拦"：会让列表/详情/分类失去匿名可读，直接违背契约 §3；放弃"全部默认放"：新增写接口会静默变成匿名可写 |
| 10006 文案 | 一个 code + `withPublicMessage` 覆盖 | 放弃"两个同 code 的枚举值"：10006 的含义会分裂；放弃"登录失败用别的 code"：契约冻结了 10006 |
| 会话内容 | 登录时快照 | 放弃每请求回查 `t_user`：v2 没有停用入口，多一次查询买不到行为差异（有停用入口时必须改） |
| 失败计数 | 内存 Map + 上限 + 只记日志 | 放弃账号锁定/429：与"连续 5 次始终 401、文案一致"的验收直接冲突；放弃不设上限：会被随机学号撑爆内存 |
| 计时侧信道 | 未知账号也跑一次 bcrypt | 代价是每次失败登录多约 100ms（cost=10 的固有开销），换取"无法用响应时间枚举账号" |

---

## 10. 演进路线（每一步可独立回滚）

| 阶段 | 内容 | 验收 | 回滚 |
|---|---|---|---|
| 已完成（B2） | 三条 auth 路由 + 门禁 + 401 出口 + bcrypt + 失败计数 | 见 `docs/testing/v2/B2-auth-evidence.md` 六条 | 回滚 auth 包与 `AuthGateFilter`，v1 读接口不受影响 |
| 下一步（C1/C2） | 资源归属判定（`publisher_id == session.userId`）、遮蔽 | 17 接口三态矩阵 | 按分支回滚 |
| 需要时 | 账号停用入口 → 门禁加 `is_active` 复核 | 停用后 ≤1 次请求内失效 | 去掉那次查询即可 |
| 需要时 | 多实例 → 外置会话（Redis/DB） | 会话跨实例可见；配对任务加分布式锁 | 切回内存会话需先摘掉多余实例 |
| 需要时 | 登录限流下沉到 Nginx（`limit_req`） | 429 由代理返回，应用契约不变 | 关掉 `limit_req` 规则 |
