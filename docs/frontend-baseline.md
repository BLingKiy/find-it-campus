# 前端结构基线（1.2 交付物 4）

> 服务对象：2.1 前端骨架（吴八哥）、4.1 三页面（功能）、4.2 UI 还原（视觉）。
> 技术栈已固定 Vue 3 + Vite，本文只定结构与约定，不选 UI 库、不选 SSR。

---

## 1. 结论先行

1. **不引入 Pinia**：当前唯一跨页状态是"分类字典"，用模块级缓存解决；筛选与分页写进 URL query（URL 即状态）。理由与补加条件见 §3（ADR-010）。
2. **不引入 UI 组件库**（Element Plus 等）：三页面 + 一个表单，组件库的主题覆盖成本高于其省时收益，且 4.2 要还原自定义视觉基线（ADR-011）。
3. **请求层单点**：`src/api/http.ts`（原生 fetch 封装，约 60 行）+ `src/api/*.ts` 按资源分模块 + `src/types/api.ts` 与 API 契约逐字段对齐。页面里不出现 `fetch`。
4. **TypeScript 建议用**，但取决于你更熟 TS 还是 JS → **Q-16 待拍板**。本文所有签名以 TS 写；若选 JS，同结构、去掉类型注解并保留 JSDoc。

---

## 2. 路由与页面清单

| # | path | name | 组件 | params / query | 数据来源 | 说明 |
|---|---|---|---|---|---|---|
| 1 | `/` | `RecordList` | `views/RecordList.vue` | query：`type` `status` `categoryId` `keyword` `page` `pageSize` `sortBy` `sortOrder`（全部可选） | `GET /api/records` | **列表页 = 首页**。不做 `/records` 与 `/` 两个入口，避免分享链接两种写法 |
| 2 | `/records/:id` | `RecordDetail` | `views/RecordDetail.vue` | params：`id`（number） | `GET /api/records/{id}` + 页内可触发 `POST /api/match` | 详情页，含"帮我找找相关启事"匹配区 |
| 3 | `/records/new` | `RecordCreate` | `views/RecordCreate.vue` | query：`type`（可选，预选 LOST/FOUND 单选） | `GET /api/categories` + `POST /api/records` | 提交页。用 `/records/new` 而非 `/add`，与 REST 风格一致 |
| 4 | `/:pathMatch(.*)*` | `NotFound` | `views/NotFound.vue` | — | — | 空态兜底，一行文案 + 回首页 |

路由基线细节：
- `path` 用 kebab-case；`name` 用 PascalCase；懒加载：`component: () => import('../views/RecordList.vue')`（三页都懒加载，骨架屏由页面自己处理）。
- **`/records/new` 必须声明在 `/records/:id` 之前**，并且 `RecordDetail` 挂载时校验 `route.params.id` 是正整数、否则跳 `NotFound`。两个动作都要做：Vue Router 按静态段优先排序能救第一条，但手输 `/records/abc` 仍会进详情页并带一个非数字 id 去打接口，换来 `10001`——那是后端的事，前端不该让用户看到。
- 全局前置守卫：只校验 `page`/`pageSize` 是正整数（非法值归一为默认），不在守卫里发请求。
- 列表 → 详情：`router.push({name:'RecordDetail', params:{id}})`；**筛选条件写进 URL，因此详情页点浏览器后退能回到原筛选结果**（这是不用 Pinia 的关键收益之一）。
- 提交成功：`router.replace({name:'RecordDetail', params:{id}})`（`replace` 防止"后退"再进一次空表单）。

---

## 3. 状态管理（回答"是否需要 Pinia"）

### 3.1 分类的四种状态，各自的落点

| 状态类型 | 例子 | 放哪里 | 为什么 |
|---|---|---|---|
| 组件局部 | 表单输入值、提交中标志、匹配区 loading | `ref/reactive` 在组件内 | 无跨组件消费者 |
| URL 可序列化 | 筛选条件、页码、排序 | **route query** | 可分享、可后退、可刷新，比 store 更便宜且不会不一致 |
| 服务端数据 | 列表、详情、匹配结果 | 组件内的 `data/error/loading` 三件套（一个 `useRequest` 组合式函数，见 §5.3） | 单一事实源在服务端，前端不自建缓存层（除分类字典） |
| 跨页客户端 | 分类字典 | `src/api/categoryCache.ts` 模块级缓存（约 12 行，含并发去重） | 只读、几乎不变、体积小；一个 `Map` + Promise 复用足够 |

