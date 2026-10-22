# UI 与组件基线（4.2 交付物）

> **一句话**：三个页面现在共用一套 token、一套原始类、一套组件语义；五种状态各有一条确定的视觉规则。本文是视觉与交互的事实源——照着它实现，不需要再回来问颜色、间距或组件用法。
>
> **基线**：`feat/4.1-frontend-pages` @ `4f637ea` → 本任务分支 `feat/4.2-ui-baseline`
> **技术栈**：Vue 3.5.44 + Vite 8.3.5 + TypeScript 5.9.3 + vue-router 5.4.0（未新增任何运行时依赖）
> **验证环境**：Windows 11 · Node 22.23.3 · npm 10.9.9 · Edge 155.0.4283.62（Playwright 驱动，headless）

---

## 0. 结论先行

1. **不引 UI 库、不引 Web 字体、不引图标字体**。全站 CSS 27.4 kB（gzip 6.8 kB），列表页首屏 JS+CSS 48.5 kB（gzip）。理由见 §2.4、§5.3。
2. **颜色只有三组**：中性灰（色相向品牌蓝偏，避免发脏）、品牌蓝、语义色（寻物红 / 招领绿 / 状态三色）。组件里**不允许出现十六进制或颜色函数**。
3. **圆角三档各司其职**：控件与按钮 `6px`、面板与卡片 `10px`、徽标 `pill`。按钮不做全圆——全圆按钮配通用阴影是"AI 味"最重的组合之一。
4. **同类元素只写一次**：`.btn` / `.page-head` / `.panel` / `.control` / `.field` / `.badge` / `.alert` / `.empty` / `.skeleton` / `.score` 十个原始类族落在 `styles/components.css`，三页的筛选区、表单、详情卡、提示条现在共用同一套高度、边框和圆角。
5. **五状态各有确定视觉**：默认、加载（骨架）、空（`EmptyState`）、错误（`ErrorBanner` + 重试）、校验失败（字段红框 + `!` 文案 + 页级可点摘要）。矩阵见 §4。
6. **`/api/match` 结果四条一起构成"可读 + 排序可见"**：名次 `1/2/3…`、相似度进度条、百分比、命中原因文案，外加一条明确排序说明（§4.5）。
7. **320px → 1280px 无横向溢出**（18 张截图逐个断言 `scrollWidth === innerWidth`），CLS 实测 0.000–0.004（阈值 0.1），见 §5。
8. **深色模式本期不做**，但已把接入成本压到"只在 `tokens.css` 追加一段同名覆盖"；这是需要人类拍板的待确认项（§8）。

---

## 1. 范围与不变量

| 可以改（4.2 已改） | 不可以改（一直没动） |
|---|---|
| 样式、间距、字号、颜色、圆角、阴影 | 路由表、路由守卫、URL query 语义 |
| 三页的结构与排版（多在 `views/*.vue` 的 template/style） | 校验规则、字段长度上限、时间格式转换 |
| 组件的**外观**与**可选 props** | `api/*.ts` 的任何调用签名与参数 |
| 空态/错误态/加载态的表现 | `VITE_USE_MOCK` 开关语义、`src/mock` 数据 |
| 无障碍属性（`aria-*`、`fieldset/legend`、跳转链接） | 状态机（`allowedStatuses` 由后端给，前端不推导） |

**没有新增第四个页面**，`/records/new`、`/`、`/records/:id`、`/:pathMatch(.*)*` 四条路由原样保留。

---

## 2. 视觉基线

### 2.1 色彩

三组语义，全部集中在 `web/src/styles/tokens.css`。

**中性**（注意：不是纯灰，色相 220° 左右向品牌蓝偏移）

| Token | 值 | 用途 |
|---|---|---|
| `--color-bg` | `#f4f6fb` | 页面底色 |
| `--color-surface` | `#ffffff` | 卡片 / 面板 / 顶栏 |
| `--color-surface-muted` | `#eef1f7` | 禁用态底、标签底、序号底 |
| `--color-surface-sunken` | `#e6eaf3` | 骨架屏底色 |
| `--color-border` | `#e0e5ef` | 面板/卡片描边（装饰性） |
| `--color-border-strong` | `#c6cddc` | ghost 按钮描边 |
| `--color-control-border` | `#8b94a4` | **表单控件专用描边**，白底 3.06:1 |
| `--color-text` | `#1a2033` | 正文 / 标题 |
| `--color-text-muted` | `#5b6478` | 次要信息 |
| `--color-text-subtle` | `#687185` | 12px 辅助信息（时间、编号、页脚） |

**品牌**：`--color-primary #2450d0`、`--color-primary-strong #1a3ca6`（hover/active）、`--color-primary-soft #e7eefc`（选中底）、`--color-primary-softer #f2f6fe`（浅底）、`--color-on-primary #ffffff`。

**语义**：`danger #c2352b`（+ soft `#fdecea`）、`warning #8a5300`（+ `#fdf3e2`）、`success #17784a`（+ `#e6f5ec`）、`info #0b6373`（+ `#e3f2f5`）。

**类型与状态映射**（唯一的映射点在 `RecordTypeTag.vue` / `RecordStatusTag.vue`，别处不许各写一套）

