# B1 数据层 v2 · 7 表 + seed 执行记录

- 任务：[ZHYYKFX-49](https://app.hifox.com/nhnok/task/ZHYYKFX-49) · B1 数据层 v2（7 表 + seed）｜磐石石
- 分支：`feat/v2-B1-data-layer`（从 `origin/main` @ `21784f6` 切出，未合并 main）
- 契约：`docs/design/v2-architecture.md` §4.2（A1 冻结稿，`feat/v2-A1-arch-contract` @ `b90270e`）
- 执行日期：2026-10-27
- 环境：Windows / MySQL 8.0.29 Community / JDK 17.0.20.1 / Maven 3.10.0
  - 为避免与同机其他任务抢 3306，本次用**独立数据目录 + 3307**做验证；脚本本身不依赖端口。

## 结论

`db/schema.sql`（7 表）与 `db/seed.sql`（3 账号 / 9 分类 / 24 记录 / 14 pair / 8 通知）交付完成。
六条验收逐条通过：空库建表 0 报错且恰好 7 表；seed 连跑两次行数/分数/状态分布逐行一致；
三档分数与四态齐全；隐私扫描 0 命中；唯一键与 CHECK 在位、无触发器/生成列；
后端公共列表恰好 21 条。**没有写 v1→v2 迁移**（人类 2026-10-27 拍板 #11：无历史数据，直接重建）。

## 交付物

| 文件 | 说明 |
|---|---|
| `db/schema.sql` | 7 表全量 DDL；按依赖逆序 DROP 后重建，可重复执行 |
| `db/seed.sql` | 幂等种子：先清后插 + 显式 id；hash 由 SQL 按 A1 §8.5 公式现算 |
| `docs/testing/v2/B1-data-layer.md` | 本执行记录 |

`db/schema.sql` 与 A1 §4.2 的列/类型/约束一一对应，没有自行加列。额外说明：
`t_pair_score.degraded_reason` / `prompt_version` 是 A1 DDL 已有列，不是 B1 新增；
本任务书第 3 项列出的 16 列全部在表内（见 §5 SHOW COLUMNS）。

## 1. 验收① 空库执行 schema：0 报错，恰好 7 表

先删库确认是真空库：

```bash
MYSQL="C:/Project/tools/mysql/mysql-8.0.29-winx64/bin/mysql.exe"
$MYSQL --no-defaults -u root -h 127.0.0.1 -P 3307 -e "DROP DATABASE IF EXISTS find_it_campus;"
$MYSQL --no-defaults -u root -h 127.0.0.1 -P 3307 -N \
  -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='find_it_campus';"
```

输出：

```text
drop-exit=0
0
```

空库执行 `db/schema.sql`：

```bash
$MYSQL --no-defaults --default-character-set=utf8mb4 -u root -h 127.0.0.1 -P 3307 < db/schema.sql
```

输出（本机用 cmd 重定向，`$LASTEXITCODE` 即 schema-exit）：

```text
schema-exit=0
```

```sql
USE find_it_campus;
SHOW TABLES;
SELECT COUNT(*) AS table_count FROM information_schema.tables
 WHERE table_schema='find_it_campus';
```

输出：

```text
Tables_in_find_it_campus
t_access_log
t_category
t_match_result
t_notification
t_pair_score
t_record
t_user
table_count
7
```

**可重复执行验证**：在库内已有完整 seed 数据的情况下再执行一次 `db/schema.sql`，
仍然 `exit=0`、`SHOW TABLES` 7 张（该次验证后又重跑了 seed，下节数据是重建后的结果）：

```text
schema-rerun-exit=0
Tables_in_find_it_campus
t_access_log
t_category
t_match_result
t_notification
t_pair_score
t_record
t_user
table_count
7
```

## 2. 验收② seed 连跑两次：行数 / 分数 / 状态分布一致

执行：

```bash
$MYSQL --no-defaults --default-character-set=utf8mb4 -u root -h 127.0.0.1 -P 3307 < db/seed.sql
```

两次执行退出码：

```text
seed-run1-exit=0
seed-run2-exit=0
```

两次执行后各跑同一组查询，输出逐行 `Compare-Object`：

```text
COMPARE: identical
```

### 2.1 关键表行数（两次一致）

| 对象 | 第一次 | 第二次 |
|---|---:|---:|
| t_user | 3 | 3 |
| t_category | 9 | 9 |
| t_record | 24 | 24 |
| t_match_result | 0 | 0 |
| t_pair_score | 14 | 14 |
| t_notification | 8 | 8 |
| t_access_log | 0 | 0 |

### 2.2 `t_record.status` 分布（两次一致）

| status | 第一次 | 第二次 |
|---|---:|---:|
| PENDING | 21 | 21 |
| COMPLETED | 2 | 2 |
| CLOSED | 1 | 1 |

### 2.3 `t_pair_score.status` 分布（两次一致）

| status | 第一次 | 第二次 |
|---|---:|---:|
| NEW | 6 | 6 |
| CONTACTED | 5 | 5 |
| MATCHED | 2 | 2 |
| IGNORED | 1 | 1 |

### 2.4 分数与聚合（两次一致）

| score_sum | pairs | matched | ignored |
|---:|---:|---:|---:|
| 10.4000 | 14 | 2 | 1 |

### 2.5 原始输出（第一次；第二次逐行相同）

```text
+---------+----------------+----+
| section | obj            | n  |
+---------+----------------+----+
| 行数    | t_user         |  3 |
| 行数    | t_category     |  9 |
| 行数    | t_record       | 24 |
| 行数    | t_match_result |  0 |
| 行数    | t_pair_score   | 14 |
| 行数    | t_notification |  8 |
| 行数    | t_access_log   |  0 |
+---------+----------------+----+
+-----------+----+
| status    | n  |
+-----------+----+
| CLOSED    |  1 |
| COMPLETED |  2 |
| PENDING   | 21 |
+-----------+----+
+-----------+---+
| status    | n |
+-----------+---+
| CONTACTED | 5 |
| IGNORED   | 1 |
| MATCHED   | 2 |
| NEW       | 6 |
+-----------+---+
+----+---------+----------+--------+-----------+
| id | lost_id | found_id | score  | status    |
+----+---------+----------+--------+-----------+
|  1 |       1 |       13 | 0.9600 | CONTACTED |
|  2 |       2 |       14 | 0.9400 | NEW       |
|  3 |       3 |       15 | 0.9200 | CONTACTED |
|  4 |       4 |       16 | 0.8600 | NEW       |
|  5 |       5 |       17 | 0.9500 | CONTACTED |
|  6 |       6 |       18 | 0.9600 | CONTACTED |
|  7 |       7 |       19 | 0.2100 | NEW       |
|  8 |       8 |       20 | 0.3400 | NEW       |
|  9 |       9 |       21 | 0.1800 | NEW       |
| 10 |      10 |       22 | 0.4200 | NEW       |
| 11 |       6 |       23 | 0.9300 | IGNORED   |
| 12 |      10 |       23 | 0.9500 | MATCHED   |
| 13 |      24 |       22 | 0.8800 | CONTACTED |
| 14 |      25 |       13 | 0.9000 | MATCHED   |
+----+---------+----------+--------+-----------+
+-----------+-------+---------+---------+
| score_sum | pairs | matched | ignored |
+-----------+-------+---------+---------+
|   10.4000 |    14 |       2 |       1 |
+-----------+-------+---------+---------+
```

> 时间窗口列（`t_record.completed_at`、`t_pair_score.*_confirmed_at`、`*_unlocked_at`）
> 刻意用 `DATE_SUB(NOW(), INTERVAL n DAY)`：保证「3 天前 / 31 天前 / 保留期内」在任何
> 执行日都成立，代价是这些列每次执行相差毫秒级。验收②比较的是行数、分数与状态分布，
> 上述三列不参与比较；其余列（含 hash、score、status、unlock 的 NULL/非 NULL 形态）完全一致。
> 如果要求时间戳也逐字一致，需要把演示基准日写死，30 天后夹具会自然失效 —— 这是刻意取舍。

## 3. 验收③ 三档分数 + 四态覆盖

```sql
SELECT CASE WHEN score>=0.90 THEN 'A >=0.90'
            WHEN score>=0.75 THEN 'B 0.75-0.90'
            ELSE 'C <0.75' END AS tier,
       COUNT(*) AS pairs
FROM t_pair_score GROUP BY tier ORDER BY tier;

SELECT status, COUNT(*) AS c FROM t_pair_score GROUP BY status ORDER BY status;

SELECT id, lost_id, found_id, score, status, engine, degraded_reason,
       (lost_unlocked_at IS NOT NULL) AS lost_unlocked,
       (found_unlocked_at IS NOT NULL) AS found_unlocked,
       (lost_confirmed_at IS NOT NULL) AS lost_confirmed,
       (found_confirmed_at IS NOT NULL) AS found_confirmed
FROM t_pair_score ORDER BY id;
```

输出：

```text
+-------------+-------+
| tier        | pairs |
+-------------+-------+
| A >=0.90    |     8 |
| B 0.75-0.90 |     2 |
| C <0.75     |     4 |
+-------------+-------+
+-----------+---+
| status    | c |
+-----------+---+
| CONTACTED | 5 |
| IGNORED   | 1 |
| MATCHED   | 2 |
| NEW       | 6 |
+-----------+---+
```

逐对数据（同一批查询）：

```text
+----+---------+----------+--------+-----------+---------------+-----------------+---------------+----------------+----------------+-----------------+
| id | lost_id | found_id | score  | status    | engine        | degraded_reason | lost_unlocked | found_unlocked | lost_confirmed | found_confirmed |
+----+---------+----------+--------+-----------+---------------+-----------------+---------------+----------------+----------------+-----------------+
|  1 |       1 |       13 | 0.9600 | CONTACTED | DEEPSEEK      | NULL            |             0 |              0 |              0 |               0 |
|  2 |       2 |       14 | 0.9400 | NEW       | DEEPSEEK      | NULL            |             0 |              0 |              0 |               0 |
|  3 |       3 |       15 | 0.9200 | CONTACTED | DEEPSEEK      | NULL            |             0 |              0 |              0 |               0 |
|  4 |       4 |       16 | 0.8600 | NEW       | DEEPSEEK      | NULL            |             0 |              1 |              0 |               0 |
|  5 |       5 |       17 | 0.9500 | CONTACTED | DEEPSEEK      | NULL            |             0 |              0 |              0 |               0 |
|  6 |       6 |       18 | 0.9600 | CONTACTED | DEEPSEEK      | NULL            |             0 |              0 |              0 |               0 |
|  7 |       7 |       19 | 0.2100 | NEW       | LOCAL_KEYWORD | NULL            |             0 |              0 |              0 |               0 |
|  8 |       8 |       20 | 0.3400 | NEW       | DEEPSEEK      | NULL            |             0 |              0 |              0 |               0 |
|  9 |       9 |       21 | 0.1800 | NEW       | LOCAL_KEYWORD | EMPTY_KEY       |             0 |              0 |              0 |               0 |
| 10 |      10 |       22 | 0.4200 | NEW       | DEEPSEEK      | NULL            |             0 |              0 |              0 |               0 |
| 11 |       6 |       23 | 0.9300 | IGNORED   | DEEPSEEK      | NULL            |             0 |              0 |              0 |               0 |
| 12 |      10 |       23 | 0.9500 | MATCHED   | DEEPSEEK      | NULL            |             0 |              0 |              1 |               1 |
| 13 |      24 |       22 | 0.8800 | CONTACTED | DEEPSEEK      | NULL            |             1 |              0 |              0 |               0 |
| 14 |      25 |       13 | 0.9000 | MATCHED   | DEEPSEEK      | NULL            |             0 |              0 |              1 |               1 |
+----+---------+----------+--------+-----------+---------------+-----------------+---------------+----------------+----------------+-----------------+
```

- `0.75–0.90` 档两对：`(4,16) 0.86` 仅 FOUND 端已公开、`(24,22) 0.88` 仅 LOST 端已公开，
  满足「含仅一端已公开」。
- MATCHED 两对（11、14 号 pair 行）两端 `confirmed_at` 均非空；非 MATCHED 行没有两端同时非空。
- `(9,21)` 是 `LOCAL_KEYWORD + degraded_reason=EMPTY_KEY` 的降级样本，供 D1 页面/证据口径用。
- pair 11 `(6,23)` 是 IGNORED 夹具，pair 12 `(10,23)` 是同一 COMPLETED 记录的正例夹具（见 §7）。

## 4. 验收④ 隐私扫描：无真实学号、无真实手机号

```sql
-- 账号：只允许 demo01~03
SELECT id, student_no, display_name FROM t_user ORDER BY id;
SELECT COUNT(*) AS non_demo_student_no FROM t_user
 WHERE student_no NOT REGEXP '^demo0[1-3]$';

-- 自由文本里不允许出现 8 位以上连续数字（学号/证件号/号码）
SELECT COUNT(*) AS long_digit_run FROM (
  SELECT title v FROM t_record UNION ALL SELECT description FROM t_record UNION ALL
  SELECT location FROM t_record UNION ALL SELECT publisher_name FROM t_record UNION ALL
  SELECT display_name FROM t_user UNION ALL SELECT title FROM t_notification UNION ALL
  SELECT body FROM t_notification UNION ALL SELECT student_no FROM t_user
) x WHERE v REGEXP '[0-9]{8,}';

-- 自由文本里不允许出现手机号形态（号码只允许在 contact 列，且为假号）
SELECT COUNT(*) AS phone_shape_in_free_text FROM (
  SELECT title v FROM t_record UNION ALL SELECT description FROM t_record UNION ALL
  SELECT location FROM t_record UNION ALL SELECT publisher_name FROM t_record UNION ALL
  SELECT display_name FROM t_user UNION ALL SELECT title FROM t_notification UNION ALL
  SELECT body FROM t_notification
) x WHERE v REGEXP '1[3-9][0-9]{9}';

-- contact 列必须是 138xxxxxxxx 假号形态
SELECT COUNT(*) AS contact_total,
       SUM(contact REGEXP '^138[0-9]{8}$') AS contact_138_format,
       COUNT(DISTINCT contact) AS distinct_contacts
FROM t_record WHERE contact IS NOT NULL;
SELECT COUNT(*) AS contact_not_138 FROM t_record
 WHERE contact IS NOT NULL AND contact NOT REGEXP '^138[0-9]{8}$';
```

输出：

```text
+----+------------+--------------+
| id | student_no | display_name |
+----+------------+--------------+
|  1 | demo01     | 演示甲       |
|  2 | demo02     | 演示乙       |
|  3 | demo03     | 演示丙       |
+----+------------+--------------+
+---------------------+
| non_demo_student_no |
+---------------------+
|                   0 |
+---------------------+
+----------------+
| long_digit_run |
+----------------+
|              0 |
+----------------+
+--------------------------+
| phone_shape_in_free_text |
+--------------------------+
|                        0 |
+--------------------------+
+---------------+--------------------+-------------------+
| contact_total | contact_138_format | distinct_contacts |
+---------------+--------------------+-------------------+
|            22 |                 22 |                22 |
+---------------+--------------------+-------------------+
+-----------------+
| contact_not_138 |
+-----------------+
|               0 |
+-----------------+
```

22 个 contact 全部落在 `1380000xxxx` / `138000001xx` 的连续假号段：

```text
13800000001,13800000002,13800000003,13800000005,13800000006,13800000007,13800000009,
13800000010,13800000011,13800000012,13800000101,13800000102,13800000103,13800000104,
13800000105,13800000106,13800000107,13800000108,13800000109,13800000110,13800000111,13800000112
```

v1 seed 里「学号 20230123」文案已删除；全库文本 8 位以上连续数字 0 命中。
`t_user` 没有 `phone` 列（`phone_col_count=0`，见 §5）。

## 5. 验收⑤ 关键列 / 唯一键 / CHECK / 无自动重置

### 5.1 关键列

```sql
SHOW COLUMNS FROM t_record LIKE 'completed_at';
SHOW COLUMNS FROM t_pair_score LIKE '%confirmed_at';
```

```text
+--------------+----------+------+-----+---------+-------+
| Field        | Type     | Null | Key | Default | Extra |
+--------------+----------+------+-----+---------+-------+
| completed_at | datetime | YES  |     | NULL    |       |
+--------------+----------+------+-----+---------+-------+

+--------------------+----------+------+-----+---------+-------+
| Field              | Type     | Null | Key | Default | Extra |
+--------------------+----------+------+-----+---------+-------+
| lost_confirmed_at  | datetime | YES  |     | NULL    |       |
| found_confirmed_at | datetime | YES  |     | NULL    |       |
+--------------------+----------+------+-----+---------+-------+
```

### 5.2 无触发器 / 无生成列（#2 新规则的库层落点）

```sql
SELECT COUNT(*) AS trigger_count FROM information_schema.triggers
 WHERE trigger_schema='find_it_campus';
SELECT COUNT(*) AS generated_col_count FROM information_schema.columns
 WHERE table_schema='find_it_campus'
   AND (extra LIKE '%VIRTUAL GENERATED%' OR extra LIKE '%STORED GENERATED%');
SELECT COUNT(*) AS phone_col_count FROM information_schema.columns
 WHERE table_schema='find_it_campus' AND table_name='t_user' AND column_name='phone';
SELECT COUNT(*) AS check_constraints FROM information_schema.table_constraints
 WHERE table_schema='find_it_campus' AND constraint_type='CHECK';
```

输出：

```text
trigger_count:       0
generated_col_count: 0
phone_col_count:     0
check_constraints:   23
```

> `information_schema.columns.extra` 里的 `DEFAULT_GENERATED` 是 `CURRENT_TIMESTAMP`
> 默认值的标记，不是生成列；上面的查询专门排除了它，`VIRTUAL/STORED GENERATED` 为 0。

### 5.3 `SHOW CREATE TABLE t_record`

```text
CREATE TABLE `t_record` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键，同时是对外 URL 里的资源 id',
  `type` varchar(8) NOT NULL COMMENT 'LOST=寻物启事 / FOUND=招领启事',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / COMPLETED / CLOSED；只有发布者 PATCH 能改；系统不自动置位',
  `completed_at` datetime DEFAULT NULL COMMENT '本人标记完成的时间；保留期起点；COMPLETED 时非空，其余状态为 NULL',
  `title` varchar(64) NOT NULL COMMENT '标题，2~64 字',
  `description` text NOT NULL COMMENT '详细描述，语义匹配的主文本；应用层限 2~2000 字',
  `category_id` bigint unsigned DEFAULT NULL COMMENT '分类，可空；停用分类不可新选',
  `location` varchar(128) NOT NULL COMMENT '丢失/发现地点',
  `occurred_at` datetime NOT NULL COMMENT '丢失/发现时间（业务时间，非提交时间）',
  `contact` varchar(64) DEFAULT NULL COMMENT '手机号；新写入按 ^1[3-9]\\d{9}$ 校验；历史非手机号值保留',
  `publisher_name` varchar(32) DEFAULT NULL COMMENT '联系人称呼（展示用，非登录身份）',
  `publisher_id` bigint unsigned DEFAULT NULL COMMENT '发布者；应用层新建必填；NULL 仅迁移期',
  `pair_state` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SCORING/READY/DEGRADED；系统写，不是业务状态',
  `pair_scored_at` datetime DEFAULT NULL COMMENT '最后一次打分完成时间',
  `deleted_at` datetime DEFAULT NULL COMMENT '非空=逻辑删除',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间，列表默认排序键',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '只由本人写路径（POST/PUT/PATCH/DELETE）显式 NOW()；系统任务不写',
  PRIMARY KEY (`id`),
  KEY `idx_record_list` (`type`,`status`,`deleted_at`,`created_at`),
  KEY `idx_record_status_created` (`status`,`deleted_at`,`created_at`),
  KEY `idx_record_category` (`category_id`,`deleted_at`,`created_at`),
  KEY `idx_record_publisher` (`publisher_id`,`deleted_at`,`created_at`),
  KEY `idx_record_pair_scan` (`pair_state`,`pair_scored_at`),
  KEY `idx_record_completed` (`status`,`completed_at`),
  FULLTEXT KEY `ft_record_search` (`title`,`description`) /*!50100 WITH PARSER `ngram` */ ,
  CONSTRAINT `fk_record_category` FOREIGN KEY (`category_id`) REFERENCES `t_category` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_record_publisher` FOREIGN KEY (`publisher_id`) REFERENCES `t_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_record_completed_at` CHECK ((((`status` = _utf8mb4'COMPLETED') and (`completed_at` is not null)) or ((`status` <> _utf8mb4'COMPLETED') and (`completed_at` is null)))),
  CONSTRAINT `ck_record_desc_len` CHECK ((char_length(`description`) between 2 and 2000)),
  CONSTRAINT `ck_record_location_len` CHECK ((char_length(`location`) between 1 and 128)),
  CONSTRAINT `ck_record_pair_state` CHECK ((`pair_state` in (_utf8mb4'PENDING',_utf8mb4'SCORING',_utf8mb4'READY',_utf8mb4'DEGRADED'))),
  CONSTRAINT `ck_record_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'COMPLETED',_utf8mb4'CLOSED'))),
  CONSTRAINT `ck_record_title_len` CHECK ((char_length(`title`) between 2 and 64)),
  CONSTRAINT `ck_record_type` CHECK ((`type` in (_utf8mb4'LOST',_utf8mb4'FOUND')))
) ENGINE=InnoDB AUTO_INCREMENT=27 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='失物/招领记录；status 三态与 pair_state 语义分离；completed_at 为保留期起点'
```

要点：`status` CHECK 只有 `PENDING/COMPLETED/CLOSED`（无 `CLAIMED`）；
`completed_at` 与 `status='COMPLETED'` 双向绑定；`updated_at` **没有** `ON UPDATE`；
`AUTO_INCREMENT=27` 保留 id 11/12 的空洞。

### 5.4 `SHOW CREATE TABLE t_pair_score`

```text
CREATE TABLE `t_pair_score` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'pairId',
  `lost_id` bigint unsigned NOT NULL COMMENT 'LOST 记录 id',
  `found_id` bigint unsigned NOT NULL COMMENT 'FOUND 记录 id',
  `score` decimal(5,4) NOT NULL COMMENT '0.0000 - 1.0000',
  `engine` varchar(32) NOT NULL COMMENT 'DEEPSEEK / LOCAL_KEYWORD',
  `degraded_reason` varchar(32) DEFAULT NULL COMMENT 'EMPTY_KEY/UNAUTHORIZED/TIMEOUT/NON_JSON/SCORE_OUT_OF_RANGE/HTTP_ERROR/CIRCUIT_OPEN',
  `prompt_version` varchar(32) NOT NULL DEFAULT 'pair-score-v1',
  `latency_ms` int DEFAULT NULL COMMENT 'LLM 调用耗时；本地引擎为 NULL',
  `prompt_tokens` int DEFAULT NULL,
  `completion_tokens` int DEFAULT NULL,
  `lost_hash` char(64) NOT NULL COMMENT 'lost 内容 SHA-256（小写 hex）',
  `found_hash` char(64) NOT NULL COMMENT 'found 内容 SHA-256（小写 hex）',
  `lost_unlocked_at` datetime DEFAULT NULL COMMENT 'LOST 端授权公开时间',
  `found_unlocked_at` datetime DEFAULT NULL COMMENT 'FOUND 端授权公开时间',
  `lost_unlock_note` varchar(200) DEFAULT NULL COMMENT 'LOST 端附言；写入前已打码',
  `found_unlock_note` varchar(200) DEFAULT NULL COMMENT 'FOUND 端附言；写入前已打码',
  `lost_confirmed_at` datetime DEFAULT NULL COMMENT 'LOST 端确认“这一对就是同一物品”的时间；两端均非空 ⇔ MATCHED',
  `found_confirmed_at` datetime DEFAULT NULL COMMENT 'FOUND 端确认“这一对就是同一物品”的时间；两端均非空 ⇔ MATCHED',
  `status` varchar(16) NOT NULL DEFAULT 'NEW' COMMENT 'NEW / CONTACTED / MATCHED / IGNORED；MATCHED ⇔ 两端 confirmed_at 均非空',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pair_lost_found` (`lost_id`,`found_id`),
  KEY `idx_pair_found_status` (`found_id`,`status`),
  KEY `idx_pair_lost_score` (`lost_id`,`score`),
  KEY `idx_pair_found_score` (`found_id`,`score`),
  KEY `idx_pair_scan` (`engine`,`updated_at`),
  KEY `idx_pair_status_updated` (`status`,`updated_at`),
  CONSTRAINT `fk_pair_found` FOREIGN KEY (`found_id`) REFERENCES `t_record` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_pair_lost` FOREIGN KEY (`lost_id`) REFERENCES `t_record` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_pair_degraded` CHECK (((`degraded_reason` is null) or (`degraded_reason` in (_utf8mb4'EMPTY_KEY',_utf8mb4'UNAUTHORIZED',_utf8mb4'TIMEOUT',_utf8mb4'NON_JSON',_utf8mb4'SCORE_OUT_OF_RANGE',_utf8mb4'HTTP_ERROR',_utf8mb4'CIRCUIT_OPEN')))),
  CONSTRAINT `ck_pair_distinct` CHECK ((`lost_id` <> `found_id`)),
  CONSTRAINT `ck_pair_engine` CHECK ((`engine` in (_utf8mb4'DEEPSEEK',_utf8mb4'LOCAL_KEYWORD'))),
  CONSTRAINT `ck_pair_found_note` CHECK ((((`found_unlock_note` is null) or (char_length(`found_unlock_note`) <= 200)) and ((`found_unlock_note` is null) or (`found_unlocked_at` is not null)))),
  CONSTRAINT `ck_pair_latency` CHECK (((`latency_ms` is null) or (`latency_ms` >= 0))),
  CONSTRAINT `ck_pair_lost_note` CHECK ((((`lost_unlock_note` is null) or (char_length(`lost_unlock_note`) <= 200)) and ((`lost_unlock_note` is null) or (`lost_unlocked_at` is not null)))),
  CONSTRAINT `ck_pair_matched` CHECK ((((`status` = _utf8mb4'MATCHED') and (`lost_confirmed_at` is not null) and (`found_confirmed_at` is not null)) or ((`status` <> _utf8mb4'MATCHED') and ((`lost_confirmed_at` is null) or (`found_confirmed_at` is null))))),
  CONSTRAINT `ck_pair_score` CHECK (((`score` >= 0) and (`score` <= 1))),
  CONSTRAINT `ck_pair_status` CHECK ((`status` in (_utf8mb4'NEW',_utf8mb4'CONTACTED',_utf8mb4'MATCHED',_utf8mb4'IGNORED'))),
  CONSTRAINT `ck_pair_tokens` CHECK ((((`prompt_tokens` is null) or (`prompt_tokens` >= 0)) and ((`completion_tokens` is null) or (`completion_tokens` >= 0))))
) ENGINE=InnoDB AUTO_INCREMENT=15 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='配对分数、逐端授权与逐端确认；一行一对；无已解决态；MATCHED 由两端 confirmed_at 派生'
```

要点：`UNIQUE KEY uk_pair_lost_found (lost_id, found_id)`；任务书要求的三组 CHECK
（score 0–1、status 四态、engine 两值、lost_id<>found_id）全部在位；
`ck_pair_matched` 保证「两端 confirmed 均非空 ⇔ MATCHED」。全表**没有触发器/生成列**，
D1 重算时只更新分数类列、不会自动把 IGNORED 翻回 NEW。

## 6. 验收⑥ 后端公共列表：21 条

构建与启动（B1 未改 server 代码，只验证数据层可被现有读接口消费）：

```bash
cd server
mvn -o -s <workspace>/m2-settings.xml -DskipTests package   # 沙箱下 ~/.m2 不可写，改用工作区本地仓库
export FINDIT_DB_URL='jdbc:mysql://127.0.0.1:3307/find_it_campus?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8&connectTimeout=3000&socketTimeout=10000'
java -jar target/find-it-campus-0.0.1-SNAPSHOT.jar
```

```bash
curl -s http://127.0.0.1:18080/api/health
curl -s 'http://127.0.0.1:18080/api/records?page=1&pageSize=50'
```

健康检查：

```json
{"code":0,"message":"ok","data":{"status":"UP","db":"UP","version":"0.0.1-SNAPSHOT","engine":"local"},"requestId":"644ddab0537d40e4"}
```

列表汇总：

```text
code=0 total=21 listCount=21 statuses=PENDING
hiddenCheck: 23=False 24=False 25=False
```

21 条明细（id / type / status / title）：

```text
26	FOUND	PENDING	捡到一张浅蓝色卡片
22	FOUND	PENDING	捡到一副黑框眼镜
15	FOUND	PENDING	教室捡到卡套内有学生证
3	LOST	PENDING	学生证和校园卡一起丢了
7	LOST	PENDING	丢了科学计算器
18	FOUND	PENDING	快递点捡到三把钥匙带小熊挂件
6	LOST	PENDING	一串宿舍钥匙丢了
19	FOUND	PENDING	捡到一副红色羽毛球拍
4	LOST	PENDING	丢失深蓝色折叠雨伞
1	LOST	PENDING	图书馆丢了黑色钱包
9	LOST	PENDING	丢了一个不锈钢保温杯
16	FOUND	PENDING	食堂捡到一把折叠伞
8	LOST	PENDING	丢失黑色篮球袜一双
14	FOUND	PENDING	捡到白色 iPhone 带透明壳
2	LOST	PENDING	白色苹果手机丢了
20	FOUND	PENDING	捡到灰色羽绒服一件
17	FOUND	PENDING	自习室捡到白色耳机和充电盒
21	FOUND	PENDING	捡到一本大学英语词汇书
5	LOST	PENDING	蓝牙耳机连充电盒一起丢了
10	LOST	PENDING	教学楼丢了一串钥匙（蓝色挂件）
13	FOUND	PENDING	图书馆拾到黑色钱包
```

`24 条 = 21 PENDING + 2 COMPLETED(23/25) + 1 CLOSED(24)`；23/24/25 都不在列表中。
说明：`⑥` 只验公共列表（B1 不改读接口）。COMPLETED/CLOSED 的详情 404、
COMPLETED 召回与 `status=ALL` 的可见性属于 C1/D1，B1 未验证、也未改代码。

## 7. 附加校验：#2 规则夹具、保留期、数据完整性

### 7.1 IGNORED 不作为 COMPLETED 详情通道（同一记录 23，两个查看者）

```sql
SELECT u.id AS user_id, u.student_no, p.id AS pair_id, p.score, p.status,
       CASE WHEN p.score >= 0.90 AND p.status <> 'IGNORED'
            THEN 'CAN_VIEW_23' ELSE '404_23' END AS completed_detail_23
