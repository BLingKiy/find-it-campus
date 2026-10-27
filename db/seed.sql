-- =====================================================================
-- find-it-campus · 种子数据 v2（7 表演示集 + v2 规则固定夹具）
-- 任务：ZHYYKFX-49 · B1 数据层 v2
-- 前置：先执行 db/schema.sql（v2 七表）
-- 用法：mysql --default-character-set=utf8mb4 -u root -p find_it_campus < db/seed.sql
--
-- 幂等：先按外键逆序 TRUNCATE 七张表再插入；显式给定 id，重复执行后
--       表行数、id 集合、分数、状态分布完全一致（completed_at / confirmed_at /
--       unlocked_at 用 NOW() 相对量，保证「3 天前 / 31 天前 / 保留期内」的窗口
--       在任何执行日都成立；这些时间列每次执行相差毫秒级，其余列稳定）。
-- 隐私：全部姓名、联系方式均为虚构；手机号一律 138xxxxxxxx 假号，
--       不使用真实学号/真实手机号。演示账号登录名 demo01~demo03，不含真实学号。
--
-- 演示账号（bcrypt cost=10；密码仅用于本地演示，禁止在生产复用）：
--   demo01 / Demo@123456  → 演示甲，id=1
--   demo02 / Demo@123456  → 演示乙，id=2（越权测试用）
--   demo03 / Demo@123456  → 演示丙，id=3（越权测试用）
--
-- 固定夹具（下游直接依赖，不要随手改成正负例不明的数据）：
--   · 24 条记录：id 1–10、13–26（11/12 故意留空；AUTO_INCREMENT 从 27 续）
--     - PENDING 21 条：LOST 1–10 + FOUND 13–22 + FOUND 26
--     - COMPLETED 2 条：23（completed_at = 3 天前，保留期内）、25（31 天前，到期用例）
--     - CLOSED 1 条：24（带一对 pair，取号 403/50005 用例）
--     ⇒ 公共列表（status=PENDING、未删除）恰好 21 条
--   · pair 14 对，唯一键 (lost_id, found_id) 一行一对，三档分数齐全：
--     ≥0.90：1-13 .96 / 2-14 .94 / 3-15 .92 / 5-17 .95 / 6-18 .96 / 6-23 .93 / 10-23 .95 / 25-13 .90
--     0.75–0.90：4-16 .86（仅 FOUND 端已公开）/ 24-22 .88
--     <0.75：7-19 .21 / 8-20 .34 / 9-21 .18 / 10-22 .42
--   · pair 状态四态齐全：NEW / CONTACTED / MATCHED(10-23、25-13) / IGNORED(6-23)
--   · #2 新规则断言夹具（同一对 COMPLETED 记录 23，两个不同查看者）：
--       正例：user1 拥有 LOST 10，(10,23) score .95 且 MATCHED → 可看 23 详情
--       反例：user2 拥有 LOST 6，(6,23) score .93 但 status=IGNORED → 必须 404
--     即「IGNORED 的 pair 不作为 COMPLETED 详情的查看通道」；
--     同时 23 有一对 MATCHED，满足『COMPLETED 需先有一对 MATCHED』的前置。
--   · 记录 25 已过保留期：即使 (25,13) 是 .90 MATCHED，任何身份详情都应 404。
--   · 记录 24 CLOSED：其 pair (24,22) 保留在库（不批量改写状态），取号应 403/50005。
--   · 记录级 pair_state：有 pair 的为 READY，26 无 pair 为 PENDING。
--     t_access_log / t_match_result 故意留空：审计与匹配快照由运行期真实产生，
--     预置假审计行会污染「谁看了什么」的证据链。
--
-- hash 口径：lost_hash / found_hash 由 SQL 按 A1 §8.5 的 contentHash 公式从
-- t_record 当前内容现算（title\ndescription\nlocation\ncategoryId\ntype，SHA-256
-- 小写 hex）。不要在 seed 里手写 hash —— 文案一改就漂移，D1 会误判为「需要重算」。
-- =====================================================================

USE `find_it_campus`;

SET NAMES utf8mb4;