| 语义 | 文字 | 前景 / 底色 |
|---|---|---|
| 寻物 LOST | 寻物 | `--color-type-lost-fg/bg` → danger / danger-soft |
| 招领 FOUND | 招领 | `--color-type-found-fg/bg` → success / success-soft |
| 待认领 PENDING | 待认领 | info / info-soft |
| 已认领 CLAIMED | 已认领 | warning / warning-soft |
| 已关闭 CLOSED | 已关闭 | text-muted / surface-muted |

**对比度（脚本实测，非估算）**

| 配对 | 实测 | 要求 | 结论 |
|---|---|---|---|
| 正文 `#1a2033` / 白面板 | 16.17:1 | 4.5:1 | ✅ |
| 正文 / 页面底 | 14.96:1 | 4.5:1 | ✅ |
| 次要 `#5b6478` / 白面板 | 5.93:1 | 4.5:1 | ✅ |
| 辅助 `#687185` / 白面板 | 4.90:1 | 4.5:1 | ✅ |
| 辅助 `#687185` / 页面底 | 4.53:1 | 4.5:1 | ✅ |
| 主按钮白字 / `#2450d0` | 6.70:1 | 4.5:1 | ✅ |
| 寻物徽标 / danger-soft | 4.78:1 | 4.5:1 | ✅ |
| 招领徽标 / success-soft | 4.88:1 | 4.5:1 | ✅ |
| 待认领 / info-soft | 6.00:1 | 4.5:1 | ✅ |
| 已认领 / warning-soft | 5.76:1 | 4.5:1 | ✅ |
| 已关闭 / surface-muted | 5.24:1 | 4.5:1 | ✅ |
| 错误红字 / danger-soft | 4.78:1 | 4.5:1 | ✅ |
| 控件描边 / 白面板 | 3.06:1 | 3:1（SC 1.4.11） | ✅ |
| 焦点环 / 页面底 | 6.20:1 | 3:1 | ✅ |

> 两个值是被这条检查逼出来的：`--color-text-subtle` 原为 `#78829a`（3.85:1，**不达标**），`--color-control-border` 是新加的一档（原 `--color-border-strong` 只有 1.59:1，画输入框不满足 SC 1.4.11）。

**规则**
- 组件里只写 `var(--…)`；`#fff` 级别的字面量只允许出现在 `tokens.css`。
- 状态永不只用颜色表达：徽标 = 底色 + 文字 + 6px 色点，三通道（§6.3）。
- 新增语义 = 新增 token，不许就地调色。

### 2.2 字号与层级

正文 14px 起（不拿 12px 当正文），四级足够：

| 级 | Token | 值 | 用途 | 行高 |
|---|---|---|---|---|
| 展示 | `--fs-display` | `clamp(22px, 5vw, 30px)` | 页面主标题 `.page-head__title` | 1.25 |
| 标题 | `--fs-2xl` / `--fs-lg` | 24 / 16 | 详情标题 / 卡片标题、面板标题 | 1.25 / 1.4 |
| 正文 | `--fs-md` | 14 | 正文、控件、按钮 | 1.6 |
| 辅助 | `--fs-sm` / `--fs-xs` | 13 / 12 | 说明、元信息、徽标 | 1.4 / 1.25 |

字重只用 400 / 500 / 600 / 700；标题收紧（`letter-spacing: -0.01em`），正文放宽行高。数字一律 `font-variant-numeric: tabular-nums`（分值、时间、页码对齐用）。

### 2.3 间距、栅格、断点

- **间距刻度**：4px 基准，`--sp-1…--sp-8`（4→40），`--sp-9/--sp-10`（48/64）只给大区块。**页内间距只用刻度值**，不写 `13px` 这种。
- **容器**：`--container-max: 1120px`、页面左右 `16px`（≥768 为 `24px`）。
- **阅读宽**：`--content-max: 760px`（详情描述、表单），约 65 字符。
- **断点只有两个**：`768px`（1→2/3 列）、`1120px`（2→3 列）。写在 token 里，`@media` 与 `matchMedia` 共用同一对数值。
- **竖排节奏**：页面 = `page-head` → 主内容 → 次内容，区块间距 `--sp-4/--sp-5`；页面顶部 `--sp-5`（≥768 `--sp-6`）。

**320px 下的实际表现**（已验证）：顶栏只留 `FI` 标；筛选区 2 列 3 行；卡片单列；页码条换行；无横向滚动。

### 2.4 字体

```
--font-sans: system-ui, -apple-system, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', 'Noto Sans CJK SC', sans-serif
--font-mono: ui-monospace, 'SF Mono', 'Cascadia Mono', Consolas, monospace  /* 仅编号/分值 */
```

**不引 Web 字体的理由（这是决策，不是偷懒）**：中文字体子集化后仍有几百 KB，弱网下会直接顶到 LCP 预算；`font-display: swap` 又必然带来一次字体切换重排（CLS）。系统 UI 栈在 Windows / Android / iOS 上都是本地字体，**0 下载、0 抖动**，代价只是三个平台字形略有差异——对课程项目完全可接受。

### 2.5 圆角、阴影、动效

