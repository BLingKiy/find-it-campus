# 校园失物招领平台（find-it-campus）程序设计文档

| 项 | 内容 |
|---|---|
| 文档版本 | v1.0 |
| 对应代码基线 | `21b033d`（`feat/5.1-integration` @ `21b033d`，含 DEF-01/DEF-02 修复与 REG-01 回归；`main` 仍停在 `6c68c01`，合并待人类放行） |
| 适用仓库 | https://github.com/BLingKiy/find-it-campus |
| 编写日期 | 2026-10-22 |
| 编写 | 文通通（文档 Agent），内容取自各阶段交付物与仓库实际代码 |
| 交付形态 | `docs/design/program-design.md`（源）+ `docs/design/program-design.docx`（可提交附件）；导出方式见 §9.3 |
| 配图 | `docs/design/diagrams/*.mmd`（Mermaid 源）+ 同名 `.png` / `.svg` |

**这份文档只写仓库里能验证的东西。** 每个字段名、接口路径、表名都可以按 §9.2 的自查记录回到源码行核对；本期没有做的能力（如外部嵌入模型）在文中明确标注为「未落地」，不写成「已支持」。

**阅读路径**：答辩前看 §1 与 §7；评审查实现看 §3–§6；复现看 §8 与附录 C。

---

## 1 项目背景与选题难度

### 1.1 要解决的问题

校园里每天都发生「丢了东西」和「捡到东西」这两件事，目前靠三类渠道传递：微信群、表白墙、宿舍楼下的纸质启事。三种渠道的共同缺陷是**信息不聚合**：

- 失主在群里刷屏式询问，招领信息却在另一个群；
- 纸片启事只在原地可见，跨校区无效；
- 即使两边都发了，也没有任何机制把「丢黑色钱包」和「捡到黑色钱包」这两条联系起来。

结果是最该发生的匹配依赖运气。本项目把这条链路做成一个可运行的系统：**发布启事 → 检索筛选 → 按描述语义找匹配 → 走完状态流转**。

### 1.2 系统做什么

一个前后端分离的 Web 应用，三个页面、九个接口、一个可替换的语义匹配能力：

| 能力 | 用户看到的样子 | 对应实现 |
|---|---|---|
| 发布启事 | 「发布启事」页填八个字段，提交后跳到该条详情 | `POST /api/records` |
| 浏览与筛选 | 列表页按类型/分类/关键词筛选，URL 记住筛选条件，可翻页 | `GET /api/records` |
| 查看详情 | 详情页展示全部字段、当前状态，以及「当前可迁往的状态」按钮 | `GET /api/records/{id}` |
| 状态流转 | 待领 → 已领 → 关闭，非法流转被拒绝 | `PATCH /api/records/{id}/status` |
| 语义匹配 | 详情页输入一段自然语言描述，返回相似记录与百分比 | `POST /api/match` |
| 分类字典 | 提交页与筛选器的下拉数据 | `GET /api/categories` |

### 1.3 选题难度自述（对标评分项「选题难度 20」）

难度不是靠堆技术栈，而是由四个**必须同时成立**的约束构成：

1. **三张表的数据模型**。表数被父任务固定为三张（第四张须先报人类），因此每张表都要承担复合职责：`t_record` 一张表同时承载寻物与招领（`type` 区分），`t_category` 既是下拉数据又要充当匹配的加权词表，`t_match_result` 既是评估证据又要能反查历史候选。少一张就缺证据链，多一张就超范围。
2. **前后端分离 + 契约先行**。前端与后端由不同执行者并行开发，接口契约（`docs/api-contract.md`）是唯一事实源。这在工程上带来一组真实约束：字段命名两侧不同（DB `snake_case` / JSON `camelCase`）、错误码要在传输层与业务层双轨表达、分页与排序参数不能各写一套。
3. **AI 语义匹配且必须可替换**。评分项「技术先进性 20」看的不是模型多大，而是**接口是否独立、实现能否替换**。本项目把匹配能力收敛到 `MatchEngine` 一个接口后，用配置值切换实现，并把「可替换」写成一条会失败的测试（`KeywordEngineSwappabilityTest`），而不是一句 PPT 话术。
4. **无登录体系下的边界设计**。明确不做权限体系，意味着写接口对任何访客开放、联系方式可被任何人看到、重复提交无法去重。这些不是遗漏，而是在需求阶段就记录、在设计中给出缓解手段（逻辑删除、状态机收口、前端 pending 禁用）的取舍。

补充难度在于**协作形态**：一人课程项目，但实际由多个 Agent 流水线交付（需求、架构、后端、AI、前端、UI、测试、文档），代码只能经仓库流转，`main` 由人类合并。这带来额外的工作量：每个阶段都要留下可核对证据，任何「我觉得做完了」都不算数。§7 记录了这个过程真实发生的问题。

### 1.4 范围与明确不做

| 做 | 不做（父任务明确 Out of Scope） |
|---|---|
| 三页主流程、九接口、三张表 | 微服务、独立 AI 服务进程 |
| 记录 CRUD、状态流转、逻辑删除 | 登录、权限体系 |
| 分类字典与筛选 | 消息推送、管理后台 |
| 本地关键词语义匹配 + 可替换接口 | 图片/附件上传与对象存储 |
| 接口测试、匹配效果评估、前端走查 | 移动端原生壳、深色模式（本期） |

### 1.5 与评分项的对应

| 评分项 | 分值 | 本文对应章节 | 证据 |
|---|---|---|---|
| 选题难度 | 20 | §1.3、§3.1、§4.1 | 三张表 ER 图、模块边界图、匹配接口 |
| 程序实现的完整性 | 30 | §3–§6、§8 | 九接口全通、三页黄金路径、落库取证 |
| 技术先进性 | 20 | §6 | 接口抽象 + 装配方式 + 可替换性测试 |
| 文档与视频 | 30 | 本文 + §7 过程记录 | 本文件、`docs/testing/`、`qa/reports/` |

> 说明：分值权重引自课程实施方案，本文只说明对应关系，不预测得分。

---

## 2 需求说明

### 2.1 功能清单与验收判据

需求清单取自 1.1（`ZHYYKFX-3`，37 条编号，按 A–G 七组组织；`R-28` 拆成 `R-28` / `R-28b` 两行，故表内共 38 行）。下表是完整清单的**压缩版**：验收判据保持「给定…当…则…」的可判定句式，删去的是重复的背景描述，判定条件本身没有放宽。

#### A 组 · 列表页

| 编号 | 功能 | 验收判据 | 落点 |
|---|---|---|---|
| R-01 | 列表渲染记录卡片 | 给定后端已启动且有种子数据，当打开列表页，则卡片数量与接口返回条数一致，每张卡片显示标题/类型/地点/发生时间/状态，点击跳转详情并携带记录标识 | 4.1 |
| R-02 | 按类型筛选 | 给定同时存在两类记录，当选择「失物」，则结果每条 `type=LOST`；选择「全部」，则两类都出现 | 3.1 + 4.1 |
| R-03 | 关键词搜索 | 给定输入只出现在某条标题中的词，当触发搜索，则命中该条且不含完全无关记录；输入不存在的词则显示空态而非报错 | 3.1 + 4.1 |
| R-04 | 分页控件 | 给定总记录数大于每页条数，当点击下一页，则列表整体更换且页码 +1；停在最后一页时再点下一页不发起请求 | 3.1 + 4.1 |
| R-05 | 默认倒序 | 给定两条发布时间不同的记录，当打开第一页，则较晚的一条在前；跨页时第二页首条不晚于第一页末条 | 3.1 + 4.1 |
| R-06 | 空结果与失败提示 | 给定查询无结果，则显示空状态文案；给定后端未启动，则显示失败提示且页面框架仍在，无未捕获异常 | 4.1 + 4.2 |

#### B 组 · 详情页

| 编号 | 功能 | 验收判据 | 落点 |
|---|---|---|---|
| R-07 | 详情展示全部字段 | 给定一条种子记录的标识，当打开详情页，则类型/标题/描述/地点/发生时间/联系方式/状态/发布时间均与库中一致，无未渲染字段 | 3.1 + 4.1 |
| R-08 | 状态展示 | 给定分别处于三种状态的记录，当打开各自详情，则状态文案与实际一致，不出现第四种取值 | 4.1 |
| R-09 | 记录不存在处理 | 给定不存在的标识，则显示「记录不存在或已删除」与返回列表入口，不渲染空白字段、不抛未捕获异常 | 3.1 + 4.1 |

#### C 组 · 提交页

| 编号 | 功能 | 验收判据 | 落点 |
|---|---|---|---|
| R-10 | 表单字段齐全 | 给定打开提交页，则类型（二选一）/标题/描述/地点/发生时间/联系方式控件齐全；选择「招领」提交后详情类型为招领 | 4.1 |
| R-11 | 必填校验 | 给定标题留空，当点击提交，则不发起请求且字段下方出现提示；绕过前端直接发缺标题请求，则返回校验失败且不写库 | 3.1 + 4.1 |
| R-12 | 成功反馈与落库 | 给定合法数据提交成功，则出现成功提示，且该条记录可在列表与详情查到（真实写库） | 3.1 + 4.1 |
| R-13 | 成功后跳转 | 给定提交成功，当跳转完成，则落在详情页；浏览器后退不会再次触发提交请求 | 4.1 |
| R-14 | 失败提示 | 给定后端未启动，则显示失败提示且已填内容不丢失；服务端校验失败信息落到对应字段而非通用「提交失败」 | 4.1 |

#### D 组 · 后端业务规则

| 编号 | 功能 | 验收判据 | 落点 |
|---|---|---|---|
| R-15 | 状态定义与初始值 | 给定一次合法新建，则状态为 `PENDING`；任意记录的状态只可能是 `PENDING`/`CLAIMED`/`CLOSED` | 3.1 |
| R-16 | 状态流转 | 给定 `PENDING` 记录，当置为 `CLAIMED`，则详情返回 `CLAIMED`；给定 `CLOSED` 记录，当尝试迁出，则返回 409 + `20002`，不静默成功 | 3.1 |
| R-17 | 删除与下架 | 给定一条记录被删除，则列表不再出现且不占用总条数，按标识查详情返回 `20001`，而数据库物理行仍存在 | 3.1 |
| R-18 | 列表空结果 | 给定匹配不到任何记录的筛选参数，则 `list=[]`、`total=0`、HTTP 2xx，响应中不含错误描述 | 3.1 |
| R-19 | 详情不存在 | 给定不存在的标识，则返回约定错误码 `20001` 与可读信息，服务端日志无未捕获异常堆栈 | 3.1 |
| R-20 | 服务端字段校验 | 给定请求缺任一必填字段，则返回 `10001` 且 `fieldErrors` 指明具体字段名；超长同理 | 3.1 |
| R-21 | 演示种子数据 | 给定执行一次初始化脚本，则记录数不少于 20 条且两类三状态均覆盖；正例输入能命中目标记录 | 2.1 + 5.1 |