-- 先清后插：TRUNCATE 重置 AUTO_INCREMENT，显式 id 才能每次一致。
-- 外键检查关闭只为清表顺序；清完立即恢复，插入仍受全部约束校验。
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE `t_access_log`;
TRUNCATE TABLE `t_notification`;
TRUNCATE TABLE `t_pair_score`;
TRUNCATE TABLE `t_match_result`;
TRUNCATE TABLE `t_record`;
TRUNCATE TABLE `t_category`;
TRUNCATE TABLE `t_user`;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------------------------------------------------
-- 1. 演示账号 3 个（t_user 无 phone 列）
-- ---------------------------------------------------------------------
INSERT INTO `t_user` (`id`, `student_no`, `password_hash`, `display_name`, `is_active`) VALUES
  (1, 'demo01',
      '$2a$10$4dNz5s2jF3B9lcfa6UhD4uYBePZWCJI3pIVIbO4DY8sgU9sBzkSr6', '演示甲', 1),
  (2, 'demo02',
      '$2a$10$pGyt/jxHrVF6BeJuompE7eo2eujLlgXr39bq53sVk9vfJDDNiDSG.', '演示乙', 1),
  (3, 'demo03',
      '$2a$10$E3y5gbRgdAtJj8gyQARBHuJfKE1y178XZI5ECvNM85MChyTgB6Vwm', '演示丙', 1);

-- ---------------------------------------------------------------------
-- 2. 分类字典 9 条（8 个启用 + 1 个停用；沿用 v1，code 稳定）
-- ---------------------------------------------------------------------
INSERT INTO `t_category` (`id`, `code`, `name`, `keywords`, `sort_order`, `is_active`) VALUES
  (1, 'WALLET',    '钱包卡证', '钱包 卡包 零钱包 现金 人民币 银行卡 饭卡',        10, 1),
  (2, 'DIGITAL',   '手机数码', '手机 iphone 安卓 耳机 充电器 充电宝 平板 电脑 手表', 20, 1),
  (3, 'KEYS',      '钥匙',     '钥匙 车钥匙 宿舍钥匙 门禁卡 挂饰 钥匙串',          30, 1),
  (4, 'DOCUMENT',  '证件资料', '学生证 校园卡 身份证 银行卡 证书 笔记本 资料 合同',  40, 1),
  (5, 'BAG',       '包袋',     '包 书包 背包 帆布包 手提包 电脑包 双肩包',          50, 1),
  (6, 'CLOTHING',  '衣物饰品', '衣服 外套 围巾 帽子 手套 伞 雨具 首饰 眼镜',        60, 1),
  (7, 'STATIONERY','书籍文具', '书 教材 笔 计算器 文件夹 试卷 笔记',                70, 1),
  (8, 'OTHER',     '其他',     NULL,                                              90, 1),
  -- 停用的分类：验证「历史数据仍显示名称、新提交不可选」
  (9, 'SPORTS',    '运动器材', '球 球拍 水壶 健身',                                80, 0);

-- ---------------------------------------------------------------------
-- 3. 记录 24 条（id 1–10、13–26）
--    publisher_id 分配（越权/可见性用例）：
--      user1 demo01：1,2,3,4,5,7,8,9,10,14,15,17,18,19,20,21,22,25,26（19 条）
--      user2 demo02：6,13,16,24（4 条，越权测试对象）
--      user3 demo03：23（1 条，COMPLETED 记录的所有者）
--    completed_at 用 DATE_SUB(NOW(), INTERVAL n DAY)：保证 3 天 / 31 天窗口在任何执行日成立。
-- ---------------------------------------------------------------------
INSERT INTO `t_record`
  (`id`, `type`, `status`, `completed_at`, `title`, `description`, `category_id`, `location`,
   `occurred_at`, `contact`, `publisher_name`, `publisher_id`, `pair_state`, `pair_scored_at`,
   `deleted_at`, `created_at`, `updated_at`)