### 3.2 决策与放弃的东西

- **不引入 Pinia**：省下 store 目录、`defineStore` 心智模型、devtools 配置；代价是分类缓存要自己写 12 行。
- **补加 Pinia 的触发条件**（写清楚，便于将来不争吵）：出现①跨页表单草稿保留，②多步向导，③需要全局订阅的用户偏好（如主题）。届时**只建一个** `useRecordDraftStore`，其他状态继续留在 URL 与组件内。这条是**可逆决策**（加 Pinia 不动现有代码，只替换 `categoryCache` 的一个调用点）。

---

## 4. 目录结构（2.1 照此建，占位文件都要有）

```
web/
  index.html
  vite.config.ts              # 端口 5173、/api 代理、alias '@' → src
  tsconfig.json  tsconfig.node.json
  package.json
  .env.example                # 提交（VITE_API_BASE_URL=/api）
  .env.local                  # 不提交（gitignore 需追加）
  src/
    main.ts                   # createApp + router，仅此两件事；不装插件
    App.vue                   # 布局壳：顶部栏 + <RouterView/> + 全局页脚
    router/index.ts           # §2 路由表，一个文件，不拆
    api/
      http.ts                 # fetch 封装：超时、解包 ApiResponse、抛 BizError
      records.ts              # listRecords/getRecord/createRecord/updateRecord/patchStatus/deleteRecord
      match.ts                # match()
      categories.ts           # listCategories()
      categoryCache.ts        # 分类字典缓存（并发去重 + 手动失效）
      __smoke__/              # 可选：契约冒烟脚本，5.1 用
    types/
      api.ts                  # 与 docs/api-contract.md 一一对齐的接口与枚举
      domain.ts               # 展示层的派生类型（如 RecordListItemVM）
    views/
      RecordList.vue  RecordDetail.vue  RecordCreate.vue  NotFound.vue
    components/
      record/  RecordCard.vue  RecordStatusTag.vue  RecordTypeTag.vue
      filter/  RecordFilterBar.vue  PaginationBar.vue
      match/   MatchPanel.vue  MatchItemCard.vue
      common/  EmptyState.vue  ErrorBanner.vue  LoadingBlock.vue  FieldError.vue
    composables/
      useRequest.ts           # data/error/loading + run，页面唯一的请求时序入口
      useRouteQueryState.ts   # query ↔ 本地 ref 双向同步（列表页筛选专用）
    styles/
      tokens.css              # 设计变量（色/字号/间距/圆角/阴影/断点）——4.2 的事实源
      base.css                # reset + 全局排版
      utilities.css           # ≤10 个工具类（.u-flex .u-gap…），禁止膨胀成第二套框架
    assets/                   # 图标 svg（内联优先）
  public/
```

规则：
- **`components/` 只放被 ≥2 处使用，或单文件超过 ~150 行需要隔离的东西**；否则留在 `views/` 里。挡住的变化是"页面级一次性组件泛滥"，代价是页面文件稍长。
- 组件名不加 `The`/`Base` 前缀体系（课程项目不值得这套约定），按域前缀：`Record*`、`Match*`、常见件用裸名（`EmptyState`）。
- `styles/` 是唯一允许写全局 CSS 的地方；组件内一律 `<style scoped>`。

---

## 5. API 请求层约定

### 5.1 `src/types/api.ts`（必须与契约同 commit 修改；字段名/可空性以 `docs/api-contract.md` 为准）

```ts
export type RecordType = 'LOST' | 'FOUND'
export type RecordStatus = 'PENDING' | 'CLAIMED' | 'CLOSED'
export type SortBy = 'createdAt' | 'occurredAt'
export type SortOrder = 'asc' | 'desc'
export type MatchEngineKind = 'LOCAL_KEYWORD' | 'EXTERNAL_EMBEDDING'

export interface ApiResponse<T> { code: number; message: string; data: T | null; requestId: string }
export interface PageResult<T> { page: number; pageSize: number; total: number; totalPages: number; hasNext: boolean; list: T[] }

export interface RecordListItem { id: number; type: RecordType; status: RecordStatus; title: string;
  categoryId: number | null; categoryName: string | null; location: string; occurredAt: string; createdAt: string }

export interface RecordDetail extends RecordListItem { description: string; contact: string | null;
  publisherName: string | null; updatedAt: string; allowedStatuses: RecordStatus[] }

export interface RecordCreatePayload { type: RecordType; title: string; description: string;
  categoryId?: number | null; location: string; occurredAt: string; contact?: string | null; publisherName?: string | null }

export interface RecordQuery { type?: RecordType; status?: RecordStatus | 'ALL'; categoryId?: number;
  keyword?: string; page?: number; pageSize?: number; sortBy?: SortBy; sortOrder?: SortOrder }

export interface MatchPayload { text: string; type?: RecordType; categoryId?: number;
  topN?: number; minScore?: number; excludeRecordId?: number | null }
export interface MatchItem { recordId: number; score: number; scorePercent: number; reason: string;
  type: RecordType; status: RecordStatus; title: string; categoryName: string | null; location: string;
  occurredAt: string; createdAt: string }
export interface MatchResult { matchId: number; engine: MatchEngineKind; inputType: RecordType | null;
  topN: number; minScore: number; tookMs: number; total: number; items: MatchItem[] }

export interface CategoryItem { id: number; code: string; name: string; sortOrder: number; active: boolean }
export interface FieldErrorItem { field: string; reason: string }
```