| 项 | 规则 |
|---|---|
| 圆角 | 控件/按钮 `--radius-sm 6px`；面板/卡片 `--radius-md 10px`；徽标/进度条 `pill`；小方块（名次、logo）`--radius-xs 4px` |
| 阴影 | 只有两级：`--shadow-sm`（卡片静置）、`--shadow-md`（卡片 hover）；`--shadow-lg` 预留给弹层，当前未使用 |
| 描边 | 1px；卡片左侧的类型色条用 `inset box-shadow` 实现（不占内宽、不引起 1px 抖动） |
| 时长 | `--dur-fast 120ms`（颜色/边框）、`--dur-base 180ms`（位移/阴影）、`--dur-slow 260ms` 预留 |
| 缓动 | 进入 `--ease-out`（减速）、退出 `--ease-in`、颜色用 `--ease-standard` |
| 减少动效 | 全局 `@media (prefers-reduced-motion: reduce)` 把动画/过渡压到 0.01ms，并关掉骨架屏 sheen；实测分值条 `transition-duration` = `1e-05s` |

**关于"只动 transform/opacity"这条更严的口径——我做了偏离，并说明原因**：

- **零布局动画**：全站没有一处过渡 `width / height / top / left / margin / padding`。分值的进度条宽度刻意**不加** transition（宽度是布局属性），渲染时就定值。
- **允许的绘制类动画**：`background-color`、`border-color`、`color`、`box-shadow`。它们只触发绘制不触发布局，是 hover / 焦点反馈必须的；如果也禁掉，按钮 hover 会变成瞬间跳变，反而更差。
- 位移类效果一律 `transform`：卡片 hover `translateY(-2px)`、骨架 sheen `translateX`、按钮 spinner `rotate`、跳转链接 `translateY`。

**这条偏离写在这里，是为了让下一个人知道它不是疏忽。** 若答辩口径必须严格到"只有 transform/opacity"，删掉 4 处 `transition` 即可，视觉会变硬。

### 2.6 深色模式：本期不做（待确认项）

- `html { color-scheme: light }` 显式声明只做浅色，防止 Chrome 自动深色把原生控件反相成和页面不搭的样子。
- 接入方式已经想好：在 `tokens.css` 末尾追加 `@media (prefers-color-scheme: dark) { :root { … } }`，**同名覆盖** 30 个左右的颜色变量即可，组件与页面零改动。
- 成本：主要是重新过一遍对比度（暗底上的 `--color-text-muted` 必换）与阴影在暗底失效的问题。**需要人类确认是否列入本阶段范围**（§8）。

### 2.7 图标

不引图标字体、不引图标库。目前只有一处"图标"——品牌方块里的 `FI` 文字与 favicon 的内联 SVG data URI。理由：三个页面没有必须用图标才能表达的语义，用文字比用装饰性图标更省也更快。

---

## 3. 组件规格

原始类在 `web/src/styles/components.css`；组件在 `web/src/components/`。**组件内只写布局差异，外观回到原始类**。

### 3.1 按钮

`.btn` + 变体 + 尺寸，`<button>` 与 `<a>/<RouterLink>` 通用。

| 变体 | 用处 | 视觉 |
|---|---|---|
| `.btn--primary` | 页面唯一主操作（发布、提交、开始匹配） | 实底 `#2450d0`，白字 |
| `.btn--ghost` | 次操作（返回、取消、状态流转、筛选重置） | 白底 + 1px 描边 |
| `.btn--danger` | 错误态里的重试 | 白底 + 红描边红字 |
| `.btn--subtle` | 低强调（当前未用，留给文本型操作） | 蓝字无底 |
| `.btn--link` | 错误摘要里的"跳到字段" | 下划线文字按钮，`white-space: normal` 可换行 |

尺寸：默认高 40px、`.btn--sm` 32px（触屏下 `@media (pointer: coarse)` 自动升到 40px）、`.btn--lg` 44px。`.btn--block` 满宽。

状态：`:hover` 换底色/描边；`:active` 用 `inset` 内阴影（不做位移，避免点击时抖）；`:disabled` **不用 opacity**（会把文字对比度一起拉低），改用 `surface-muted` 底 + `text-muted` 字（5.24:1）；`[data-loading]` + `.btn__spinner` 给提交中的转圈。

反例：一屏出现两个 `.btn--primary`；用 `opacity: .6` 表示禁用；给按钮加 `translateY` hover。

### 3.2 表单控件与字段

```html
<div class="field">
  <label class="field__label" for="field-title">
    标题 <span class="field__req">*</span>
    <span class="field__counter">0/64</span>
  </label>
  <input id="field-title" class="control" aria-invalid="true" aria-describedby="field-title-error" />
  <ul id="field-title-error" class="field-error"><li>请填写标题</li></ul>
</div>
```

- `.control`：高 40px、1px `--color-control-border`、`6px` 圆角；`:focus-visible` 用 `box-shadow: var(--ring)` 环（不用 `outline`，圆角处不出缺口）；`[aria-invalid="true"]` 边框转红并换红环。
- `select.control` **保留系统原生箭头**：自绘箭头要内联 SVG 硬编码颜色，跨内核/内嵌 WebView 风险大于收益。
- `textarea.control` 最小高 `2×40`，`resize: vertical`（只允许纵向拉，横向拉会撑破布局）。
- `.radio`：`label` 包住 `input`，整块可点，`:has(input:checked)` 变蓝底蓝框；**一组单选必须用 `<fieldset> + <legend>`**，不能拿 `<label for>` 指向一个 `<div>`（那是无效 HTML，读屏也读不出这组单选在问什么）。
- 表单错误的三条硬规则：
  1. 字段级：`aria-invalid="true"` + `aria-describedby` 指向 `#field-{name}-error`；
  2. 每条错误前面有 `!` 圆点（文字符号，不靠颜色）；
  3. 点过提交后还在表单顶部给一条**页级摘要**（`role="alert"`），列出所有问题并可点回字段——长表单滚到一半看不到红字时，这条是救命的。