#### E 组 · `/api/match` 语义匹配

| 编号 | 功能 | 验收判据 | 落点 |
|---|---|---|---|
| R-22 | 输入形态 | 给定只带描述文本的请求，则返回候选列表且不报参数缺失；文本为空则返回 `30001` 而非空结果或 500 | 3.1 |
| R-23 | 返回条数上限 | 给定不传条数参数，则返回条数不超过默认上限；传入超过上限的值则 `10001`，不返回超限条数 | 3.1 |
| R-24 | 相似度口径 | 给定任意结果，则分值落在 `[0,1]` 且带百分比字段；前端展示 83% 与响应 0.83 一致 | 3.1 + 4.1 |
| R-25 | 无匹配表现 | 给定与库中记录完全无关的描述，则返回成功 + 空数组；前端显示空状态文案而非错误提示 | 3.1 + 4.1 |
| R-26 | 不可用时降级 | 给定引擎不可用，则返回明确错误码且无未捕获异常；前端提示「匹配服务暂不可用」，页面其余功能可用 | 3.1 + 4.1 |
| R-27 | 接口与实现解耦 | 给定接口定义，当把实现从一种换成另一种，则调用方代码与请求/响应契约均无改动 | 3.2 |
| R-28 | 效果自测样例集 | 给定样例集中的每条正例，目标记录出现在结果中且排序在前列；负例不产生高分误报 | 3.2 + 5.1 |
| R-28b | 匹配的前端入口 | 给定在详情页输入描述并触发，则候选以列表展示且每条带相似度，一次触发只发一次请求 | 4.1 |

#### F 组 · 交付材料

| 编号 | 功能 | 验收判据 | 落点 |
|---|---|---|---|
| R-29 | 程序设计文档 | 给定接口清单与 ER 图，当与代码/建表脚本逐条比对，则路径、方法、字段名、表名全部对得上 | 6.1（本文） |
| R-30 | 接口测试报告 | 给定报告中每个接口，当按样例重放，则响应与记录一致；任一失败项都有后续结论 | 5.1 |
| R-31 | 匹配效果评估 | 给定评估记录，则写明样本数、判定阈值、命中与未命中条数；未命中案例有原因归类 | 5.1 |
| R-32 | 答辩 PPT | 给定标为「已完成」的表述，当与测试报告或代码比对，则能找到证据；找不到证据的改写为「计划中」 | 6.2 |
| R-33 | 录屏脚本与成片 | 给定脚本，当按它操作一遍，则每一步都能执行，无找不到入口的步骤 | 6.2 |
| R-34 | 开发过程证据链 | 给定材料中任一阶段结论，当回溯，则能在 commit 历史或任务记录中找到对应时点的证据 | 本文 §7.2、§7.3 |

#### G 组 · 工程基线

| 编号 | 功能 | 验收判据 | 落点 |
|---|---|---|---|
| R-35 | 前端骨架可启动可构建 | 给定一次全新克隆，当执行 README 的安装与启动命令，则开发服务可访问；构建命令成功退出并产出 `dist/` | 2.1 |
| R-36 | 后端骨架可启动可构建 | 给定一次全新克隆与可用 MySQL，当按 README 启动，则应用启动成功并能响应健康检查；构建成功退出 | 2.1 |
| R-37 | 建表脚本与初始化 | 给定空数据库，当执行脚本，则三张表被创建且脚本可重复执行；查询表清单只有三张，无第四张 | 2.1 |

### 2.2 角色与使用场景

系统没有登录，只有一种角色：**访客**。所有读接口对访客开放，所有写接口同样开放（这是已声明的风险，不是遗漏）。三个典型场景：

| 场景 | 起点 | 路径 | 结束状态 |
|---|---|---|---|
| 失主找东西 | 列表页 | 筛选「招领」→ 打开详情 → 输入描述点「开始匹配」→ 查看相似记录 → 联系发布者 | 线下联系，无需系统操作 |
| 拾到者发布 | 提交页 | 填表 → 提交 → 跳详情 → 可继续手工标记状态 | 记录 `PENDING` |
| 物品归还 | 详情页 | 打开自己的启事 → 点「标记为已认领」→ 状态变 `CLAIMED` → 事后「标记为已关闭」 | 记录 `CLOSED` |

### 2.3 需求与实现的偏差（照实登记）

1.1 的默认假设与最终实现有五处不一致（表末另列一条经核对**没有**偏差的项，用作对照）。**每一处都不是实现写错，而是下游在契约或人类口径上做了更具体的决定**；列在这里，避免读者拿 1.1 的句子去核对代码时误判。

| # | 需求侧（1.1 默认假设） | 实现侧 | 原因与出处 |
|---|---|---|---|
| D-1 | 提交表单六个字段全必填 | `contact`、`publisherName` 可选；新增 `categoryId`、`publisherName` 两个字段 | 联系方式是否必填属 Q-01，未拍板前取最宽松；分类是匹配加权的重要输入。见 `docs/api-contract.md` §5.1 |
| D-2 | `topN` 默认 10、上限 20 | 默认 5、上限 20 | 契约 §8.1 取更保守的默认值，避免首屏返回过多弱相关结果 |
| D-3 | 提交成功后页面直接展示匹配结果 | 只在详情页提供「开始匹配」按钮 | R-28b 的入口位置由 Q-13 决定，实现取「详情页」单入口，避免提交后自动发起一次额外请求 |
| D-4 | 排序固定倒序，不提供切换 | 支持 `sortBy ∈ {createdAt, occurredAt}` 与 `sortOrder` | 契约 §1 用白名单实现，成本低；前端默认仍为 `createdAt DESC` |
| D-5 | 「删除」即「下架」语义 | 拆成两件事：`CLOSED` 状态 = 下架（仍可在 `status=ALL` 下查到），`deleted_at` = 删除（任何读接口不可见） | ADR-006：两者语义不同，混用会让「下架后还能改回来」与「删除不可恢复」无法区分 |
| D-6 | 状态流转「已领/关闭不可回退」 | 一致：`PENDING → CLAIMED → CLOSED`，`CLOSED` 为终态 | 无偏差，列此说明该条已按需求实现，见 `RecordStatusPolicy` |

### 2.4 需求变更与拍板记录

| 日期 | 事项 | 决定 | 影响 |
|---|---|---|---|
| 2026-10-21 | `/api/match` 课程版实现选型（Q-01） | 本地关键词实现 + 文档化替换路径；外部嵌入模型另立任务 | 3.2 只交付本地实现，本文 §6.4 写切换成本 |
| 2026-10-22 | `10005` 的 HTTP 口径（三处文档/代码互相矛盾） | 方案 A：枚举固定映射 500，依赖不可用（DB DOWN）由 `HealthController` 显式覆写 503，body `code` 仍为 10005 | 改基线 §6.2 注脚；`docs/api-contract.md` 三处无需改 |
| 2026-10-22 | `t_match_result.input_type` 是否可空 | 放宽为可空（`NULL` = 不限类型） | 契约 §8.1 允许不带 `type`，§8.5 又要求每次成功都写快照，原 `NOT NULL` 下二者无解；属放宽方向，ALTER 见 `db/schema.sql` 附 2 |
| 2026-10-22 | 4.2 三项视觉选择 | 品牌色 `#2450d0`、按钮圆角 6px、本期不做深色模式 | 影响三页一致性断言（5.1 已按新值复验 9/9） |
| 2026-10-22 | 3.2 外部实现本期是否落地 | 不落地；只认本地实现 + 文档化替换路径 | `docs/testing/5.1-*.md` 不评估 external 效果，不因此判 3.2 不通过 |

---

## 3 总体架构

### 3.1 架构决策：模块化单体

**结论：Spring Boot 单进程 + 按能力分包 + MyBatis 显式 SQL + 匹配能力以接口隔离。** 三条理由：

1. **规模输入不支持拆分**。项目没有并发量与数据量指标（架构基线 A7 按 ≤1 万条估算，属经验区间）。微服务、缓存、消息队列的成本由部署与联调支付，收益要规模才显现。
2. **评分点在「接口解耦」而不是「架构复杂度」**。技术先进性 20 分看的是 `/api/match` 与实现可替换，一个 Java 接口 + 一个 `@ConditionalOnProperty` 就能拿到，不需要独立进程。
3. **三张表恰好覆盖三个所有权**：记录（record）、主数据（category）、评估证据（match）。

**放弃的东西**：将来若真要拆服务，`t_record` 与 `t_match_result` 的读写归属要重新划分。

### 3.2 系统上下文与容器

![系统上下文与容器图](diagrams/architecture-container.png)

| 容器 | 职责 | 技术 | 端口 |
|---|---|---|---|
| 前端 SPA | 三个页面、筛选与分页写进 URL、请求封装与错误提示 | Vue 3.5.44 + Vite 8.3.5 + TypeScript 5.x + vue-router 5.4.0 | 5173 |
| 后端应用 | 九个接口、统一响应体、错误码、状态机、匹配编排 | Spring Boot 3.5.16 + MyBatis starter 3.0.5，JDK 17 | 18080 |
| 数据库 | 三张表的持久化 | MySQL 8.0.x（实测 8.0.29） | 3306 |
| 外部嵌入服务 | **本期未落地**，仅保留配置位与装配位 | — | — |

> 端口取 18080 而不是 8080：开发机上 8080 已被另一个常驻服务占用，与其让每个执行环境各配一次，不如把仓库默认值直接对齐（`docs/architecture-baseline.md` §9.2）。

### 3.3 模块边界与依赖方向

![后端模块边界与依赖图](diagrams/architecture-modules.png)

四个包，依赖方向单向：

```
web → service → (mapper | engine 接口)
common ← 所有层（单向，common 不反向依赖业务包）
match  → record.mapper（只读候选池与回表）   record  ✗→  match
category ✗→ 任何业务上下文
禁止：controller → mapper；service → controller；任何包 import engine 实现类
```

