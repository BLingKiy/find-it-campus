# db/ · 数据库脚本（v2）

七张表：`t_user`（登录身份，无手机号列）、`t_category`（分类字典）、`t_record`（启事主表）、
`t_match_result`（匹配快照）、`t_pair_score`（LOST↔FOUND 配对，一条一对）、
`t_notification`（通知）、`t_access_log`（联系方式查看审计，append-only）。
库名 `find_it_campus`，引擎 InnoDB / utf8mb4。字段与约束的设计理由见
`docs/design/v2-architecture.md` §4（A1 冻结稿），完整 DDL 注释写在 `schema.sql` 里。

> v2 不做 v1→v2 迁移：筹备期没有真实历史数据，`schema.sql` 直接全量重建。
> 原计划的 `migration-v1-to-v2.sql` 已按人类 2026-10-27 拍板 #11 作废。

## 1. 建库 + 建表（空库一次执行）

```bash
mysql --default-character-set=utf8mb4 -u root -p -e "SOURCE db/schema.sql"
```

`schema.sql` 自带 `CREATE DATABASE IF NOT EXISTS`，并按外键依赖逆序 `DROP TABLE IF EXISTS`
后重建，因此**可重复执行**；代价是每次都会清掉已有数据 —— 正式环境不要跑它。

## 2. 导入种子数据

```bash
mysql --default-character-set=utf8mb4 -u root -p find_it_campus -e "SOURCE db/seed.sql"
```

`seed.sql` 先 `TRUNCATE` 七张表再显式 id 插入，可重复执行；重复执行后行数、分数、
状态分布一致（`completed_at/confirmed_at/unlocked_at` 用 `NOW()-n DAY` 相对量，保证
3 天/31 天窗口在任何执行日成立）。

固定夹具：

- 3 个演示账号：`demo01` / `demo02` / `demo03`，密码统一 `Demo@123456`（bcrypt cost=10，
  **仅本地演示**，禁止生产复用）；demo02/demo03 供越权测试。
- 24 条记录（id 1–10、13–26）：21 条 PENDING + 2 条 COMPLETED（3 天前 / 31 天前）
  + 1 条 CLOSED（带 pair）。公共列表恰好 21 条。
- 14 对 pair：≥0.90 八对、0.75–0.90 两对（含仅一端已公开）、<0.75 四对；
  NEW / CONTACTED / MATCHED / IGNORED 四态齐全。
- `(6,23)` 是 `0.93 IGNORED`、`(10,23)` 是 `0.95 MATCHED` 的同一 COMPLETED 记录对照夹具，
  用于证明「IGNORED 的 pair 不作为 COMPLETED 详情通道」。
- `(24,22)` 验证 CLOSED 的 pair 读时失效（403/50005）；`(25,13)` 是已过 30 天保留期的用例。
- `t_match_result` / `t_access_log` 故意留空：真实快照与审计由运行期产生，不预置假数据。

**不要随手改成正负例不明的数据**；下游 C1/D1/E1 直接依赖上面这些 id 与状态组合。

## 3. 重置演示数据

```bash
mysql --default-character-set=utf8mb4 -u root -p find_it_campus -e "SOURCE db/seed.sql"
```

`seed.sql` 自身就会清空七张表，不需要再手工 TRUNCATE。

## 本机实测（Windows / MySQL 8.0.29 / root 免密）

免密时省掉 `-p`；Windows 上若 `mysql` 不在 `PATH`，写全路径：

```bash
C:/Project/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe --default-character-set=utf8mb4 -u root < db/schema.sql
C:/Project/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe --default-character-set=utf8mb4 -u root find_it_campus < db/seed.sql
```

已验证：空库执行 `schema.sql` 0 报错，`SHOW TABLES` 恰好 7 张；seed 连跑两次结果一致；
`FULLTEXT ... WITH PARSER ngram` 建成功（依赖 MySQL ≥ 8.0.14 且内核含 ngram；
若目标机没有 ngram，按 `schema.sql` 文末说明注释掉 `ft_record_search` 一行，业务行为不变）。
完整执行记录见 `docs/testing/v2/B1-data-layer.md`。

后端怎么连这个库：`server/src/main/resources/application.yml` 从环境变量读
`FINDIT_DB_URL` / `FINDIT_DB_USER` / `FINDIT_DB_PASSWORD`，**密码不进仓库**
（本机私密覆盖写 `application-local.yml`，该文件已 ignore）。

> 下游注意：v1 的 `RecordStatus` 枚举仍是 `PENDING/CLAIMED/CLOSED`，而 v2 库已改为
> `PENDING/COMPLETED/CLOSED`。公共列表（默认 `status=PENDING`）不受影响；
> `status=ALL|COMPLETED` 与 `PATCH status=COMPLETED` 需要 C1/B2 同步枚举后再上线，
> 不要只把 B1 的库结构单独发到运行环境。
