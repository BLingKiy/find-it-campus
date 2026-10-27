# B3 前端登录态 · 验收证据

- 任务：[ZHYYKFX-51](https://app.hifox.com/nhnok/task/ZHYYKFX-51) · B3 前端登录态
- 分支：`feat/v2-B3-frontend-auth`（从 `origin/main` @ `21784f67` 切出）
- 范围：只改 `web/`；后端零改动
- 验证时间：2026-10-27 20:05–20:12（UTC+8）

## 1. 结论

6 条验收标准全部通过，7 条端到端检查在真实后端 + 真实 session cookie 上跑通（`run-result.json` 中 7/7 `ok: true`）。
截图均为**完整浏览器窗口**（地址栏 URL + 页面顶栏登录态同框），截图脚本与结果一并留档。

## 2. 环境（联调基线）

| 项 | 值 |
|---|---|
| 前端 | `web/` · Vite `8.3.5` · Node `22.23.3` · dev server `http://127.0.0.1:5173` |
| 后端 | `feat/v2-B2-auth` @ `2553695` 构建的 jar · `http://127.0.0.1:18080` |
| 数据库 | MySQL `8.0.29` · 独立数据目录 · 端口 `3307` |
| Schema/Seed | `feat/v2-B1-data-layer` @ `9b2ad25` 的 `db/schema.sql` + `db/seed.sql`（7 表、24 条记录、3 个演示账号） |
| 浏览器 | Microsoft Edge `155.0.4283.62`（headed，独立 profile，无痕等价：全新 cookie 罐） |

> 后端不是从 `main` 起的：auth 接口只存在于 `feat/v2-B2-auth`，从 `main` 起会让登录全部失败。
> 演示账号口令从 B1 `db/seed.sql` 读取，按交付约定不写入本文件与任何证据。

## 3. 改动清单（全部在 `web/`）

| 文件 | 改动 |
|---|---|
| `src/types/api.ts` | 新增 `UserInfo`、`LoginPayload`，与契约 §4.9/§4.11 三字段对齐 |
| `src/api/auth.ts`（新增） | `login` / `logout` / `fetchMe`，三者走 `silentAuth`（登录失败的 401 不触发全局引导） |
| `src/api/http.ts` | 所有请求 `credentials:'include'`；401 统一回调 + 2s 合并窗口，并发 401 只引导一次 |
| `src/composables/useAuth.ts`（新增） | 登录态单例：`/auth/me` 恢复会话、并发探测去重、登录/登出/清状态；**不写 localStorage、不存 token** |
| `src/views/Login.vue`（新增） | 学号 + 密码；无注册入口；失败文案直接用后端 `message`；`redirect` 只接受站内路径 |
| `src/router/index.ts` | 新增 `/login`；守卫覆盖发布 / 编辑 / 我的启事 / 通知；`redirect` 白名单；query 统一 `%2F` 编码 |
| `src/App.vue` | 顶栏：未登录显示「登录」，已登录显示「学号 demo01 + 退出」；启动时恢复登录态 |
| `src/main.ts` | 注册 401 落点：清本地登录态 → `router.replace('/login?redirect=<当前地址>')` |

守卫的「编辑 / 我的启事 / 通知」按路径规则预置（`/records/:id/edit`、`/my`、`/notifications`）：
这些页面由 C2/E2 落地，先兜住，避免新页面忘加 `meta.requiresAuth` 就变成匿名可进。

## 4. 验收对照

| # | 验收标准 | 结果 | 证据 |
|---|---|---|---|
| 1 | 无痕访问 `/records/new` → `/login?redirect=%2Frecords%2Fnew`；登录成功回 `/records/new` | 通过 | `01-guard-redirect-login.png`（URL + 顶栏「登录」）→ `03-login-redirect-back.png`（URL `/records/new` + 顶栏「学号 demo01 退出」） |
| 2 | 已登录刷新任意页面仍为登录态，顶栏仍显示学号 | 通过 | `04-refresh-keeps-session.png`（`Page.reload` 后 URL 与顶栏学号均保持） |
| 3 | 退出后回首页且顶栏变登录入口；再访问 `/records/new` 仍被拦 | 通过 | `06-logout-home.png`（URL `/` + 顶栏「登录」）→ `07-guard-after-logout.png`（再次 `/login?redirect=%2Frecords%2Fnew`） |
| 4 | 接口 401 只弹一次登录引导（不重复、不并发） | 通过 | 3 个并发写请求同时 401（`[401,401,401]`）；登录引导元素数 = 1，401 跳转日志 = 1 条；`05-401-single-prompt.png`；详见 `run-result.json` |
| 5 | 登录失败文案与后端一致，不区分学号不存在 / 密码错误 | 通过 | `02-login-failure.png`：`登录失败 / 学号或密码不正确`，与后端 401/10006 的 `message` 相同 |
| 6 | 不改后端，只动 `web/` | 通过 | `git diff --stat origin/main...HEAD` 仅 `web/` 源码 + 本文档 |

### 401 并发去重的证据细节

```json
"concurrentStatuses": "[401,401,401]",
"loginPromptCount": 1,
"redirectLogCount": 1
```

- 三个请求并发进入 `web/src/api/http.ts` 的 401 出口，2s 合并窗口只放行第一个回调；
- 页面只发生一次 `/login` 跳转，登录页只渲染一个「请先登录」引导条；
- 浏览器控制台只有一条 `[auth] POST /records 返回 401，跳转登录`。

## 5. 截图（含地址栏 URL 与顶栏状态）

| 截图 | 场景 |
|---|---|
| `B3-frontend-auth/01-guard-redirect-login.png` | 未登录访问 `/records/new` → `/login?redirect=%2Frecords%2Fnew`，顶栏「登录」 |
| `B3-frontend-auth/02-login-failure.png` | 错误口令登录：统一文案「学号或密码不正确」 |
| `B3-frontend-auth/03-login-redirect-back.png` | 登录成功回跳 `/records/new`，顶栏「学号 demo01 退出」 |
| `B3-frontend-auth/04-refresh-keeps-session.png` | 刷新后仍是登录态，顶栏学号保持 |
| `B3-frontend-auth/05-401-single-prompt.png` | 并发 401 后只出现一次登录引导 |
| `B3-frontend-auth/06-logout-home.png` | 退出后回首页 `/`，顶栏变「登录」 |
| `B3-frontend-auth/07-guard-after-logout.png` | 退出后再访问 `/records/new` 仍被守卫拦截 |

截图由 headed Edge + Win32 `PrintWindow` 抓取完整窗口，地址栏与页面顶栏在同一张图内，未做任何拼接或遮挡。
原始断言与 URL/顶栏文本见 `B3-frontend-auth/run-result.json`。

## 6. 复现步骤

```powershell
# 1) 依赖与静态检查（在 b3/web）
npm ci
npm run type-check
npm run build

# 2) 联调后端（B2 分支 jar + B1 schema/seed，MySQL 3307）
$env:FINDIT_DB_URL = 'jdbc:mysql://127.0.0.1:3307/find_it_campus?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8'
$env:FINDIT_DB_USER = 'root'
java -jar server/target/find-it-campus-0.0.1-SNAPSHOT.jar   # 18080

# 3) 前端
npm run dev -- --host 127.0.0.1 --port 5173
```

验收脚本（工作区临时目录，不随仓库提交）：`b3-acceptance.mjs`，通过 CDP 驱动 Edge 完成 7 条断言，
每步用 `capture-window.ps1` 抓完整浏览器窗口，结果写入 `run-result.json`。

## 7. 已知限制

- 会话在后端进程内存中，后端重启即失效（B2 已知限制，前端按 401 重新引导，不自行保活）；
- 401 合并窗口为 2s：同一波并发只引导一次；2s 后新的 401 会再次引导，这是刻意保留的可用性行为；
- 「编辑 / 我的启事 / 通知」页面本身不在本任务范围，路由守卫已按其路径预置规则；
- 前端不持久化任何凭证：刷新恢复完全依赖服务端 `HttpOnly` 的 `JSESSIONID`。