### 5.2 `src/api/http.ts` 签名（实现约 60 行，2.1 落骨架、4.1 填细节）

```ts
export class BizError extends Error {
  code: number; requestId: string | null; fieldErrors: FieldErrorItem[]; status: number
}
export function request<T>(path: string, init?: {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  query?: Record<string, string | number | boolean | null | undefined>   // null/undefined 自动丢弃
  body?: unknown
  timeoutMs?: number                                                      // 默认 8000；match 传 12000
}): Promise<T>
```

行为契约（页面可依赖，不许各自变通）：
1. `baseURL` 取 `import.meta.env.VITE_API_BASE_URL ?? '/api'`，**代码里不出现 `localhost:8080`**。
2. `AbortController` 超时；网络失败/超时抛 `BizError{code:-1,status:0}`，`message` 用固定中文"网络请求失败，请稍后再试"。
3. 解包：`code===0 && 2xx` ⇒ resolve `data`；否则抛 `BizError`（携带 `code/message/fieldErrors/requestId`）。**页面层永远看不到 `ApiResponse` 外层**，也就永远不会忘了判 `code`。
4. HTTP 与 code 的映射文案在 §9（api-contract.md）那张表里，前端错误展示按它分流，不在组件里 switch 数字——统一走 `describeError(err): string`（`src/api/http.ts` 导出）。
5. 不自动重试任何接口（含幂等的 GET）：无缓存层、无写接口，重试只让演示时序更难解释。若人类要求 GET 失败静默重试 1 次，加在 `request` 内部一处（可逆）。

### 5.3 `useRequest`（把"请求时序"收在一个地方，防四类页面各写一套 loading）

```ts
const { data, error, loading, run } = useRequest(listRecords, { immediate: false })
```
- 必须处理的状态：`loading`（骨架/占位）、`error`（`ErrorBanner` + `requestId`）、空数组（`EmptyState`）。**任何页面不许出现"请求中白屏"**——这是 4.1 的验收项之一。
- 竞态：`run` 内部丢弃过期响应（记 sequence id），列表页快速切筛选时不会渲染旧结果。这条比"引入 Pinia"重要得多，别搞反优先级。

---

## 6. 组件清单（每个都写清它挡住什么变化，供 4.2 对齐）

| 组件 | props / emits | 挡住的重复 | 出现处 |
|---|---|---|---|
| `RecordCard` | `item: RecordListItem` | 卡片视觉只改一次 | 列表页 |
| `RecordStatusTag` | `status: RecordStatus` | 状态→颜色/文案的映射表集中一处 | 列表、详情、匹配卡片 |
| `RecordTypeTag` | `type: RecordType` | 寻物/招领的措辞与配色 | 同上 |
| `RecordFilterBar` | `v-model:query: RecordQuery`（不含 page） | 筛选表单与 URL 同步逻辑 | 列表页 |
| `PaginationBar` | `page,pageSize,total` / `update:page` | 页码窗口算法（当前页 ±2） | 列表页 |
| `MatchPanel` | `seedText, excludeRecordId` / 内部自调 `POST /api/match` | 匹配区三段状态（未开始/结果/不可用） | 详情页、列表页顶部入口（可选） |
| `MatchItemCard` | `item: MatchItem` | 分数展示口径（用 `scorePercent`，**不自己算**） | MatchPanel 内 |
| `EmptyState` | `title, description?, actionText?` | 所有空态一致 | 列表、匹配、404 |
| `ErrorBanner` | `error: unknown`（内部调 `describeError`） | 错误文案与 requestId 展示一致 | 三页 |
| `LoadingBlock` | `rows?, height?` | 骨架屏样式一致 | 三页 |
| `FieldError` | `errors: FieldErrorItem[], field: string` | 后端 `fieldErrors` → 输入框下方红字 | 提交页、详情页表单 |