- 计数器 `.field__counter` 用 `tabular-nums` + `margin-left: auto` 靠右。

### 3.3 标签 / 徽标

`RecordTypeTag` 与 `RecordStatusTag` 都渲染 `.badge.badge--dot.badge--{type|status}`，**共用同一个原始类**，所以三个页面的徽标高度、内距、字距必然一致。

| props | 类型 | 说明 |
|---|---|---|
| `type` | `'LOST' \| 'FOUND'` | 文案 `寻物 / 招领` 的唯一映射点 |
| `status` | `'PENDING' \| 'CLAIMED' \| 'CLOSED'` | 文案 `待认领 / 已认领 / 已关闭` 的唯一映射点 |

高度 22px、内距 8px、12px/500、色点 6px。**三通道表达状态**：颜色 + 文字 + 圆点，色盲与灰度打印都能读。

### 3.4 加载态

`LoadingBlock.vue`

| props | 默认 | 说明 |
|---|---|---|
| `rows` | `3` | 骨架块数量 |
| `variant` | `'list'` | `list`（卡片网格，跟随 1/2/3 列）/ `detail`（单块）/ `inline`（区内两三行） |
| `label` | `'正在加载'` | 只给读屏（`.sr-only`），视觉上是骨架 |

- 骨架**与真实内容同构**（徽标行 + 标题行 + 两行元信息），高度固定，所以数据回来时不产生 CLS。
- `role="status"` + `aria-busy="true"`；sheen 只动 `transform`，`prefers-reduced-motion` 下不显示。
- 任何页面不许"请求中白屏"——这是 4.1 的验收项，4.2 把它变成唯一实现。

### 3.5 错误态

`ErrorBanner.vue`

| props | 类型 | 说明 |
|---|---|---|
| `error` | `unknown` | 内部调 `describeError()`，组件里不 `switch` 错误码 |
| `title` | `string?` | 默认「加载失败」；提交类传「提交失败」 |
| `retryLabel` | `string?` | 传了就显示重试按钮 |
| `@retry` | `() => void` | 父组件重发**同一个请求** |

- 视觉：`.alert.alert--danger`（红底 + 3px 左边 + 红字），`role="alert"`。
- **每个异步调用都有可行动的失败路径**：列表/详情/匹配/提交都有重试按钮，且重试走的是原来的 `run(...)`，没有新增调用路径。
- 业务空态（`20001` 记录不存在）**不是**错误态，用 `EmptyState` 展示——这两者混淆会让"记录被删了"看起来像"服务挂了"。
- 匹配区的 `30002/30003` 只降级本区（文案「匹配服务暂不可用」），详情主体照常可用。

### 3.6 空态

`EmptyState.vue`

| props | 类型 | 说明 |
|---|---|---|
| `title` | `string` | 一句话结论（`暂无符合条件的启事`） |
| `description` | `string?` | 为什么空 / 当前条件 |
| `actionText` | `string?` | 纯文案建议（动作在别处时用，如"点上方重置筛选"） |
| `actionLabel` | `string?` | 有 `to` 时渲染成按钮 |
| `to` | `RouteLocationRaw?` | 下一步去哪 |

视觉：`.empty`——虚线框、白底、居中、顶部一条 32×3px 的品牌色短横（唯一的装饰，替代"大圆角图标 + 插画"那套 AI 味组合）。空态必须教用户下一步做什么，所以 `to` 优先于 `actionText`。

### 3.7 面板

`.panel`：白底 + 1px `--color-border` + 10px 圆角 + 内距 16（≥768 为 20）。`.panel__head / __title / __desc` 提供标题区。

用它的地方：筛选区、详情正文、提交表单、匹配区。**新增一个"白底带框的块"时必须用它**，不许再写一遍 `border + border-radius + background`——这是三个页面视觉不一致最常见的原因。

### 3.8 相似度分值

`.score` + `data-band`，见 §4.5。

| 元素 | 说明 |
|---|---|
| `.score__bar` | 6px 轨道，`pill` 圆角，底色 `--color-score-track` |
| `.score__fill` | 宽度来自内联 `--score: N%`；颜色来自档位；**无 transition** |
| `.score__value` | `N%`，`tabular-nums`，同档位色 |

分档（**只影响颜色，不参与排序、不改分值**）：`≥70` 高（绿）、`≥40` 中（琥珀）、其余低（灰）。

### 3.9 分页

`PaginationBar.vue`（props `page / pageSize / total`，emit `update:page`）：页码窗口算法保持 4.1 的实现（当前页 ±2 + 首尾 + 省略号），只把外观换成 `.btn.btn--ghost.btn--sm`；当前页用**底色 + 边框 + 字重**三通道，并带 `aria-current="page"` 与 `aria-label="第 N 页"`。

