-- =====================================================================
-- v2.1-review-gate-owner-confirm-up.sql
-- 发布闸门返工增量（人类 2026-10-29 拍板）：去掉 ops 人工改判，新增
-- 「AI 失败后由发布者本人确认发布」与「删除清空 raw_*」两个审计动作。
--
-- 适用：已经执行过 v2.1-review-gate-up.sql 的库（初版 CHECK 不包含新动作）。
-- 新库直接执行 db/schema.sql 或初版 up（本文件已同步新动作）也可；再执行本文件
-- 是幂等的 DDL（DROP + ADD 同一个约束），不会重复建列。
--
-- 执行前：停应用（避免写入落到旧约束上）。
-- 回滚：v2.1-review-gate-owner-confirm-down.sql；若库中已有 OWNER_CONFIRM /
--       RAW_PURGE 行，down 会失败——先归档或把这些行转成历史动作，再回滚。
-- =====================================================================

ALTER TABLE `t_review_audit`
  DROP CHECK `ck_review_audit_action`,
  ADD CONSTRAINT `ck_review_audit_action` CHECK (`action` IN
    ('REVIEW','OWNER_CONFIRM','RAW_VIEW','RAW_PURGE','MANUAL_APPROVE','MANUAL_REJECT'));

-- 自检：约束应存在，且新动作可插入（插入后立刻回滚，不留测试数据）
-- START TRANSACTION;
-- INSERT INTO t_review_audit (record_id, action, actor_type)
--   SELECT id, 'OWNER_CONFIRM', 'SYSTEM' FROM t_record LIMIT 1;
-- ROLLBACK;