| 上下文 | 职责 | 数据所有权 | 核心不变量 |
|---|---|---|---|
| **record** | 记录生命周期：发布、查询、修改、状态流转、删除 | `t_record` | `type ∈ {LOST, FOUND}`、`status ∈ {PENDING, CLAIMED, CLOSED}`；状态只能按 §5.5 表迁移；逻辑删除记录对所有读接口不可见 |
| **match** | 输入自由文本 → 返回候选与相似度，并落一份可评估快照 | `t_match_result` | 引擎输出 `score ∈ [0,1]`；结果按分数倒序、条数 ≤ `topN`；返回前必须经 record 回表校验合法性；快照 `impl` 与实际执行引擎一致 |
| **category** | 分类主数据（编码、名称、匹配词表、排序、启用位） | `t_category` | `code` 唯一；分类不可物理删除，只能停用 |
| **common** | 统一响应体、错误码、异常出口、Web 配置、引擎装配 | 无 | 任何 controller 不得自定义响应包裹；任何错误必须映射到 `ErrorCode` |

**为什么按能力分包，而不是顶层 `controller/service/mapper` 三个桶**：挡住的变化是「新增一个能力」——只增一个包，不动已有包的 import 关系；顶层分层会把新能力摊到三个桶里，改动面 ×3。代价是初次阅读要适应一次，这在 §7 的交付中被实际验证：3.2 只在 `engine/local` 增一个类 + 一处装配，`MatchService` 与 `MatchController` 零 diff。

### 3.4 技术选型与理由

| 决策点 | 选了什么 | 放弃的备选 | 理由 |
|---|---|---|---|
| 后端框架版本 | Spring Boot 3.5.16（JDK 17） | Boot 4.1.1 | 3.x 与课程资料、MyBatis starter 3.0.x 的兼容表最短；升 4.x 只改 `pom.xml`，属可逆 |
| 持久层 | 原生 MyBatis（接口 + XML） | MyBatis-Plus、JPA | SQL 全在仓库里，能讲执行计划；不引「版本对照表」风险 |
| 迁移工具 | 无（`db/schema.sql` 手工执行 + 提交说明附 ALTER） | Flyway / Liquibase | 三张表、一人改、周期短；引入迁移目录与启动期行为是净负担 |
| 前端状态管理 | 不引 Pinia，筛选状态写 URL | Pinia | 唯一的跨页状态是分类字典，用一个模块级 Promise 缓存即可；URL 承载筛选额外得到「刷新保留、可分享、前进后退可用」 |
| HTTP 封装 | 原生 `fetch` 封装（`src/api/http.ts`） | axios | 只有一个用途（base URL + 解包 + 超时），axios 的拦截器带不来收益 |
| 样式方案 | 手写 CSS 变量 + 十个原始类族（`components.css`） | Element Plus 等组件库 | 自定义视觉基线会与组件库主题冲突；三页规模不需要组件库 |
| 匹配实现 | 本地关键词（bigram + IDF + 类目词表），接口隔离 | 先做外部 embedding | 零外部依赖、可离线演示、结果确定可复现；接口先立，换实现只改装配 |
| 架构形态 | 模块化单体 | 微服务 | §3.1 |

### 3.5 前后端分离与接口分层

**物理分离**：`web/` 与 `server/` 两套独立构建，唯一通信方式是 `/api/**` 的 JSON 请求。开发期浏览器请求 `/api`，由 Vite 代理转发到 18080，浏览器视角同源；后端同时配置了白名单 CORS 作为兜底（`allowCredentials=false`，不用 `*`）。

**契约先行**：`docs/api-contract.md` 是唯一事实源。任一方需要改字段，顺序固定为「先改契约 → 再改 DDL/DTO/TS 类型 → 三处同一 commit」。这条规则在阶段 3 被真实使用过一次：`input_type` 可空同时改了 `schema.sql`、契约 §8.2/§8.5、基线 §7.3（见 §2.4）。

**后端四层 + 一条实现层**：

| 层 | 允许 | 禁止 |
|---|---|---|
| Controller | 参数绑定、`@Valid`、调 service、映射 Response DTO | 写业务规则、注入 Mapper、返回实体、手拼 `ApiResponse` |
| Service | 业务规则与不变量、事务边界、编排引擎调用、排序字段白名单 | 依赖 HTTP 类型、把实体当出参、跨聚合开一个事务改多张表 |
| Mapper | 单表 CRUD、显式分页 SQL、LIKE 检索 | 业务分支、用 `${}` 拼接请求值 |
| Engine | 纯文本打分/排序，消费候选池 | 访问 `t_record` 之外的表、知道 HTTP 概念 |
| Domain | 与表字段一一对应 | 承载校验注解、出现在响应体 |

**统一响应体**：所有接口（含错误、404、405）返回同一外壳：

```json
{ "code": 0, "message": "ok", "data": {}, "requestId": "6f1c2b9e" }
```

**双轨错误表达**：HTTP 状态码由 `ErrorCode.httpStatus` 单一映射，业务分支只看 `code`。好处是前端能按状态码分流（4xx 归用户、5xx 归系统）又按 `code` 出精确文案；代价是两处表达同一事实，靠「`ApiResponse.success/fail` 唯一构造入口 + `GlobalExceptionHandler` 唯一失败出口」约束。

**前端分层**：`api/`（`http.ts` 解包与 `BizError`，`records.ts`/`categories.ts`/`match.ts` 各自封装）→ `composables/`（`useRequest` 收敛 loading/error 时序，`useRouteQueryState` 把筛选写回 URL）→ `views/` + `components/`。页面不直接 `fetch`，也不各自实现 loading。

### 3.6 部署与运行形态

开发期两个进程：后端 18080（`./mvnw spring-boot:run`）、前端 5173（`npm run dev`）。生产打包不在本项目范围（课程演示用 dev server + 生产构建产物本地 `preview`）。

后端无状态：不存 session、不落本地文件，因此可多实例；这条不是为扩展准备，而是避免「换个端口就登不上」这类演示事故。
## 4 数据库设计

### 4.1 ER 图

![三张表 ER 图](diagrams/er.png)

图只画主键、外键与起约束作用的列（把 31 个字段全塞进一张图会让字号小到不可读），完整字段清单见 §4.3。

关系只有两条外键，第三条是逻辑关系：

| 关系 | 基数 | 外键行为 | 说明 |
|---|---|---|---|
| `t_category` → `t_record` | 1 : N | `ON DELETE RESTRICT` | 分类被记录引用时不可删；可空（允许「说不上是哪一类」） |
| `t_category` → `t_match_result` | 1 : N | `ON DELETE SET NULL` | 分类停用/删除不影响历史快照可读 |
| `t_match_result.candidates` → `t_record.id` | 逻辑 | 无外键 | 候选存在 JSON 列里，无法建外键；已删除记录由「回表过滤」吸收，前端不会看到死链 |

### 4.2 三张表的职责与不变量

| 表 | 所有权 | 存在理由 | 关键不变量 |
|---|---|---|---|
| `t_record` | record | 业务主表，一张表同时承载寻物与招领 | `type`、`status` 取值受限（CHECK）；`deleted_at IS NOT NULL` 的记录对所有读接口不可见；状态只能按 §5.5 迁移；标题/描述/地点有长度下限与上限（CHECK） |
| `t_category` | category | 分类字典，同时充当本地匹配的加权词表 | `code` 唯一；不可物理删除，只能 `is_active=0`；停用分类不出现在下拉，但历史记录仍保留其名称 |
| `t_match_result` | match | 每次 `/api/match` 成功调用写一行（含空结果），是 5.1 评估与录屏的证据链 | `impl` 与实际执行引擎一致；`candidates` 与响应 `items` 同构但**不含展示字段**；写失败不影响已返回的响应 |

### 4.3 字段说明

#### `t_record`（记录主表）

| 列 | 类型 | 约束 | 用途 |
|---|---|---|---|
| `id` | BIGINT UNSIGNED | PK, AUTO_INCREMENT | 对外 URL 中的资源 id |
| `type` | VARCHAR(8) | NOT NULL, CHECK IN ('LOST','FOUND') | 寻物 / 招领；列表筛选与候选池过滤的依据 |
| `status` | VARCHAR(16) | NOT NULL DEFAULT 'PENDING', CHECK IN ('PENDING','CLAIMED','CLOSED') | 待领 / 已领 / 关闭；状态筛选与流转校验 |
| `title` | VARCHAR(64) | NOT NULL, CHECK 长度 2–64 | 列表卡片与详情标题；匹配的次要文本 |
| `description` | TEXT | NOT NULL, CHECK 长度 2–2000 | 详情正文；**匹配的主文本** |
| `category_id` | BIGINT UNSIGNED | NULL, FK → `t_category.id`, RESTRICT | 分类筛选、提交页下拉、匹配加权 |
| `location` | VARCHAR(128) | NOT NULL, CHECK 长度 1–128 | 详情展示；本地实现的地点加分项 |
| `occurred_at` | DATETIME | NOT NULL | 业务时间（丢失/发现），非提交时间 |
| `contact` | VARCHAR(64) | NULL | 联系方式，格式由 DTO 层限制（数字/字母/中文与 `-_()`） |
| `publisher_name` | VARCHAR(32) | NULL | 联系人称呼；无登录体系，由提交人自填 |
| `deleted_at` | DATETIME | NULL | 逻辑删除时间，非空即删除 |
| `created_at` | DATETIME | NOT NULL DEFAULT CURRENT_TIMESTAMP | 发布时间，列表默认排序键 |
| `updated_at` | DATETIME | NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE | 最后修改时间 |

#### `t_category`（分类字典）

| 列 | 类型 | 约束 | 用途 |
|---|---|---|---|
| `id` | BIGINT UNSIGNED | PK | 提交与筛选用它 |
| `code` | VARCHAR(32) | NOT NULL, UNIQUE | 稳定标识（如 `WALLET`），前端做图标映射不因改名而变 |
| `name` | VARCHAR(32) | NOT NULL | 展示文案 |
| `keywords` | VARCHAR(255) | NULL | 本地匹配扩展词表，空格分隔；`NULL` 表示只靠 `name` |
| `sort_order` | SMALLINT | NOT NULL DEFAULT 0 | 展示顺序，小的在前 |
| `is_active` | TINYINT(1) | NOT NULL DEFAULT 1 | 1 可选 / 0 停用 |
| `created_at` / `updated_at` | DATETIME | NOT NULL | 时间戳 |

> `keywords` **不出现在 `/api/categories` 的响应里**：它是后端匹配实现的内部资料，暴露给前端会形成「前端顺手用上」的隐性耦合。

#### `t_match_result`（匹配快照）