---

## 4. 三页布局与五状态标注

### 4.1 列表页 `/`（`views/RecordList.vue`）

```
header.page-head            启事列表 + 一句话说明 + [发布启事]（窄屏满宽）
RecordFilterBar             类型 / 状态 / 分类 / 关键词 + 重置筛选
div.record-list__body       四选一的状态区
  ├─ ErrorBanner            retry-label="重试"
  ├─ LoadingBlock           variant=list rows=3
  ├─ EmptyState             title + description + actionText + 「去发布一条启事」
  └─ ul.record-list__grid   RecordCard × N（1 / 2 / 3 列）
PaginationBar               仅在有数据时渲染
```

| 状态 | 触发 | 视觉定义 |
|---|---|---|
| 默认 | 有数据 | 卡片网格，左色条按类型着色，徽标行 + 标题 16/600 + 元信息 13 + 时间 12 |
| 加载 | 请求中 | 3 个同构骨架卡，`aria-busy`，不白屏 |
| 空 | `total = 0` | 虚线空态 + "换个筛选条件" + 去发布按钮 |
| 错误 | 网络/服务失败 | 红色提示条 + 重试按钮；筛选区仍可用（换条件会重发） |
| 校验失败 | — | 列表页无表单，不适用（表单页 §4.3） |

### 4.2 详情页 `/records/:id`（`views/RecordDetail.vue`）

```
header.page-head            启事详情 + [← 返回列表]
LoadingBlock variant=detail
EmptyState                  20001 记录不存在（业务空态，不是错误）
ErrorBanner                 其它失败 + 重试
article.panel               徽标行 + 编号 / h2 标题 / dl 七项事实 / 详细描述 / 状态流转
MatchPanel                  匹配区（自带四段状态）
```

- `h1` 固定为「启事详情」，记录标题降为 `h2`——这样五种状态下的标题结构一致，读屏不会在"加载中/不存在"时找不到主标题。
- 事实区断点：2 列（320）→ 3 列（768）→ 4 列（1120）。**刻意不做 7 列**：14px 的 `2026-10-19 17:30:00` 约 146px，7 列会把时间戳挤成两行。
- 状态流转区：有 `allowedStatuses` 时出按钮；**没有时给出说明文字**（原来直接不渲染，用户会以为页面缺了一块）。
- 发布成功（`?created=1`）用 `.alert--success` + `role="status"`，一次性、不重复弹。

| 状态 | 视觉定义 |
|---|---|
| 默认 | 白面板承载全部事实，描述区限宽 760px、行高 1.75 |
| 加载 | 单块详情骨架 |
| 空 | `20001` → 空态 + 回列表按钮（**不显示成错误**） |
| 错误 | 红条 + 重试 + 返回列表仍可用 |
| 校验失败 | 不适用 |

### 4.3 提交页 `/records/new`（`views/RecordCreate.vue`）

```
header.page-head            发布启事 + 必填说明（* 用语义红）
form.panel                  最大宽 760
  ├─ div.alert--danger     校验失败摘要（role=alert，条目可点回字段）
  ├─ fieldset + legend     类型（两个 .radio）
  ├─ .field × 5            标题 / 描述 / 地点 / 发生时间 / 联系方式 / 联系人称呼
  ├─ ErrorBanner           非字段级失败（网络/服务端）→ 「提交失败」+ 再试一次
  └─ actions               [提交启事]（lg，提交中转圈）+ [取消并返回]
```

| 状态 | 视觉定义 |
|---|---|
| 默认 | 白面板、字段竖排、必填 `*` 红、计数器右对齐 |
| 加载 | 提交按钮转圈 + 文案「提交中…」+ 禁用（不是整页骨架：表单内容不该消失） |
| 空 | 不适用（表单本身即是入口） |
| 错误 | 页级红条「提交失败」+ 再试一次；**字段错误不会进这条**，避免同一错误说两遍 |
| 校验失败 | 顶部摘要（"有 N 处需要修改" + 可点条目）+ 字段红框 + `!` 文案 + 焦点落到第一个错误字段 |

字段与校验规则**完全沿用 4.1**（`TITLE_MAX 64 / DESC_MAX 2000 / LOCATION_MAX 128 / CONTACT_MAX 64 / PUBLISHER_MAX 32`，`occurredAt` 允许 5 分钟时钟偏差）。4.2 只把 `maxlength` 对齐到规则值（原来标题写 `80`，与 64 的规则矛盾）。

### 4.4 404 `/:pathMatch(.*)*`（`views/NotFound.vue`）

`page-head`（h1「页面不存在」）+ `EmptyState`（404 · 没有这个页面 / 当前地址 / 回列表）。与其它空态同一套视觉，不再单独一套。

### 4.5 `/api/match` 结果的展示形态

**目标**：给定 mock 数据，相似度与候选信息可读、排序可见。

单条候选（`MatchItemCard.vue`，props `item: MatchItem`、`rank: number`）：

