USE find_it_campus;

-- 记录 26 的「出分后」状态：由夹具模拟 D1 打分完成后的写库结果（t_pair_score 真实行），
-- 页面不做任何网络层注入，仍由 GET /api/my/records 返回 pairs[]。
UPDATE t_record SET pair_state = 'READY', updated_at = NOW() WHERE id = 26;

INSERT INTO t_pair_score
  (lost_id, found_id, score, engine, prompt_version, degraded_reason,
   lost_hash, found_hash, status, created_at, updated_at)
VALUES
  (9, 26, 0.8800, 'DEEPSEEK', 'pair-score-v1', NULL,
   REPEAT('d', 64), REPEAT('e', 64), 'NEW', NOW(), NOW())
ON DUPLICATE KEY UPDATE score = VALUES(score), engine = VALUES(engine), status = 'NEW';

SELECT id, lost_id, found_id, score, engine, status FROM t_pair_score WHERE found_id = 26;
