USE find_it_campus;

-- E2 证据夹具：只改本地演示库，不动仓库代码/种子文件。
-- 记录 30/31 是 demo02 的招领帖，用于给记录 1 造第二、第三条 pair（0.80 / 0.60）。
INSERT INTO t_record
  (id, type, status, title, description, category_id, location, occurred_at,
   contact, publisher_name, publisher_id, pair_state, created_at, updated_at)
VALUES
  (30, 'FOUND', 'PENDING', '图书馆拾到黑色钱包（E2 夹具）',
   '在三楼自习区靠窗座位拾到黑色皮钱包，内有银行卡，请失主核对细节后联系。',
   1, '图书馆三楼自习区', '2026-10-19 18:00:00',
   '13800000130', '演示乙', 2, 'READY', '2026-10-19 18:10:00', '2026-10-19 18:10:00'),
  (31, 'FOUND', 'PENDING', '图书馆拾到卡套（E2 低分夹具）',
   '在图书馆一楼服务台拾到透明卡套一个，内有一张校园卡，请失主认领。',
   4, '图书馆一楼服务台', '2026-10-19 18:30:00',
   '13800000131', '演示乙', 2, 'READY', '2026-10-19 18:40:00', '2026-10-19 18:40:00')
ON DUPLICATE KEY UPDATE title = VALUES(title), contact = VALUES(contact), publisher_id = VALUES(publisher_id);

-- 直接看档样本改成 0.92（E2 补充要求的三档样本值），NEW 便于演示“标一条不影响另一条”。
UPDATE t_pair_score SET score = 0.9200, status = 'NEW',
  lost_confirmed_at = NULL, found_confirmed_at = NULL
WHERE id = 1;

INSERT INTO t_pair_score
  (lost_id, found_id, score, engine, degraded_reason, lost_hash, found_hash,
   lost_unlocked_at, found_unlocked_at, lost_unlock_note, found_unlock_note,
   lost_confirmed_at, found_confirmed_at, status, created_at, updated_at)
VALUES
  (1, 30, 0.8000, 'DEEPSEEK', NULL, REPEAT('0', 64), REPEAT('0', 64),
   NULL, NULL, NULL, NULL, NULL, NULL, 'NEW', NOW(), NOW()),
  (1, 31, 0.6000, 'DEEPSEEK', NULL, REPEAT('0', 64), REPEAT('0', 64),
   NULL, NULL, NULL, NULL, NULL, NULL, 'NEW', NOW(), NOW())
ON DUPLICATE KEY UPDATE score = VALUES(score), status = 'NEW',
  lost_unlocked_at = NULL, found_unlocked_at = NULL,
  lost_unlock_note = NULL, found_unlock_note = NULL;

SELECT id, lost_id, found_id, score, status, engine FROM t_pair_score WHERE lost_id = 1 ORDER BY score DESC;