FROM t_user u
JOIN t_record own ON own.publisher_id = u.id AND own.type = 'LOST'
JOIN t_pair_score p ON p.lost_id = own.id AND p.found_id = 23
ORDER BY u.id;
```

```text
+---------+------------+---------+--------+---------+---------------------+
| user_id | student_no | pair_id | score  | status  | completed_detail_23 |
+---------+------------+---------+--------+---------+---------------------+
|       1 | demo01     |      12 | 0.9500 | MATCHED | CAN_VIEW_23         |
|       2 | demo02     |      11 | 0.9300 | IGNORED | 404_23              |
+---------+------------+---------+--------+---------+---------------------+
```

- demo01 的 LOST 10 与 COMPLETED 23 是 `.95 MATCHED` → 可看；
- demo02 的 LOST 6 与同一记录 23 是 `.93 IGNORED` → 必须 404。
  两者分数都 ≥ `completed_visible_threshold=0.90`，唯一差异就是 `status=IGNORED`，
  因此这条夹具能单独证明 #2 的「只认非 IGNORED」分支。
- 23 同时有一对 MATCHED（10,23），满足「COMPLETED 需先有一对 MATCHED」的前置；
  它不会因为 6,23 被 IGNORE 而失去完成资格。

### 7.2 COMPLETED 保留期窗口（不改 status）

```sql
SELECT id, status, completed_at,
       DATE_ADD(completed_at, INTERVAL 30 DAY) AS expire_at, NOW() AS now_ts,
       (DATE_ADD(completed_at, INTERVAL 30 DAY) > NOW()) AS within_retention
