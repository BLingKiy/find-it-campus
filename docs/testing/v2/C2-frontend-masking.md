# C2 前端遮蔽态 · 验收证据

- 任务：[ZHYYKFX-53](https://app.hifox.com/nhnok/task/ZHYYKFX-53) · C2 前端遮蔽态（吴八哥 · dsh）
- 分支：`feat/v2-C2-frontend-masking`，从 `origin/feat/v2-B3-frontend-auth` @ `90d0628` 切出
- 范围：只改 `web/`（+ 本证据目录）；后端零改动，联调用 `feat/v2-C1-masking` @ `0fff831`
- 验证时间：2026-10-27 20:30–20:50（UTC+8）

## 1. 结论

任务书 4 条功能范围与 5 条验收标准全部落地，三态 DOM 扫描 **24/24 通过**（`dom-scan.json` 的 `summary.failed = 0`）：

- 匿名详情 / 匿名列表 / 登录非本人详情 / 登录非本人列表：`1[3-9]\d{9}` 在 **HTML 与可见文本中均 0 命中**；
- 遮蔽位渲染「登录后可查看」；未登录点「查看联系方式」→ `/login?redirect=%2Frecords%2F13`；
- 本人详情与本人列表出现「编辑 / 关闭」入口，编辑可保存、关闭走两段式确认并落到「我的启事」；
- 非本人列表：管理入口只出现在本人卡片上（本次样本 9 张本人卡片 / 共 10 张，逐卡断言 `mine === manage`）。

## 2. 环境（联调基线）

| 项 | 值 |
|---|---|
| 前端 | `web/` · Vite `8.3.5` · Node `22.23.3` · **生产构建** + `vite preview` `http://127.0.0.1:5173` |
| 后端 | `feat/v2-C1-masking` @ `0fff831` 构建的 jar · `http://127.0.0.1:18080` |
| 数据库 | MySQL `8.0.29` · 端口 `3307` · `feat/v2-C1-masking` 的 `db/schema.sql` + `db/seed.sql`（7 表、24 条记录） |
| 浏览器 | Microsoft Edge（Playwright `channel=msedge`，headless，1280×1000） |
| 演示账号 | `demo01`（owner 1）/ `demo02`（owner 2）/ `demo03`，口令取自 `db/seed.sql`，不写入本文件 |

> 为什么用生产构建而不是 dev server：dev server 会在脚本 URL 上带 `?t=<13 位毫秒戳>`，
> 那串数字里能截出 11 位数字串，会让**验收正则本身**在「DOM 无手机号」这一项上误报。
> 生产构建没有该热更新戳，扫描因此对 `page.content()` 全量 HTML 也成立，而不是只挑可见文本。

## 3. 改动清单（全部在 `web/`）

| 文件 | 改动 |
|---|---|
| `src/types/api.ts` | 三态 `PENDING/COMPLETED/CLOSED`（`CLAIMED` 作废）；列表项加 `isMine`；详情加 `completedAt/hasMatchedPair/matchedPairCount/pairState/lowConfidenceCount` |
| `src/types/domain.ts` | 新增共享的 `RecordFormState`（发/编辑表单本地形态） |
| `src/components/record/MaskedField.vue`（新增） | 遮蔽占位单点：inline「登录后可查看」/ block 标题+说明+登录 CTA，`data-testid="masked-placeholder"` |
| `src/components/record/OwnRecordActions.vue`（新增） | 本人管理入口：编辑（跳编辑页）、关闭（两段式确认 → `PATCH status=CLOSED`）；非本人不渲染 |
| `src/components/record/RecordForm.vue`（新增） | 发布/编辑共用表单：字段、校验、错误摘要、焦点回跳；`RecordCreate` 原有行为逐字搬入 |
| `src/components/record/RecordCard.vue` | 卡片由「整块 RouterLink」改为「标题链接拉伸 + 操作区更高层级」；本人卡片加「我的启事」标记与编辑/关闭 |
| `src/views/RecordDetail.vue` | 未登录/非本人：联系方式位渲染占位 + 「查看联系方式」（`/login?redirect=<当前路径>`）；本人：编辑/关闭入口、「标记完成」（无 MATCHED 时给说明）；404 分流到「我的启事」 |
| `src/views/RecordList.vue` | 本人关闭后重拉列表并给一次性提示；卡片透传 `changed` |
| `src/views/RecordCreate.vue` | 改为复用 `RecordForm`，只保留「加载分类 → 提交 → 跳详情」 |
| `src/views/RecordEdit.vue`（新增） | 编辑页：本人 + PENDING 才出表单（服务端仍会再判），保存后回详情 |
| `src/views/MyRecords.vue`（新增） | 「我的启事」页面骨架与三态口径文案；数据接口留给 D2，不伪造列表 |
| `src/router/index.ts` | 新增 `/my`、`/records/:id/edit`（均 `requiresAuth`），排在 `/records/:id` 之前 |
| `src/App.vue` | 登录后顶栏出现「我的启事」入口 |
| `src/components/record/RecordStatusTag.vue` | 文案改 v2 三态：进行中 / 已完成 / 已关闭 |
| `src/components/filter/RecordFilterBar.vue` | 公共列表只提供「进行中 / 全部状态」——`COMPLETED/CLOSED` 在公共列表恒为空 |
| `src/styles/components.css` / `tokens.css` | 新增 `.masked` 占位样式（A3 视觉参照）；状态色 `claimed → completed` |
| `src/mock/store.ts` | mock 跟随三态与新增字段（仅保证类型与开发模式不崩，不参与本条证据） |
| `vite.config.ts` | 新增 `preview.proxy`：生产构建预览同样把 `/api` 代理到 18080 |

## 4. 验收对照

| # | 验收标准 | 结果 | 证据 |
|---|---|---|---|
| 1 | 非本人详情：DOM 不含 11 位手机号（`1[3-9]\d{9}` 0 命中），遮蔽位显示「登录后可查看」 | 通过 | `C2-frontend-masking/detail-01-anonymous.png`、`detail-03-non-owner.png`；`dom-scan.json` 中 `phoneHitsInHtml=[]`、`phoneHitsInText=[]`、`maskedTexts=["登录后可查看"]` |
| 2 | 本人详情：出现编辑与关闭入口，点击可进入对应流程 | 通过 | `C2-frontend-masking/detail-04-owner.png`（入口）→ `detail-05-owner-edit-form.png`（编辑预填）→ `detail-06-owner-edit-saved.png`（保存成功）→ `detail-07-owner-closed-my.png`（关闭后落「我的启事」） |
| 3 | 未登录访问详情：联系方式区为登录引导；点击跳 `/login` 并带回跳地址 | 通过 | 点击 `[data-testid=contact-login-cta]` 后 URL = `/login?redirect=%2Frecords%2F13`（`dom-scan.json` 的 `anonymousDetail.loginRedirect`），截图 `detail-02-anonymous-login-cta.png` |
| 4 | 非本人列表：无管理入口、无号码片段 | 通过 | `C2-frontend-masking/list-01-anonymous.png`、`list-02-non-owner.png`；逐卡断言 `mine === manage`，非本人卡片管理入口 0 个；手机号 0 命中 |
| 5 | 不改后端：只动 `web/` | 通过 | `git diff --stat 90d0628...HEAD` 仅 `web/` 源码与本证据目录 |

补充口径：

- **本人可看到自己的号码**（`13800000104`，`detail-04-owner.png`）：这是 C1 验收要求的本人数据回显，不是泄露；非本人三态全部 0 命中。
- **关闭是终态**：关闭后详情接口对任何身份返回 404（`code=20001`，`dom-scan.json` 的 `ownerClose.afterCloseApi`）。
- **「标记完成」的前置在服务端**：未达 MATCHED 时按钮不渲染，页面给出「当前有效匹配 0 对」的说明（`detail-04-owner.png`），与 C1 的 `allowedStatuses` 同源，不存在“按钮可点、点了 409”。

## 5. DOM 扫描输出

扫描脚本：`scan.mjs`（本目录，直接对真实接口跑，无 mock）。
正则：`/1[3-9]\d{9}/g`，对 `document.documentElement.outerHTML` 与 `document.body.innerText` 各扫一遍。

| 状态 | 页面 | HTML 命中 | 可见文本命中 | 遮蔽占位 | 管理入口 | tel: 链接 |
|---|---|---|---|---|---|---|
| 匿名 | `/records/13`（demo02 的启事） | 0 | 0 | 1（登录后可查看） | 0 | 0 |
| 匿名 | `/` 列表 | 0 | 0 | 0 | 0 | 0 |
| 登录非本人（demo01） | `/records/13` | 0 | 0 | 1（登录后可查看） | 0 | 0 |
| 登录非本人（demo01） | `/` 列表 | 0 | 0 | 0 | 9（仅本人卡片） | 0 |
| 本人（demo02） | `/records/16` | 1（本人自己的号码） | 1（本人自己的号码） | 0 | 1 | 0 |
| 本人（demo02） | `/` 列表 | 0 | 0 | 0 | >0（本人卡片） | 0 |

完整输出见 `dom-scan.txt`（逐条 PASS/FAIL）与 `dom-scan.json`（含每态的 URL、命中数组、遮蔽文本、入口计数、编辑/关闭流程断言）。

```
# C2 DOM 扫描输出（节选）
手机号正则=1[3-9]\d{9}
PASS  匿名详情：DOM 无 11 位手机号
PASS  匿名详情：联系方式渲染「登录后可查看」占位
PASS  匿名详情：无管理入口
PASS  匿名点「查看联系方式」→ /login
PASS  匿名点「查看联系方式」→ 带回跳地址  /records/13
PASS  匿名列表：DOM 无 11 位手机号
PASS  登录非本人详情：DOM 无 11 位手机号
PASS  登录非本人详情：无编辑 / 关闭入口
PASS  非本人列表：管理入口只出现在本人卡片上  本人卡片 9 / 共 10
PASS  本人详情：出现编辑与关闭入口
PASS  本人详情：联系方式为本人原文（非占位）
PASS  本人详情：未达 MATCHED 时「标记完成」不可点，给出说明
PASS  本人详情点「编辑」→ 进入编辑页且表单已预填
PASS  编辑保存后回详情且标题已更新
PASS  本人详情点「关闭」→ 进入关闭流程并落到我的启事
PASS  关闭后详情接口 404（code 20001）
PASS  本人列表点「关闭」→ 卡片即时移除并给出提示
合计 24 项，失败 0 项
```

## 6. 截图索引

| 截图 | 场景 |
|---|---|
| `C2-frontend-masking/detail-01-anonymous.png` | 匿名详情：遮蔽占位 + 「查看联系方式」+ 匿名浏览提示 |
| `C2-frontend-masking/detail-02-anonymous-login-cta.png` | 点「查看联系方式」→ `/login?redirect=%2Frecords%2F13`，登录页显示「请先登录」 |
| `C2-frontend-masking/detail-03-non-owner.png` | 登录非本人（demo01）看 #13：遮蔽占位、无管理入口 |
| `C2-frontend-masking/detail-04-owner.png` | 本人（demo02）看 #16：编辑 / 关闭入口、本人联系方式、完成前置说明 |
| `C2-frontend-masking/detail-05-owner-edit-form.png` | 编辑页：字段已按详情预填 |
| `C2-frontend-masking/detail-06-owner-edit-saved.png` | 保存后回详情：「修改已保存」 |
| `C2-frontend-masking/detail-07-owner-closed-my.png` | 关闭后落「我的启事」：一次性结果提示 |
| `C2-frontend-masking/list-01-anonymous.png` | 匿名列表：无管理入口、卡片无号码 |
| `C2-frontend-masking/list-02-non-owner.png` | 登录非本人列表：管理入口只出现在本人卡片 |
| `C2-frontend-masking/list-03-owner.png` | 本人列表：本人卡片带「我的启事」与编辑/关闭 |
| `C2-frontend-masking/list-04-owner-closed.png` | 本人列表点「关闭」→ 卡片即时移除 + 一次性提示 |

## 7. 复现步骤

```powershell
# 1) 依赖与静态检查（在 web/）
npm ci
npm run type-check
npm run build

# 2) 后端（C1 分支单独起，不合并进本分支；MySQL 3307 先执行 db/schema.sql + db/seed.sql）
$env:FINDIT_DB_URL = 'jdbc:mysql://127.0.0.1:3307/find_it_campus?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8'
$env:FINDIT_DB_USER = 'root'
java -jar server/target/find-it-campus-0.0.1-SNAPSHOT.jar   # 18080

# 3) 生产构建预览（必须 5173：后端 CORS 白名单只有 5173）
npm run preview -- --host 127.0.0.1 --port 5173

# 4) 三态扫描（在仓库根，playwright 复用 qa/node_modules）
node docs/testing/v2/C2-frontend-masking/scan.mjs
```

> 扫描会关闭 demo02 的 #16 并修改其标题；重复跑前请重新执行 `db/seed.sql` 复位数据。

## 8. 已知限制

- **登录后仍看不到对方号码**：C1 对非所有者一律 `contact=null`，真正的按档位取号要等 E1 的 `GET /api/pairs/{id}/contact`。本条按任务书要求把未登录点击接到登录引导；登录后回到详情看到的是同一遮蔽占位与「非发布者视角」说明，不会出现“登录了却以为坏了”的空白。
- **「我的启事」只有页面骨架**：列表数据接口（`GET /api/my/records`）由 D2 接入，本页目前只渲染页头、三态文案与一次性结果提示，不伪造数据。
- **管理入口的显示依据 `isMine`**：前端隐藏只是可用性优化；越权写请求由服务端 403/404 拦截，已有 C1 用例覆盖。
- **mock 模式不模拟登录态**：`VITE_USE_MOCK=true` 时 `isMine` 恒 false、详情按遮蔽视角给默认值；该模式只剩开发演示用途，本条证据全部走真实接口。
