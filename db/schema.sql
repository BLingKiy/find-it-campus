-- =====================================================================
-- find-it-campus · 校园失物招领 · 数据库结构（三张表）
-- 任务：ZHYYKFX-4 · 1.2 架构与接口边界
-- 目标引擎：MySQL 8.0.x / InnoDB / utf8mb4
-- 说明：本脚本幂等（可重复执行），但会 DROP 已有数据。
--       生产/正式数据请勿执行本脚本，改用逐条 ALTER（见 docs/architecture-baseline.md §9.6）。
-- 依赖：FULLTEXT ... WITH PARSER ngram 需 MySQL 8.0.14+ 且内核含 ngram 解析器。
--       若该语句报错，注释掉它即可，接口契约与业务行为不变（退化路径见文末 §附）。
-- =====================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `find_it_campus`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE `find_it_campus`;

-- 建表顺序：先主数据（分类），再记录，最后快照（都引用前两者）
DROP TABLE IF EXISTS `t_match_result`;
DROP TABLE IF EXISTS `t_record`;
DROP TABLE IF EXISTS `t_category`;

-- ---------------------------------------------------------------------
-- 表 1：t_category 物品分类字典（主数据）
-- 所有权：category 上下文
-- 为什么独立成表而不是 ENUM 列：调整分类（增/改名/停用）不该要求 DDL + 全量数据迁移
-- 不变量：code 唯一；分类只停用不物理删除（历史记录要能显示名称）
-- ---------------------------------------------------------------------
CREATE TABLE `t_category` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `code`       VARCHAR(32)     NOT NULL               COMMENT '稳定标识，如 WALLET；程序内引用用它，不用 id',
  `name`       VARCHAR(32)     NOT NULL               COMMENT '中文名，前端下拉与标签展示（统一语言：与业务口径同词）',
  `keywords`   VARCHAR(255)              DEFAULT NULL COMMENT '本地关键词匹配用的扩展词表，空格分隔；NULL=只靠 name',
  `sort_order` SMALLINT        NOT NULL DEFAULT 0     COMMENT '展示顺序，小的在前',
  `is_active`  TINYINT(1)      NOT NULL DEFAULT 1     COMMENT '1=可选 0=停用（历史数据仍保留，不出现在下拉与筛选）',
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_category_code` (`code`),
  KEY `idx_category_active_sort` (`is_active`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='物品分类字典（主数据，只读消费方：record / match）';

-- ---------------------------------------------------------------------
-- 表 2：t_record 失物/招领记录（业务主表）
-- 所有权：record 上下文
-- 一张表承载两类：type=LOST 寻物启事（我丢了东西）/ FOUND 招领启事（我捡到东西）
-- 不变量：
--   1. type、status 取值受限（CHECK）
--   2. 逻辑删除的记录（deleted_at IS NOT NULL）对全部读接口不可见
--   3. 状态迁移只能按 docs/architecture-baseline.md §7.4 的流转表
--   4. occurred_at 不得晚于提交时间（业务时间不能是未来 —— 基线放在应用层校验，见 api-contract Q-08）
-- ---------------------------------------------------------------------
CREATE TABLE `t_record` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，同时是对外 URL 里的资源 id',
  `type`           VARCHAR(8)      NOT NULL               COMMENT 'LOST=寻物启事 / FOUND=招领启事',
  `status`         VARCHAR(16)     NOT NULL DEFAULT 'PENDING'
                                                            COMMENT 'PENDING=待领 CLAIMED=已领 CLOSED=已关闭/下架',
  `title`          VARCHAR(64)     NOT NULL               COMMENT '标题，2~64 字（上限须与 1.1 确认的校验上限一致，Q-04）',
  `description`    TEXT            NOT NULL               COMMENT '详细描述，语义匹配的主文本；应用层限 2~2000 字',
  `category_id`    BIGINT UNSIGNED          DEFAULT NULL  COMMENT '分类，可空（允许"说不上是哪一类"）；停用分类不可新选',
  `location`       VARCHAR(128)    NOT NULL               COMMENT '丢失/发现地点，如"图书馆三楼自习区"',
  `occurred_at`    DATETIME        NOT NULL               COMMENT '丢失/发现时间（业务时间，非提交时间）',
  `contact`        VARCHAR(64)              DEFAULT NULL  COMMENT '联系方式（手机/QQ/微信）。是否必填与是否脱敏=待拍板 Q-01',
  `publisher_name` VARCHAR(32)              DEFAULT NULL  COMMENT '联系人称呼（无登录体系，由提交人自填）。若 1.1 判定不需要则删列 Q-01',
  `deleted_at`     DATETIME                 DEFAULT NULL  COMMENT '逻辑删除时间，NULL=未删除（ADR-006）',
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间，列表默认排序键',
  `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
  PRIMARY KEY (`id`),
  -- 列表主查询：type=? AND status=? AND deleted_at IS NULL ORDER BY created_at DESC, id DESC
  -- 三个等值列在前、排序列在后 ⇒ 免 filesort；同时覆盖 3.2 匹配候选池取数
  KEY `idx_record_list` (`type`, `status`, `deleted_at`, `created_at`),
  -- 分类筛选路径
  KEY `idx_record_category` (`category_id`, `deleted_at`, `created_at`),
  -- 关键词检索与本地匹配取数的加速（可选，见文末 §附；不建则退化为 LIKE）
  FULLTEXT KEY `ft_record_search` (`title`, `description`) WITH PARSER ngram,
  CONSTRAINT `fk_record_category` FOREIGN KEY (`category_id`) REFERENCES `t_category` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_record_type`   CHECK (`type` IN ('LOST', 'FOUND')),
  CONSTRAINT `ck_record_status` CHECK (`status` IN ('PENDING', 'CLAIMED', 'CLOSED')),
  CONSTRAINT `ck_record_title_len`  CHECK (CHAR_LENGTH(`title`) BETWEEN 2 AND 64),
  CONSTRAINT `ck_record_desc_len`   CHECK (CHAR_LENGTH(`description`) BETWEEN 2 AND 2000),
  CONSTRAINT `ck_record_location_len` CHECK (CHAR_LENGTH(`location`) BETWEEN 1 AND 128)
  -- 未建 idx_record_occurred：1.1 未要求按发生时间筛选/排序，无读取方即不建（避免无收益的写入成本）。
  -- 若 1.1 增加"时间范围筛选"需求，再补：KEY idx_record_occurred (occurred_at, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='失物/招领记录主表';

-- ---------------------------------------------------------------------
-- 表 3：t_match_result 语义匹配快照
-- 所有权：match 上下文
-- 存在理由：5.1「匹配效果评估」与 6.2「录屏证据」需要可追溯的历史；本地/外部两种实现的 A/B 对比
-- 写入语义：POST /api/match 每次成功调用写一行（含返回空数组的调用）；写失败只告警，不影响响应（§7.4 一致性口径）
-- 已知取舍：候选存 JSON ⇒ 无法建外键、无法 SQL 级聚合、可能引用已删除记录（由回表过滤吸收）
--            若需 SQL 统计命中率 ⇒ 触发第四张表 t_match_candidate，须先报人类（Q-09）
-- ---------------------------------------------------------------------
CREATE TABLE `t_match_result` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '快照主键，即响应体 data.matchId',
  `input_text`        TEXT            NOT NULL               COMMENT '本次输入的原始描述（复现评估用）',
  -- 3.1（2026-10-22）放宽为可空：契约 §8.1 允许调用不带 type（不限类型），而 §8.5 要求每次成功调用都写快照，
  -- 二者在原 DDL（NOT NULL）下无解。此时"本次要找的类型"这个事实不存在，编一个 LOST/FOUND 是污染评估数据。
  -- 变更属放宽方向（可空 → NOT NULL 才是不可逆的窄化），ALTER 见文末「附 2」。
  `input_type`        VARCHAR(8)                DEFAULT NULL COMMENT '本次要找的记录类型 LOST/FOUND；NULL=不限类型',
  `input_category_id` BIGINT UNSIGNED          DEFAULT NULL  COMMENT '本次传入的分类过滤/加权参数',
  `impl`              VARCHAR(32)     NOT NULL               COMMENT '产出结果的引擎标识：LOCAL_KEYWORD / EXTERNAL_EMBEDDING',
  `top_n`             SMALLINT        NOT NULL               COMMENT '请求参数留痕：返回条数上限',
  `min_score`         DECIMAL(6,4)    NOT NULL               COMMENT '请求参数留痕：相似度阈值，0~1',
  `candidates`        JSON            NOT NULL               COMMENT '候选数组 [{"recordId":1,"score":0.83,"reason":"…"}]，与响应体 items 同构（不含展示字段）',
  `cost_ms`           INT                      DEFAULT NULL  COMMENT '引擎耗时（毫秒），性能证据；未采集时为 NULL',
  `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '调用时间',
  PRIMARY KEY (`id`),
  KEY `idx_match_result_impl_created` (`impl`, `created_at`),
  KEY `idx_match_result_created` (`created_at`),
  CONSTRAINT `fk_match_result_category` FOREIGN KEY (`input_category_id`) REFERENCES `t_category` (`id`)
    ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `ck_match_result_type`  CHECK (`input_type` IS NULL OR `input_type` IN ('LOST', 'FOUND')),
  CONSTRAINT `ck_match_result_impl`  CHECK (`impl` IN ('LOCAL_KEYWORD', 'EXTERNAL_EMBEDDING')),
  CONSTRAINT `ck_match_result_score` CHECK (`min_score` >= 0 AND `min_score` <= 1)
  -- 不给 candidates 加 CHECK(JSON_VALID(...))：JSON 列类型本身已在写入时校验，再加约束是纯噪声
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='/api/match 调用快照（评估证据链，不参与业务读写路径）';