| 列 | 类型 | 约束 | 用途 |
|---|---|---|---|
| `id` | BIGINT UNSIGNED | PK | 响应体 `data.matchId` |
| `input_text` | TEXT | NOT NULL | 原始输入，用于复现评估 |
| `input_type` | VARCHAR(8) | NULL, CHECK 为 NULL 或 LOST/FOUND | 本次「要找的记录类型」；不传时如实记 NULL，不伪造 |
| `input_category_id` | BIGINT UNSIGNED | NULL, FK → `t_category.id`, SET NULL | 本次传入的分类参数 |
| `impl` | VARCHAR(32) | NOT NULL, CHECK IN ('LOCAL_KEYWORD','EXTERNAL_EMBEDDING') | 产出该结果的引擎标识；跨实现对比与分组统计的维度 |
| `top_n` | SMALLINT | NOT NULL | 请求参数留痕 |
| `min_score` | DECIMAL(6,4) | NOT NULL, CHECK 0–1 | 请求参数留痕 |
| `candidates` | JSON | NOT NULL | `[{recordId,score,reason}]`，与响应 `items` 同构但不含展示字段 |
| `cost_ms` | INT | NULL | 引擎耗时（毫秒） |
| `created_at` | DATETIME | NOT NULL DEFAULT CURRENT_TIMESTAMP | 调用时间 |

### 4.4 索引与查询路径

| 索引 | 列 | 服务的查询 |
|---|---|---|
| PRIMARY | `t_record.id` | 详情、状态流转、回表 |
| `idx_record_list` | (`type`, `status`, `deleted_at`, `created_at`) | 列表主查询：三个等值条件 + 按 `created_at` 免排序；同时覆盖匹配候选池取数 |
| `idx_record_category` | (`category_id`, `deleted_at`, `created_at`) | 分类筛选 |
| `ft_record_search` | FULLTEXT(`title`, `description`) WITH PARSER ngram | 关键词检索与本地匹配取数加速（**可选**，非 MySQL 8 或内核无 ngram 时可删掉该行，接口契约不变） |
| `uk_category_code` | `t_category.code` | 分类编码唯一 |
| `idx_category_active_sort` | (`is_active`, `sort_order`) | 下拉与筛选取启用分类并按序返回 |
| `idx_match_result_impl_created` | (`impl`, `created_at`) | 按实现分组统计 |
| `idx_match_result_created` | `created_at` | 时序查询 |

**已知薄弱点**：`/api/match` 不带 `type` 时，候选池查询的首列条件缺位，`idx_record_list` 用不上，会退化为较大范围扫描。当前不为此加第二条索引（无实测依据）；若 `EXPLAIN` 显示超过 1 秒，再补 `idx_record_status_created (status, deleted_at, created_at)`——加索引可逆。

**排序稳定性**：`DATETIME` 取秒精度，同一秒内创建的记录排序不稳定，因此所有分页排序一律追加 `id DESC` 兜底。

### 4.5 设计取舍

| 取舍 | 选择 | 代价 | 为什么可接受 |
|---|---|---|---|
| 两类启事同表 | `type` 枚举区分 | `type` 参与所有查询与索引；将来字段分叉要拆表 | 字段几乎同构，代码量约省一半；拆表属不可逆变更，届时先报 |
| 分类做成字典表 | 独立表 + `code` | 多一次 JOIN、占一张表名额 | 用 ENUM 则每次调整分类都要 DDL + 数据迁移 |
| 候选存 JSON | 一张表搞定 | 无外键、不能索引、SQL 级聚合难 | 评估样本几十条量级，读 JSON 够用；需要聚合才考虑第四张表 |
| 逻辑删除 | `deleted_at` | 每条查询都要带 `deleted_at IS NULL`，忘带即 bug | 快照的历史引用不会因删除而失真 |
| 无迁移工具 | 手工执行 schema + 提交说明附 ALTER | 靠人守约定 | 三张表、一人改；周期内只有一次结构变更（`input_type`） |

---

## 5 接口设计

### 5.1 通用约定

| 项 | 约定 |
|---|---|
| Base path | `/api`（不带版本段，单一前端消费者） |
| 编码 | UTF-8，`Content-Type: application/json` |
| 字段命名 | JSON `camelCase`；DB `snake_case`，由 `mapUnderscoreToCamelCase` 桥接 |
| 时间格式 | `yyyy-MM-dd HH:mm:ss`，本地时间无时区（与 `DATETIME` 精度对齐，避开 JS 时区偏移） |
| 分页请求 | `page`（默认 1，≥1）、`pageSize`（默认 10，1–50） |
| 分页响应 | `page`、`pageSize`、`total`、`totalPages`、`list`、`hasNext` |
| 排序 | `sortBy ∈ {createdAt, occurredAt}`（默认 `createdAt`）、`sortOrder ∈ {asc, desc}`（默认 `desc`）；同值追加 `id DESC` |
| 枚举 | 后端只回 `LOST`/`FOUND`、`PENDING`/`CLAIMED`/`CLOSED` 原值，中文文案由前端一处映射 |
| 空结果 | `200` + 空数组，不是 404、不是错误码 |
| 鉴权 | 无（项目范围明确不做权限体系，写接口对任何访客开放） |

#### 错误码表

| code | HTTP | 含义 | 触发场景 | `data` |
|---|---|---|---|---|
| 0 | 200 | 成功 | — | 业务数据 |
| 10001 | 400 | 参数校验失败 | `@Valid` 未过、必填缺失、超长、枚举非法、分页/排序非法 | `fieldErrors[{field,reason}]` |
| 10002 | 413 | 请求体过大 | 超过 `spring.codec.max-in-memory-size` | null |
| 10003 | 404 | 资源不存在 | 路径不存在（通用兜底） | null |
| 10004 | 405 | 方法不支持 | 路由存在但动词错 | null |
| 10005 | 500（默认）；DB 不可用时由 `HealthController` 覆写 503 | 未预期异常 / 依赖不可用 | 兜底出口；响应体不含堆栈，只含 `requestId` | null（`/api/health` 例外） |
| 20001 | 404 | 记录不存在或已删除 | 详情/修改/状态/删除 | null |
| 20002 | 409 | 状态流转非法 | 违反 §5.5 流转表 | `{current, allowed}` |
| 20003 | 409 | 当前状态不可执行该操作 | `CLOSED` 记录被 PUT | null |
| 30001 | 400 | 匹配输入无效 | `text` 空白或长度不在 2–2000 | `fieldErrors` |
| 30002 | 503 | 匹配引擎不可用 | 引擎未装配 / 外部服务失败 | `{engine}` |
| 30003 | 504 | 匹配引擎超时 | 超过读超时 | `{engine}` |
| 40001 | 404 | 分类不存在 | 提交/筛选/匹配带了不存在的 `categoryId` | null |
| 40002 | 409 | 分类停用不可选 | 提交时指向 `is_active=0` 的分类 | null |

### 5.2 接口清单

九个接口，逐条与 `server/src/main/java/com/findit/campus/*/web/*Controller.java` 的注解对照过（核对记录见 §9.2）。参数级明细见 `docs/design/api-list.md`。

| # | 方法与路径 | 用途 | 主要参数 | 响应与错误码 |
|---|---|---|---|---|
| 1 | `GET /api/health` | 健康检查 | 无 | `{status,db,version,engine}`；DB DOWN → 503 + 10005 |
| 2 | `GET /api/records` | 列表 | `type` `status` `categoryId` `keyword` `page` `pageSize` `sortBy` `sortOrder` | 分页对象 + 9 字段列表项；错误 10001、40001 |
| 3 | `GET /api/records/{id}` | 详情 | path `id` | 完整对象 + `allowedStatuses`；错误 10001、20001 |
| 4 | `POST /api/records` | 提交 | 8 字段请求体 | 与详情同构；错误 10001、40001、40002 |
| 5 | `PUT /api/records/{id}` | 全量修改 | path `id` + 8 字段请求体 | 与详情同构；错误 10001、20001、20003、40001、40002 |
| 6 | `PATCH /api/records/{id}/status` | 状态流转 | `{status}` | `{id,status,allowedStatuses}`；错误 10001、20001、20002 |
| 7 | `DELETE /api/records/{id}` | 逻辑删除 | path `id` | `data=null`，`message="已删除"`；错误 20001 |
| 8 | `GET /api/categories` | 分类字典 | `onlyActive`（默认 true） | 数组，按 `sortOrder` 升序；无业务错误 |
| 9 | `POST /api/match` | 语义匹配 | `text` `type` `categoryId` `topN` `minScore` `excludeRecordId` | `matchId` + `items[]`；错误 10001、30001、30002、30003、40001 |

实现位置：`record/web/RecordController`（2–7）、`category/web/CategoryController`（8）、
`match/web/MatchController`（9）、`common/web/HealthController`（1）。

### 5.3 典型请求与响应

以下示例取自 3.1 / 3.2 的实测输出（`db/seed.sql` 数据），不是手写示意。

#### 5.3.1 健康检查

```http
GET /api/health
```

```json
{"code":0,"message":"ok","data":{"status":"UP","db":"UP","version":"0.0.1-SNAPSHOT","engine":"local"},"requestId":"…"}
```

#### 5.3.2 列表（默认只看待领）

```http
GET /api/records?page=1&pageSize=10&sortBy=createdAt&sortOrder=desc
```

```json
{
  "code": 0, "message": "ok", "requestId": "…",
  "data": {
    "page": 1, "pageSize": 10, "total": 21, "totalPages": 3, "hasNext": true,
    "list": [
      {"id":26,"type":"FOUND","status":"PENDING","title":"捡到一张 unknown 的卡片",
       "categoryId":null,"categoryName":null,"location":"大学生活动中心公告栏",
       "occurredAt":"2026-10-21 13:30:00","createdAt":"2026-10-21 13:50:00"}
    ]
  }
}
```

> 种子数据共 24 条；默认 `status=PENDING` 排除 23（`CLAIMED`）、24（`CLOSED`）、25（逻辑删除），故 `total=21`、`totalPages=3`。数字对不上就是实现写歪了。

#### 5.3.3 详情

```http
GET /api/records/13
```

```json
{
  "code": 0, "message": "ok", "requestId": "…",
  "data": {
    "id": 13, "type": "FOUND", "status": "PENDING",
    "title": "图书馆拾到黑色钱包",
    "description": "今天在图书馆三楼自习区…",
    "categoryId": 1, "categoryName": "钱包卡证",
    "location": "图书馆三楼自习区",
    "occurredAt": "2026-10-20 15:00:00",
    "contact": "13800000101", "publisherName": "周同学",
    "allowedStatuses": ["CLAIMED", "CLOSED"],
    "createdAt": "2026-10-20 15:30:00", "updatedAt": "2026-10-20 15:30:00"
  }
}
```