FROM t_record WHERE id IN (23,25) ORDER BY id;
```

```text
+----+-----------+---------------------+---------------------+---------------------+------------------+
| id | status    | completed_at        | expire_at           | now_ts              | within_retention |
+----+-----------+---------------------+---------------------+---------------------+------------------+
| 23 | COMPLETED | 2026-10-24 19:22:49 | 2026-11-23 19:22:49 | 2026-10-27 19:22:56 |                1 |
| 25 | COMPLETED | 2026-09-26 19:22:49 | 2026-10-26 19:22:49 | 2026-10-27 19:22:56 |                0 |
+----+-----------+---------------------+---------------------+---------------------+------------------+
```

### 7.3 CLOSED 记录仍保留 pair（读时失效，不批量改写状态）

```sql
SELECT p.id AS pair_id, p.score, p.status AS pair_status, r.status AS record_status
FROM t_pair_score p JOIN t_record r ON r.id = p.lost_id WHERE p.lost_id = 24;
```

```text
+---------+--------+-------------+---------------+
| pair_id | score  | pair_status | record_status |
+---------+--------+-------------+---------------+
|      13 | 0.8800 | CONTACTED   | CLOSED        |
+---------+--------+-------------+---------------+
```

### 7.4 发布者分配（越权测试用）

```sql
SELECT publisher_id, GROUP_CONCAT(id ORDER BY id) AS record_ids, COUNT(*) AS n
FROM t_record GROUP BY publisher_id ORDER BY publisher_id;
```

```text
+--------------+--------------------------------------------------+----+
| publisher_id | record_ids                                       | n  |
+--------------+--------------------------------------------------+----+
|            1 | 1,2,3,4,5,7,8,9,10,14,15,17,18,19,20,21,22,25,26 | 19 |
|            2 | 6,13,16,24                                       |  4 |
|            3 | 23                                               |  1 |
+--------------+--------------------------------------------------+----+
```

### 7.5 hash 口径与完整性

`lost_hash` / `found_hash` 由 seed 用 SQL 按 A1 §8.5 现算：
`sha256(trim(title)\n trim(description)\n trim(location)\n coalesce(categoryId,'')\n type)`，
小写 hex。校验 14 对全部与当前记录内容一致：

```sql
SELECT COUNT(*) AS wrong_hash FROM t_pair_score p
JOIN t_record l ON l.id=p.lost_id JOIN t_record f ON f.id=p.found_id
WHERE p.lost_hash <> SHA2(CONCAT(TRIM(l.title),CHAR(10),TRIM(l.description),CHAR(10),
        TRIM(l.location),CHAR(10),IFNULL(CAST(l.category_id AS CHAR),''),CHAR(10),l.type),256)
   OR p.found_hash <> SHA2(CONCAT(TRIM(f.title),CHAR(10),TRIM(f.description),CHAR(10),
        TRIM(f.location),CHAR(10),IFNULL(CAST(f.category_id AS CHAR),''),CHAR(10),f.type),256);