VALUES
  -- ===== 正例：寻物（失主在找）1~6 =====
  (1, 'LOST', 'PENDING', NULL, '图书馆丢了黑色钱包',
      '昨天下午在图书馆三楼自习区靠窗的位置看书，起身走的时候发现黑色皮质钱包不见了，里面有两张银行卡和三百多块现金，卡是工行的，非常着急，捡到的同学联系我，必有感谢。',
      1, '图书馆三楼自习区', '2026-10-19 17:30:00', '13800000001', '李同学', 1,
      'READY', '2026-10-25 10:00:00', NULL, '2026-10-20 09:10:00', '2026-10-20 09:10:00'),
  (2, 'LOST', 'PENDING', NULL, '白色苹果手机丢了',
      '十月十八号晚上在操场跑步，中途把白色 iPhone 14 放在跑道边的台阶上休息，走的时候忘拿，手机壳是透明的，背面贴了一张小熊贴纸，锁屏密码是六个八。',
      2, '南操场跑道边台阶', '2026-10-18 20:15:00', '13800000002', '王同学', 1,
      'READY', '2026-10-25 10:00:00', NULL, '2026-10-19 08:00:00', '2026-10-19 08:00:00'),
  (3, 'LOST', 'PENDING', NULL, '学生证和校园卡一起丢了',
      '在今天上午的高等数学课上，装学生证、校园卡和一张银行卡的透明卡套从书包侧袋掉出来了，怀疑落在第三教学楼 201 教室，证件上的姓名是张三，请捡到的同学联系我。',
      4, '第三教学楼201', '2026-10-21 10:40:00', '13800000003', '张三', 1,
      'READY', '2026-10-25 10:00:00', NULL, '2026-10-21 11:05:00', '2026-10-21 11:05:00'),
  (4, 'LOST', 'PENDING', NULL, '丢失深蓝色折叠雨伞',
      '前天傍晚下雨，把一把深蓝色的自动折叠伞落在食堂二楼靠窗的餐桌上，伞柄是木头的，伞面有一个小破洞，麻烦捡到的同学联系我。',
      6, '第二食堂二楼', '2026-10-19 18:50:00', NULL, '赵同学', 1,
      'READY', '2026-10-24 09:00:00', NULL, '2026-10-20 12:00:00', '2026-10-20 12:00:00'),
  (5, 'LOST', 'PENDING', NULL, '蓝牙耳机连充电盒一起丢了',
      '在图书馆自习室丢了白色的无线蓝牙耳机，连同一个白色的方形充电盒，耳机右耳外壳上有一道划痕，大概上周三下午丢的，里面可能还有电。',
      2, '图书馆一楼自习室', '2026-10-15 16:00:00', '13800000005', '陈同学', 1,
      'READY', '2026-10-22 10:20:00', NULL, '2026-10-16 10:00:00', '2026-10-16 10:00:00'),
  (6, 'LOST', 'PENDING', NULL, '一串宿舍钥匙丢了',
      '钥匙串上有宿舍门钥匙、柜子钥匙和一个黄色小熊挂饰，共三把钥匙，昨天在宿舍楼下的快递点取件时掉的，小熊挂饰是判断依据，急用，谢谢。',
      3, '7号宿舍楼下快递点', '2026-10-20 19:20:00', '13800000006', '孙同学', 2,
      'READY', '2026-10-24 12:00:00', NULL, '2026-10-20 20:00:00', '2026-10-20 20:00:00'),

  -- ===== 正例：招领（有人捡到）13~18，与上面的寻物一一对应 =====
  (13, 'FOUND', 'PENDING', NULL, '图书馆拾到黑色钱包',
      '前些天在图书馆三楼自习区靠窗座位旁的地上捡到一个黑色皮质钱包，里面有银行卡两张和现金若干，已妥善保管，请丢失的同学联系我核对后领取。',
      1, '图书馆三楼自习区', '2026-09-15 15:00:00', '13800000101', '周同学', 2,
      'READY', '2026-10-23 09:00:00', NULL, '2026-09-15 15:30:00', '2026-09-15 15:30:00'),
  (14, 'FOUND', 'PENDING', NULL, '捡到白色 iPhone 带透明壳',
      '在南操场跑道旁边的台阶上捡到一部白色的苹果手机，装着透明手机壳，背面有一个小熊图案的贴纸，屏幕完好，请失主联系描述锁屏和内部照片来认领。',
      2, '南操场跑道边台阶', '2026-10-19 07:40:00', '13800000102', '吴同学', 1,
      'READY', '2026-10-22 10:05:00', NULL, '2026-10-19 08:10:00', '2026-10-19 08:10:00'),
  (15, 'FOUND', 'PENDING', NULL, '教室捡到卡套内有学生证',
      '第三教学楼 201 教室讲台旁边捡到一个透明卡套，里面有校园卡、学生证和一张银行卡，学生证姓名张三，请本人联系我领取。',
      4, '第三教学楼201', '2026-10-21 12:10:00', '13800000103', '郑同学', 1,
      'READY', '2026-10-23 11:00:00', NULL, '2026-10-21 12:30:00', '2026-10-21 12:30:00'),
  (16, 'FOUND', 'PENDING', NULL, '食堂捡到一把折叠伞',
      '昨天傍晚在第二食堂二楼靠窗的餐桌上捡到一把深蓝色的自动折叠伞，伞柄是木质的，伞面靠近边缘处有一处破损，请联系我领取。',
      6, '第二食堂二楼', '2026-10-19 20:30:00', '13800000104', '冯同学', 2,
      'READY', '2026-10-24 09:00:00', NULL, '2026-10-19 21:00:00', '2026-10-19 21:00:00'),
  (17, 'FOUND', 'PENDING', NULL, '自习室捡到白色耳机和充电盒',
      '在图书馆一楼自习室的椅子上捡到一副白色的无线蓝牙耳机和一个白色方形充电盒，右边那只耳机外壳有一道划痕，请失主联系我。',
      2, '图书馆一楼自习室', '2026-10-16 18:00:00', '13800000105', '褚同学', 1,
      'READY', '2026-10-23 10:00:00', NULL, '2026-10-16 18:20:00', '2026-10-16 18:20:00'),
  (18, 'FOUND', 'PENDING', NULL, '快递点捡到三把钥匙带小熊挂件',
      '在 7 号宿舍楼下的快递驿站门口捡到一串钥匙，一共三把，其中一个钥匙扣是黄色的小熊挂饰，已交驿站前台保管，请失主前来认领。',
      3, '7号宿舍楼下快递点', '2026-10-20 21:00:00', '13800000106', '卫同学', 1,
      'READY', '2026-10-24 12:00:00', NULL, '2026-10-20 21:20:00', '2026-10-20 21:20:00'),

  -- ===== 负例：寻物 7~10（与下面的招领类别不同，不该高匹配）=====
  (7, 'LOST', 'PENDING', NULL, '丢了科学计算器',
      '今天上午在自习桌上了科学计算器，型号是卡西欧 fx-991，灰色的，背面贴着写有我名字的名贴，考试要用，请捡到的同学联系我。',
      7, '第一教学楼自习室', '2026-10-21 09:00:00', '13800000007', '蒋同学', 1,
      'READY', '2026-10-22 10:30:00', NULL, '2026-10-21 09:30:00', '2026-10-21 09:30:00'),
  (8, 'LOST', 'PENDING', NULL, '丢失黑色篮球袜一双',
      '在体育馆更衣室的长凳上落了一双黑色的篮球袜，袜口有白色条纹，不太值钱但是刚买的，希望好心人联系我。',
      6, '体育馆更衣室', '2026-10-18 21:00:00', NULL, '沈同学', 1,
      'READY', '2026-10-22 10:35:00', NULL, '2026-10-19 10:00:00', '2026-10-19 10:00:00'),
  (9, 'LOST', 'PENDING', NULL, '丢了一个不锈钢保温杯',
      '大概前天下午在实验楼 402 的座位上，一个银色的不锈钢保温杯不见了，容量 500 毫升，杯身有几道磕碰痕迹，杯盖是黑色的。',
      8, '实验楼402', '2026-10-19 15:00:00', '13800000009', '韩同学', 1,
      'READY', '2026-10-22 10:40:00', NULL, '2026-10-20 08:30:00', '2026-10-20 08:30:00'),
  -- 第二串钥匙：与 23（第二教学楼拾到的蓝色挂件钥匙）同一物品，构成 (10,23) 正例
  (10, 'LOST', 'PENDING', NULL, '教学楼丢了一串钥匙（蓝色挂件）',
      '十月十一号晚上在第二教学楼自习，离开时把一串钥匙落在楼梯口附近，一共两把，钥匙扣上有一个蓝色塑料挂件，麻烦捡到的同学联系我。',
      3, '第二教学楼楼梯口', '2026-10-11 19:00:00', '13800000010', '杨同学', 1,
      'READY', DATE_SUB(NOW(), INTERVAL 4 DAY), NULL, '2026-10-12 08:00:00', '2026-10-12 08:00:00'),

  -- ===== 负例：招领 19~22 =====
  (19, 'FOUND', 'PENDING', NULL, '捡到一副红色羽毛球拍',
      '在体育场看台的座位上捡到一支红色的羽毛球拍，拍柄缠着新的手胶，请失主联系我描述品牌与型号来认领。',
      8, '体育场看台', '2026-10-20 17:00:00', '13800000107', '朱同学', 1,
      'READY', '2026-10-22 10:30:00', NULL, '2026-10-20 17:30:00', '2026-10-20 17:30:00'),
  (20, 'FOUND', 'PENDING', NULL, '捡到灰色羽绒服一件',
      '在宿舍楼下的洗衣机房捡到一件灰色的连帽羽绒服，口袋里有一张超市小票，请失主联系我领取。',
      6, '3号宿舍楼洗衣房', '2026-10-17 22:00:00', '13800000108', '秦同学', 1,
      'READY', '2026-10-22 10:35:00', NULL, '2026-10-18 09:00:00', '2026-10-18 09:00:00'),
  (21, 'FOUND', 'PENDING', NULL, '捡到一本大学英语词汇书',
      '在图书馆二楼的书架旁边捡到一本绿色封面的大学英语四级词汇书，书里有很多铅笔标注，请失主联系我。',
      7, '图书馆二楼', '2026-10-16 14:00:00', '13800000109', '尤同学', 1,
      'READY', '2026-10-22 10:40:00', NULL, '2026-10-16 15:00:00', '2026-10-16 15:00:00'),
  (22, 'FOUND', 'PENDING', NULL, '捡到一副黑框眼镜',
      '在食堂一楼门口的地上捡到一副黑框近视眼镜，眼镜盒是蓝色的，请失主联系我认领。',
      8, '第一食堂一楼门口', '2026-10-21 12:40:00', '13800000110', '许同学', 1,
      'READY', DATE_SUB(NOW(), INTERVAL 2 DAY), NULL, '2026-10-21 13:00:00', '2026-10-21 13:00:00'),

  -- ===== 状态与边界覆盖 =====
  -- 23：COMPLETED（3 天前）+ 一对 MATCHED（10,23）+ 一对 IGNORED（6,23）
  (23, 'FOUND', 'COMPLETED', DATE_SUB(NOW(), INTERVAL 3 DAY),
      '已归还：第二教学楼捡到的蓝色挂件钥匙',
      '在第二教学楼楼梯口捡到一串钥匙，一共两把，带蓝色塑料挂件，已由失主本人核实认领，本条标记完成留档。',
      3, '第二教学楼楼梯口', '2026-10-12 19:00:00', '13800000111', '何同学', 3,
      'READY', DATE_SUB(NOW(), INTERVAL 4 DAY), NULL,
      '2026-10-12 19:30:00', DATE_SUB(NOW(), INTERVAL 3 DAY)),
  -- 24：CLOSED（无前置条件），保留一对 pair 供 403/50005 用例；completed_at 必须为 NULL
  (24, 'LOST', 'CLOSED', NULL,
      '已关闭：丢失的黑色圆框眼镜',
      '十月十号在第一食堂门口丢的黑框眼镜，已经配了新的，此贴关闭不再寻找，感谢各位同学关注。',
      8, '第一食堂一楼门口', '2026-10-10 16:00:00', '13800000011', '吕同学', 2,
      'READY', DATE_SUB(NOW(), INTERVAL 2 DAY), NULL, '2026-10-10 18:00:00', '2026-10-22 09:00:00'),
  -- 25：COMPLETED（31 天前，已过 30 天保留期）+ 一对 MATCHED（25,13）
  (25, 'LOST', 'COMPLETED', DATE_SUB(NOW(), INTERVAL 31 DAY),
      '已找到：棕色短款钱包',
      '九月十八号在图书馆二楼丢的棕色短款钱包，里面有校园卡和少量现金，已经通过平台匹配找回，本条标记完成留档。',
      1, '图书馆二楼', '2026-09-18 10:00:00', '13800000012', '施同学', 1,
      'READY', DATE_SUB(NOW(), INTERVAL 32 DAY), NULL,
      '2026-09-20 09:00:00', DATE_SUB(NOW(), INTERVAL 31 DAY)),
  -- 26：category_id 为空（验证可空路径），无 pair
  (26, 'FOUND', 'PENDING', NULL, '捡到一张浅蓝色卡片',
      '在公告栏下面捡到一张没有姓名只有编号的浅蓝色卡片，看不出用途，也不知道属于哪一类物品，暂时登记为无分类。',
      NULL, '大学生活动中心公告栏', '2026-10-21 13:30:00', '13800000112', '同学', 1,
      'PENDING', NULL, NULL, '2026-10-21 13:50:00', '2026-10-21 13:50:00');

