-- =====================================================================
-- find-it-campus · 校园失物招领 · 数据库结构 v2.1（八张表）
-- 任务：ZHYYKFX-49 · B1 数据层 v2；ZHYYKFX-85 · AI 发布闸门增量
-- 契约来源：docs/design/v2-architecture.md §4.2（A1 冻结稿）+ docs/design/adr/AI-REVIEW-GATE.md
-- 目标引擎：MySQL 8.0.x / InnoDB / utf8mb4_0900_ai_ci
--
-- 执行语义：本脚本面向「空库全量重建」，自身可重复执行 —— 先按外键依赖逆序
--           DROP TABLE IF EXISTS，再重建；执行一次 0 报错，SHOW TABLES 恰好 8 张。
--           脚本会清空已有数据，正式数据请勿执行（已有库用 db/migration/v2.1-review-gate-up.sql）。
--
-- 八张表与所有权（每个上下文只写自己的表，没有两处写同一张表）：
--   t_user          登录身份（无手机号列）
--   t_category      分类字典（只读消费方：record / match / pair）
--   t_record        启事主表（三态 PENDING / COMPLETED / CLOSED + 正交审核态）
--   t_review_audit  发布闸门审计（append-only，不含原文）
--   t_match_result  /api/match 快照（不参与业务读写路径）
--   t_pair_score    每条 LOST↔FOUND 一行（四态 NEW / CONTACTED / MATCHED / IGNORED）
--   t_notification  通知（正文不含号码）
--   t_access_log    联系方式查看审计（append-only）
--
-- 本脚本刻意没有触发器、没有定时事件；唯一生成列是 t_record.is_visible：
--   ① pair 的 status 只由用户显式操作（CONTACT / CONFIRM / IGNORE）写入；
--      文本 hash 变化触发的重算只允许更新 score / engine / latency_ms / tokens / 两个 hash 列，
--      不得自动把 IGNORED 翻回 NEW —— 这是任务书 #2 新规则的库层落点。
--   ② t_record.status 只由发布者手动改；系统任务不得写 status / completed_at。
--   ③ COMPLETED 到期是读时窗口（completed_at + completed_retention_days），到期不改写 status。
--   ④ is_visible 只由 review_state 与 deleted_at 派生，任何写路径都不能直接写它 ——
--      对外可见性因此只有一个事实来源，列表/匹配/配对不可能各写一份而分叉。
--
-- 依赖：FULLTEXT ... WITH PARSER ngram 需 MySQL 8.0.14+ 且内核含 ngram 解析器。
--       若该语句报错，注释掉 ft_record_search 一行即可，业务行为不变（退化路径见文末）。
-- =====================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `find_it_campus`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE `find_it_campus`;

-- 建表按依赖顺序；删表按依赖逆序。
-- 关外键检查是为了让「带数据的库再次执行本脚本」也不因删除顺序报 1451；
-- 删除顺序本身已经是逆序，双重保险。
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS `t_access_log`;
DROP TABLE IF EXISTS `t_review_audit`;
DROP TABLE IF EXISTS `t_notification`;
DROP TABLE IF EXISTS `t_pair_score`;
DROP TABLE IF EXISTS `t_match_result`;
DROP TABLE IF EXISTS `t_record`;
DROP TABLE IF EXISTS `t_category`;
DROP TABLE IF EXISTS `t_user`;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------------------------------------------------
-- 表 1：t_user 登录身份
-- 所有权：auth / user 上下文（B2 只读该表，写入只属于注册/停用路径）
-- 硬点：没有 phone 列。手机号只做格式校验、不做短信验证，不落库；
--       登录名是 student_no（演示账号用 demo01~demo03，不含真实学号）。
-- ---------------------------------------------------------------------
CREATE TABLE `t_user` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `student_no`    VARCHAR(32)     NOT NULL               COMMENT '学号，登录名；唯一；不存手机号',
  `password_hash` VARCHAR(100)    NOT NULL               COMMENT 'bcrypt 哈希，cost >= 10；禁止明文',
  `display_name`  VARCHAR(32)     NOT NULL               COMMENT '展示名',
  `is_active`     TINYINT(1)      NOT NULL DEFAULT 1     COMMENT '1=可登录 0=停用（不做物理删除）',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_student_no` (`student_no`),
  KEY `idx_user_active` (`is_active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='登录用户；不存手机号/邮箱/短信验证码';