`allowedStatuses` 是「状态机放后端、前端不自己推」的落点：详情页据此渲染按钮，前端不需要任何流转知识。空数组表示终态，不显示状态操作。

#### 5.3.4 提交

```http
POST /api/records
Content-Type: application/json

{
  "type": "LOST",
  "title": "丢了一个灰色背包",
  "description": "今天下午在第一教学楼 305 的座位上落下了一个灰色的双肩背包，侧面有个水杯袋。",
  "categoryId": 5,
  "location": "第一教学楼305",
  "occurredAt": "2026-10-21 15:20:00",
  "contact": "13800000012",
  "publisherName": "张同学"
}
```

成功返回 `200` + `message="已提交"` + 与详情同构的对象（含 `id` 与 `allowedStatuses`）。失败示例：

```json
{
  "code": 10001, "message": "请求参数校验失败", "requestId": "c0ffee20261021",
  "data": {"fieldErrors": [
    {"field": "title", "reason": "不得为空"},
    {"field": "description", "reason": "长度需在 2 到 2000 之间"}
  ]}
}
```

`fieldErrors` 返回**全部**违规字段而不是第一条：多字段同时错时前端要一次性标红，只给一条会让用户「改一个、提交、再报下一个」。

#### 5.3.5 状态流转

```http
PATCH /api/records/27/status
{"status": "CLAIMED"}
```

```json
{"code":0,"message":"状态已更新","data":{"id":27,"status":"CLAIMED","allowedStatuses":["CLOSED"]},"requestId":"…"}
```

非法流转（如对 `CLOSED` 记录再迁出）：

```json
{"code":20002,"message":"状态流转非法","data":{"current":"CLOSED","allowed":["(无)"]},"requestId":"…"}
```

#### 5.3.6 语义匹配

```http
POST /api/match
{"text":"我在图书馆三楼弄丢了一个黑色皮钱包，里面有银行卡","type":"FOUND","topN":5,"minScore":0.3}
```

```json
{
  "code": 0, "message": "ok", "requestId": "9f3c1a7e",
  "data": {
    "matchId": 41, "engine": "LOCAL_KEYWORD", "inputType": "FOUND",
    "topN": 5, "minScore": 0.3, "tookMs": 18, "total": 1,
    "items": [
      {"recordId":13,"score":0.95,"scorePercent":95,
       "reason":"标题与描述命中「图书馆三楼」「黑色」「钱包」，分类一致",
       "type":"FOUND","status":"PENDING","title":"图书馆拾到黑色钱包",
       "categoryName":"钱包卡证","location":"图书馆三楼自习区",
       "occurredAt":"2026-10-20 15:00:00","createdAt":"2026-10-20 15:30:00"}
    ]
  }
}
```

### 5.4 错误到界面的映射

前端拦截器只做一件事：`code !== 0` 抛 `BizError{code,message,fieldErrors,requestId}`，页面按下表处理。这张表是「用户看到什么」的唯一依据。

| code | 界面行为 |
|---|---|
| 10001 / 30001 | 把 `fieldErrors` 落到对应输入框下方；非表单场景 toast `message` |
| 20001 | 详情页显示「记录不存在或已删除」空态 + 返回列表，**不重试** |
| 20002 | toast + 重新 GET 详情刷新 `allowedStatuses`（状态可能已被别人改过） |
| 20003 / 40002 | toast `message` |
| 30002 / 30003 | 匹配区显示「匹配服务暂不可用，请稍后再试」，**列表区不受影响**（降级 UI，不整页报错） |
| 10005 / 网络失败 | toast「请求失败，请稍后再试（requestId）」，把 `requestId` 带出来便于查日志 |

### 5.5 状态流转

```
PENDING ──► CLAIMED ──► CLOSED（终态）
   └──────────────────► CLOSED
```

| 当前 | 允许迁往 | 说明 |
|---|---|---|
| `PENDING` | `CLAIMED`、`CLOSED` | 正常认领 / 直接下架 |
| `CLAIMED` | `CLOSED` | 撤销认领回到 `PENDING` **不允许**（会改写历史状态） |
| `CLOSED` | （无） | 终态 |

规则只写在一处（`RecordStatusPolicy`），三处消费同一份数据：详情出参的 `allowedStatuses`、PATCH 的合法性判定、`20002` 错误体里的 `allowed`。写成两份的话，改一处忘一处就变成「详情页按钮点了才报 409」。

---

## 6 AI 语义匹配设计

### 6.1 边界：谁负责什么

```
MatchController → MatchService → MatchEngine（接口）→ KeywordMatchEngine（本地，默认）
                       │                  ▲          └► ExternalEmbeddingMatchEngine（未落地）
                       ├─► MatchCandidatePoolPort → RecordMapper（候选池，只读）
                       ├─► RecordMapper（回表补展示字段 + 过滤非法候选）
                       └─► MatchResultMapper（写 t_match_result 快照）
```

三条边界规则，违反任何一条就等于「接口独立」没做到：

1. **引擎不碰数据库**：不注入 Mapper、不写库，只消费 `MatchCandidatePoolPort` 递到手里的一池候选文本。挡住的变化：换实现时不需要理解表结构，外部实现可以被单测覆盖（无需起库）。
2. **引擎只返回 `recordId + score + reason`**：返回展示字段是非法的。候选合法性（未删除、状态可展示）由 `MatchService` 统一保证，不由每个算法各实现一遍。
3. **引擎不知道 HTTP**：没有 `HttpStatus`、没有 `ApiResponse`、不接收 DTO；异常只有 `MatchEngineException`。

一次调用的完整时序（含失败出口，对应 `MatchService.match` 的实际顺序）：

![POST /api/match 调用时序](diagrams/match-sequence.png)

### 6.2 接口抽象

以下是**仓库中的实际签名**（`docs/ai-match-module.md` §2 的初版提案在实现时做了调整，差异见附录 B 的 C-04）：

```java
public interface MatchEngine {
    /** 引擎标识，落 t_match_result.impl：LOCAL_KEYWORD / EXTERNAL_EMBEDDING。 */
    String impl();

    /** 对候选池打分，返回分数降序、条数 ≤ topN 的候选。 */
    List<MatchCandidate> match(List<MatchCandidateSource> candidates, MatchQuery query);
}

public record MatchQuery(String inputText, RecordType inputType,
                         Long categoryId, int topN, BigDecimal minScore) {}

public record MatchCandidate(Long recordId, BigDecimal score, String reason) {}

public record MatchCandidateSource(Long recordId, String title, String description,
                                   Long categoryId, String location) {}

public interface MatchCandidatePoolPort {
    List<MatchCandidateSource> load(RecordType targetType, Long categoryId, int limit);
}
```

设计要点：

- **候选池由调用方递进来**（`match(candidates, query)`），而不是引擎自己去取。这样「哪些记录可见」这条业务规则只存在于 `MatchService` 与 SQL 一处，引擎换实现时不会复制一遍。
- **`MatchQuery` 与 HTTP DTO 解耦**：`MatchRequest` 只存在于 web 层，`MatchService` 把它转成 `MatchQuery` 再交给引擎。
- **不加 `MatchEngineRegistry`、`AbstractMatchEngine`、`MatchEngineChain`**：只有一个注入点、两个实现，注册表挡不住任何真实变化，只增加阅读成本。

### 6.3 本地实现：`KeywordMatchEngine`

不是「语义理解」，是**关键词 + 统计权重**。这一点在文档与答辩口径上都写清楚，避免把关键词分数说成语义置信度。

| 环节 | 做法 |
|---|---|
| 分词 | 中文按 unigram + bigram 切分；英文/数字按整词（正则 `[a-z0-9]+` 与 `[\u4e00-\u9fff]+` 分段） |
| 权重 | 基础权重 × 本次候选池的 IDF：bigram 1.0、unigram 0.45、英文数字整词 1.4；稀有词权重更高 |
| 分数构成 | 文本覆盖 0.78 + 标题覆盖 0.22 + 地点 bigram 覆盖 0.07 + 类目词表命中 0.25，`clamp` 到 `[0,1]` 后 `setScale(2, HALF_UP)` |
| 类目词表 | 首次匹配时读一次 `t_category.keywords`，进程内缓存；词表不可用时降级为纯文本匹配，不让接口 500 |
| 契约自保 | 非 null、`size ≤ topN`、`score ≥ minScore`、去重、降序（同分按 `recordId` 升序） |
| 依赖 | 只注入 `CategoryMapper`（`@Lazy`）；不注入 `RecordMapper`、不读 `t_record`、不写库 |

**为什么这样选**：零外部依赖、可离线演示、结果确定可复现（5.1 的正/负例断言不会飘）。代价在 §6.5 的失效抽样里有实测证据。

### 6.4 可替换性

**切换方式**：一个配置值，`findit.match.engine=local|external`。装配在 `MatchEngineConfig`：

```java
@Bean
@ConditionalOnProperty(prefix = "findit.match", name = "engine",
                       havingValue = "local", matchIfMissing = true)
MatchEngine keywordMatchEngine(MatchCandidatePoolPort pool, CategoryMapper categories, MatchProperties props) {
    return new KeywordMatchEngine(pool, categories, props);
}
```

容器里**恒只有一个 `MatchEngine` bean**，所以注入不需要 `@Qualifier`，也不存在「两个实现谁生效」的歧义。不配置时是本地实现（演示环境零配置可跑）。

**不做自动回退**（ADR-009）：外部挂了就是 503，不悄悄用关键词分数顶上。理由是评分诚信——静默降级会让用户以为拿到的是语义结果，`impl` 列也会被污染。

**换实现的改动面**：

| 变更 | 需要改 | 不需要改 |
|---|---|---|
| 本地 → 外部 | `application.yml` 的 `engine: external` + 环境变量；新增 `engine/remote/ExternalEmbeddingMatchEngine` 与包内 `EmbeddingClient`；`MatchEngineConfig` 加一个 `havingValue="external"` 的 `@Bean`；如需新枚举值则加 `impl` 取值与 `schema.sql` 的 CHECK | `MatchEngine` / `MatchQuery` / `MatchCandidate` / `MatchService` / `MatchController` / 请求响应 DTO / 前端全部代码 |
| 外部换供应商 | `EmbeddingClient` 实现类 + 3 个配置项 | 同上 |
| 本地算法改进 | `KeywordMatchEngine` 内部 | 接口、调用方、契约 |