-- ---------------------------------------------------------------------
-- 4. pair 14 对（一 LOST 一 FOUND；lost_id 必须指向 LOST，found_id 指向 FOUND）
--    lost_hash / found_hash 由 SQL 按 A1 §8.5 公式现算，避免文案改动后哈希漂移。
--    时间列用相对量表达「4 天前确认 / 3 天前完成」的先后关系。
-- ---------------------------------------------------------------------
INSERT INTO `t_pair_score`
  (`id`, `lost_id`, `found_id`, `score`, `engine`, `degraded_reason`, `prompt_version`,
   `latency_ms`, `prompt_tokens`, `completion_tokens`,
   `lost_hash`, `found_hash`,
   `lost_unlocked_at`, `found_unlocked_at`, `lost_unlock_note`, `found_unlock_note`,
   `lost_confirmed_at`, `found_confirmed_at`, `status`, `created_at`, `updated_at`)
SELECT p.id, p.lost_id, p.found_id, p.score, p.engine, p.degraded_reason, p.prompt_version,
       p.latency_ms, p.prompt_tokens, p.completion_tokens,
       SHA2(CONCAT(
         TRIM(l.title), CHAR(10),
         TRIM(l.description), CHAR(10),
         TRIM(l.location), CHAR(10),
         IFNULL(CAST(l.category_id AS CHAR), ''), CHAR(10),
         l.type
       ), 256),
       SHA2(CONCAT(
         TRIM(f.title), CHAR(10),
         TRIM(f.description), CHAR(10),
         TRIM(f.location), CHAR(10),
         IFNULL(CAST(f.category_id AS CHAR), ''), CHAR(10),
         f.type
       ), 256),
       p.lost_unlocked_at, p.found_unlocked_at, p.lost_unlock_note, p.found_unlock_note,
       p.lost_confirmed_at, p.found_confirmed_at, p.status, p.created_at, p.updated_at
