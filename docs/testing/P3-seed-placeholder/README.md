# P3 修复② · db/seed.sql 记录 #26 占位符文案

任务：`ZHYYKFX-42` · 分支：`fix/p3-seed-placeholder` · 基线：`main @31ca40f`

## 改动

| 文件 | 改动 |
|---|---|
| `db/seed.sql:126` | 记录 #26 标题 `捡到一张 unknown 的卡片` → `捡到一张浅蓝色卡片` |
| `web/src/mock/store.ts:61` | mock 演示数据镜像同步为 `捡到一张浅蓝色卡片` |

未改：`category_id` 仍为 `NULL`；`type/status/location/occurred_at/contact` 等数据形状全部不变。description 原文已可读，未改。
授权范围外的文档样例（`docs/api-contract.md:89`、`:333`、`docs/design/program-design.md:491`）保持原样，作为已知残留，不在本单处理。

## 验收环境

| 项 | 值 |
|---|---|
| MySQL | 8.0.29，`127.0.0.1:3306`，库 `find_it_campus` |
| 种子 | 重灌 `db/schema.sql` + `db/seed.sql`，默认列表 `total=21` |
| 后端 | Spring Boot 3.5.16，`http://localhost:18080`，`findit.match.engine=local` |
| 前端 | Vite 8.3.5 dev server `http://localhost:5173`，`web/.env.local` 中 `VITE_USE_MOCK=false`（真实接口） |
| 浏览器 | 本机 Edge，Playwright `channel=msedge` headless |

## 执行命令与结果

```bash
# 重灌种子后执行 5.1 全量回归（脚本前后各重置一次库）
node qa/run-all.mjs
```

| 阶段 | 结果 |
|---|---|
| 接口测试 `qa/api/run.mjs` | 64 / 64 通过，0 失败，退出码 0 |
| 匹配效果评估 `qa/match/evaluate.mjs` | 10 / 10 样例符合预期，退出码 0 |
| E2E 三页走查 `qa/e2e/walkthrough.mjs` | 19 / 19 通过，0 失败，退出码 0 |
| 落库取证 `qa/db/evidence.mjs` | 10 / 10，结论「通过」，退出码 0 |
| 跨页一致性 `qa/e2e/consistency.mjs` | 9 / 9 通过，0 失败，退出码 0 |

原始输出见 `5.1-regression-output.txt`，分项报告见 `5.1-*.txt` / `5.1-match-eval.md`。

## 验收标准逐条对照

1. **首页列表**：`GET /api/records` 首页首条 = #26，标题 `捡到一张浅蓝色卡片`，不含 `unknown`；`categoryName=null`，前端按 `?? '未分类'` 回落。见 `list-first-page.json`、`01-list-first-record-26.png`。
2. **详情与匹配**：`GET /api/records/26` 返回 200，`category_id` 仍为 `NULL`，类型/状态仍为 `FOUND/PENDING`；`POST /api/match`（`text=捡到一张浅蓝色卡片`, `type=FOUND`）中 #26 仍作为候选返回且标题已同步。见 `record-26-detail.json`、`match-request.json`、`match-response.json`、`api-evidence-check.txt`、`02-detail-record-26.png`。
3. **回归与截图**：5.1 全量回归全部通过；三页面截图 `01`（列表）/ `02`（详情 #26）/ `03`（提交页）与 `/api/match` 详情页截图 `04` 均在 `docs/testing/P3-seed-placeholder/`；匹配请求与响应 JSON、`db/seed.sql` 改动 diff 一并落盘。
4. **改动范围**：`git diff --stat` 仅 `db/seed.sql`、`web/src/mock/store.ts` 两处修改，加本证据目录；无其他 tracked 文件变化。

## 证据清单

| 文件 | 说明 |
|---|---|
| `5.1-regression-output.txt` | `node qa/run-all.mjs` 原始控制台输出（含汇总表） |
| `5.1-api-report.txt` | 接口测试 64 条逐条结果 |
| `5.1-e2e-report.txt` | 三页 E2E 19 条逐条结果 |
| `5.1-match-eval.md` | S1–S6 / N1–N4 匹配评估 |
| `5.1-db-evidence.txt` | 直查 MySQL 的落库取证 |
| `5.1-consistency-report.txt` | 跨页样式与响应式一致性 |
| `01-list-first-record-26.png` | 列表页：首条 #26 新标题 + 「未分类」 |
| `02-detail-record-26.png` | 详情页 #26：分类显示「未分类」 |
| `03-create-page.png` | 提交页（E2E 黄金路径填写态） |
| `04-detail-match.png` | 详情页匹配区（E2E 结果态） |
| `list-first-page.json` | `GET /api/records` 原始响应 |
| `record-26-detail.json` | `GET /api/records/26` 原始响应 |
| `match-request.json` / `match-response.json` | `POST /api/match` 请求与原始响应 |
| `api-evidence-check.txt` | 上述三条读取路径的断言结果（结论：通过） |
| `seed-and-mock.diff` | `db/seed.sql` 与 `web/src/mock/store.ts` 的改动 diff |

## 环境备注

本机 DSH 沙箱禁止子进程管道（Node `spawn`/`exec` 默认 `stdio: 'pipe'` 报 `EPERM`），Vite 启动时的 `net use` 探测与 Playwright 启动 Edge 会因此失败。回归命令在获得完整本地访问授权后执行；为启动 dev server，仅在本机 `node_modules`（gitignored）内绕过了 Vite 的 `exec("net use")`，**仓库源码与该绕过无关**。所有断言与截图均来自真实后端 + 真实 MySQL + 真实页面。