**怎么证明「可替换」不是话术**：`KeywordEngineSwappabilityTest` 在同一调用点 `serviceWith(engine).match(request)` 分别注入 `FakeEngine` 与真实引擎，断言两者响应都满足契约（≤`topN`、≥`minScore`、去重、降序、`score ∈ [0,1]`）；另有一条用乱序/重复/低分的 Fake 输出验证 service 的兜底。3.1 的 `MatchServiceTest` 同样用 Fake 引擎覆盖编排，`MatchService` 与 `MatchController` 在 3.2 交付时**零 diff**。

### 6.5 效果评估结论（取自 5.1）

样例集来自 `db/seed.sql` 文末的 S1–S6（应命中）与 N1–N4（应不命中或低分），共 10 条 × 两种协议。

| 指标 | P1 产品路径（带 `type`） | P2 不限类型（对照） |
|---|---|---|
| top1 命中率 | 10/10（100%） | 6/10（60%） |
| top3 命中率 | 10/10（100%） | 10/10（100%） |
| 明显误报（score ≥ 0.6 且不相关） | 0 | 0 |
| 同一输入两次调用结果一致 | 10/10 | 10/10 |
| 后端 `tookMs`（只计引擎）avg / p95 / max | 0 / 0 / 0 ms | 0.2 / 1 / 1 ms |

**怎么读 P2 的 4 条「不通过」**：2/14、3/15、4/16、6/18 是同一件事的两侧（一个丢了、一个捡到），不带 `type` 时「同事件的另一侧」天然更容易命中。这不是误报，是**缺方向约束时的排序歧义**；证据是 top3 两种协议都 10/10。产品路径永远带 `type`，所以歧义不出现在真实界面上。

**失效边界（实测，不进入正式判定）**：

| 输入 | 结果 | 说明 |
|---|---|---|
| `丢了一个水杯` | 空结果 | 库里有「不锈钢保温杯」。「水杯」与「保温杯」没有共同 bigram，单字权重不足以过 0.3 阈值 |
| `丢了airpods右耳` | 命中 5 号 0.53 | 靠「充电/耳」等弱重合召回，不是真正识别了同义词 |
| `图书馆丢了黑色钱保` | 正确 top1 | 错别字没毁掉结果，因为地点与颜色词仍在；这是「错得不够狠」，不代表容错 |
| `tushuguan diu le qianbao` | 空结果 | 拼音/英文输入不支持 |

结论：当前实现对**具体名词 + 地点/颜色描述**有效，对**同义词、拼音、脱离词表的近义表达**无效。这不是缺陷（3.2 交付时就写明这是「零外部依赖、可复现」的代价），但答辩时不能把它讲成语义理解；需要泛化时按 §6.4 换实现，前端不动。

### 6.6 本期未做的部分

| 项 | 状态 | 说明 |
|---|---|---|
| `ExternalEmbeddingMatchEngine` | **未落地** | 人类口径：本期只认「本地实现 + 文档化替换路径」，另立任务 `ZHYYKFX-19` |
| 外部实现的可用性验证（V2：切 `external` 后 503/30002） | 未执行 | 无实现即无 503 路径可测；契约与装配位已就绪 |
| 向量持久化、向量数据库、多路召回融合 | 明确不做 | 与三张表 + 单机基线冲突，且无规模依据 |
| 匹配缓存 | 明确不做 | 缓存会绕过快照，评估样本被吞掉 |

---

## 7 关键实现说明与开发过程

这一节回答的是「这东西是怎么被做出来的」，素材全部来自各子任务的交付/验收评论与 commit 历史，不做文学加工。每条问题都给了可回溯的位置。

### 7.1 交付流水线

一人课程项目，实际由多个 Agent 按阶段交付，人只做两类事：**拍板**与**合并 `main`**。

| 角色 | 承担的任务 |
|---|---|
| 需求（1.1） | 37 条需求拆解、验收判据、待拍板清单 |
| 架构（1.2） | 三张表 DDL、API 契约、AI 模块边界、ADR |
| 后端（3.1） | 六类记录接口 + 分类字典 + `/api/match` 编排 + 统一错误出口 |
| AI（3.2） | `KeywordMatchEngine` 实现与装配 |
| 前端（4.1） | 三页主流程、URL 状态、匹配入口 |
| UI（4.2） | 视觉基线、组件类族、五状态、无障碍 |
| 测试（5.1） | 接口 64 例、前端走查 19 例、一致性 9 例、落库取证 10 项、匹配评估 10 样例 |
| 文档（6.1） | 本文 |

**分支模型**：功能分支 `feat/<阶段>-<主题>` → 各 Agent 的 `hifox-agent/<名>/<任务号>` → 集成分支 `feat/5.1-integration` → `main`（仅人类合并）。规则是：Agent 只推自己的分支，不改表结构，不碰 `main`。

**验收方式**：看证据不看自述。每条交付评论必须带分支名 + commit SHA + 实测输出 + 附件；验收方会 `git ls-remote` 核对 SHA、下载附件抽查、必要时本机重跑。

### 7.2 阶段推进与时间线

时间为 commit 时间（CST，2026 年）。

| 时间 | 阶段 | 分支 / commit | 结论 |
|---|---|---|---|
| 10-21 19:50 | 初始化 | `dc66a07` | 空仓库 + 课程资料 |
| 10-21 23:18 | 2.1-A 前端骨架 | `eafdcd7` | Vue3 + TS + Vite 可启动 |
| 10-21 23:37 | 2.1-B 后端骨架 | `2e22132` | Boot 3.5.16 + MyBatis + 建表脚本 |
| 10-22 09:03 | 阶段 2 整合 | `80258ab` | 合并两骨架，解决 README/.gitignore 冲突 |
| 10-22 09:04 | 阶段 2 收口 | `5a166ac` | 默认端口对齐 18080；10005 HTTP 口径统一 |
| 10-22 09:41 | P1 修复 B | `74f07cf` | 前端 mock 改为显式开关，默认关 |
| 10-22 09:50 | P1 修复 A | `1537d54` | 10005 口径闭合 + 方法级校验出口补齐 |
| 10-22 10:18 | 阶段 2 合并 main | `6c68c01` | 人类批准，`--no-ff` 合并，树与 `1537d54` 一致 |
| 10-22 11:19 | 4.1 三页面 | `4f637ea` | 主流程通；落库项以 mock 兜底，记期中状态 |
| 10-22 11:40 | 4.2 UI 基线 | `89559e6` | 十类组件族、五状态、CLS 归零 |
| 10-22 11:42 | 3.1 后端接口 | `229aaa4` | 144 条接口断言全通；32 个单测 |
| 10-22 12:01 | 3.2 匹配引擎 | `daedc22` | S1–N4 全中；51 个测试；契约零改动 |
| 10-22 14:23 | 阶段 3/4 集成 | `dd30992`、`919b3e9` | 两次 `--no-ff`，无冲突 |
| 10-22 15:13 | 5.1 测试与评估 | `34c5dd1` | 64 例接口 / 19 例走查 / 9 例一致性 / 10 项落库取证 |
| 10-22 17:52 | 6.1 / 6.2 文档合入 | `f454dc9` | `--no-ff`，改动只落在 `docs/` |
| 10-22 17:33 | DEF-02 / DEF-01 修复合入 | `5d05961`、`d21fe78` | 两次 `--no-ff`；修复本体是 `web/src/composables/useRouteQueryState.ts` 与后端新增的 `@StrictDateTime` 校验器 |
| 10-22 17:47 | REG-01 回归（去豁免） | `d2d7d36` | 移除 `API-C-08` / `E2E-L-04` 两处 `knownDefect` 后全量复跑：接口 64/64、走查 19/19 |
| 10-22 19:03 | 阶段 6 收口合并 | `346bad1`、`21b033d` | 两次 `--no-ff`，先合 REG-01 报告（`d2d7d36`）再合演示资产（`9c4c7d5`）；改动只落在 `docs/` 与 `qa/`，集成分支头 = `21b033d` |

关键路径是 `1.2 契约 → 2.1 骨架 → 3.1 接口 → 3.2 引擎 → 4.1 页面 → 5.1 测试 → 6.1 文档`。3.1 与 3.2 串行（3.2 要按 3.1 的接口签名实现），4.1 与 4.2 串行（4.2 要按 4.1 的 DOM 结构加样式），其余可并行。

### 7.3 遇到的问题与解决

#### P-01 执行环境没有 JDK / Maven / MySQL

- **现象**：架构基线在写环境章节时实测发现 Agent 运行机 `PATH` 上只有 Node 与 Git，`Program Files` 下也没有 JDK/MySQL/Maven，而 2.1 的验收要求「干净环境按 README 命令可启动前后端、脚本可在 MySQL 建表」。
- **处置**：基线 §9.1 提前写下这条警告，要求把验收放到装有 JDK + MySQL 的机器；复审环节由执行人下载免安装 Temurin 17 放在工作目录临时使用，**未装进系统、未改 PATH**。
- **结果**：后端构建与测试在临时 JDK 下跑通（`BUILD SUCCESS`），Java 版本为 17.0.20.1。
- **教训（写进文档而非口头）**：凡声称「可复现」的证据，必须同时写明环境；本文 §8.2 因此逐项列出了实测版本。

#### P-02 `10005` 的口径三处互相矛盾

- **现象**：契约 §2 写「DB 不可用 → 503 + 10005」，而 `ErrorCode.INTERNAL_ERROR` 枚举映射 500；基线 §6.2 的表格又标着「待人类确认」。同一条错误有三种说法。
- **定位**：不是实现写错，是**规范自身没闭合**。三处都「各自合理」，组合起来无解。
- **处置**：人类拍板方案 A——枚举固定 500（未预期异常走默认值），DB 探测失败由 `HealthController` 在返回时**显式覆写** HTTP 503，body 的 `code` 仍为 10005（「两轨不同源」是刻意的）。覆写点只允许 `HealthController` 一处。
- **证据**：`docs/architecture-baseline.md` §6.2 注脚；`ErrorCode.java:24`；`HealthController.java:64-71`（DB DOWN → 503）；复审记录里的三处自洽核对。

#### P-03 方法级参数校验漏了一个异常出口