FROM (
  -- id, lost, found, score, engine, degraded, prompt_version, latency, ptok, ctok,
  -- lost_unlocked, found_unlocked, lost_note, found_note, lost_confirmed, found_confirmed, status, created, updated
  SELECT 1 AS id, 1 AS lost_id, 13 AS found_id, 0.9600 AS score, 'DEEPSEEK' AS engine,
         CAST(NULL AS CHAR(32)) AS degraded_reason, 'pair-score-v1' AS prompt_version,
         1830 AS latency_ms, 420 AS prompt_tokens, 12 AS completion_tokens,
         CAST(NULL AS DATETIME) AS lost_unlocked_at, CAST(NULL AS DATETIME) AS found_unlocked_at,
         CAST(NULL AS CHAR(200)) AS lost_unlock_note, CAST(NULL AS CHAR(200)) AS found_unlock_note,
         CAST(NULL AS DATETIME) AS lost_confirmed_at, CAST(NULL AS DATETIME) AS found_confirmed_at,
         'CONTACTED' AS status, CAST('2026-10-22 10:00:00' AS DATETIME) AS created_at,
         CAST('2026-10-23 09:00:00' AS DATETIME) AS updated_at
  UNION ALL SELECT 2, 2, 14, 0.9400, 'DEEPSEEK', NULL, 'pair-score-v1', 1520, 405, 10,
         NULL, NULL, NULL, NULL, NULL, NULL, 'NEW', '2026-10-22 10:05:00', '2026-10-22 10:05:00'
  UNION ALL SELECT 3, 3, 15, 0.9200, 'DEEPSEEK', NULL, 'pair-score-v1', 1710, 412, 11,
         NULL, NULL, NULL, NULL, NULL, NULL, 'CONTACTED', '2026-10-22 10:10:00', '2026-10-23 11:00:00'
  -- 4-16：0.86 落在 0.75–0.90 档；只有 FOUND 端公开（仅一端已公开）→ E1 授权前后用例
  UNION ALL SELECT 4, 4, 16, 0.8600, 'DEEPSEEK', NULL, 'pair-score-v1', 1680, 408, 10,
         NULL, DATE_SUB(NOW(), INTERVAL 2 DAY), NULL, '已公开联系方式，可直接联系',
         NULL, NULL, 'NEW', '2026-10-24 09:00:00', DATE_SUB(NOW(), INTERVAL 2 DAY)
  UNION ALL SELECT 5, 5, 17, 0.9500, 'DEEPSEEK', NULL, 'pair-score-v1', 1590, 400, 9,
         NULL, NULL, NULL, NULL, NULL, NULL, 'CONTACTED', '2026-10-22 10:20:00', '2026-10-23 10:00:00'
  UNION ALL SELECT 6, 6, 18, 0.9600, 'DEEPSEEK', NULL, 'pair-score-v1', 1620, 415, 9,
         NULL, NULL, NULL, NULL, NULL, NULL, 'CONTACTED', '2026-10-22 10:25:00', '2026-10-24 12:00:00'
  -- 7-19 / 8-20 / 9-21 / 10-22：<0.75 负例；9-21 同时是 LOCAL_KEYWORD 降级样本
  UNION ALL SELECT 7, 7, 19, 0.2100, 'LOCAL_KEYWORD', NULL, 'pair-score-v1', NULL, NULL, NULL,
         NULL, NULL, NULL, NULL, NULL, NULL, 'NEW', '2026-10-22 10:30:00', '2026-10-22 10:30:00'
  UNION ALL SELECT 8, 8, 20, 0.3400, 'DEEPSEEK', NULL, 'pair-score-v1', 1450, 410, 8,
         NULL, NULL, NULL, NULL, NULL, NULL, 'NEW', '2026-10-22 10:35:00', '2026-10-22 10:35:00'
  UNION ALL SELECT 9, 9, 21, 0.1800, 'LOCAL_KEYWORD', 'EMPTY_KEY', 'pair-score-v1', NULL, NULL, NULL,
         NULL, NULL, NULL, NULL, NULL, NULL, 'NEW', '2026-10-22 10:40:00', '2026-10-22 10:40:00'
  UNION ALL SELECT 10, 10, 22, 0.4200, 'DEEPSEEK', NULL, 'pair-score-v1', 1500, 407, 8,
         NULL, NULL, NULL, NULL, NULL, NULL, 'NEW', '2026-10-22 10:45:00', '2026-10-22 10:45:00'
  -- 6-23：score .93 但 IGNORED —— #2 反例（user2 看 COMPLETED 23 必须 404）
  UNION ALL SELECT 11, 6, 23, 0.9300, 'DEEPSEEK', NULL, 'pair-score-v1', 1660, 418, 10,
         NULL, NULL, NULL, NULL, NULL, NULL, 'IGNORED',
         DATE_SUB(NOW(), INTERVAL 4 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)
  -- 10-23：score .95 且 MATCHED —— #2 正例（user1 可看 COMPLETED 23 详情），也是 23 的完成前置
  UNION ALL SELECT 12, 10, 23, 0.9500, 'DEEPSEEK', NULL, 'pair-score-v1', 1580, 402, 9,
         NULL, NULL, NULL, NULL,
         DATE_SUB(NOW(), INTERVAL 4 DAY), DATE_SUB(NOW(), INTERVAL 4 DAY), 'MATCHED',
         DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 4 DAY)
  -- 24-22：CLOSED 记录仍有 pair（不批量改写状态）；0.88 + 仅 LOST 端已公开 → 取号 403/50005
  UNION ALL SELECT 13, 24, 22, 0.8800, 'DEEPSEEK', NULL, 'pair-score-v1', 1730, 409, 10,
         DATE_SUB(NOW(), INTERVAL 2 DAY), NULL, '已公开联系方式，可直接联系', NULL,
         NULL, NULL, 'CONTACTED', '2026-10-22 09:00:00', DATE_SUB(NOW(), INTERVAL 2 DAY)
  -- 25-13：.90 MATCHED，但 25 已完成 31 天、过了 30 天保留期 → 详情/取号都 404/403
  UNION ALL SELECT 14, 25, 13, 0.9000, 'DEEPSEEK', NULL, 'pair-score-v1', 1610, 404, 10,
         NULL, NULL, NULL, NULL,
         DATE_SUB(NOW(), INTERVAL 32 DAY), DATE_SUB(NOW(), INTERVAL 32 DAY), 'MATCHED',
         DATE_SUB(NOW(), INTERVAL 33 DAY), DATE_SUB(NOW(), INTERVAL 32 DAY)
) p
JOIN `t_record` l ON l.id = p.lost_id
JOIN `t_record` f ON f.id = p.found_id;