SELECT COUNT(*) AS wrong_pair_type FROM t_pair_score p
JOIN t_record l ON l.id=p.lost_id JOIN t_record f ON f.id=p.found_id
WHERE l.type<>'LOST' OR f.type<>'FOUND';

SELECT COUNT(*) AS matched_invariant_bad FROM t_pair_score
WHERE (status='MATCHED' AND (lost_confirmed_at IS NULL OR found_confirmed_at IS NULL))
   OR (status<>'MATCHED' AND lost_confirmed_at IS NOT NULL AND found_confirmed_at IS NOT NULL);
```

```text
wrong_hash:            0
wrong_pair_type:       0
matched_invariant_bad: 0
```

## 8. 数据设计说明（为什么是这些列/约束）

- **一条 LOST↔FOUND 一行**：`uk_pair_lost_found` 保证不重复；一条招领可以与多条寻物各成
  pair（seed 中 23 同时有 10/23 MATCHED 与 6/23 IGNORED），处理一对不影响其他 pair。
  没有任何「占用 / 锁定 / 已认领不可再匹配」的列或约束。
- **pair 四态不含「已解决」**：`MATCHED` 由两端 `confirmed_at` 派生，`ck_pair_matched` 兜底；
  `IGNORED` 是终态，重算不重置（库层无触发器/生成列）。
- **记录三态**：`COMPLETED` 与 `completed_at` 双向绑定；`CLOSED` 不写 `completed_at`；
  `CLAIMED` 已从 CHECK 删除。`t_record.updated_at` 去掉了 `ON UPDATE`，系统任务不会
  隐式改它，A2 的「自动任务跑完 status/updated_at 不变」可验证。
- **保留期是读时窗口**：`completed_at + 30 天` 不写成 status，到期不改库；散在
  `t_record.completed_at` + 配置类 `completed_retention_days`，不新增调度。
- **审计只插不改**：`t_access_log` 没有 contact/note/prompt 列；seed 留空，避免假审计行。
- **无真实数据**：`t_user` 无 phone 列；contact 全部是 1380000xxxx 假号；学生证文案里的
  学号已删。

## 9. 已知边界与下游注意事项

1. **没有 v1→v2 迁移**：按人类 #11 拍板，v2 无历史数据，`db/schema.sql` 即全量重建；
   v1→v2 迁移脚本不交付（原第 3 项作废）。
2. **A1 文档尚未合入 main**：B1 按任务书从 `origin/main@21784f6` 切出，契约以
   `feat/v2-A1-arch-contract@b90270e` 为准；本分支没有复制 A1 文档，避免和文档侧改同一文件。
   建议合并顺序 **A1 → B1**，否则 B1 分支缺少 v2 契约上下文。
3. **时间窗口列是相对量**：`completed_at/confirmed_at/unlocked_at` 用 `NOW()-n DAY`，
   保证任何执行日都能跑 3 天/31 天用例；验收②比较的是行数/分数/状态分布，不含这三列。
4. **#2 规则不在 schema 里做“自动判断”**：库层只保证「没有自动重置 status 的触发器/生成列」；
   「IGNORED 不算 COMPLETED 详情通道」是 D1 查询谓词
   （`score >= completed_visible_threshold AND status <> 'IGNORED'`），本任务只提供夹具。
   A1 §9.3 的 A10 仍写着「只看 score 与 pairUsable，不看 pair.status」，与 #2 冲突，
   需文档侧更新 TERM-06 / PAIR-07；B1 不改 A1 文档。
5. **v1 后端枚举未同步**：`RecordStatus` 仍是 `PENDING/CLAIMED/CLOSED`，数据库 CHECK 已删
   `CLAIMED`、新增 `COMPLETED`。默认公共列表（`status=PENDING`）不受影响（本验收已过），
   但 `GET /api/records?status=ALL|COMPLETED` 和 `PATCH status=COMPLETED` 需要 C1 同步枚举，
   否则会出现枚举映射失败或 CHECK 拒绝。**B1 与 C1 必须同批上线，不能只发 B1 到运行环境。**
6. **t_match_result / t_access_log 故意为空**：真实的匹配快照与审计由运行期写入；
   预置假数据会污染评估与审计证据链。
7. **ngram 依赖**：`FULLTEXT ... WITH PARSER ngram` 需 MySQL ≥ 8.0.14 且内核含 ngram；
   本机 8.0.29 建成功。若目标机没有 ngram，按 schema 文末说明注释该行即可，业务行为不变。
8. **本机验证用 3307 隔离实例**：避免与同机其他任务抢 3306；脚本不含端口/密码，
   后端通过 `FINDIT_DB_URL` 指向实际库。

## 10. 复现步骤（最小集）

```bash
# 0) 依赖：MySQL 8.0.29+（含 ngram）、JDK 17、Maven 3.10
# 1) 建库建表（空库一次执行，0 报错，7 表）
mysql --default-character-set=utf8mb4 -u root -p < db/schema.sql

# 2) 导入 seed（可重复执行）
mysql --default-character-set=utf8mb4 -u root -p find_it_campus < db/seed.sql

# 3) 验收查询
mysql --default-character-set=utf8mb4 -u root -p find_it_campus \
  -e "SELECT status,COUNT(*) FROM t_record GROUP BY status;
      SELECT CASE WHEN score>=0.90 THEN 'A' WHEN score>=0.75 THEN 'B' ELSE 'C' END t,
             COUNT(*) FROM t_pair_score GROUP BY t;
      SELECT status,COUNT(*) FROM t_pair_score GROUP BY status;"

# 4) 后端列表（默认 status=PENDING）
cd server && mvn -DskipTests package
FINDIT_DB_URL='jdbc:mysql://127.0.0.1:3306/find_it_campus?...' \
  java -jar target/find-it-campus-0.0.1-SNAPSHOT.jar
curl -s 'http://127.0.0.1:18080/api/records?page=1&pageSize=50'   # total=21
```
