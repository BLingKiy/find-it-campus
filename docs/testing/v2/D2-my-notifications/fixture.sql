USE find_it_campus;

-- D2 证据夹具（只改本地演示库，不动仓库 db/seed.sql，不改后端代码）。
-- 目标：
--   1) 记录 26 = 刚发布、pair_state=PENDING 的「分析中」样本（pairs 清空）；
--   2) 记录 1  = 3 条 t_pair_score（0.92 直看 / 0.80 降级授权 / 0.60 低分），3 条 pair 独立；
--   3) 记录 10 = 已有一对 MATCHED（pair 12），用于「标记完成」成功路径；
--   4) 记录 25 = completed_at 31 天前，用于「已过保留期」标记；
--   5) 记录 7  = PENDING，用于「关闭启事」成功路径；
--   6) demo01 恰好 3 条未读通知（id 1/5/7），其余已读。
-- 所有手机号均为 seed 假号，不在文档/截图出现。

-- 1) 分析中样本
UPDATE t_record
   SET status = 'PENDING', pair_state = 'PENDING', publisher_id = 1,
       completed_at = NULL, created_at = NOW(), updated_at = NOW(), deleted_at = NULL
 WHERE id = 26;
DELETE FROM t_pair_score WHERE lost_id = 26 OR found_id = 26;

-- 2) 多 pair：记录 1 三行 pair；pair 1 回到 NEW，pair 15 用本地关键词（降级），pair 16 低分
UPDATE t_record
   SET status = 'PENDING', pair_state = 'READY', publisher_id = 1, completed_at = NULL
 WHERE id = 1;
UPDATE t_pair_score
   SET status = 'NEW', lost_confirmed_at = NULL, found_confirmed_at = NULL,
       lost_unlocked_at = NULL, found_unlocked_at = NULL,
       lost_unlock_note = NULL, found_unlock_note = NULL
 WHERE id = 1;
UPDATE t_pair_score
   SET score = 0.8000, engine = 'LOCAL_KEYWORD', degraded_reason = 'CIRCUIT_OPEN',
       status = 'NEW', lost_confirmed_at = NULL, found_confirmed_at = NULL
 WHERE id = 15;
UPDATE t_pair_score
   SET score = 0.6000, status = 'NEW', lost_confirmed_at = NULL, found_confirmed_at = NULL
 WHERE id = 16;

-- 3) 标记完成成功路径：记录 10 + pair 12 两端均已确认
UPDATE t_record
   SET status = 'PENDING', pair_state = 'READY', publisher_id = 1, completed_at = NULL
 WHERE id = 10;
UPDATE t_pair_score
   SET status = 'MATCHED',
       lost_confirmed_at = COALESCE(lost_confirmed_at, NOW()),
       found_confirmed_at = COALESCE(found_confirmed_at, NOW())
 WHERE id = 12;

-- 4) 已过保留期样本：completed_at 31 天前（到期不改写 status，按时间算）
UPDATE t_record
   SET status = 'COMPLETED', pair_state = 'READY', publisher_id = 1,
       completed_at = DATE_SUB(NOW(), INTERVAL 31 DAY)
 WHERE id = 25;

-- 5) 关闭成功路径样本
UPDATE t_record
   SET status = 'PENDING', pair_state = 'READY', publisher_id = 1, completed_at = NULL
 WHERE id IN (4, 7);

-- 6) demo01 恰好 3 条未读
UPDATE t_notification SET read_at = NULL WHERE id IN (1, 5, 7);
UPDATE t_notification SET read_at = COALESCE(read_at, NOW()) WHERE id IN (3, 8, 9);

SELECT id, status, pair_state, completed_at FROM t_record WHERE id IN (1, 7, 10, 25, 26) ORDER BY id;
SELECT COUNT(*) AS pair_rows_record1 FROM t_pair_score WHERE lost_id = 1 OR found_id = 1;
SELECT COUNT(*) AS unread_demo01 FROM t_notification WHERE user_id = 1 AND read_at IS NULL;