-- ---------------------------------------------------------------------
-- 表 2：t_category 物品分类字典（沿用 v1）
-- 所有权：category 上下文
-- 为什么独立成表而不是 ENUM 列：调整分类（增/改名/停用）不该要求 DDL + 全量数据迁移。
-- 不变量：code 唯一；分类只停用不物理删除（历史记录要能显示名称）。
-- ---------------------------------------------------------------------
CREATE TABLE `t_category` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `code`       VARCHAR(32)     NOT NULL               COMMENT '稳定标识，如 WALLET；程序内引用用它，不用 id',
  `name`       VARCHAR(32)     NOT NULL               COMMENT '中文名，前端下拉与标签展示',
  `keywords`   VARCHAR(255)             DEFAULT NULL  COMMENT '本地关键词匹配用的扩展词表，空格分隔；NULL=只靠 name',
  `sort_order` SMALLINT        NOT NULL DEFAULT 0     COMMENT '展示顺序，小的在前',
  `is_active`  TINYINT(1)      NOT NULL DEFAULT 1     COMMENT '1=可选 0=停用（历史数据仍保留，不出现在下拉与筛选）',
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_category_code` (`code`),
  KEY `idx_category_active_sort` (`is_active`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='物品分类字典（只读消费方：record / match / pair）';

-- ---------------------------------------------------------------------
-- 表 3：t_record 启事主表（v1 扩展）
-- 所有权：record 上下文
-- 状态机（只有发布者一条写入路径，见契约 §9.2）：
--   PENDING  正常发布
--   COMPLETED 需本记录已有一对有效 MATCHED pair，由本人手动标记；
--             同一条 UPDATE 写 completed_at=NOW()，ck_record_completed_at 库层兜底
--   CLOSED   本人手动关闭，无前置条件；completed_at 必须为 NULL
-- 系统任务（打分 / 通知 / 扫描 / 到期清扫）不得写 status / completed_at。
-- updated_at 刻意不带 ON UPDATE：只有本人写路径显式 NOW()，自动任务不碰它，
-- 这样 A2 的「自动任务跑完 status 与 updated_at 不变」才可验证。
-- ---------------------------------------------------------------------
CREATE TABLE `t_record` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，同时是对外 URL 里的资源 id',
  `type`           VARCHAR(8)      NOT NULL               COMMENT 'LOST=寻物启事 / FOUND=招领启事',
  `status`         VARCHAR(16)     NOT NULL DEFAULT 'PENDING'
                                     COMMENT 'PENDING / COMPLETED / CLOSED；只有发布者 PATCH 能改；系统不自动置位',
  `completed_at`   DATETIME                 DEFAULT NULL
                                     COMMENT '本人标记完成的时间；保留期起点；COMPLETED 时非空，其余状态为 NULL',
  `title`          VARCHAR(64)     NOT NULL               COMMENT '标题，2~64 字',
  `description`    TEXT            NOT NULL               COMMENT '详细描述，语义匹配的主文本；应用层限 2~2000 字',
  `category_id`    BIGINT UNSIGNED          DEFAULT NULL  COMMENT '分类，可空；停用分类不可新选',
  `location`       VARCHAR(128)    NOT NULL               COMMENT '丢失/发现地点',
  `occurred_at`    DATETIME        NOT NULL               COMMENT '丢失/发现时间（业务时间，非提交时间）',
  `contact`        VARCHAR(64)              DEFAULT NULL  COMMENT '手机号；新写入按 ^1[3-9]\\d{9}$ 校验；历史非手机号值保留',
  `publisher_name` VARCHAR(32)              DEFAULT NULL  COMMENT '联系人称呼（展示用，非登录身份）',
  `publisher_id`   BIGINT UNSIGNED          DEFAULT NULL  COMMENT '发布者；应用层新建必填；NULL 仅迁移期',
  `pair_state`     VARCHAR(16)     NOT NULL DEFAULT 'PENDING'
                                     COMMENT 'PENDING/SCORING/READY/DEGRADED；系统写，不是业务状态',
  `pair_scored_at` DATETIME                 DEFAULT NULL  COMMENT '最后一次打分完成时间',
  -- ------------------------------------------------------------------
  -- AI 发布闸门（ZHYYKFX-85）：审核态与业务 status 正交，不进入三态状态机。
  -- 默认 APPROVED 是为了 v2.0 → v2.1 的向后兼容：迁移前的行、seed、mode=off 的
  -- 新行都视为已通过，读路径行为与 v2.0 完全一致。
  -- ------------------------------------------------------------------
  `review_state`   VARCHAR(16)     NOT NULL DEFAULT 'APPROVED'
                                     COMMENT 'PENDING/APPROVED/REJECTED；只有审核链路写，不参与 status 状态机',
  `reviewed_at`    DATETIME                 DEFAULT NULL COMMENT '最后一次审核完成时间',
  `review_reason`  VARCHAR(500)             DEFAULT NULL COMMENT '判定理由（≤3 条，中文，分号分隔）；不含原文',
  `review_engine`  VARCHAR(32)              DEFAULT NULL COMMENT '审核引擎：DEEPSEEK / RULE_DOUBLE_CHECK / MANUAL / OFF',
  `review_model`   VARCHAR(64)              DEFAULT NULL COMMENT '模型名；不落 key',
  `review_prompt_version` VARCHAR(32)        DEFAULT NULL COMMENT '后处理 prompt 版本，如 pair-postprocess-v1',
  `review_latency_ms` INT                   DEFAULT NULL COMMENT '审核调用耗时；未调用为 NULL',
  `review_tokens`  INT                      DEFAULT NULL COMMENT 'prompt+completion token 合计；缺失为 NULL，不估算',
  `review_attempts` INT            NOT NULL DEFAULT 0 COMMENT 'AI 审核尝试次数（含失败）',
  `review_manual_required` TINYINT(1) NOT NULL DEFAULT 0
                                     COMMENT '1=AI 连续失败/人工驳回后待人工处理；该行永不被自动上架',
  -- 双通道：raw_* 原文（仅本人可见、读取走审计）；match_* 仅剥离身份类信息（出分/contentHash）；
  -- title/description/location 存 display_text（全脱敏 + LLM 泛化），所有对外接口只出这一份。
  `raw_title`      VARCHAR(64)              DEFAULT NULL COMMENT '原文标题；仅本人详情可见',
  `raw_description` TEXT                    DEFAULT NULL COMMENT '原文描述；仅本人详情可见',
  `raw_location`   VARCHAR(128)             DEFAULT NULL COMMENT '原文地点；仅本人详情可见',
  `match_title`    VARCHAR(64)              DEFAULT NULL COMMENT '比对文本标题（仅剥离身份类，地点/时间/特征保留）',
  `match_description` TEXT                  DEFAULT NULL COMMENT '比对文本描述（同上）',
  `match_location` VARCHAR(128)             DEFAULT NULL COMMENT '比对文本地点（保留精准定位，供打分区分）',
  `summary`        VARCHAR(64)              DEFAULT NULL COMMENT '展示摘要（≤40 字，类别+关键特征+模糊地点）',
  -- 可见性单点谓词：所有对外列表/匹配/配对 SQL 只认这一列（生成列，避免各写一份 review_state 条件）。
  `is_visible`     TINYINT(1) GENERATED ALWAYS AS (
                     CASE WHEN `review_state` = 'APPROVED' AND `deleted_at` IS NULL THEN 1 ELSE 0 END
                   ) STORED COMMENT '1=已审核且未删除；对外可见的唯一判据',
  `deleted_at`     DATETIME                 DEFAULT NULL  COMMENT '非空=逻辑删除',
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间，列表默认排序键',
  `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                     COMMENT '只由本人写路径（POST/PUT/PATCH/DELETE）显式 NOW()；系统任务不写',
  PRIMARY KEY (`id`),
  -- 公共列表：type=? AND status='PENDING' AND deleted_at IS NULL ORDER BY created_at DESC
  KEY `idx_record_list` (`type`, `status`, `deleted_at`, `created_at`),
  -- 不带 type 的公共列表：status='PENDING' AND deleted_at IS NULL ORDER BY created_at DESC
  KEY `idx_record_status_created` (`status`, `deleted_at`, `created_at`),
  -- 分类筛选
  KEY `idx_record_category` (`category_id`, `deleted_at`, `created_at`),
  -- /my 三态列表
  KEY `idx_record_publisher` (`publisher_id`, `deleted_at`, `created_at`),
  -- 每日扫描：pair_state IN (...) AND pair_scored_at < ?
  KEY `idx_record_pair_scan` (`pair_state`, `pair_scored_at`),
  -- COMPLETED 保留期召回与到期统计（不改 status）
  KEY `idx_record_completed` (`status`, `completed_at`),
  -- 对外可见集合（列表/匹配/配对共用的单列谓词；生成列可索引）
  KEY `idx_record_visible` (`is_visible`, `status`, `deleted_at`, `created_at`),
  -- 人工审核队列：review_state='PENDING' AND review_manual_required=1
  KEY `idx_record_review` (`review_state`, `review_manual_required`, `created_at`),
  -- 关键词检索加速（可选；退化路径见文末）
  FULLTEXT KEY `ft_record_search` (`title`, `description`) WITH PARSER ngram,
  CONSTRAINT `fk_record_category` FOREIGN KEY (`category_id`) REFERENCES `t_category` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_record_publisher` FOREIGN KEY (`publisher_id`) REFERENCES `t_user` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_record_type` CHECK (`type` IN ('LOST', 'FOUND')),
  CONSTRAINT `ck_record_status` CHECK (`status` IN ('PENDING', 'COMPLETED', 'CLOSED')),
  CONSTRAINT `ck_record_completed_at` CHECK (
    (`status` = 'COMPLETED' AND `completed_at` IS NOT NULL) OR
    (`status` <> 'COMPLETED' AND `completed_at` IS NULL)),
  CONSTRAINT `ck_record_pair_state` CHECK (`pair_state` IN ('PENDING', 'SCORING', 'READY', 'DEGRADED')),
  CONSTRAINT `ck_record_review_state` CHECK (`review_state` IN ('PENDING', 'APPROVED', 'REJECTED')),
  CONSTRAINT `ck_record_review_attempts` CHECK (`review_attempts` >= 0),
  CONSTRAINT `ck_record_title_len` CHECK (CHAR_LENGTH(`title`) BETWEEN 2 AND 64),
  CONSTRAINT `ck_record_desc_len` CHECK (CHAR_LENGTH(`description`) BETWEEN 2 AND 2000),
  CONSTRAINT `ck_record_location_len` CHECK (CHAR_LENGTH(`location`) BETWEEN 1 AND 128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='失物/招领记录；status 三态与 pair_state 语义分离；completed_at 为保留期起点';

-- ---------------------------------------------------------------------
-- 表 3b：t_review_audit 发布闸门审计（append-only）
-- 所有权：record/review 上下文
-- 只插不改不删；回答「谁、哪条、判定、理由、模型、版本、耗时、token」，
-- **没有原文列**：审计不能变成第二份未脱敏语料库。reasons 只存模型给出的
-- 中文理由（≤500 字），不复制 title/description/location。
-- ---------------------------------------------------------------------
CREATE TABLE `t_review_audit` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `record_id`        BIGINT UNSIGNED NOT NULL COMMENT '被审核的记录',
  `action`           VARCHAR(24)     NOT NULL
                       COMMENT 'REVIEW=AI 审核 / MANUAL_APPROVE / MANUAL_REJECT / RAW_VIEW=本人读原文',
  `decision`         VARCHAR(16)              DEFAULT NULL COMMENT 'APPROVE/REJECT/PENDING/ERROR；RAW_VIEW 为 NULL',
  `review_state`     VARCHAR(16)              DEFAULT NULL COMMENT '动作完成后的记录审核态',
  `reason`           VARCHAR(500)             DEFAULT NULL COMMENT '判定理由；不含原文',
  `engine`           VARCHAR(32)              DEFAULT NULL COMMENT 'DEEPSEEK / RULE_DOUBLE_CHECK / MANUAL',
  `model`            VARCHAR(64)              DEFAULT NULL,
  `prompt_version`   VARCHAR(32)              DEFAULT NULL,
  `latency_ms`       INT                      DEFAULT NULL,
  `prompt_tokens`    INT                      DEFAULT NULL,
  `completion_tokens` INT                     DEFAULT NULL,
  `actor_type`       VARCHAR(8)      NOT NULL DEFAULT 'AI' COMMENT 'AI / HUMAN / SYSTEM',
  `actor_id`         BIGINT UNSIGNED          DEFAULT NULL COMMENT 'HUMAN 时为操作人；AI/SYSTEM 为 NULL',
  `request_id`       VARCHAR(64)              DEFAULT NULL COMMENT '与响应体 requestId 同值',
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_review_audit_record` (`record_id`, `created_at`),
  KEY `idx_review_audit_action` (`action`, `created_at`),
  CONSTRAINT `fk_review_audit_record` FOREIGN KEY (`record_id`) REFERENCES `t_record` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_review_audit_action` CHECK (`action` IN
    ('REVIEW','MANUAL_APPROVE','MANUAL_REJECT','RAW_VIEW')),
  CONSTRAINT `ck_review_audit_decision` CHECK (`decision` IS NULL OR `decision` IN
    ('APPROVE','REJECT','PENDING','ERROR')),
  CONSTRAINT `ck_review_audit_state` CHECK (`review_state` IS NULL OR `review_state` IN
    ('PENDING','APPROVED','REJECTED')),
  CONSTRAINT `ck_review_audit_latency` CHECK (`latency_ms` IS NULL OR `latency_ms` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='发布闸门审计；append-only，不含原文';

-- ---------------------------------------------------------------------
-- 表 4：t_match_result /api/match 快照（v1 扩展 impl 取值）
-- 所有权：match 上下文
-- 只做评估证据，不参与业务读写；写入前已脱敏，候选 JSON 与响应 items 同构。
-- ---------------------------------------------------------------------
CREATE TABLE `t_match_result` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '快照主键，即响应体 data.matchId',
  `input_text`        TEXT            NOT NULL               COMMENT '写入前已脱敏',
  `input_type`        VARCHAR(8)                DEFAULT NULL COMMENT 'LOST/FOUND；NULL=不限类型',
  `input_category_id` BIGINT UNSIGNED          DEFAULT NULL  COMMENT '本次传入的分类过滤/加权参数',
  `impl`              VARCHAR(32)     NOT NULL               COMMENT 'LOCAL_KEYWORD / EXTERNAL_EMBEDDING',
  `top_n`             SMALLINT        NOT NULL               COMMENT '请求参数留痕：返回条数上限',
  `min_score`         DECIMAL(6,4)    NOT NULL               COMMENT '请求参数留痕：相似度阈值，0~1',
  `candidates`        JSON            NOT NULL               COMMENT '写入前已脱敏；与响应 items 同构（不含展示字段）',
  `cost_ms`           INT                      DEFAULT NULL  COMMENT '引擎耗时（毫秒）；未采集时为 NULL',
  `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '调用时间',
  PRIMARY KEY (`id`),
  KEY `idx_match_result_impl_created` (`impl`, `created_at`),
  KEY `idx_match_result_created` (`created_at`),
  CONSTRAINT `fk_match_result_category` FOREIGN KEY (`input_category_id`) REFERENCES `t_category` (`id`)
    ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `ck_match_result_type` CHECK (`input_type` IS NULL OR `input_type` IN ('LOST', 'FOUND')),
  CONSTRAINT `ck_match_result_impl` CHECK (`impl` IN ('LOCAL_KEYWORD', 'EXTERNAL_EMBEDDING')),
  CONSTRAINT `ck_match_result_score` CHECK (`min_score` >= 0 AND `min_score` <= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='/api/match 调用快照；不参与业务读写路径';

-- ---------------------------------------------------------------------
-- 表 5：t_pair_score 每条 LOST↔FOUND 一行（核心新表）
-- 所有权：pair 上下文
-- 口径（任务书已冻结，不得改）：
--   · 唯一键 (lost_id, found_id)，一行一对；一条招领可与多条寻物各成 pair
--   · status 四态 NEW / CONTACTED / MATCHED / IGNORED，只描述这一对内部的进展，
--     没有「已解决」；不引入占用/锁定语义
--   · 两端 confirmed_at 均非空 ⇔ MATCHED，由 ck_pair_matched 库层兜底
--   · IGNORED 是终态；文本 hash 变化触发的重算只更新分数类列，不重置 status
--     （本表无触发器/生成列，重算列清单由 D1 的 UPDATE 语句约束）
--   · 没有「自动置 status」的默认值表达式：status 默认 NEW 仅用于首次插入
-- ---------------------------------------------------------------------
CREATE TABLE `t_pair_score` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'pairId',
  `lost_id`           BIGINT UNSIGNED NOT NULL COMMENT 'LOST 记录 id',
  `found_id`          BIGINT UNSIGNED NOT NULL COMMENT 'FOUND 记录 id',
  `score`             DECIMAL(5,4)    NOT NULL COMMENT '0.0000 - 1.0000',
  `engine`            VARCHAR(32)     NOT NULL COMMENT 'DEEPSEEK / LOCAL_KEYWORD',
  `degraded_reason`   VARCHAR(32)              DEFAULT NULL
                        COMMENT 'EMPTY_KEY/UNAUTHORIZED/TIMEOUT/NON_JSON/SCORE_OUT_OF_RANGE/HTTP_ERROR/CIRCUIT_OPEN',
  `prompt_version`    VARCHAR(32)     NOT NULL DEFAULT 'pair-score-v2',
  `latency_ms`        INT                      DEFAULT NULL COMMENT 'LLM 调用耗时；本地引擎为 NULL',
  `prompt_tokens`     INT                      DEFAULT NULL,
  `completion_tokens` INT                      DEFAULT NULL,
  `reason`            VARCHAR(200)             DEFAULT NULL COMMENT '模型给出的 ≤200 字打分依据；v2.0 曾丢弃，v2.1 落库',
  `lost_hash`         CHAR(64)        NOT NULL COMMENT 'lost 内容 SHA-256（小写 hex）',
  `found_hash`        CHAR(64)        NOT NULL COMMENT 'found 内容 SHA-256（小写 hex）',
  `lost_unlocked_at`  DATETIME                 DEFAULT NULL COMMENT 'LOST 端授权公开时间',
  `found_unlocked_at` DATETIME                 DEFAULT NULL COMMENT 'FOUND 端授权公开时间',
  `lost_unlock_note`  VARCHAR(200)             DEFAULT NULL COMMENT 'LOST 端附言；写入前已打码',
  `found_unlock_note` VARCHAR(200)             DEFAULT NULL COMMENT 'FOUND 端附言；写入前已打码',
  `lost_confirmed_at` DATETIME                  DEFAULT NULL
                        COMMENT 'LOST 端确认“这一对就是同一物品”的时间；两端均非空 ⇔ MATCHED',
  `found_confirmed_at` DATETIME                 DEFAULT NULL
                        COMMENT 'FOUND 端确认“这一对就是同一物品”的时间；两端均非空 ⇔ MATCHED',
  `status`            VARCHAR(16)     NOT NULL DEFAULT 'NEW'
                        COMMENT 'NEW / CONTACTED / MATCHED / IGNORED；MATCHED ⇔ 两端 confirmed_at 均非空',
  `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pair_lost_found` (`lost_id`, `found_id`),
  KEY `idx_pair_found_status` (`found_id`, `status`),
  KEY `idx_pair_lost_score` (`lost_id`, `score`),
  KEY `idx_pair_found_score` (`found_id`, `score`),
  KEY `idx_pair_scan` (`engine`, `updated_at`),
  KEY `idx_pair_status_updated` (`status`, `updated_at`),
  CONSTRAINT `fk_pair_lost` FOREIGN KEY (`lost_id`) REFERENCES `t_record` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_pair_found` FOREIGN KEY (`found_id`) REFERENCES `t_record` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_pair_distinct` CHECK (`lost_id` <> `found_id`),
  CONSTRAINT `ck_pair_score` CHECK (`score` >= 0 AND `score` <= 1),
  CONSTRAINT `ck_pair_engine` CHECK (`engine` IN ('DEEPSEEK', 'LOCAL_KEYWORD')),
  CONSTRAINT `ck_pair_status` CHECK (`status` IN ('NEW', 'CONTACTED', 'MATCHED', 'IGNORED')),
  CONSTRAINT `ck_pair_matched` CHECK (
    (`status` = 'MATCHED' AND `lost_confirmed_at` IS NOT NULL AND `found_confirmed_at` IS NOT NULL) OR
    (`status` <> 'MATCHED' AND (`lost_confirmed_at` IS NULL OR `found_confirmed_at` IS NULL))),
  CONSTRAINT `ck_pair_degraded` CHECK (`degraded_reason` IS NULL OR `degraded_reason` IN
    ('EMPTY_KEY','UNAUTHORIZED','TIMEOUT','NON_JSON','SCORE_OUT_OF_RANGE','HTTP_ERROR','CIRCUIT_OPEN')),
  CONSTRAINT `ck_pair_latency` CHECK (`latency_ms` IS NULL OR `latency_ms` >= 0),
  CONSTRAINT `ck_pair_tokens` CHECK (
    (`prompt_tokens` IS NULL OR `prompt_tokens` >= 0) AND
    (`completion_tokens` IS NULL OR `completion_tokens` >= 0)),
  CONSTRAINT `ck_pair_lost_note` CHECK (
    (`lost_unlock_note` IS NULL OR CHAR_LENGTH(`lost_unlock_note`) <= 200) AND
    (`lost_unlock_note` IS NULL OR `lost_unlocked_at` IS NOT NULL)),
  CONSTRAINT `ck_pair_found_note` CHECK (
    (`found_unlock_note` IS NULL OR CHAR_LENGTH(`found_unlock_note`) <= 200) AND
    (`found_unlock_note` IS NULL OR `found_unlocked_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='配对分数、逐端授权与逐端确认；一行一对；无已解决态；MATCHED 由两端 confirmed_at 派生';

-- ---------------------------------------------------------------------
-- 表 6：t_notification 通知（内容不含号码）
-- 所有权：notification 上下文
-- 唯一键 (user_id, pair_id, type) 保证重复打分/重复扫描不刷屏；
-- pair_id 可空（SYSTEM 通知）。title/body 写入前已打码，不得含手机号。
-- ---------------------------------------------------------------------
CREATE TABLE `t_notification` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`    BIGINT UNSIGNED NOT NULL COMMENT '接收人',
  `pair_id`    BIGINT UNSIGNED          DEFAULT NULL COMMENT '关联 pair；SYSTEM 通知可为 NULL',
  `type`       VARCHAR(32)     NOT NULL COMMENT 'PAIR_MATCH / PAIR_UNLOCK / SYSTEM',
  `title`      VARCHAR(128)    NOT NULL COMMENT '写入前已打码',
  `body`       VARCHAR(500)    NOT NULL COMMENT '写入前已打码；不得含手机号',
  `read_at`    DATETIME                 DEFAULT NULL COMMENT 'NULL=未读',
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notification_user_pair_type` (`user_id`, `pair_id`, `type`),
  KEY `idx_notification_user_read` (`user_id`, `read_at`, `created_at`),
  KEY `idx_notification_pair` (`pair_id`),
  CONSTRAINT `fk_notification_user` FOREIGN KEY (`user_id`) REFERENCES `t_user` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_notification_pair` FOREIGN KEY (`pair_id`) REFERENCES `t_pair_score` (`id`)
    ON DELETE SET NULL ON UPDATE RESTRICT,
  CONSTRAINT `ck_notification_type` CHECK (`type` IN ('PAIR_MATCH', 'PAIR_UNLOCK', 'SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='通知；body/title 写入前脱敏';

-- ---------------------------------------------------------------------
-- 表 7：t_access_log 联系方式查看审计（append-only）
-- 所有权：accesslog 上下文
-- 只插不改不删；没有 contact / note / prompt 列 —— 审计只回答
-- “谁在何时看了哪条 pair、结果如何”，不复制敏感值。
-- fail-closed：返回号码前必须成功插入本表。
-- ---------------------------------------------------------------------
CREATE TABLE `t_access_log` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `pair_id`     BIGINT UNSIGNED NOT NULL,
  `viewer_id`   BIGINT UNSIGNED NOT NULL,
  `viewer_side` VARCHAR(8)      NOT NULL COMMENT 'LOST / FOUND',
  `target_side` VARCHAR(8)      NOT NULL COMMENT 'LOST / FOUND',
  `score`       DECIMAL(5,4)    NOT NULL COMMENT '查看时分数快照',
  `result`      VARCHAR(24)     NOT NULL
                  COMMENT 'GRANTED/DENIED_NOT_PUBLIC/DENIED_NOT_PROVIDED/DENIED_LOW_SCORE/DENIED_NOT_OWNER/DENIED_PAIR_INVALID',
  `request_id`  VARCHAR(64)              DEFAULT NULL COMMENT '与响应体 requestId 同值',
  `ip`          VARCHAR(45)              DEFAULT NULL COMMENT '客户端 IP；反代场景取可信 XFF',
  `user_agent`  VARCHAR(255)             DEFAULT NULL,
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_access_pair_created` (`pair_id`, `created_at`),
  KEY `idx_access_viewer_created` (`viewer_id`, `created_at`),
  KEY `idx_access_result_created` (`result`, `created_at`),
  CONSTRAINT `fk_access_pair` FOREIGN KEY (`pair_id`) REFERENCES `t_pair_score` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_access_viewer` FOREIGN KEY (`viewer_id`) REFERENCES `t_user` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_access_side` CHECK (`viewer_side` IN ('LOST', 'FOUND')
    AND `target_side` IN ('LOST', 'FOUND')),
  CONSTRAINT `ck_access_result` CHECK (`result` IN
    ('GRANTED','DENIED_NOT_PUBLIC','DENIED_NOT_PROVIDED','DENIED_LOW_SCORE','DENIED_NOT_OWNER','DENIED_PAIR_INVALID'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='联系方式查看审计；只插不改不删，不记录号码/附言原文';

-- =====================================================================
-- 附：ngram 全文索引的前置检查与退化路径
--   检查：SHOW VARIABLES LIKE 'ngram_token_size';   -- 无此变量 ⇒ 内核不含 ngram
--         SELECT VERSION();                         -- 需 >= 8.0.14
--   中文建议 ngram_token_size=2（my.ini 的 [mysqld] 段设置后重启，再建索引）。
--   若本机建索引报错：注释掉 `FULLTEXT KEY ft_record_search ... WITH PARSER ngram` 一行，
--   业务行为不变 —— /api/match 与 pair 链路的候选池 + 内存打分不依赖 SQL 全文检索。
-- =====================================================================
