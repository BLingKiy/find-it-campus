-- =====================================================================
-- v2.1-review-gate-owner-confirm-down.sql
-- 把 t_review_audit 的动作 CHECK 回退到初版集合。
--
-- 注意：如果库中已存在 OWNER_CONFIRM / RAW_PURGE 行，本脚本会失败。
-- 这是有意的：回退结构不能悄悄丢掉"本人确认发布"与"原文已清除"的审计证据。
-- 先归档（或把这些行转成 REVIEW/RAW_VIEW 的历史口径）再执行。
-- =====================================================================

ALTER TABLE `t_review_audit`
  DROP CHECK `ck_review_audit_action`,
  ADD CONSTRAINT `ck_review_audit_action` CHECK (`action` IN
    ('REVIEW','MANUAL_APPROVE','MANUAL_REJECT','RAW_VIEW'));