- **现象**：`@RequestParam` / `@PathVariable` 上挂 `@Min` / `@Pattern` 时，违规不经过 `@Valid` 请求体那条路径，而是方法校验阶段抛异常。项目只注册了一个出口，换一种装配方式就静默退化成 500。
- **关键认知**：同一个错误在两种装配下抛**两个不同的异常**——`HandlerMethodValidationException`（Spring 6.1+ 内建方法校验）与 `ConstraintViolationException`（类上标了 `@Validated`，走 AOP 代理）。漏一个就等于给前端漏一个 500。
- **处置**：两个出口都注册，行为一致（`400` + `10001` + 全量 `fieldErrors`）。测试里两个 handler 都真实触发过（4 次 / 1 次）。
- **证据**：`GlobalExceptionHandler.java:75` 与 `:117`；`MethodValidationExitTest`（6 个测试）。

#### P-04 前端 mock 默认开着，「联调通过」是假象

- **现象**：2.1 阶段 `listRecords` 在未设置环境变量时走 mock，页面看起来一切正常，但请求根本没到后端。
- **处置**：改成显式开关 `USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'`，**未设置即默认关**；三处 TODO 阶段标注统一指向 4.1。
- **验证方式（值得记下来）**：不只看代码，还对比构建产物——默认构建的 `RecordList` chunk 5.54 kB 且**不含** mock 数据，`VITE_USE_MOCK=true` 构建 6.59 kB 且含 mock。体积差异就是「mock 有没有被打进去」的物证。

#### P-05 4.1 交付时后端还没就绪，验收项无法成立

- **现象**：4.1 开发时 3.1 尚未交付，任务正文允许用 mock 兜底，于是「提交后真实落库」这条验收不成立。
- **处置**：验收方明确记为**期中状态**，不判通过也不判失败；4.1 的分支保留，等 3.1 就绪后置 `VITE_USE_MOCK=false` 复验。5.1 阶段用真实接口重跑：UI 提交 → 用**全新浏览器上下文**（无 sessionStorage）打开该 URL 仍能渲染 → MySQL 直查该行 13 个字段与 UI 一致，10/10 通过。
- **为什么值得写进文档**：这是「看起来做完了」与「真的做完了」的差别。如果当时按页面截图判通过，后端没接上这件事要到答辩当天才暴露。

#### P-06 契约文档里的接口签名与实现漂移

- **现象**：`docs/ai-match-module.md` §2 的提案签名是 `MatchEngineKind kind()` + `List<MatchCandidate> match(MatchQuery query)`，并注明「勿改签名」；实际实现是 `String impl()` + `match(List<MatchCandidateSource> candidates, MatchQuery query)`。
- **定位**：3.1 在落地时把「候选池」从引擎内部取数改成由 `MatchService` 递入（这是更好的边界——可见性规则不必在引擎里复制一遍），签名随之变化，但 1.2 的文档没有同步。
- **处置**：本文以**代码为准**书写（§6.2），并在一致性自查里登记为已知偏差（`§9.2` 的 C-07）。未改代码、未改他人文档；建议后续把 `ai-match-module.md` §2 的签名段与 §8 落地记录对齐一次。
- **这条为什么必须写**：文档与代码不一致时，读者按文档写实现会拿到编译不过的代码。这正是本任务要防的事。

#### P-07 移动端 320px 下 CLS 超标

- **现象**：4.2 首次测量时 320px 宽度下 CLS 为 0.069（阈值 0.1），看似合格但其实已经在边缘。
- **定位**：用 `layout-shift` 的 `sources` 定位到**页脚**——内容回填后页脚被推出视口，触发一次大位移。
- **处置**：给 `main` 加 `min-height: calc(100dvh - header)`，18 个状态全部 ≤ 0.004。
- **证据**：`docs/ui-baseline.md` §5.4 记录了这个案例（含 320px 的对比数值）。

#### P-08 日志 `requestId` 双前缀（未修复）

- **现象**：logback 的 MDC 前缀与代码里手写的前缀叠加，日志出现 `[abc][abc]` 形式的重复。
- **处置**：复审时判定「新出口沿用既有写法，取舍正确，但重复未解决」，建议单独立一条任务统一交给 logback pattern。**本文不声称已修复**。

### 7.4 缺陷与观察项（5.1 登记；DEF-01 / DEF-02 已修复，剩余未修项见本节末）

| 编号 | 级别 | 位置 | 现象（修复前） | 影响 | 状态 |
|---|---|---|---|---|---|
| DEF-01 | P2 | `RecordCreateRequest.occurredAtAsDateTime`（`RecordCreateRequest.java:129`） | `occurredAt` 传日历上不存在的值（如 `2026-13-40 25:61:61`）返回 `500/10005`，契约 §5.3 要求 `400/10001` | UI 的 `datetime-local` 造不出非法日历值，**不阻塞演示**；脚本/第三方调用方会拿到 500，且日志被 ERROR 污染 | **已修复**：`3548a27`（`@StrictDateTime` + `ResolverStyle.STRICT`，合入 `d21fe78`），用例 `API-C-08` 于 REG-01 转 PASS（`qa/reports/api-5.1.txt:35`），已合入 `feat/5.1-integration` @`21b033d` |
| DEF-02 | P2 | `web/src/composables/useRouteQueryState.ts` | 点「重置筛选」后 URL 仍带 `?keyword=钱包`，刷新后筛选复活 | 当次显示正确；影响链接分享、刷新、收藏三种场景 | **已修复**：`1349f09`（写回 URL 前先删掉本次 state 管辖的全部键，合入 `5d05961`），用例 `E2E-L-04` 于 REG-01 转 PASS（`qa/reports/e2e-5.1.txt:7`），已合入 `feat/5.1-integration` @`21b033d` |
| DEF-03 | P3 | `MatchPanel` 标题与 `describeError()` 文案 | 503 时提示条上同一句话出现两次 | 纯观感，降级行为正常 | 未修复，可延后 |


| 编号 | 观察项 | 说明 |
|---|---|---|
| OBS-01 | `/api/match` 服务端耗时抖动：24 行数据下连续 8 次为 375/18/357/367/379/359/14/12 ms，而列表接口稳定 5–8 ms | 性能不在本期范围。`tookMs` 显示引擎本身 0–1 ms，怀疑点在快照写入或连接池，不建议先改算法 |
| OBS-02 | 详情页「最后更新」显示 seed 导入时间 | `db/seed.sql` 只写 `created_at`，`updated_at` 走默认值；建议 seed 显式写 `updated_at = created_at` |
| OBS-03 | 前端没有 `data-testid`，E2E 靠类名/文案定位 | 改文案会让用例失败——维护成本，不是产品回归 |
| OBS-04 | 请求体不是合法 JSON 时，`10001` 的 `fieldErrors` 是空数组 | 契约未规定此场景，前端只 toast，行为可接受 |

> **DEF-01 / DEF-02 转绿后仍遗留的两处 P2（未修复，待人类拍板）**
>
> 1. 请求体不是合法 JSON 时 `10001` 的 `fieldErrors` 是空数组（即上表 OBS-04）。契约未规定该场景，前端只 toast，不影响演示；是否补齐取决于契约是否要覆盖这一条。
> 2. 点「重置筛选」只在筛选项发生变化时把页码归 1：`RecordList.vue:46` 的 `updateFilter` 用「四个筛选项是否全未变」判断，若进入页面后直接翻页再点重置，`page` 会留在原页。
>
> 两处已于 2026-10-22 11:04Z 向人类提出「合并前修 / 记遗留」，**尚未拍板**。本文按现状写「已记录为遗留 P2，未修复」，既不写「已修复」，也不写「已排期」。

### 7.5 缺陷处置与未修复项的影响

**已修复项**（2026-10-22 合入集成分支）

| 项 | 修复提交 | 怎么验的 | 结论 |
|---|---|---|---|
| DEF-01 | 后端 `3548a27`（合入 `d21fe78`，`ZHYYKFX-20`） | 去掉 `API-C-08` 的 `knownDefect` 标记后重跑 `node qa/api/run.mjs`：64/64，该用例 PASS | 已修复、已回归、**已合入集成分支 `feat/5.1-integration` @`21b033d`**；`main` 合并待人类放行 |
| DEF-02 | 前端 `1349f09`（合入 `5d05961`，`ZHYYKFX-21`） | 去掉 `E2E-L-04` 的标记后重跑 `node qa/e2e/walkthrough.mjs`：19/19，该用例 PASS | 同上 |

**未修复项**

| 项 | 谁该修 | 修好后怎么验 | 现在的影响 |
|---|---|---|---|
| DEF-03 | 前端，随下次改动 | 看 `E2E-E-06` 截图，文案不重复 | 观感 |
| `fieldErrors` 空数组（OBS-04） | 后端，待人类拍板 | 发一个非法 JSON 请求体，`10001` 的 `fieldErrors` 应有具体字段 | 契约未规定该场景；前端只 toast。已记录为遗留 P2，未修复 |
| 重置筛选后 `page` 残留 | 前端，待人类拍板 | 翻到第 2 页后直接点「重置筛选」，URL 的 `page` 应回到 1 | 只在「先翻页、后重置」这一条路径上出现。已记录为遗留 P2，未修复 |
| 外部匹配实现 | 独立任务 `ZHYYKFX-19`（待开始） | 切 `engine=external` 后调 `/api/match` 返回 503/30002，且 `git diff` 只含 yml | 本期只有本地实现，§6.6 已声明 |

> 本文对 DEF-01 的处理口径：**契约怎么写就怎么写**（要求 `400/10001`），实现修复前在 §7.4 与 `consistency-checklist.md` C-01 里标明「实现当前为 500/10005」。修复落地后实现与契约一致，文档不需要为迁就缺陷改一个字——这条口径从头到尾没有变过。

---

## 8 测试与结论

### 8.1 测试范围与方法

测试资产在 `qa/`（只新增测试与文档，未改业务代码），一条命令复现：

```bash
node qa/run-all.mjs     # 前置：MySQL 已起、后端 18080、前端 5173（VITE_USE_MOCK=false）
```

脚本做四件事：重置库到 seed 初始状态 → 依次跑五组测试 → 再重置一次库（不留下走查数据）→ 汇总退出码并把完整输出写入 `qa/reports/run-all-output.txt`。