-- =====================================================================
-- 附 1：ngram 全文索引的前置检查与退化路径
--   检查：SHOW VARIABLES LIKE 'ngram_token_size';        -- 无此变量 ⇒ 内核不含 ngram
--         SELECT VERSION();                              -- 需 >= 8.0.14
--   中文建议 ngram_token_size=2（在 my.ini / my.cnf 的 [mysqld] 段设置后重启，再建索引）
--   若不想改服务器配置：删掉 FULLTEXT KEY ... WITH PARSER ngram 这一行，
--   改用 ALTER TABLE t_record ADD FULLTEXT KEY ft_record_search (title, description);
--   （默认 parser 按空格切词，中文召回效果差，因此 KeywordMatchEngine 必须能走
--    "候选池 + 内存打分"路径，不依赖 SQL 全文检索 —— 这是接口设计的容错点）
--
-- 附 2：结构变更流程（无迁移工具的约定，ADR-015）
--   ① 改本文件 → ② 在提交说明里写清 ALTER 语句 → ③ 本地执行 ALTER 或 TRUNCATE 重建
--   ④ 同步改 docs/architecture-baseline.md §7 字段表 + docs/api-contract.md 字段表
--   已入库的列不要改名/改语义：数据一旦存在即不可逆（§11 不可逆点①）
--
-- 已执行的变更（按时间倒序）：
--   2026-10-22 · 3.1 后端接口：t_match_result.input_type 放宽为可空
--     —— 契约 §8.1 允许 /api/match 不带 type（不限类型），而 §8.5 要求每次成功调用都写快照，
--        原 DDL 的 NOT NULL 让这两条无法同时满足；此时"本次要找的类型"这个事实不存在，
--        编一个 LOST/FOUND 会污染 5.1 的评估数据。属放宽（窄化才是不可逆方向）。
--     ALTER TABLE t_match_result
--       MODIFY COLUMN `input_type` VARCHAR(8) DEFAULT NULL COMMENT '本次要找的记录类型 LOST/FOUND；NULL=不限类型';
--     ALTER TABLE t_match_result DROP CHECK `ck_match_result_type`;
--     ALTER TABLE t_match_result ADD CONSTRAINT `ck_match_result_type`
--       CHECK (`input_type` IS NULL OR `input_type` IN ('LOST', 'FOUND'));
-- =====================================================================
