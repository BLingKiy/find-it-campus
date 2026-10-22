# qa/ · 5.1 测试资产（ZHYYKFX-10）

本目录只放**测试资产**，不改业务代码。业务事实源仍是 `docs/api-contract.md` 与 `docs/ui-baseline.md`。

## 目录

| 路径 | 作用 |
|---|---|
| `api/run.mjs` | 后端接口测试套件（health / 通用契约 / 列表 / 详情 / 提交 / 修改 / 状态流转 / 删除 / 分类 / 匹配），含边界与异常路径 |
| `match/evaluate.mjs` | `/api/match` 效果评估：seed 的 S1–S6 / N1–N4 十条样例，输出「输入 / 期望 / 实际 / 判定」与指标 |
| `e2e/walkthrough.mjs` | Playwright 三页走查：列表 / 详情 / 提交的黄金路径 + 校验失败 / 后端不可用 / 空数据 / 404 / 500 / 匹配 503，逐条截图 |
| `e2e/consistency.mjs` | 4.2 跨页一致性：量计算样式（token / 圆角 / 控件高度 / 容器宽）+ 320/768/1280 溢出检查 |
| `db/evidence.mjs` | 4.1 真实落库取证：E2E 之后直查 MySQL，核对 UI 提交的数据确实写进了表 |
| `run-all.mjs` | 一条命令跑完上面全部，前后各重置一次库 |
| `lib/` | 极简测试执行器（`tester.mjs`）与 HTTP 客户端（`client.mjs`）；无第三方断言库 |
| `reports/` | 生成物：`api-5.1.*`、`e2e-5.1.*`、`consistency-5.1.*`、`match-eval.*`、`db-evidence.txt`、`run-all-output.txt`、`screenshots/` |

## 前置

1. MySQL 8 + `db/schema.sql` + `db/seed.sql`（脚本会自己重置，结束再重置回种子状态）
2. 后端：`server/` 起在 18080
3. 前端：`web/` 起在 5173，且 `web/.env.local` 里 `VITE_USE_MOCK=false`（该文件不提交）

## 一条命令

```bash
node qa/run-all.mjs
```

单独跑某一组：

```bash
node qa/api/run.mjs
node qa/match/evaluate.mjs
node qa/e2e/walkthrough.mjs
node qa/db/evidence.mjs
node qa/e2e/consistency.mjs
```

环境变量：`FINDIT_API_BASE`（默认 `http://localhost:18080`）、`WEB_BASE`（默认 `http://localhost:5173`）、
`MYSQL_BIN`（默认 `C:/Project/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe`）。

## 约定

- 用例走 AAA（Arrange / Act / Assert），每条独立可跑，不依赖执行顺序。
- 断言只写契约里写死的东西；对实现细节（类名、SQL）不作断言。
- 失败分两种：`FAIL`（原因不明，需要人看）与 `KNOWN_FAIL`（已登记缺陷，报告里带缺陷号）。
  已知缺陷不会被静默吞掉，也不会把「新回归」的信号搅浑。
- 本轮创建的记录会在收尾逻辑删除，种子数据不动；E2E 会改 1 号记录状态，`run-all` 结束时统一重置库。