| 组 | 脚本 | 用例数 | 覆盖点 |
|---|---|---|---|
| 后端接口 | `qa/api/run.mjs` | 64 | 通用契约、列表、详情、提交、修改、状态流转、删除、分类、匹配 |
| 匹配评估 | `qa/match/evaluate.mjs` | 10 样例 × 2 协议 | S1–S6 正例、N1–N4 负例、命中率与耗时 |
| 前端走查 | `qa/e2e/walkthrough.mjs` | 19 | 三页黄金路径 + 校验失败 / 后端不可用 / 空数据 / 404 / 500 / 匹配 503 |
| 落库取证 | `qa/db/evidence.mjs` | 10 项检查 | 直查 MySQL，不采信接口自述 |
| 跨页一致性 | `qa/e2e/consistency.mjs` | 9 | token / 圆角 / 控件 / 容器 / 骨架 / 空态 / 响应式 |

### 8.2 环境（实测算，非引用文档）

| 组件 | 实测值 | 来源 |
|---|---|---|
| JDK | Temurin 17.0.20.1+1 | `java -version` |
| Maven | 3.10.0 | `mvn -v` |
| 后端 | Spring Boot 3.5.16，端口 18080 | 启动日志 |
| MySQL | 8.0.29（`db/schema.sql` + `db/seed.sql`，库 `find_it_campus`） | `SELECT VERSION()` |
| Node / npm | v22.23.3 / 10.9.9 | `node -v` / `npm -v` |
| 前端 | Vite 8.3.5 dev server，端口 5173，`VITE_USE_MOCK=false` | `npm run dev` |
| 浏览器 | Edge 155.0.4283.62（Playwright `channel=msedge`，headless） | `browser.version()` |
| 前端实际连的后端 | `http://localhost:18080/api`（经 Vite 代理，浏览器同源） | `web/.env.local` + 网络面板 |

### 8.3 结果汇总

**当前结论（REG-01 复跑：2026-10-22T09:44:43Z 接口、09:45:08Z 走查；被测树 `feat/5.1-integration` @`d21fe78`，报告提交 `d2d7d36`）**

| 测试组 | 用例数 | 通过 | 失败 | 通过率 | 与首次跑相比 |
|---|---|---|---|---|---|
| 后端接口 | 64 | 64 | 0 | 100.0% | 63 → 64（`API-C-08` 转 PASS） |
| 前端走查 | 19 | 19 | 0 | 100.0% | 18 → 19（`E2E-L-04` 转 PASS） |
| 跨页一致性 | 9 | 9 | 0 | 100% | 持平 |
| 落库取证 | 10 项 | 10 | 0 | 100% | 持平 |
| 匹配评估 | 10 样例 × 2 | top1 10/10（P1） | 0 | — | 持平；P2 对照有 4 条排序歧义，非失败 |

原始输出：`qa/reports/api-5.1.txt:2`（`用例总数: 64 | 通过: 64 | 失败: 0 | 通过率: 100.0%`）、`qa/reports/e2e-5.1.txt:2`（`用例总数: 19 | 通过: 19 | 通过率: 100.0%`）。两组 `knownDefect` 豁免已移除，全组按原断言判定（`qa/reports/run-all-output.txt:344`）。

**首次跑历史（5.1 测试，两条缺陷修复前）** —— 当时实测，保留原样，不代表当前状态

| 测试组 | 用例数 | 通过 | 失败 | 通过率 | 失败原因 |
|---|---|---|---|---|---|
| 后端接口 | 64 | 63 | 1 | 98.4% | DEF-01 |
| 前端走查 | 19 | 18 | 1 | 94.7% | DEF-02 |

那次的两条失败都指向**已登记缺陷**，不是原因不明的回归；三条缺陷都不影响「三页可用 + 接口全通 + 匹配可演示」这条主线。

### 8.4 关键证据

| 结论 | 证据 |
|---|---|
| 接口契约全通 | `qa/reports/api-5.1.txt` / `.json`，64 例逐条记录 HTTP 状态与 body `code` |
| 前端三页可用 | `qa/reports/e2e-5.1.txt`、28 张截图（`qa/reports/screenshots/`） |
| 真实落库（不是 mock） | `qa/reports/db-evidence.txt`：UI 提交后用全新上下文重开 URL，直查 MySQL 13 个字段与 UI 一致 |
| 匹配效果可复核 | `qa/reports/match-eval.md` / `.json`：每条样例「输入 / 期望 / 实际 / 判定」四要素 |
| 4.1 / 4.2 残留已复验 | `docs/testing/5.1-stage4-recheck.md`：真实接口 10/10、跨页一致性 9/9 |
| 需求可追溯到用例 | `docs/testing/5.1-traceability.md`：21 条条目中 20 条 ✅、1 条 ➖（external 按口径不评估），无 ⚠️ |

### 8.5 未覆盖项与风险

| 项 | 原因 | 风险提示 |
|---|---|---|
| 性能压测与容量基准 | 任务范围明确排除 | `/api/match` 在 24 行数据上已观察到 12–379 ms 抖动（OBS-01），演示够用，但不构成容量结论 |
| 安全渗透（注入 / 越权 / XSS） | 不属于 5.1；项目本身无权限体系 | 写接口对任何访客开放属已声明风险 |
| 兼容性矩阵 | 只实测 Edge 155 | Firefox / Safari / 移动真机未验证 |
| 3.2 external 实现 | 人类口径：本期不落地 | 不评估外部效果，不因此判 3.2 不通过 |
| 并发写入与竞态 | 只做了 10 次并发匹配 | 重复提交产生两条记录是契约已知行为，不是缺陷 |
| 浏览器前进/后退与深链接刷新 | 未单独覆盖 | DEF-02 已修复，建议补一条前进/后退用例 |
| 深色模式 | 人类拍板本期不做 | 已实测暗色偏好下仍为浅色，不算缺陷 |

### 8.6 结论

1. **接口层**：契约范围内的九组接口在正常、边界、异常三类路径上行为一致。首次跑唯一不符合契约的 DEF-01（输入校验漏到运行时）已修复（`3548a27`，合入 `d21fe78`；`@StrictDateTime` + `ResolverStyle.STRICT`），REG-01 复跑 64/64。
2. **前端层**：三页黄金路径全通；校验失败、后端不可用、空数据、404、500、匹配 503 六类异常态都有确定的视觉与可恢复动作。
3. **匹配**：产品调用方式下 top1 10/10、top3 10/10；不限类型时 top1 6/10 但 top3 仍 10/10，差异来自「同事件两侧」的排序歧义，说明分数口径可信、方向过滤是该带的。
4. **可替换性**：调用方零 diff 的替换路径已被测试固定；外部实现本身未落地，属已知范围而非缺陷。

**放行判断**：阶段 6 收口条件已满足。DEF-01 / DEF-02 已修复、已回归、已合入集成分支 `feat/5.1-integration` @`21b033d`；主线 `main` 仍停在 `6c68c01`，合并 `main` 前按 `docs/testing/5.1-regression-checklist.md` 再复跑一次 `node qa/run-all.mjs`，由人类决定是否放行。

---

## 9 附录

### 9.1 术语

| 术语 | 含义 |
|---|---|
| 记录（record） | 一条失物或招领启事，`t_record` 的一行 |
| 启事类型（type） | `LOST` 寻物（我丢了东西）/ `FOUND` 招领（我捡到东西） |
| 状态（status） | `PENDING` 待领 / `CLAIMED` 已领 / `CLOSED` 关闭（终态） |
| 候选池（pool） | 参与打分的记录集合：`status='PENDING'`、未删除、类型匹配，上限 500 条 |
| 引擎（engine） | 实现 `MatchEngine` 的打分组件，`impl` 值落 `t_match_result.impl` |
| 快照（snapshot） | `t_match_result` 的一行，记录一次匹配的输入、参数与候选 |
| 逻辑删除 | 写 `deleted_at` 而不删行；所有读接口过滤 `deleted_at IS NULL` |

### 9.2 一致性自查

文档中出现的每个表名、字段名、接口路径都在仓库里核对过，逐条结果（含 10 处需要说明的偏差，其中 C-01 / C-02 已随修复闭合）见 **`docs/design/consistency-checklist.md`**，该记录作为附录 B 一并导出。

自查的判定口径：**以代码与 `db/schema.sql` 为准**。文档与基线文档冲突时，若代码站在基线一侧，说明代码；若代码已偏离基线，说明代码并在自查里登记偏离项，同时不改代码。

### 9.3 导出与复现

**文档源**：`docs/design/program-design.md`（Markdown，UTF-8）。

**导出 DOCX**（生成可提交的附件）：

```bash
cd docs/design/tools
npm install          # 首次执行；唯一的依赖是 docx
node build-docx.mjs  # 输出 docs/design/program-design.docx
```

脚本做的事：把本文与附录 A/B 按标题层级转成 Word 结构（标题样式、表格、代码块、图片按页宽缩放），A4 纵向、页脚带页码。生成物可直接作为课程附件提交；如需 PDF，用 Word 打开后「另存为 PDF」即可——本项目不引入额外的 PDF 工具链。

实测环境：Node 22.23.3 + docx 9.13.0，生成的 `.docx` 在 Microsoft Word 16 中打开为 59 页、无缺图（缺图会显示红色的「［缺图：…］」占位）。

**图**：`docs/design/diagrams/*.mmd` 是源，`*.png` / `*.svg` 是导出物（已提交，通常不需要重新生成）。

```bash
cd docs/design/diagrams
npx @mermaid-js/mermaid-cli -i er.mmd -o er.png -b white -s 2 -p puppeteer.json
```

`-p puppeteer.json` 是 puppeteer 配置，内容只有一项 `executablePath`，指向本机的 Edge 或 Chrome：

```json
{ "executablePath": "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe", "args": ["--no-sandbox"] }
```

该文件不随仓库提交（路径与本机绑定）；没有 Chromium 的机器，执行 `npx puppeteer browsers install chrome` 后删掉 `-p` 参数即可。ER 图、时序图等宽图统一用 `-s 2` 渲染 2 倍像素，避免插入 Word 后发虚。

### 9.4 文档维护约定

| 场景 | 动作 |
|---|---|
| 接口字段/路径变化 | 先改 `docs/api-contract.md`，再改 DTO/DDL/TS 类型，最后同步本文 §5 与 `api-list.md`，三处同一 commit |
| 表结构变化 | 改 `db/schema.sql` + 提交说明附 ALTER，同步本文 §4 与 `er.mmd`，重新导出图 |
| 缺陷修复 | 更新 §7.4 状态与 §8.3 数字，去掉用例的 `knownDefect` 标记，不改历史结论 |
| 软件版本发布 | 更新本文首页的「对应代码基线」commit，旧版本文档标记废弃但保留 |

> 本文不承诺任何未经实测的性能、并发或安全数字。需要这些数字时，先跑测量，再把方法和结果一起写进来。