匹配区 UI 的硬要求（写给 4.1/4.2）：`30002/30003` 时**只降级匹配区**（显示"匹配服务暂不可用"），详情页主体照常可用；整页报错会让演示看起来崩了。

---

## 7. 样式约定（4.2 UI 还原对齐用）

- **变量优先**：一切颜色/字号/间距/圆角/阴影走 `styles/tokens.css` 的 CSS 变量，组件内**不写死十六进制**（`#fff` 只允许出现在 token 文件里）。挡住的变化：4.2 调配色时不改组件，只改 token。
- 命名：BEM-lite（`.record-card__title`、`.is-active`），不引入 CSS Modules / Tailwind / Sass。理由：Vite 原生 CSS 能力够用，多一套预处理对一人项目是净成本。**若你更熟 Tailwind，这是可换项 → Q-17**（换它要同步改 4.2 基线）。
- 响应式：移动优先，断点仅两个（`--bp-md: 768px`、`--bp-lg: 1120px`），写在 token 里由 `matchMedia` 或 `@media` 使用；列表页在窄屏单列，详情页表单纵向排列。
- 无障碍基线（成本很低、答辩可讲）：表单控件必须有 `<label for>`；状态标签除颜色外**必须带文字**（不靠色分状态）；可点击卡片用 `<button>`/`<a>` 而非 `div @click`；焦点环不全局清除。
- 图标：优先内联 SVG（`assets/icons/`），不引图标字体库。

---

## 8. 构建 / 运行 / 环境变量

```bash
cd web
npm install            # Node 22.x LTS（本机实测 v22.23.3 / npm 10.9.9）
npm run dev            # http://localhost:5173 ，/api 代理到 http://localhost:8080
npm run build          # 产物 dist/
npm run preview        # 本地验证构建产物
npm run type-check     # vue-tsc --noEmit（若选 TS）
```

`vite.config.ts` 基线（2.1 照抄）：

```ts
export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    port: 5173,
    proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } }
  },
  build: { outDir: 'dist', sourcemap: false }
})
```

| 变量 | 默认 | 说明 |
|---|---|---|
| `VITE_API_BASE_URL` | `/api` | 一般不改；若前端直连后端 8080 则设 `http://localhost:8080/api`，此时依赖后端 CORS（架构基线 §9.4） |

- 前端**不读任何密钥**（外部模型 Key 只在后端）。若将来需要前端直连模型服务，那属于范围变更，须先报人类。
- 生产形态（若答辩需要打包演示）：`npm run build` 后把 `dist/` 交给 `server` 静态目录或由 Nginx 反代同源；本课程版**不实现**，README 写一句"演示用 dev server"。

---

## 9. 版本（来源：`npm view`，查询于 2026-10-21；「实际锁定」列由 2.1-A 依据构建通过的 `web/package-lock.json` 回填）

| 包 | latest | 基线选择 | 实际锁定（2026-10-21） |
|---|---|---|---|
| vue | 3.5.44 | ^3.5.x | 3.5.44 |
| vite | 8.3.5 | ^8.x | 8.3.5 |
| @vitejs/plugin-vue | 6.0.10 | ^6.x | 6.0.10 |
| vue-router | 5.4.0 | ^5.x | 5.4.0 |
| pinia | 4.1.0 | **不引入**（§3） | 未引入 |
| typescript | 7.0.2（原生编译器线） | **建议锁 5.x**：`vue-tsc` 3.3.13 与 TS 7 的组合未在本项目验证，2.1 建骨架时以 `npm create vue@latest` 解析到的稳定组合为准，并把实际版本写进 README；升级 TS 7 另立任务 | 5.9.3（配套 vue-tsc 3.3.13） |

> 这些是"当前 npm latest"，不是"验证过的组合"。2.1 的验收要求构建通过，实际锁定版本以 2.1 生成并构建成功的 `package-lock.json` 为事实源，回头把版本号补进本表。（已回填：vue 3.5.44 / vite 8.3.5 / vue-router 5.4.0 / @vitejs/plugin-vue 6.0.10 / typescript 5.9.3 / vue-tsc 3.3.13，Node v22.23.3 实测 `npm run build` 与 `npm run type-check` 通过。）
