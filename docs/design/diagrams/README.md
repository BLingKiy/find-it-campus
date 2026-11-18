# 配图导出说明

本目录的 `.mmd` 是 **唯一事实源**，同名 `.svg` 与 `.png` 由 `export.mjs` 同一次生成。改图只改 `.mmd`，然后跑一次导出，三件套一起提交——手工替换某一张会导致源与成品对不上。

## 跑一次导出

```powershell
# 依赖在仓库外的本机工具目录（含 mermaid / puppeteer），不随仓库分发
cd C:\Project\hifox-workspaces\wentongtong\.build-tools
npm install

cd C:\Project\hifox-workspaces\wentongtong\find-it-campus
node docs/design/diagrams/export.mjs
```

导出脚本会自动发现本目录下所有 `.mmd`，逐个输出 `.svg`（矢量，嵌 docx 用）与 `.png`（兜底栅格）。

## 固定参数

| 参数 | 取值 | 为什么 |
|---|---|---|
| 排版宽度 | 1200–2000 CSS px（按图自身宽度夹取） | 太窄会折行，太宽字号相对变小 |
| 基准字号 | **18 px**（`themeVariables.fontSize`） | 100% 缩放下一屏可读，A4 页宽内不糊 |
| PNG 输出宽度 | **≥ 3200 px**（`deviceScaleFactor = ceil(3200 / 排版宽度)`） | 满足「兜底图 ≥3000 px」；docx 优先嵌 SVG |
| 背景 | 纯白 `#FFFFFF` | 打印与深色主题下都不出现透明底 |
| 折线 | `flowchart.curve = basis` | 直角折线在节点密集处会互相压线 |
| 节点文案 | ≤ 12 字 | 超过就会被 mermaid 折成两行，行高吃掉可视面积 |
| 时序参与者 | ≤ 5 个 | 第 6 个起，消息箭头会被压到 200 px 以内 |
| 单条消息 | ≤ 18 字 | 同上；细节写进正文，不写进图 |

## 当前图清单

| 文件 | 内容 | 引用位置 |
|---|---|---|
| `architecture-container` | 系统上下文与容器 | 设计文档 §3.2、答辩 PPT |
| `architecture-modules` | 后端八个能力包与依赖方向 | 设计文档 §3.3 |
| `deploy-windows` | Windows 单机部署拓扑 | 设计文档 §3.6、答辩 PPT |
| `er-core` | ER（一）用户 / 记录 / 分类 | 设计文档 §4.1、答辩 PPT |
| `er-pair` | ER（二）配对 / 通知 / 审计 | 设计文档 §4.1 |
| `login-sequence` | 登录、门禁与 401 出口 | 设计文档 §5.4.1、答辩 PPT |
| `match-sequence` | `POST /api/match` 调用 | 设计文档 §6.2 |
| `pair-publish-sequence` | 发布 → 审核闸门 → 异步打分 | 设计文档 §6.6、答辩 PPT |
| `pair-contact-sequence` | 三档判定 → 先落审计 → 出号 | 设计文档 §6.6、答辩 PPT |

## 验收自检

```powershell
# 每张 PNG 的实际像素宽（必须 ≥3000）
node docs/design/diagrams/export.mjs --check
```

`--check` 只读不写：打印每个 `.mmd` / `.svg` / `.png` 的时间戳与 PNG 的 IHDR 宽高，任一张低于 3000 px 或三件套时间戳不一致就以非零码退出。
