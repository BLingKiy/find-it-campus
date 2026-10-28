-- =====================================================================
-- find-it-campus · v2.1 → v2.0 回滚：AI 发布闸门（ZHYYKFX-85）
--
-- 语义：撤回结构，恢复 v2.0 的读路径。审核/双通道/审计数据随之删除，
-- 这是**有损回滚**，只在灰度失败且决定放弃闸门时执行。
-- 执行前必须先停应用（避免旧进程继续写 review_* 列导致报错）。
--
-- 顺序与 up 相反：先删审计表，再删索引/约束，最后删列。
-- =====================================================================

SET NAMES utf8mb4;
USE `find_it_campus`;

DROP TABLE IF EXISTS `t_review_audit`;

ALTER TABLE `t_record`
  DROP KEY `idx_record_review`,
  DROP KEY `idx_record_visible`,
  DROP CHECK `ck_record_review_attempts`,
  DROP CHECK `ck_record_review_state`,
  DROP COLUMN `is_visible`,
  DROP COLUMN `summary`,
  DROP COLUMN `match_location`,
  DROP COLUMN `match_description`,
  DROP COLUMN `match_title`,
  DROP COLUMN `raw_location`,
  DROP COLUMN `raw_description`,
  DROP COLUMN `raw_title`,
  DROP COLUMN `review_manual_required`,
  DROP COLUMN `review_attempts`,
  DROP COLUMN `review_tokens`,
  DROP COLUMN `review_latency_ms`,
  DROP COLUMN `review_prompt_version`,
  DROP COLUMN `review_model`,
  DROP COLUMN `review_engine`,
  DROP COLUMN `review_reason`,
  DROP COLUMN `reviewed_at`,
  DROP COLUMN `review_state`;

ALTER TABLE `t_pair_score`
  DROP COLUMN `reason`;
