# db/ · 数据库脚本

三张表：`t_category`（分类字典）、`t_record`（失物/招领记录）、`t_match_result`（匹配快照）。
库名 `find_it_campus`，引擎 InnoDB / utf8mb4。字段设计理由见 `docs/architecture-baseline.md` §7，
完整 DDL 注释写在 `schema.sql` 里，改动请先改那里再同步基线文档（ADR-015：不用 Flyway）。

## 1. 建库 + 建表（仅需一次）

```bash
mysql -u root -p -e "SOURCE db/schema.sql"
```

`schema.sql` 自带 `CREATE DATABASE IF NOT EXISTS` 与 `DROP TABLE IF EXISTS`，
因此**可重复执行**（幂等），代价是每次都会清掉已有数据 —— 生产/正式数据不要跑它，改用逐条 `ALTER`。

## 2. 导入种子数据（分类字典 + 24 条演示记录）

```bash
mysql -u root -p find_it_campus -e "SOURCE db/seed.sql"
```

`seed.sql` 先 `TRUNCATE` 三张表再插入，可重复执行。
数据同时充当 5.1 匹配自测样例集：正例 6 对、负例 4 对，另有 `CLAIMED`/`CLOSED`/逻辑删除/无分类各一组，
**不要随手改成正负例不明的数据**。默认列表（`status=PENDING` 且未删除）应查到 21 条。

## 3. 重置演示数据

```bash
mysql -u root -p find_it_campus -e "SET FOREIGN_KEY_CHECKS=0; TRUNCATE t_match_result; TRUNCATE t_record; TRUNCATE t_category; SET FOREIGN_KEY_CHECKS=1;"
mysql -u root -p find_it_campus -e "SOURCE db/seed.sql"
```

## 本机实测（Windows / MySQL 8.0.29 / root 免密）

免密时省掉 `-p`；Windows 上若 `mysql` 不在 `PATH`，写全路径：

```bash
C:/Project/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe -u root < db/schema.sql
C:/Project/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe -u root find_it_campus < db/seed.sql
```

已验证：`SHOW TABLES;` 返回三张表；`FULLTEXT ... WITH PARSER ngram` 建成功
（依赖 MySQL ≥ 8.0.14 且内核含 ngram；若本机 `SHOW VARIABLES LIKE 'ngram_token_size'` 为空，
按 `schema.sql` 文末「附 1」注释掉那一行，接口契约不变）。

后端怎么连这个库：`server/src/main/resources/application.yml` 里从环境变量读 ——
`FINDIT_DB_URL` / `FINDIT_DB_USER` / `FINDIT_DB_PASSWORD`，**密码不进仓库**（本机私密覆盖写 `application-local.yml`，该文件已 ignore）。