```
┌ ①1 ②招领 ③待认领 ───────────────────── #13 ┐   ① 名次（mono、tabular-nums，aria-hidden：顺序本身已表达）
│ 图书馆拾到黑色钱包                          │   ② 类型徽标  ③ 状态徽标   #id 编号
│ 相似度 ████████████████████ 100%            │   ④ 进度条 + 百分比（值只来自后端 scorePercent）
│ 钱包卡证 · 图书馆三楼自习区 · 发生 2026-10-20 15:00:00 │  ⑤ 分类 / 地点 / 发生时间
│ ┃ 命中关键词「图书」「书馆」「黑色」等 21 个词   │   ⑥ reason，左侧 2px 竖线强调
└──────────────────────────────────────────┘
```

区级文案（`MatchPanel.vue`）：

- 说明行：`按这段描述在候选池里找相似启事，排除本条自身；结果按相似度从高到低排列。`——**排序规则写在界面上**，用户不用猜。
- 结果摘要（`role="status"`）：`共 N 条候选 · 用时 18ms · 引擎：本地关键词匹配 · 阈值 30%`。
- 四段状态：未开始（引导文案）/ 加载（`variant="inline"`）/ 不可用（区级红条 + 重试，不影响主体）/ 结果 / 空（"暂无匹配结果"）。
- 按钮文案随状态变：`开始匹配` → `匹配中…` → `重新匹配`。

**口径**：分值只展示后端的 `scorePercent`，前端不二次计算、不重排（排序与 `total` 都由后端给）；分档只改颜色。列表用 `<ol>`，读屏能听出"第 1 条 / 第 N 条"。

---

## 5. 性能

### 5.1 方法（可复现）

- `vite build --mode <mock|real>` 的**生产产物**，`vite preview` 本地静态服务，Edge 155 headless，DPR 1，冷缓存（每次 `newContext`）。
- 用 `addInitScript` 装 `PerformanceObserver` 采集 `largest-contentful-paint` / `layout-shift`（忽略 `hadRecentInput`）/ `paint`，`navigation` 取 TTFB。
- 每张截图同时断言 `document.documentElement.scrollWidth <= window.innerWidth`（横向溢出）与收集 `console.error` / `pageerror`。
- **限制**：localhost，无真实网络延迟与带宽限制，单次采样不是多轮中位数。这些数字只能说明"渲染链路没有额外阻塞、没有布局抖动"，**不能**当作弱网下的 LCP。

### 5.2 实测（18 个状态，同一轮）

| 截图 | 页面 | LCP | CLS | 横向溢出 | 控制台报错 |
|---|---|---|---|---|---|
| 01 | 列表 1280 | 508ms | 0.000 | 无 | 0 |
| 02 | 列表 320 | 268ms | 0.000 | 无 | 0 |
| 03 | 列表 390 | 236ms | 0.000 | 无 | 0 |
| 04 | 列表·空 | 296ms | 0.000 | 无 | 0 |
| 05 | 详情 1280 | 620ms | 0.000 | 无 | 0 |
| 06 | 详情·匹配结果 | 528ms | 0.000 | 无 | 0 |
| 07 | 详情 390 | 532ms | 0.000 | 无 | 0 |
| 08 | 详情·20001 | 516ms | 0.000 | 无 | 0 |
| 09 | 提交 1280 | 388ms | 0.000 | 无 | 0 |
| 10 | 提交·校验失败 | 232ms | 0.000 | 无 | 0 |
| 11 | 提交 390 | 248ms | 0.000 | 无 | 0 |
| 12 | 404 | 316ms | 0.000 | 无 | 0 |
| 13 | 列表·加载 | 252ms | 0.004 | 无 | 1（见下） |
| 14 | 列表·错误 | 228ms | 0.003 | 无 | 2（见下） |
| 15 | 详情·错误 | 252ms | 0.000 | 无 | 1（见下） |
| 16 | 提交·错误 | 940ms | 0.001 | 无 | 2（见下） |
| 17 | 键盘焦点 | 228ms | 0.000 | 无 | 0 |
| 18 | 减少动效 | 524ms | 0.000 | 无 | 0 |

- **CLS 全部 ≤ 0.004**（阈值 0.1）。16 号 LCP 940ms 包含脚本填表与点击的交互时间，不是首屏。
- LCP 区间 228–620ms（16 号除外）。同一页面两轮之间会有 ±200ms 波动，**单次采样不能当基线**，这里只用于确认"没有额外阻塞"。
- **错误态的"控制台报错"只有浏览器自身的 `Failed to load resource: net::ERR_CONNECTION_REFUSED`**（我故意把 `VITE_API_BASE_URL` 指向一个没人监听的端口来制造失败）。应用自身 `pageerror` 全程 0，没有未处理的 Promise 拒绝。
- **LCP 元素**：列表页 = 页面说明文本；详情页 = 详情描述段落；提交页 = 表单区（浏览器未暴露 element）。没有大图、没有 Web 字体，所以 LCP 就是首屏文字，链路里没有其它可优化的阻塞点。

### 5.3 包体积（构建日志实测）

| 项 | 原始 | gzip |
|---|---|---|
| 全部 JS（9 chunk，真实接口模式） | 126.79 kB | 51.01 kB |
| 全部 CSS（6 chunk） | 27.44 kB | 6.80 kB |
| `index.html` | 0.96 kB | 0.63 kB |
| **列表页首屏需要的那一组**（`index.js` + `index.css` + `RecordList` + `useRequest` + `LoadingBlock` + `EmptyState`） | **130.47 kB** | **48.54 kB** |
| 仅 mock 模式额外带上的 `src/mock` | +11.62 kB | +4.64 kB |

