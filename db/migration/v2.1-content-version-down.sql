-- =====================================================================
-- find-it-campus · v2.1 → v2.0 回滚：编辑-审核内容版本（P1-3）
--
-- 语义：删除 t_record.content_version。**有损**：已累计的编辑次数不保留。
-- 执行前先停应用；若还要回滚整个发布闸门，顺序是：
--   1) db/migration/v2.1-content-version-down.sql
--   2) db/migration/v2.1-review-gate-owner-confirm-down.sql
--   3) db/migration/v2.1-review-gate-down.sql
-- 库名由调用方 -D 指定，本脚本不写死 USE。
-- =====================================================================

SET NAMES utf8mb4;

ALTER TABLE `t_record`
  DROP COLUMN `content_version`;