-- ---------------------------------------------------------------------
-- 5. 通知 8 条（正文不含手机号/完整描述；唯一键 user_id+pair_id+type 防刷屏）
-- ---------------------------------------------------------------------
INSERT INTO `t_notification` (`id`, `user_id`, `pair_id`, `type`, `title`, `body`, `read_at`, `created_at`) VALUES
  (1, 1, 1, 'PAIR_MATCH', '有一条新的匹配结果',
      '你发布的寻物启事有了一条新的匹配结果，登录后可查看配对详情。为保护隐私，通知不展示联系方式与完整描述。',
      NULL, '2026-10-22 10:01:00'),
  (2, 2, 1, 'PAIR_MATCH', '有一条新的匹配结果',
      '你发布的招领启事有了一条新的匹配结果，登录后可查看配对详情。为保护隐私，通知不展示联系方式与完整描述。',
      NULL, '2026-10-22 10:01:00'),
  (3, 1, 4, 'PAIR_UNLOCK', '对方已公开联系方式',
      '有一条配对中，对方已公开联系方式，你可在配对详情页查看。',
      NULL, '2026-10-25 09:00:00'),
  (4, 2, 6, 'PAIR_MATCH', '有一条新的匹配结果',
      '你发布的寻物启事有了一条新的匹配结果，登录后可查看配对详情。为保护隐私，通知不展示联系方式与完整描述。',
      '2026-10-23 10:00:00', '2026-10-22 10:26:00'),
  (5, 1, 6, 'PAIR_MATCH', '有一条新的匹配结果',
      '你发布的招领启事有了一条新的匹配结果，登录后可查看配对详情。为保护隐私，通知不展示联系方式与完整描述。',
      NULL, '2026-10-22 10:26:00'),
  (6, 3, 12, 'PAIR_MATCH', '有一条新的匹配结果',
      '你发布的招领启事已与一条寻物启事完成确认，登录后可查看配对详情。',
      NULL, DATE_SUB(NOW(), INTERVAL 4 DAY)),
  (7, 1, 12, 'PAIR_MATCH', '有一条新的匹配结果',
      '你发布的寻物启事已与一条招领启事完成确认，登录后可查看配对详情。',
      NULL, DATE_SUB(NOW(), INTERVAL 4 DAY)),
  (8, 1, NULL, 'SYSTEM', '平台维护通知',
      '平台将于近期进行例行维护，维护期间写操作可能短暂不可用，请提前保存草稿。',
      NULL, '2026-10-26 09:00:00');

-- t_access_log / t_match_result 保持空表：由运行期真实请求产生，不预置假数据。