分割策略：三条路由全部懒加载（`RecordList 7.95 / RecordDetail 8.04 / RecordCreate 9.75 kB`）；公共件（`useRequest`、`LoadingBlock`、`EmptyState`）被提取为共享 chunk。**没有**为了"看起来快"去手工 preload 路由 chunk——首屏只需要列表页那一个。

### 5.4 一个具体的 CLS 案例（值得写下来）

第一轮实测里 320px 的 CLS 是 **0.069**，用 `layout-shift` 的 `sources` 定位到**页脚**：内容短的时候 flex 把页脚顶在视口底部，列表数据回来后页脚被推出视口，这次"可见元素位移"被计了分。

修法：给 `main` 一个 `min-height: calc(100dvh - var(--header-height))`，页脚永远在首屏之外，不再随内容长短移动。修完 0.069 → **0.000**。

代价是短内容页面下方会留一块空白（详情页与错误态可见）。这是有意识的取舍：**留白不影响可用性，跳动影响体验指标**。

### 5.5 没做 / 待做

- 没有做图片优化（当前无图片资产），没有做 Service Worker / 离线（PWA 不在本阶段范围）。
- 没有对 `tokens.css` 之外的内联颜色做主题化（当前也不需要）。
- 真机（中端 Android + 4G 限速）未测：**建议用 Chrome DevTools 的 4G/Slow 4G 预设 + CPU 4× 降速复核一次**，指标看 LCP 与 INP（本阶段没有 INP 长任务来源：无虚拟滚动、无逐步输入重算）。

---

## 6. 无障碍

### 6.1 键盘路径（实测 Tab 顺序）

1. `跳过导航` 跳转链接（聚焦时从顶部滑出，直接跳到 `#main`）→
2. 品牌链接 → 3. `启事列表` → 4. `发布启事` → 5. `+ 发布启事` → 6. 筛选控件（类型/状态/分类/关键词/重置）→ 7. 每张卡片（`RouterLink`，整卡可点、`Enter` 生效）→ 8. 分页按钮（带 `aria-current`）。

- 焦点环全局 `:focus-visible` 2px + 2px offset，从不 `outline: none`（仅鼠标点击时隐藏）。
- `main` 带 `tabindex="-1"` 作为跳转落点，且该元素聚焦时不显示焦点环（它不是控件）。
- 弹层/下拉一律用原生元素（`<details>` 未使用，也没有自定义弹窗），所以不需要手写焦点陷阱——**这是刻意的：不写焦点陷阱就不会写错**。

### 6.2 读屏

- 地标：`header` / `nav[aria-label="主导航"]` / `main#main` / `footer`。
- 标题层级：列表页 `h1 → h2`（每张卡片）；详情页 `h1 启事详情 → h2 记录标题 → h3 详细描述 → h2 匹配区`；提交页 `h1 发布启事`。**没有跳级**。
- 表单：`label[for]` ↔ `id` 全部配对；单选组用 `fieldset/legend`；错误用 `aria-invalid` + `aria-describedby` 关联到 `#field-{name}-error`；校验失败摘要 `role="alert"`。
- 加载：`role="status"` + `aria-busy="true"` + `.sr-only` 文案（"正在加载启事列表，请稍候"）。
- 匹配结果：结果摘要 `role="status"`；候选列表是 `<ol>`，顺序天然可读。
- 徽标：文字 + 色点 + 底色，不依赖颜色。

### 6.3 对比度

见 §2.1 的实测表，全部达到 WCAG 2.1 AA（正文 4.5:1、控件边界与焦点环 3:1）。两处是被检查逼着改的（`--color-text-subtle`、`--color-control-border`）。

### 6.4 动效

`prefers-reduced-motion: reduce` 下全局动画/过渡降到 0.01ms，骨架 sheen 直接 `display: none`，分值条无 transition。实测分值条 `transition-duration = 1e-05s`。

### 6.5 还没解决

- **没有跑过自动化 axe 审计**（不引新依赖）。上面每一条都是手工核对 + DOM 断言，不是审计分数。
- **页面标题（`document.title`）不随路由变化**，读屏切换页面时听不到"现在是详情页"。建议加 `meta.title` 或路由守卫里改 `document.title`（一行代码，但属于行为改动，留给人类决定）。
- 状态流转按钮点击后只有按钮禁用，**没有 `aria-live` 播报"状态已更新"**；成功后详情会重新拉取，视觉上状态徽标变化足够，但读屏用户可能不知道发生了。建议后续补一条 `role="status"`。
- 表单错误摘要里点条目会 `scrollIntoView` + 聚焦，实测可用；但 `focus({preventScroll:true})` 后再 `scrollIntoView` 的顺序在 Safari 上需要复核（未测 Safari）。

---

## 7. 落地清单（给 4.1 与后续 Agent）

新增任何界面元素前，按顺序自问：

