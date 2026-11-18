-- =====================================================================
-- find-it-campus · v2.1 增量迁移：编辑-审核并发的内容版本（P1-3）
--
-- 背景：审核线程只加载一次记录快照。用户在这期间编辑内容，updateContent 会把
-- review_state 重置为 PENDING；旧线程随后写回时 WHERE 只看 review_state，仍会命中，
-- 于是旧文本覆盖新内容，或把 review_manual_required=1 写到新内容上。
--
-- 本迁移只做一件事：加 t_record.content_version。
--   * 本人编辑：content_version = content_version + 1（见 RecordMapper.updateContent）
--   * 审核写回：WHERE ... AND content_version = #{expectedVersion}
--     影响 0 行 = 内容已变，旧结论丢弃，新内容由编辑时发布的审核事件重新审。
--
-- 执行顺序（已执行过 v2.1-review-gate-up.sql / owner-confirm-up.sql 的库）：
--   1) 停应用
--   2) 本脚本（mysql -D <DbName> < db/migration/v2.1-content-version-up.sql）
--   3) start.ps1
-- 本脚本刻意不写死 USE `find_it_campus`：库名由调用方 -D 指定。
--
-- 回滚：db/migration/v2.1-content-version-down.sql（有损：版本号丢失）
-- =====================================================================

SET NAMES utf8mb4;

ALTER TABLE `t_record`
  ADD COLUMN `content_version` INT NOT NULL DEFAULT 0
    COMMENT '内容版本；本人编辑时 +1，审核写回必须匹配，防止旧审核覆盖新内容'
    AFTER `review_manual_required`;

-- 自检（人工执行，期望 1）：
-- SELECT COUNT(*) FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_record'
--    AND COLUMN_NAME = 'content_version';
