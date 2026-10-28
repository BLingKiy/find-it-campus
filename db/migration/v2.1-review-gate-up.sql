-- =====================================================================
-- find-it-campus · v2.0 → v2.1 增量迁移：AI 发布闸门（ZHYYKFX-85）
--
-- 适用：已有 v2.0 七表数据，需要【原地升级】的库。
-- 空库请直接执行 db/schema.sql（已包含本迁移的全部结构）。
--
-- 兼容性承诺：
--   1. review_state 默认 'APPROVED'：迁移前所有行、seed、findit.review.mode=off
--      的新行都视为已通过，v2.0 的读路径行为不变（回归基线）。
--   2. 新列全部可空或带默认值；生成列 is_visible 由 review_state/deleted_at 派生，
--      不改变任何既有写入语句的语义。
--   3. 本脚本只做 DDL，不删改任何业务数据；不可重复执行（MySQL 8 ALTER ADD COLUMN
--      不支持 IF NOT EXISTS）。重复执行失败不会留下半套结构：每条语句独立提交，
--      失败点之前的列已加、之后的没有；重跑前先用 SHOW COLUMNS 对齐。
--   4. 回滚脚本：db/migration/v2.1-review-gate-down.sql。
-- =====================================================================

SET NAMES utf8mb4;
USE `find_it_campus`;

-- ---------------------------------------------------------------------
-- 1. t_record：审核态 + 审计元数据 + 双通道文本 + 摘要 + 可见性生成列
-- ---------------------------------------------------------------------
ALTER TABLE `t_record`
  ADD COLUMN `review_state` VARCHAR(16) NOT NULL DEFAULT 'APPROVED'
    COMMENT 'PENDING/APPROVED/REJECTED；只有审核链路写，不参与 status 状态机' AFTER `pair_scored_at`,
  ADD COLUMN `reviewed_at` DATETIME DEFAULT NULL
    COMMENT '最后一次审核完成时间' AFTER `review_state`,
  ADD COLUMN `review_reason` VARCHAR(500) DEFAULT NULL
    COMMENT '判定理由（≤3 条，中文，分号分隔）；不含原文' AFTER `reviewed_at`,
  ADD COLUMN `review_engine` VARCHAR(32) DEFAULT NULL
    COMMENT '审核引擎：DEEPSEEK / RULE_DOUBLE_CHECK / MANUAL / OFF' AFTER `review_reason`,
  ADD COLUMN `review_model` VARCHAR(64) DEFAULT NULL
    COMMENT '模型名；不落 key' AFTER `review_engine`,
  ADD COLUMN `review_prompt_version` VARCHAR(32) DEFAULT NULL
    COMMENT '后处理 prompt 版本，如 pair-postprocess-v1' AFTER `review_model`,
  ADD COLUMN `review_latency_ms` INT DEFAULT NULL
    COMMENT '审核调用耗时；未调用为 NULL' AFTER `review_prompt_version`,
  ADD COLUMN `review_tokens` INT DEFAULT NULL
    COMMENT 'prompt+completion token 合计；缺失为 NULL，不估算' AFTER `review_latency_ms`,
  ADD COLUMN `review_attempts` INT NOT NULL DEFAULT 0
    COMMENT 'AI 审核尝试次数（含失败）' AFTER `review_tokens`,
  ADD COLUMN `review_manual_required` TINYINT(1) NOT NULL DEFAULT 0
    COMMENT '1=AI 连续失败/人工驳回后待人工处理；该行永不被自动上架' AFTER `review_attempts`,
  ADD COLUMN `raw_title` VARCHAR(64) DEFAULT NULL
    COMMENT '原文标题；仅本人详情可见' AFTER `review_manual_required`,
  ADD COLUMN `raw_description` TEXT DEFAULT NULL
    COMMENT '原文描述；仅本人详情可见' AFTER `raw_title`,
  ADD COLUMN `raw_location` VARCHAR(128) DEFAULT NULL
    COMMENT '原文地点；仅本人详情可见' AFTER `raw_description`,
  ADD COLUMN `match_title` VARCHAR(64) DEFAULT NULL
    COMMENT '比对文本标题（仅剥离身份类，地点/时间/特征保留）' AFTER `raw_location`,
  ADD COLUMN `match_description` TEXT DEFAULT NULL
    COMMENT '比对文本描述（同上）' AFTER `match_title`,
  ADD COLUMN `match_location` VARCHAR(128) DEFAULT NULL
    COMMENT '比对文本地点（保留精准定位，供打分区分）' AFTER `match_description`,
  ADD COLUMN `summary` VARCHAR(64) DEFAULT NULL
    COMMENT '展示摘要（≤40 字，类别+关键特征+模糊地点）' AFTER `match_location`,
  ADD COLUMN `is_visible` TINYINT(1) GENERATED ALWAYS AS (
    CASE WHEN `review_state` = 'APPROVED' AND `deleted_at` IS NULL THEN 1 ELSE 0 END
  ) STORED COMMENT '1=已审核且未删除；对外可见的唯一判据' AFTER `summary`,
  ADD CONSTRAINT `ck_record_review_state`
    CHECK (`review_state` IN ('PENDING', 'APPROVED', 'REJECTED')),
  ADD CONSTRAINT `ck_record_review_attempts` CHECK (`review_attempts` >= 0),
  ADD KEY `idx_record_visible` (`is_visible`, `status`, `deleted_at`, `created_at`),
  ADD KEY `idx_record_review` (`review_state`, `review_manual_required`, `created_at`);

-- 迁移前的历史行：显式补成已通过（默认值已覆盖，这一步只让迁移在
-- 未来默认值被改掉时仍然保持 v2.0 语义）。
UPDATE `t_record`
SET `review_state` = 'APPROVED'
WHERE `review_state` IS NULL OR `review_state` = '';

-- 比对通道的回填：历史行没有 raw/match，读取侧用 COALESCE(match_*, display_*)
-- 兜底，因此这里刻意不复制正文（避免把未脱敏原文写进新列）。

-- ---------------------------------------------------------------------
-- 2. t_pair_score：模型打分依据落库（v2.0 解析后丢弃）
-- ---------------------------------------------------------------------
ALTER TABLE `t_pair_score`
  ADD COLUMN `reason` VARCHAR(200) DEFAULT NULL
    COMMENT '模型给出的 ≤200 字打分依据；v2.0 曾丢弃，v2.1 落库' AFTER `completion_tokens`;

-- ---------------------------------------------------------------------
-- 3. t_review_audit：发布闸门审计（append-only，不含原文）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_review_audit` (
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
-- 4. 迁移后自检（人工执行，期望 1 行且 visible_rows=total_rows）
-- ---------------------------------------------------------------------
-- SELECT COUNT(*) AS total_rows,
--        SUM(`is_visible`) AS visible_rows
-- FROM `t_record` WHERE `deleted_at` IS NULL;