- [ ] 这个东西是不是已经有一个原始类了？（`.panel` `.control` `.field` `.badge` `.alert` `.empty` `.btn` `.score`）
- [ ] 我写的颜色/字号/间距/圆角，是不是都来自 `var(--…)`？有没有出现十六进制？
- [ ] 它有没有加载 / 空 / 错误三个状态？（表单再加一个校验失败）
- [ ] 点得进去吗？键盘能到吗？`Enter` 有用吗？焦点环看得见吗？
- [ ] 320px 下会不会横向滚动？长标题/长地点会不会把卡片撑破？
- [ ] 状态是不是只靠颜色表达？有没有文字或形状做第二通道？
- [ ] 动的东西是不是只有 `transform` 和颜色/阴影？有没有动 `width/height/top/left`？
- [ ] 异步失败时用户能做什么？有没有一条"重试"或"回退"的路？
- [ ] 新文案是给用户看的词，还是系统的词？（例：「标记为已认领」而不是「PATCH status=CLAIMED」）

---

## 8. 待确认项与已知缺口

| # | 事项 | 现状 | 需要谁定 |
|---|---|---|---|
| 1 | **深色模式是否进入本阶段范围** | 未做。已按"只改 `tokens.css` 同名覆盖"设计好接法（§2.6） | 人类。若要做，请明确：只做自动跟随系统，还是要一个手动切换（后者要引入一份跨页偏好状态，会触碰 `frontend-baseline.md §3.2` 的"加 Pinia 触发条件"） |
| 2 | **品牌色是否批准从 `#2f6fed` 改为 `#2450d0`** | 已改（为了把白字对比度做到 6.7:1 并统一三页）。改回只需改 `tokens.css` 一处 + `index.html` 的 favicon 与 `theme-color` | 人类 |
| 3 | 按钮圆角从 pill 改为 6px | 已改（与控件/面板同族） | 人类 |
| 4 | `document.title` 不随路由变化 | 未做（行为改动） | 人类 / 4.1 收口时一并 |
| 5 | 状态更新没有 `aria-live` 播报 | 未做 | 后续小任务 |
| 6 | 自动化 a11y 审计（axe）与真机性能 | 未做 | 需要引依赖 / 需要真机，超出范围 |

**已知缺口（不影响验收，但要记着）**：
- 详情页与错误态页面下方会有一块空白（§5.4 的取舍）。
- `src/mock` 仍在，`VITE_USE_MOCK` 开关未动；3.1 后端就绪后按原计划整目录删除。
- 匹配结果的 `reason` 文案质量取决于后端（mock 用的是 2-gram 命中词，会出现「图书」「书馆」这类碎词），**这不是 UI 层能修的**。

---

## 9. 变更记录（4.1 → 4.2，文件级）

| 文件 | 改动要点 |
|---|---|
| `styles/tokens.css` | 重写：中性色改为向品牌偏、新增 `control-border / skeleton / score / ring / control-height / ease / content-max` 等 40 余个 token；全部保留旧变量名，4.1 代码不受影响 |
| `styles/components.css` | **新增**：`.btn / .control / .field / .field-error / .radio / .panel / .badge / .alert / .empty / .skeleton / .score / .page-head` 十个原始类族 |
| `styles/base.css` | reset + 排版 + `.sr-only` / `.skip-link` / `prefers-reduced-motion` / `100dvh` / 长串换行；`.btn` 移到 components.css |
| `styles/utilities.css` | 未改（仍 10 个以内） |
| `main.ts` | 增加 components.css 的引入（顺序：tokens → base → components → utilities） |
| `App.vue` | 跳转链接、当前页高亮（`router-link-active` + `aria-current`）、页脚安全区、`main` 最小高度（CLS 修复） |
| `views/RecordList.vue` | `page-head` / 状态区 / 网格断点 / 空态带动作 / 错误带重试 |
| `views/RecordDetail.vue` | h1 固定、事实区 2/3/4 列、无流转时给说明、成功提示、错误重试 |
| `views/RecordCreate.vue` | 统一 `.control`、`fieldset/legend`、`aria-invalid` + `aria-describedby`、页级错误摘要、`maxlength` 对齐规则；**修掉一个既有缺陷**：网络失败时 `fieldErrors` 为空，`focusFirstError` 会抛 `TypeError`（表现为"点提交没反应"） |
| `components/common/*` | `EmptyState` 加动作、`ErrorBanner` 加重试、`LoadingBlock` 改骨架、`FieldError` 固定 id |
| `components/record/*` | 卡片：类型左色条、hover 只动 transform、标题层级 h2；两个 Tag 改为共用 `.badge` |
| `components/filter/*` | 筛选区改用 `.field/.control`（手机 2 列）、分页复用 `.btn--sm` |
| `components/match/*` | 名次 + 分值进度条 + 排序说明 + 区级状态与重试 |
| `views/NotFound.vue` | 与其它空态统一 |
| `index.html` | favicon / `theme-color` 跟随新品牌色；补 `description` |

**新增依赖：0**。`package.json` 未改。

---

## 10. 证据

- 18 张界面截图（1280 / 390 / 320，含五状态、键盘焦点、减少动效）见任务评论附件。
- `npm run type-check`、`npm run build` 输出见任务评论。
- 采集脚本与原始指标（`metrics.json`）在本地 `.tools/shots/`，**未提交**（不属于产品代码，且会污染仓库）；如需复跑，需要 Node 22 + 本机 Edge。
