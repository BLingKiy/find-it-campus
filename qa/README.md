# qa/ · v2 F1 回归资产

本目录只放**测试资产**，不改业务代码。业务事实源是 `docs/api-contract-v2.md` 与 `docs/design/v2-architecture.md`。

## 目录

| 路径 | 作用 |
|---|---|
| `api/run.mjs` | 后端接口套件：原 5.1 的 64 条覆盖点全部保留，按 v2 契约改写断言；新增登录/越权/遮蔽/三档/幂等/降级/pair 独立/三态/TERM-06/PAIR-07，共 111 条 |
| `match/evaluate.mjs` | `/api/match` 效果评估：S1–S6 / N1–N4 + v2 遮蔽断言，输出 `match-eval.*` 与 `match-baseline.json` |
| `e2e/walkthrough.mjs` | Playwright 三页走查：列表 / 详情 / 提交按**匿名 / 本人 / 非本人**三态各走一遍，共 25 条 |
| `e2e/consistency.mjs` | 跨页一致性：量计算样式 + 320/768/1280 溢出检查，9 条 |
| `db/evidence.mjs` | 真实落库取证：直查 MySQL 七张表，19 项检查 |
| `run-all.mjs` | 一条命令跑完上面全部，前后各重置一次库 |
| `lib/` | 测试执行器、HTTP 客户端（支持多身份 Cookie）、登录夹具、MySQL/日志证据工具 |
| `reports/` | 生成物：`api-f1-v2.*`、`e2e-f1-v2.*`、`consistency-5.1.*`、`match-eval.*`、`db-evidence.txt`、`run-all-output.txt`、`screenshots/` |

## 前置

1. MySQL 8 + `db/schema.sql` + `db/seed.sql`（脚本会自己重置，结束再重置回种子状态）
2. 后端：`server/` 起在 18080
3. 前端：`web/` 起在 5173，且 `web/.env.local` 里 `VITE_USE_MOCK=false`（该文件不提交）
4. 登录夹具：`demo01` / `demo02` / `demo03`，密码统一 `Demo@123456`（seed 内）

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
`MYSQL_BIN`（默认 `C:/Project/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe`）、
`FINDIT_SERVER_LOG`（PAIR-07 / TERM-06 的 EV-LOG 用；默认找 `qa/reports/server.log`）。

> 启动后端时把 stdout 指到 `qa/reports/server.log`，EV-LOG 才能给出「该 pair 的 LLM 调用计数」：
> `java -jar server/target/find-it-campus-0.0.1-SNAPSHOT.jar > qa/reports/server.log 2>&1`
> 日志缺失时 API 用例仍会跑，但 PAIR-07 会明确失败在「EV-LOG 不可用」，不会用推断冒充证据。

## 约定

- 用例走 AAA（Arrange / Act / Assert），每条独立可跑，不依赖执行顺序。
- 断言只写契约里写死的东西；对实现细节（类名、SQL）不作断言。
- **每条写接口用例都覆盖匿名 / 本人 / 非本人三态**（AUTHZ-01..07；PUT/PATCH/DELETE/pair/通知逐条）。
- 失败分两种：`FAIL`（原因不明，需要人看）与 `KNOWN_FAIL`（已登记缺陷，报告里带缺陷号）。
- 本轮创建的记录会在收尾逻辑删除，种子数据不动；E2E 会改 10 号记录状态，`run-all` 结束时统一重置库。
- 直查 MySQL 只用于取证与夹具（例如构造指定分数 / MATCHED 的 pair），不用它断言接口实现细节。
