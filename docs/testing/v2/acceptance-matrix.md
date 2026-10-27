# find-it-campus v2 · 验收矩阵（A2）

> 任务：ZHYYKFX-47（A2 验收矩阵与回归清单）· 作者：端测测 · 日期 `2026-10-27`
> 契约基线：`docs/api-contract-v2.md` v2.1 + `docs/design/v2-architecture.md` v2.1（A1 v2 重交 `b90270e`，调度达独立复核通过）
> 需求基线：ZHYYKFX-44 父任务「产品口径 1/2/2b/3/4/5/6」+「技术口径 11 条」；人类 2026-10-27 追加「终态口径 vF」6 条
> 范围：**只出文档**。本文不写用例代码；F1 按本文写 `qa/` 资产，F2 按本文出报告。
> 姊妹文件：`docs/testing/v2/regression-checklist.md`（5.1 接口 / 匹配评估 / 三页面 / 7.x / 6.x 五块回归结论）

---

## 0. 使用约定

### 0.1 三条读法

1. **一条用例一个判据。** 每行只允许一个可判定的结果集合（一组状态码 + 字段值）；多断言必须属于同一次请求的同一次响应，或同一次 SQL 查询。
2. **期望列不写形容词。** 每格必须能落到 HTTP 状态码、业务 `code`、字段名与值、SQL 结果或数值区间。检索式见 §6。
3. **夹具先行。** 所有前置都用 §2 的夹具别名（`R-A`、`P-1` 等）；用例不依赖执行顺序，也不依赖上一条的残留。

### 0.2 优先级

| 级别 | 定义 | 门禁位置 |
|---|---|---|
| P0 | 契约硬判据，错一条即整条需求不成立（三态/四态、409 零写入、可见性、越权、遮蔽、号码不泄露） | 门禁 1 / 门禁 2 / 门禁 3 |
| P1 | 接口与页面主路径，错一条即功能不可用（登录、发布、取号、通知、计数） | 门禁 2 |
| P2 | 边界、文案、取证完整性；错一条记缺陷不阻塞合并 | 不阻塞 |

### 0.3 证据形式码

| 码 | 形式 | 采集方式 |
|---|---|---|
| `EV-H` | 响应头 | `curl -i` 或 Playwright `response.headers()`，只保留 `Set-Cookie` 的属性段、`Cache-Control` |
| `EV-HTTP` | 响应体 JSON | 原样落 `*.json`；断言取 `HTTP 状态码` + `code` + `data.<字段>` |
| `EV-SQL` | 数据库直查 | `SELECT` 结果原样贴；禁止只贴「已确认」 |
| `EV-SC` | 截图 | 1280×800 视口，文件名 `<用例ID>-<序号>.png` |
| `EV-LOG` | 服务端日志 | 按 `requestId` 过滤，去掉 Cookie 与 Authorization |
| `EV-NET` | 出站调用记录 | mock 服务端请求计数 / `netstat` 连接计数；用于证明「没有调用」 |
| `EV-GREP` | 仓库检索 | `git grep -n` 命中行原样贴；用于零明文与枚举检查 |

> 含号码的证据一律用假号（`13800000001` 起）；Cookie/session id 截断为前 8 位；任何证据文件不得出现真实 `DEEPSEEK_API_KEY`、完整 prompt/response。

### 0.4 编号与分组

用例 ID = `<组>-<序号>`，组共 15 个：

| 组 | 主题 | 条数 | 对应硬判据 |
|---|---|---|---|
| `AUTH` | 登录态与会话 | 8 | 登录态 |
| `PERM` | 17 接口 × 3 身份权限矩阵 | 51 | 越权 |
| `AUTHZ` | 越权的终态分支（合格对端/CLOSED/已删除/忽略字段） | 6 | 越权 |
| `MASK` | 遮蔽边界（正例/反例/所有者/出口） | 19 | 遮蔽边界 |
| `TIER` | 三档号码可见与授权方向 | 14 | 分档 |
| `IDEM` | 幂等与重复提交 | 12 | 幂等 |
| `DEG` | 双链路降级 | 12 | 降级 |
| `PAIR` | 多对 pair 独立、无占用语义 | 7 | 新增判据 ① |
| `ST` | 三态流转与「不自动置位」 | 12 | 新增判据 ② |
| `PHONE` | 手机号只做格式校验、无短信 | 8 | 新增判据 ③ |
| `RET` | 不自动下架与保留期窗口 | 6 | 新增判据 ④ / 终态 vF-5/6 |
| `TERM` | 终态组：COMPLETED/CLOSED 三套可见性 | 14 | 新增判据 ⑤（终态组） |
| `SCHEMA` | 数据层七表与约束 | 5 | 技术口径 |
| `AUDIT` | 取号审计与 fail-closed | 4 | 技术口径 |
| `NOTI` | 通知生成/未读/失效 | 5 | 技术口径 |
| `MY` | 我的启事三态与三计数 | 4 | 技术口径 |
| `API` | 通用契约与错误码 | 7 | 通用 |
| `CONF` | 配置集中与启动校验 | 5 | 技术口径 |
| `SEC` | 零明文与证据白名单 | 5 | 红线 |

**合计 204 条**（逐组数字见 §4.1，与上表一致；`PERM` 51 条是 17 接口 × 3 身份的逐格用例）。

---

## 1. 需求条目登记表（0 遗漏的对账基准）

> 来源标记：`P` = ZHYYKFX-44 产品口径；`T` = ZHYYKFX-44 技术口径；`V` = 人类 2026-10-27 终态口径 vF；`C` = A1 契约 v2.1 的接口级细化（需求正文授权 A1 展开，验收按契约判定）。
> 「原文要点」是需求原文的**摘录**，只摘与判据相关的最小片段；完整原文见任务正文，不以本文代替需求。

| REQ | 源 | 原文要点（摘录） | 对应用例 ID |
|---|---|---|---|
| REQ-01 | P1 | 每一对 LOST↔FOUND 是独立 1:1 单元；一条招领可与多条寻物各成一个 pair；某一对处理完不影响其他启事、也不影响其他 pair；不得引入占用/锁定语义 | `PAIR-01` `PAIR-02` `PAIR-03` `PAIR-04` `PAIR-05` |
| REQ-02 | P1 | 唯一键仍为 (lost_id, found_id)，一行一对 | `PAIR-01` `SCHEMA-03` |
| REQ-03 | P1 | pair.status 取 NEW / CONTACTED / MATCHED / IGNORED，只描述这一对内部的进展 | `PAIR-06` `IDEM-09` `SCHEMA-03` |
| REQ-04 | P1 | 增 lost_confirmed_at / found_confirmed_at，两列均非空 ⇔ MATCHED | `PAIR-05` `ST-04` `IDEM-02` `SCHEMA-04` |
| REQ-05 | P2 | 三态 PENDING / COMPLETED / CLOSED，CLAIMED 作废 | `ST-08` `ST-09` `ST-10` `API-06` `SCHEMA-04` |
| REQ-06 | P2 | COMPLETED 前置为该记录已有一对 pair 处于 MATCHED，由本人手动标记 | `ST-05` `ST-06` `ST-08` `TERM-07` |
| REQ-07 | P2 | CLOSED 由本人手动关闭、无前置 | `ST-09` `PERM-05c` |
| REQ-08 | P2 | 两态都只能本人操作，系统不自动置位、不自动关闭 | `ST-01` `ST-02` `ST-03` `ST-11` `RET-05` |
| REQ-09 | P2 | pair 的任何进展都不触发记录状态变更 | `ST-04` `PAIR-02` `PAIR-05` |
| REQ-10 | P2b | COMPLETED 在公共列表与搜索 0 命中 | `TERM-01` `TERM-02` |
| REQ-11 | P2b | 保留期内（completed_retention_days=30）仍进召回 | `RET-03` `TERM-03` |
| REQ-12 | P2b | 仅当某位已登录用户自己的启事与该记录匹配且 score ≥ 0.90 时可看详情，其余 404 | `TERM-05` `TERM-06` |
| REQ-13 | P2b | 到期处理并入 D1 每日定时器，到期不改写 status | `RET-04` `RET-05` `TERM-13` `TERM-14` `ST-11` |
| REQ-14 | P2b | CLOSED 不召回、不显示、任何身份详情 404，相关 pair 一并失效 | `TERM-08` `TERM-09` `TERM-10` `TERM-11` `TERM-12` |
| REQ-15 | P2b / V1 | 未达 MATCHED 标完成 → 409，status 与 updated_at 不变 | `ST-05` `ST-06` |
| REQ-16 | P3 | 列表与详情允许匿名浏览但走遮蔽；写一律要求登录 | `AUTH-01` `AUTH-06` `AUTH-07` `PERM-01a` `PERM-03a` |
| REQ-17 | P4 | 手机号只做格式校验，不做短信验证；UI 标注「自行填写，请自行核实」 | `PHONE-01` `PHONE-02` `PHONE-03` `PHONE-05` `PHONE-08` |
| REQ-18 | P5 | t_user 不存手机号，号码只挂在启事上 | `SCHEMA-02` `PHONE-06` |
| REQ-19 | P6 | 不做自动下架（PENDING 永不下架） | `RET-01` `RET-02` |
| REQ-20 | T | 表数 7 张 | `SCHEMA-01` |
| REQ-21 | T / H5 | 查看号码看「对方那一端」是否已授权；未授权回「对方尚未公开」；< 0.75 一律 403 | `TIER-01` `TIER-02` `TIER-04` `TIER-07` |
| REQ-22 | T / H6 | 附言 ≤200 字，随授权写入、撤回时清空，且必须过打码 | `TIER-09` `TIER-11` `MASK-16` |
| REQ-23 | T / H13 | 配置集中：四键同配置类读取，不散落硬编码 | `CONF-01` `CONF-02` `CONF-03` `CONF-04` `TIER-13` |
| REQ-24 | T / H7 | pair 链路可降级（engine=LOCAL_KEYWORD 且页面提示）；/api/match 不自动降级 | `DEG-01` `DEG-07` `DEG-08` `DEG-09` `DEG-10` |
| REQ-25 | T / H8 | t_match_result 写入前必须脱敏 | `MASK-13` `IDEM-10` |
| REQ-26 | T / H9 | 不开放注册，seed 内置 3 个演示账号（含 2 个越权测试用） | `API-06` `AUTH-03` |
| REQ-27 | T / H10 | publisher_id 应用层强制非空，不接受客户端传值 | `AUTHZ-04` `SCHEMA-01` |
| REQ-28 | T / H4 | 密钥只从运行时读，任务/评论/代码/配置/日志/截图/证据/文档零明文 | `SEC-01` `SEC-02` `SEC-03` `SEC-04` |
| REQ-29 | C §3 | 17 接口 × 3 身份逐格有状态码，无空格 | `PERM-01a` … `PERM-17c`（51 条）、`AUTHZ-01` … `AUTHZ-06` |
| REQ-30 | C §7 | 逐接口幂等性：同 action 幂等、状态机同值 409、写请求不自动重放 | `IDEM-01` … `IDEM-12` |
| REQ-31 | T / H4 | 证据只记 model / latency_ms / prompt_tokens / completion_tokens | `SEC-05` |
| REQ-32 | C §6 | 403 两个 code 分流（50002 / 50005）；409 三个 code 分流（20002 / 20003 / 20005） | `API-04` `TIER-14` |
| REQ-33 | C §4.16–4.17 | 通知三类型、unreadCount 为全部未读、失效 pair 的通知仍返回 | `NOTI-01` `NOTI-02` `NOTI-03` `NOTI-04` `NOTI-05` |
| REQ-34 | C §4.14 | 每次取号写一条 t_access_log；审计写失败返回 500 且不出号 | `AUDIT-01` `AUDIT-02` `AUDIT-03` `AUDIT-04` |
| REQ-35 | C §1.1 | session Cookie 属性、credentials、CORS 显式 origin | `AUTH-02` `AUTH-04` `AUTH-05` `AUTH-08` |
| REQ-36 | C §1 / §6 | 统一响应体 `{code,message,data,requestId}`；错误码表逐值可用 | `API-01` `API-02` `API-03` `API-05` `API-07` |
| REQ-37 | C §4.6 | 删除与三态正交：只写 deleted_at，不写 status | `ST-12` `PERM-06c` |
| REQ-38 | C §9.4 | pair_state 四值内部状态，与 status 无联动 | `ST-01` `DEG-07` `MY-01` |
| REQ-39 | C §4.12 | 我的启事发三态都返回，带 detailVisible；三计数不混用 | `MY-01` `MY-02` `MY-03` `MY-04` |
| REQ-40 | C §4.1 | 公共列表恒 `status='PENDING'`；分页/排序白名单 | `PERM-01a` `PERM-01b` `API-05` `RET-01` |
| REQ-41 | A2 §范围-2 | 遮蔽边界（六类硬判据之一）：`location` 截断、`description` 打码、`contact` 置空、`publisherName` 打码，正例/反例各可复现 | `MASK-01` … `MASK-19`（19 条） |
| REQ-42 | A2 §范围-2 | 分档（六类硬判据之一）：直看 0.90 / 可授权 0.75 两档与三档响应语义 | `TIER-01` … `TIER-14` |
| REQ-43 | A2 §范围-2 | 幂等（六类硬判据之一）：同 action 幂等、状态机同值 409、每次取号留痕 | `IDEM-01` … `IDEM-12` |
| REQ-44 | A2 §范围-2 | 降级（六类硬判据之一）：pair 链路七种降级原因、`/api/match` 不降级 | `DEG-01` … `DEG-12` |
| REQ-45 | A2 §范围-2 | 登录态（六类硬判据之一）：session、Cookie 属性、401 统一出口、匿名可读 | `AUTH-01` … `AUTH-08` |
| REQ-46 | A2 §范围-2 | 越权（六类硬判据之一）：17×3 逐格 + 终态分支（合格对端/CLOSED/已删除/被忽略字段） | `PERM-01a` … `PERM-17c`、`AUTHZ-01` … `AUTHZ-06` |

**对账：46 条需求 → 204 条用例，无空行；反向核对见 §4.2（19 个用例组全部挂到 REQ，孤儿数 0）。**

> **不计入用例的需求条目（不算遗漏，理由写在这里）：** ①ZHYYKFX-44「技术口径」最后一条是**运行时岗位分工**（开发岗 dsh、测试/审查/文档 codebuddy、qoder-cn 不派）——人员约定，没有可观测的系统行为；②同任务书的「6 阶段 / 17 条任务 / 4 道门禁」是项目计划，由任务状态与门禁判定，不由用例判定。这两条在本文以「不计入」处理，若验收要求把它们也变成判据，需要先定义判定主体（谁看、看什么文件）。

> 说明：`REQ-41`…`REQ-46` 是 A2 任务书「六类硬判据」的直接落项，不是从 P/T 条目推导出来的新需求；它们与 `REQ-01`…`REQ-40` 有交叉（如 `REQ-21` 分档语义），交叉处不合并编号，便于按判据回查。

---

## 2. 夹具（F1 按此建数据，F2 按此复现）

> 夹具**直插** `t_pair_score` 与 `t_record`，用于验证判据；不用于验证打分链路（打分链路由 `DEG-*` 与 `PAIR-01` 覆盖）。
> 所有时间以库服务器 `NOW()` 为基准；`created_at` / `completed_at` 的偏移量写死在下方，避免「跑的时候刚好跨天」。

### 2.1 账号

| 别名 | student_no | display_name | 密码（bcrypt 存储） | 用途 |
|---|---|---|---|---|
| `U1` | `20230001` | 李同学 | seed 中明文口令 `Demo#2026` 的 bcrypt 值 | 记录所有者 A/C/H |
| `U2` | `20230002` | 王同学 | 同上 | 记录所有者 B/D；越权测试主体 |
| `U3` | `20230003` | 张同学 | 同上 | 招领所有者 F1/F2/E/G；越权测试主体 |
| `U4` | `20230004` | 陈同学 | 同上 | 既非所有者也无 pair 的登录用户（COMPLETED 详情 404 用） |

> 口令只出现在 `db/seed.sql` 注释里（H9/A8 已拍板）；任何证据文件不得复述口令。

### 2.2 记录

| 别名 | type | status | publisher | 关键字段 | 用途 |
|---|---|---|---|---|---|
| `R-A` | LOST | PENDING | U1 | title「图书馆丢了黑色钱包」，location「图书馆三楼自习区A12座」，description「昨天 14:30 在图书馆三楼弄丢，联系 13812345678」 | 多 pair、分档、遮蔽 |
| `R-B` | LOST | PENDING | U2 | title「操场丢了白色 iPhone」 | 多 pair 的另一位失主 |
| `R-C` | LOST | PENDING | U1 | `created_at = NOW() - INTERVAL 40 DAY` | 不自动下架（REQ-19） |
| `R-D` | LOST | PENDING | U2 | `created_at = NOW() - INTERVAL 400 DAY` | 不自动下架上界 |
| `R-F1` | FOUND | PENDING | U3 | title「图书馆拾到黑色钱包」，`contact="13800000003"` | 与 A、B 各成一对 |
| `R-F2` | FOUND | PENDING | U3 | title「拾到一把深蓝雨伞」 | 与 A 成低分 pair、与 H 成 CLOSED pair |
| `R-E` | FOUND | COMPLETED | U3 | `completed_at = NOW() - INTERVAL 5 DAY` | 保留期内 COMPLETED |
| `R-G` | FOUND | COMPLETED | U3 | `completed_at = NOW() - INTERVAL 40 DAY` | 已到期 COMPLETED |
| `R-H` | LOST | CLOSED | U1 | `updated_at` 固定值 | CLOSED 全套 |
| `R-X` | LOST | PENDING | U1 | `deleted_at = NOW()` | 已删除记录（pair 404/20004） |

### 2.3 pair

| 别名 | lost | found | score | status | confirmed_at | 授权的端 | 用途 |
|---|---|---|---|---|---|---|---|
| `P-1` | R-A | R-F1 | 0.9500 | NEW | 两端 NULL | 无 | 直看档、多对 A 侧 |
| `P-2` | R-B | R-F1 | 0.8800 | NEW | 两端 NULL | 无 | 多对 B 侧、可授权档 |
| `P-3` | R-A | R-F2 | 0.6200 | NEW | 两端 NULL | 无 | 低分档（不出卡片） |
| `P-4` | R-B | R-E | 0.9500 | MATCHED | 两端 = NOW()-4d | 无 | MATCHED 前置、COMPLETED 详情门槛 |
| `P-5` | R-H | R-F2 | 0.9200 | NEW | 两端 NULL | 无 | CLOSED 失效（403/50005） |

> `R-A ↔ R-F2` 只有一行（`P-3`）：`TIER-05`…`TIER-07`、`PAIR-06` 需要别的分数时，用 §2.6 的 ①改分 就地改，**不新建第二行**（唯一键 `uk_pair_lost_found` 会拒绝）。

### 2.4 补充夹具（只被单个用例引用，避免主夹具膨胀）

| 别名 | 表 | 关键字段 | 用于 |
|---|---|---|---|
| `R-P2` | t_record | U1 所有，PENDING，`location="东区3号宿舍楼512室"`，`description="QQ 123456789，证件号 110101199001011234"` | `MASK-03` |
| `R-P3` | t_record | U1 所有，PENDING，`location="图书馆三楼自习区A12座"`，`description="上周五 09:15 放在 3 号楼 201，电话 13900001111"` | `MASK-04` |
| `R-N1` | t_record | U1 所有，PENDING，`location="图书馆"`，`description="3 号楼 201 教室，无联系方式"` | `MASK-05` |
| `R-N2` | t_record | U1 所有，PENDING，`description="编号 12345，另一个 12345678，均非 QQ；时间 13:30 未写日期"` | `MASK-06` |
| `R-N3` | t_record | U1 所有，PENDING，`location="操场"`，`description="2026-10-21 13:30 在操场捡到校园卡，卡号 201"` | `MASK-07` |
| `R-S1` / `R-S2` | t_record | U1 所有，PENDING，`publisher_name` 分别为 `"赵"` / `NULL` | `MASK-09` |
| `R-L1` | t_record | U1 所有，PENDING，`contact='QQ123456'`（历史非手机号值，直插） | `PHONE-07` |
| `R-M1` | t_record | U1 所有，`status='COMPLETED'`，`completed_at=NOW()-2d` | `MY-01` |
| `R-T1` / `R-T2` | t_record | U3 所有，`status='COMPLETED'`，`completed_at` 分别为 `NOW()-30d` / `NOW()-29d` | `RET-06` |
| `R-U4` | t_record | U4 所有的 LOST，PENDING | `TERM-05` |
| `P-8` | t_pair_score | `R-U4 ↔ R-E`，`score=0.8500`，`status='NEW'` | `TERM-05` |
| `P-9` | t_pair_score | `R-U4 ↔ R-G`，`score=0.9500`，`status='NEW'` | `TERM-12` `TERM-13` |
| `N1` / `N2` | t_notification | 分别属 U1 / U1（`N2` 用于他人的越权格） | `IDEM-05` `PERM-17c` |

### 2.5 执行约定

- **每个用例执行前重建夹具**（`beforeEach` 跑 §2.1–2.4 全量 + `TRUNCATE` 掉用例自己产生的行）。母句：本文所有前置都指「重置后」的状态。
- `t_pair_score` 的 `updated_at` 是 `ON UPDATE CURRENT_TIMESTAMP`——`IDEM-01`/`PAIR-02` 这类要断言「`updated_at` 不变」的用例，比较的是**调用前后两次 SQL 的值**，不是与 `created_at` 比较。
- 时间偏移一律用库服务器 `NOW()`，用例内不出现硬编码日期（`2026-10-27` 只出现在文档叙述里）。

### 2.6 变换脚本（每个用例前置只允许这 4 类操作）

```sql
-- ① 改分
UPDATE t_pair_score SET score = ?, updated_at = updated_at WHERE id = ?;
-- ② 改授权（单向，模拟「对方已公开」）
UPDATE t_pair_score SET found_unlocked_at = NOW(), found_unlock_note = ? WHERE id = ?;
-- ③ 改确认（单向或双向，模拟 pair 进展）
UPDATE t_pair_score SET found_confirmed_at = NOW() WHERE id = ?;
-- ④ 改保留期窗口
UPDATE t_record SET status = 'COMPLETED', completed_at = NOW() - INTERVAL ? DAY WHERE id = ?;
```

> ②③④ 的写入都必须**直接 SQL**，不走接口——它们模拟「另一端做过动作」，用接口造会混入被测行为。

---

## 3. 用例正文

### 3.1 `AUTH` · 登录态与会话（8 条）

| ID | P | 前置 | 步骤 | 期望 | 证据 |
|---|---|---|---|---|---|
| AUTH-01 | P0 | 无 Cookie | `POST /api/records` 带合法 body；再试 `PUT /api/records/{R-A}`、`PATCH /api/records/{R-A}/status`、`DELETE /api/records/{R-A}`、`POST /api/match`、`GET /api/my/records`、`GET /api/notifications` | 6 个请求全部 `HTTP 401` + `code=10006` + `message="请先登录"`；响应体 `data=null`；`t_record` 中 R-A 的 `updated_at` 与调用前一致 | `EV-HTTP` ×6 + `EV-SQL` |
| AUTH-02 | P0 | 无 Cookie | `POST /api/auth/login` body `{"studentNo":"20230001","password":"Demo#2026"}` | `HTTP 200` + `code=0` + `data={id,studentNo:"20230001",displayName:"李同学"}`；`Set-Cookie` 含 `JSESSIONID=`、`Path=/`、`HttpOnly`、`SameSite=Lax`，**不含** `Secure`（HTTP 环境）；`message="登录成功"` | `EV-H` + `EV-HTTP` |
| AUTH-03 | P1 | 无 Cookie | 分别用 ① 不存在学号 `20239999` ② `U1` 的学号 + 错口令 ③ `U1` 停用（`is_active=0`）登录 | 三例全部 `HTTP 401` + `code=10006`；三次 `message` 用 `===` 比较为同一字符串、码点数 = 9、且不含子串「账号」「密码错误」（不区分账号与口令，防枚举） | `EV-HTTP` ×3 |
| AUTH-04 | P0 | 已登录（U1） | ① 服务端重启后带旧 Cookie 调 `GET /api/auth/me` ② `session` 超时 30 分钟后调同接口 | 两例均 `HTTP 401` + `code=10006`；`data=null` | `EV-HTTP` + `EV-LOG`（重启事件） |
| AUTH-05 | P1 | 已登录（U1） | `POST /api/auth/logout` 两次，中间不重新登录；随后 `GET /api/auth/me` | 两次 logout 均 `HTTP 200` + `code=0` + `data=null` + `message="已退出"`；随后 me → `HTTP 401` + `code=10006` | `EV-HTTP` ×3 |
| AUTH-06 | P0 | 无 Cookie | `GET /api/records`、`GET /api/records/{R-A}`、`GET /api/categories` | 三者均 `HTTP 200` + `code=0`；列表 `data.list` 非空且每项 `isMine=false`；详情 `data.contact=null` 且 `data` 内无 `pairs` 键（`Object.keys` 不含 `pairs`） | `EV-HTTP` |
| AUTH-07 | P1 | 前端无 session | 浏览器直开 `/my`；再直开 `/records/new` | 两页均重定向到 `/login?redirect=<原路径>`；URL 的 `redirect` 值等于被访问路径（`/my`、`/records/new`） | `EV-SC` ×2 |
| AUTH-08 | P1 | 已登录（U1） | 用 `Origin: http://localhost:5173` 发 `GET /api/auth/me`；再用 `Origin: http://evil.example` 发同请求 | 前者 `HTTP 200` + `Access-Control-Allow-Credentials: true` + `Access-Control-Allow-Origin: http://localhost:5173`；后者**不返回** `Access-Control-Allow-Origin`（值不为 `*`） | `EV-H` ×2 |

---

### 3.2 `PERM` · 17 接口 × 3 身份权限矩阵（51 条）

**统一前置：** 夹具已建（§2）；`U1` 拥有 `R-A`，`U2` 不拥有 `R-A`，`U4` 既非所有者也无 pair。
**统一身份定义：** `a` = 无 Cookie（匿名）；`b` = `U2` 的 Cookie（登录非所有者）；`c` = `U1` 的 Cookie（所有者）。
**统一步骤：** 用对应身份调用该行接口的**最小合法请求**（路径参数取该行标注的记录/pair/通知 ID）；每格单独一次调用，不复用上格 Cookie 状态。
**统一证据：** `EV-HTTP`（每格一份 JSON，文件名 `PERM-<行><身份>.json`）。

| # | 接口 | a 匿名 | b 登录非所有者 | c 所有者 |
|---|---|---|---|---|
| 1 | `GET /api/records` | `PERM-01a`：200 / 0；`list` 每项 `isMine=false`，无 `contact/publisherName/description`；`total` = 仅 PENDING 条数 | `PERM-01b`：200 / 0；同一集合，`isMine` 全 `false`；传 `status=COMPLETED` 仍 200 且 `data.list=[]`、`total=0` | `PERM-01c`：200 / 0；本人 `PENDING` 行 `isMine=true`、`location` 原文；本人 `COMPLETED/CLOSED` 行 0 命中 |
| 2 | `GET /api/records/{R-A}` | `PERM-02a`：200 / 0；`contact=null`、`publisherName="李**"`、`location="图书馆三楼自习区…"`；无 `pairs` 键 | `PERM-02b`：200 / 0；同遮蔽，`allowedStatuses=[]` | `PERM-02c`：200 / 0；四项原文、`isMine=true`、`pairs` 为数组、`allowedStatuses=["CLOSED"]`（`P-1` 未 MATCHED） |
| 3 | `POST /api/records` | `PERM-03a`：401 / 10006 | `PERM-03b`：200 / 0；`data.publisherId` 未返回，`t_record.publisher_id` = U2 的 id | `PERM-03c`：200 / 0；新行 `publisher_id` = U1 的 id |
| 4 | `PUT /api/records/{R-A}` | `PERM-04a`：401 / 10006 | `PERM-04b`：403 / 10007 | `PERM-04c`：200 / 0；`updated_at` 变为请求时刻 |
| 5 | `PATCH /api/records/{R-A}/status` | `PERM-05a`：401 / 10006 | `PERM-05b`：403 / 10007 | `PERM-05c`：body `{"status":"CLOSED"}` → 200 / 0；`data.status="CLOSED"` |
| 6 | `DELETE /api/records/{R-A}` | `PERM-06a`：401 / 10006 | `PERM-06b`：403 / 10007 | `PERM-06c`：200 / 0；`data=null`；`t_record.deleted_at` 非空、`status` 仍原值 |
| 7 | `GET /api/categories` | `PERM-07a`：200 / 0；数组按 `sortOrder` 升序 | `PERM-07b`：200 / 0；与 a 同集合 | `PERM-07c`：200 / 0；与 a 同集合 |
| 8 | `POST /api/match` | `PERM-08a`：401 / 10006 | `PERM-08b`：200 / 0；`items[].title/location/reason` 已打码，无 `contact/publisherName` | `PERM-08c`：200 / 0；候选仍遮蔽（候选是他人记录） |
| 9 | `POST /api/auth/login` | `PERM-09a`：错口令 → 401 / 10006；对的口令 → 200 / 0 | `PERM-09b`：同上两条 | `PERM-09c`：已登录再登录 → 200 / 0 且 `Set-Cookie` 的 `JSESSIONID` 值变化 |
| 10 | `POST /api/auth/logout` | `PERM-10a`：200 / 0（未登录调用也 200） | `PERM-10b`：200 / 0 | `PERM-10c`：200 / 0 |
| 11 | `GET /api/auth/me` | `PERM-11a`：401 / 10006 | `PERM-11b`：200 / 0；`data.id` = U2 | `PERM-11c`：200 / 0；`data.id` = U1 |
| 12 | `GET /api/my/records` | `PERM-12a`：401 / 10006 | `PERM-12b`：200 / 0；`list` 只含 `publisher_id=U2` 的行（含 `R-B`、`R-D`），不含 `R-A` | `PERM-12c`：200 / 0；只含 U1 行，三态都在（`R-A` PENDING、`R-C` PENDING、`R-H` CLOSED） |
| 13 | `PATCH /api/pairs/{P-1}` | `PERM-13a`：401 / 10006 | `PERM-13b`：403 / 10007（`U2` 不是 `P-1` 任一端） | `PERM-13c`：body `{"action":"CONTACT"}` → 200 / 0；`data.status="CONTACTED"` |
| 14 | `GET /api/pairs/{P-1}/contact` | `PERM-14a`：401 / 10006 | `PERM-14b`：403 / 10007 | `PERM-14c`：200 / 0；`data.tier="DIRECT"`、`available=true`、`contact="13800000003"` |
| 15 | `POST /api/pairs/{P-1}/contact-unlock` | `PERM-15a`：401 / 10006 | `PERM-15b`：403 / 10007 | `PERM-15c`：body `{"action":"PUBLISH","note":"周六下午图书馆门口"}` → 200 / 0；`data.side="LOST"`、`public=true` |
| 16 | `GET /api/notifications` | `PERM-16a`：401 / 10006 | `PERM-16b`：200 / 0；`data.list` 只含 `user_id=U2` 的通知 | `PERM-16c`：200 / 0；只含 `user_id=U1` 的通知 |
| 17 | `PATCH /api/notifications/{N2}/read` | `PERM-17a`：401 / 10006 | `PERM-17b`：403 / 10007（`N2` 属 U1） | `PERM-17c`：200 / 0；`data.read=true`、`data.unreadCount` 减 1 |

**矩阵补充格（终态分支，单列以免与上表 51 格混算）：**

| ID | P | 前置 | 步骤 | 期望 | 证据 |
|---|---|---|---|---|---|
| AUTHZ-01 | P0 | `R-E` 为 COMPLETED（未到期），U2 是 `P-4` 一端、U4 无关 | ① U2 调 `GET /api/records/{R-E}`（`P-4.score=0.95`）② U4 调同接口 ③ U1 调同接口 | ① 200 / 0（遮蔽视图，`contact=null`，无 `pairs` 键）② 404 / 20001 ③ 404 / 20001 | `EV-HTTP` ×3 |
| AUTHZ-02 | P0 | `R-H` 为 CLOSED | U1（所有者）、U2、匿名分别调 `GET /api/records/{R-H}` | 三次全部 404 / 20001（不含 403） | `EV-HTTP` ×3 |
| AUTHZ-03 | P1 | `R-A` PENDING | U3 调 `PUT /api/records/{R-A}`；U3 调 `DELETE /api/records/{R-A}` | 两次 403 / 10007；`t_record` 的 `updated_at`、`deleted_at` 与调用前一致 | `EV-HTTP` ×2 + `EV-SQL` |
| AUTHZ-04 | P1 | 已登录 U2 | `POST /api/records` body 内附加 `"publisherId": <U1 的 id>`、`"status":"COMPLETED"`、`"completedAt":"2026-10-01 10:00:00"` | 200 / 0；新行 `publisher_id` = U2 的 id、`status="PENDING"`、`completed_at IS NULL`（三字段均被忽略） | `EV-SQL` |
| AUTHZ-05 | P1 | `R-X` 已逻辑删除，`P-*` 无关联 | U1 调 `PATCH /api/pairs/{P-1}`（把 `R-A` 换成 `R-X` 侧构造 `P-7`）后调 `GET /api/pairs/{P-7}/contact` | 两次均 404 / 20004（记录已删走 20004，不是 403/50005） | `EV-HTTP` ×2 |
| AUTHZ-06 | P2 | 前端以 U2 登录，打开 `/records/{R-A}` | 观察按钮区 | 页面不渲染「编辑」「关闭」「删除」按钮；`allowedStatuses=[]`（接口值），不出现「标记完成」 | `EV-SC` |

### 3.3 `MASK` · 遮蔽边界（19 条）

> 全部用例的请求身份 = 匿名（`a`），除 `MASK-12` 为所有者；正例 P1–P3 / 反例 N1–N3 取自契约 §6.4，期望值是**逐字**的字符串，不做「包含」断言。

| ID | P | 前置 | 步骤 | 期望 | 证据 |
|---|---|---|---|---|---|
| MASK-01 | P0 | `R-A`（location `图书馆三楼自习区A12座`，PENDING） | 匿名 `GET /api/records/{R-A}` | `data.location="图书馆三楼自习区…"`（UTF-8 逐字节相等；末尾只有一个 `U+2026`） | `EV-HTTP` |
| MASK-02 | P0 | `R-A`（description `昨天 14:30 在图书馆三楼弄丢，联系 13812345678`） | 同 MASK-01 | `data.description="[时间已隐藏] 在图书馆三楼弄丢，联系 [手机号已隐藏]"`；不出现 `13812345678`、不出现 `14:30` | `EV-HTTP` |
| MASK-03 | P0 | 新建 `R-P2`：description `QQ 123456789，证件号 110101199001011234`，location `东区3号宿舍楼512室` | 匿名 `GET /api/records/{R-P2}` | `data.description="QQ[QQ号已隐藏]，证件号 [证件号已隐藏]"`；`data.location="东区3号宿舍楼…"` | `EV-HTTP` |
| MASK-04 | P0 | 新建 `R-P3`：description `上周五 09:15 放在 3 号楼 201，电话 13900001111`，location `图书馆三楼自习区A12座` | 匿名 `GET /api/records/{R-P3}` | `data.description="[时间已隐藏] 放在 3 号楼 201，电话 [手机号已隐藏]"`；子串 `3 号楼 201` 原样保留 | `EV-HTTP` |
| MASK-05 | P1 | 新建 `R-N1`：location `图书馆`，description `3 号楼 201 教室，无联系方式` | 匿名 `GET /api/records/{R-N1}` | `data.location="图书馆"`（无 `…`）；`data.description` 与原文逐字相等 | `EV-HTTP` |
| MASK-06 | P1 | 新建 `R-N2`：description `编号 12345，另一个 12345678，均非 QQ；时间 13:30 未写日期` | 匿名 `GET /api/records/{R-N2}` | `data.description` 与原文逐字相等（三个数字与 `13:30` 都不被替换） | `EV-HTTP` |
| MASK-07 | P1 | 新建 `R-N3`：description `2026-10-21 13:30 在操场捡到校园卡，卡号 201`，location `操场` | 匿名 `GET /api/records/{R-N3}` | `data.description` 与原文逐字相等；`data.location="操场"` | `EV-HTTP` |
| MASK-08 | P0 | `R-A`（contact `13812345678`） | ① 匿名 `GET /api/records/{R-A}` ② U2 登录后同请求 ③ 匿名 `GET /api/records` | ①② `data.contact` 为 `null`（不是 `"138****5678"`、不是 `""`）③ 列表项不含 `contact` 键 | `EV-HTTP` ×3 |
| MASK-09 | P0 | `R-A.publisherName="李同学"`；另建 `R-S1.publisherName="赵"`、`R-S2.publisherName=NULL` | 匿名分别取三条详情 | `"李同学"→"李**"`；`"赵"→"*"`；`NULL→"匿名同学"` | `EV-HTTP` ×3 |
| MASK-10 | P0 | `R-A` 所有者 U1 | U1 `GET /api/records/{R-A}` | `title`/`description`/`location`/`contact`/`publisherName` 五项与落库原文逐字相等（`contact="13812345678"`） | `EV-HTTP` + `EV-SQL` |
| MASK-11 | P0 | 列表含 `R-A`（匿名） | 匿名 `GET /api/records` | 每个 `list[]` 项 `Object.keys` = `{id,type,status,title,location,occurredAt,createdAt,categoryId,categoryName,isMine}`（10 个键）；不含 `description`/`contact`/`publisherName` | `EV-HTTP` |
| MASK-12 | P0 | `R-A` | U2 调 `POST /api/match` body `{"text":"图书馆黑色钱包 三楼自习区"}` | `items[].title`、`items[].location`、`items[].reason` 三个字段均无 `13812345678`、无 `A12`；`items[]` 无 `contact`/`publisherName` 键 | `EV-HTTP` |
| MASK-13 | P0 | 一次 `/api/match` 成功后 | `SELECT input_text, candidates FROM t_match_result ORDER BY id DESC LIMIT 1` | `input_text` 中无 `1[3-9]\d{9}` 形态的 11 位串；`candidates` JSON 内同正则命中数 = 0 | `EV-SQL` |
| MASK-14 | P0 | U1 打开自己 `R-A` 详情（含 `P-1` 卡片） | 取 `data.pairs[0]` 的键集合 | `pairs[0]` 无 `contact` 键；`counterpart.title`/`counterpart.location` 已打码（若含号码/门牌则被替换）；`contactAvailable` 为 boolean | `EV-HTTP` |
| MASK-15 | P0 | 触发一条 `PAIR_UNLOCK` 通知（U3 对 `P-1` PUBLISH） | U1 `GET /api/notifications` | `list[].title` 与 `list[].body` 中 `1[3-9]\d{9}` 命中数 = 0；`body` 含 `…` 之外的原文引用不出现完整门牌 `A12` | `EV-HTTP` |
| MASK-16 | P0 | `R-A` owner U1，`P-1` | U1 对 `P-1` 发 `{"action":"PUBLISH","note":"电话 13800001111，周六见"}` | 响应 `data.note="电话 [手机号已隐藏]，周六见"`；`SELECT found_unlock_note`/`lost_unlock_note` 中 `1[3-9]\d{9}` 命中数 = 0 | `EV-HTTP` + `EV-SQL` |
| MASK-17 | P1 | 新建 location 为 `   `（三空格）的记录 | 匿名 `GET /api/records/{id}` | `data.location="校园内"`（不是空串、不是 `"…"`） | `EV-HTTP` |
| MASK-18 | P1 | `R-A` | 连续两次匿名 `GET /api/records/{R-A}` | 两次响应的 `description`/`location`/`publisherName` 三个字符串逐字节相等（打码器无状态、不随调用漂移） | `EV-HTTP` ×2 |
| MASK-19 | P1 | `R-A` | `GET /api/records/{R-A}` 的响应头 | `Cache-Control` 含 `private` 且含 `no-store` | `EV-H` |

---

### 3.4 `TIER` · 分档与授权方向（14 条）

| ID | P | 前置 | 步骤 | 期望 | 证据 |
|---|---|---|---|---|---|
| TIER-01 | P0 | `P-1.score=0.95`（≥0.90），`score` 双方均未授权 | U1 `GET /api/pairs/{P-1}/contact` | 200 / 0；`data.tier="DIRECT"`、`available=true`、`contact` = `R-F1.contact` 原值、`reason=null`、`message="匹配度较高，可直接查看"`、`scorePercent=95` | `EV-HTTP` |
| TIER-02 | P0 | `P-2.score=0.88`，`R-F2`/`R-F1` 两端均未授权 | U2 `GET /api/pairs/{P-2}/contact` | 200 / 0；`available=false`、`contact=null`、`reason="NOT_PUBLIC"`、`message="对方尚未公开"`、`tier="AUTHORIZABLE"` | `EV-HTTP` |
| TIER-03 | P0 | `P-2.score=0.88`，`found_unlocked_at=NOW()`、`found_unlock_note="周六下午图书馆门口"` | U2（LOST 端）`GET /api/pairs/{P-2}/contact` | 200 / 0；`available=true`、`contact` = `R-F1.contact`、`reason=null`、`message="对方已公开联系方式"`、`unlockedAt` 非 null、`note="周六下午图书馆门口"` | `EV-HTTP` |
| TIER-04 | P0 | `P-3.score=0.62`（<0.75） | U1 `GET /api/pairs/{P-3}/contact` | 403 / 50002；`data=null`；`message="匹配度不足，无法查看联系方式"`；响应体全文 `1[3-9]\d{9}` 命中数 = 0（含掩码形式 `\d{3}\*{4}\d{4}` 也命中数 = 0） | `EV-HTTP` |
| TIER-05 | P0 | `P-3.score` 改 `0.7500`，双方未授权 | U1 取号 | 200 / 0；`tier="AUTHORIZABLE"`、`available=false`、`reason="NOT_PUBLIC"`（边界值属于可授权档，不是 403） | `EV-HTTP` |
| TIER-06 | P0 | `P-3.score` 改 `0.7499` | U1 取号 | 403 / 50002 | `EV-HTTP` |
| TIER-07 | P0 | `P-1.score` 改 `0.9000`，双方未授权 | U1 取号 | 200 / 0；`tier="DIRECT"`、`available=true`（边界值属于直看档） | `EV-HTTP` |
| TIER-08 | P0 | `P-2.score=0.88`，`lost_unlocked_at` 为 NULL | U2（LOST 端）先 `PUBLISH`，再**自己**取号 | `PUBLISH` 200 / 0 且 `data.public=true`；随后取号 `available=false`、`reason="NOT_PUBLIC"`（自己公开只让对方能看，不让自己看对方） | `EV-HTTP` ×2 |
| TIER-09 | P1 | `P-2.score=0.88`，`found_unlocked_at=NOW()`、note 非空 | U3（FOUND 端）`REVOKE`，随后 U2 取号 | `REVOKE` 200 / 0 且 `data.public=false`、`data.note=null`；U2 取号 `available=false`、`reason="NOT_PUBLIC"`、`unlockedAt=null`、`note=null` | `EV-HTTP` ×2 + `EV-SQL`（`found_unlock_note IS NULL`） |
| TIER-10 | P1 | `P-2.score=0.62` | U2 `POST /api/pairs/{P-2}/contact-unlock` body `{"action":"PUBLISH"}` | 403 / 50002；`t_pair_score.found_unlocked_at`/`lost_unlocked_at` 均保持 NULL | `EV-HTTP` + `EV-SQL` |
| TIER-11 | P1 | `P-2.score=0.88`，note 长度 201 字 | U2 `PUBLISH` | 400 / 10001；`data.fieldErrors[0].field="note"`；`lost_unlocked_at` 保持 NULL | `EV-HTTP` + `EV-SQL` |
| TIER-12 | P1 | `P-2.score=0.88`，`found_unlocked_at=NOW()`，`R-F1.contact IS NULL` | U2 取号 | 200 / 0；`available=false`、`contact=null`、`reason="NOT_PROVIDED"`、`message="对方未填写联系方式"` | `EV-HTTP` |
| TIER-13 | P1 | `P-3.score=0.62` | U1 `GET /api/records/{R-A}` 的 `pairs[]`；再 `GET /api/my/records` | `pairs[]` 不含 `pairId=P-3`；`lowConfidenceCount=1`、`relatedCount` = 1（只数 `P-1`）；列表项 `pairs` 长度 = `relatedCount` | `EV-HTTP` |
| TIER-14 | P1 | 改 `application.yml`：`contact_direct_threshold: 0.95`，重启 | U1 对 `P-1`（0.95）与 `P-3`（改回 0.92）取号 | 0.95 的记录 `tier="DIRECT"`；0.92 的记录 `tier="AUTHORIZABLE"` 且 `available` 由对方授权决定（阈值从配置读，未硬编码） | `EV-HTTP` + `EV-LOG`（启动打印阈值） |

---

### 3.5 `IDEM` · 幂等（12 条）

| ID | P | 前置 | 步骤 | 期望 | 证据 |
|---|---|---|---|---|---|
| IDEM-01 | P0 | `P-1` 为 `NEW` | U1 连续两次 `PATCH /api/pairs/{P-1}` body `{"action":"CONTACT"}` | 两次均 200 / 0 且 `data.status="CONTACTED"`；第二次响应 `data.updatedAt` 与第一次相等；`t_pair_score.updated_at` 只变一次（第二次调用前后 SQL 相等） | `EV-HTTP` ×2 + `EV-SQL` ×2 |
| IDEM-02 | P0 | `P-1` 为 `CONTACTED`，`lost_confirmed_at` 已非空 | 同一端再 `{"action":"CONFIRM"}` 两次 | 两次 200 / 0；`lostConfirmedAt` 与首次相等（时间不刷新）；`found_confirmed_at` 仍 NULL、`status` 仍 `CONTACTED` | `EV-HTTP` ×2 + `EV-SQL` |
| IDEM-03 | P0 | `P-2`，`score=0.88`，无授权 | U2 连续两次 `PUBLISH`（body 相同、note 相同） | 两次 200 / 0；`data.unlockedAt` 两次相等；`t_notification` 中 `(user_id=U3, pair_id=P-2, type='PAIR_UNLOCK')` 行数 = 1 | `EV-HTTP` ×2 + `EV-SQL` |
| IDEM-04 | P1 | `P-2` 已 `PUBLISH` | 连续两次 `REVOKE` | 两次 200 / 0 且 `data.public=false`；`lost_unlocked_at`/`lost_unlock_note` 两次调用后均 NULL | `EV-HTTP` ×2 + `EV-SQL` |
| IDEM-05 | P1 | U1 有 1 条未读通知 `N1`（`unreadCount=1`） | `PATCH /api/notifications/{N1}/read` 两次 | 两次 200 / 0；第一次 `data.unreadCount=0`、`data.readAt` 非空；第二次 `data.readAt` 与第一次相等、`unreadCount` 仍 0（不出现负数） | `EV-HTTP` ×2 |
| IDEM-06 | P1 | 已登录 U1 | `POST /api/records` 同一 body 连发两次（不经前端禁用） | 两次均 200 / 0 且 `data.id` 不同；`SELECT COUNT(*)` 该 title 行数 = 2（契约明示非幂等） | `EV-HTTP` ×2 + `EV-SQL` |
| IDEM-07 | P1 | 前端已登录 U1，记录 `R-B`（`P-4` 已 MATCHED） | 在详情/我的启事页双击「标记完成」 | 前端只发出 1 次 `PATCH .../status`；`t_record.status="COMPLETED"`、`completed_at` 只写 1 次（无 409 出现） | `EV-NET`（请求计数=1）+ `EV-SQL` + `EV-SC` |
| IDEM-08 | P2 | 无 Cookie | `POST /api/auth/logout` 两次 | 两次 200 / 0；`data=null` | `EV-HTTP` ×2 |
| IDEM-09 | P0 | `P-1` 已 `MATCHED`（两端 `confirmed_at` 非空） | ① `{"action":"CONFIRM"}` ② `{"action":"CONTACT"}` ③ `{"action":"IGNORE"}` | ① 200 / 0 且 `lostConfirmedAt`/`foundConfirmedAt` 不变 ② 409 / 50003 ③ 409 / 50003；三次调用后 `t_pair_score` 行完全不变 | `EV-HTTP` ×3 + `EV-SQL` |
| IDEM-10 | P1 | 已登录 U1 | `POST /api/match` 同一 body 连发两次 | 两次 200 / 0；`data.matchId` 不同；`t_match_result` 新增行数 = 2（非幂等，每次写快照） | `EV-HTTP` ×2 + `EV-SQL` |
| IDEM-11 | P1 | 配置 `OPS_SCAN_TOKEN` 已设 | 并发两次 `POST /api/ops/pair-scan`（同 token） | 一个 200 / 0，一个 409（并发互斥）；200 的响应 `data` 含 `scanned/rescored/degraded/skipped/elapsedMs` 五个数值字段 | `EV-HTTP` ×2 |
| IDEM-12 | P1 | `P-1`，mock 服务端调用计数已清零 | U1 对 `P-1` 取号两次 | 两次 200；`t_access_log` 新增行数 = 2，`result` 均为 `GRANTED`（每次查看都留痕是预期副作用） | `EV-SQL` |

---

### 3.6 `DEG` · 双链路降级（12 条）

**统一前置：** `findit.llm.base-url` 指向本地 mock（`http://127.0.0.1:18999/v1`），mock 按用例返回不同响应；每个用例前把 `R-A` 的 `pair_state` 置 `PENDING`、`P-1` 的 `lost_hash`/`found_hash` 置空以强制重算。**禁止**用真实 Key。

| ID | P | mock 行为 / 环境 | 步骤 | 期望 | 证据 |
|---|---|---|---|---|---|
| DEG-01 | P0 | `DEEPSEEK_API_KEY` 未设 | 编辑 `R-A` 触发重算，等 `pair_state` 终止 | `POST /api/records/{R-A}` 的 `PUT` 返回 200 / 0（发布链路不失败）；`P-1.engine="LOCAL_KEYWORD"`、`degraded_reason="EMPTY_KEY"`、`score` 属 `[0,1]`、`latency_ms IS NULL`；`t_record.status` 仍 `PENDING` | `EV-SQL` + `EV-HTTP` |
| DEG-02 | P0 | mock 返回 401 | 同上 | `engine="LOCAL_KEYWORD"`、`degraded_reason="UNAUTHORIZED"`；接口仍 200 | `EV-SQL` |
| DEG-03 | P0 | mock 延时 20s（read-timeout 15s） | 同上 | `degraded_reason="TIMEOUT"`；单次调用 `elapsed < 25s`（不无限等待）；接口仍 200 | `EV-SQL` + `EV-LOG` |
| DEG-04 | P0 | mock 返回 `not a json` | 同上 | `degraded_reason="NON_JSON"` | `EV-SQL` |
| DEG-05 | P0 | mock 返回 `{"score":"abc"}` 与 `{"score":1.4}` 各一次 | 同上（两次） | 两次均 `degraded_reason="SCORE_OUT_OF_RANGE"`；`score` 为本地分数而非 1.4 | `EV-SQL` ×2 |
| DEG-06 | P0 | mock 返回 429 | 同上 | `degraded_reason="HTTP_ERROR"` | `EV-SQL` |
| DEG-07 | P0 | DEG-01 之后 | U1 打开 `/my` 与 `R-A` 详情 | 页面出现「已降级」与「智能匹配暂不可用，已用关键词匹配」两段文案；接口 `pairState="DEGRADED"`、卡片 `degraded=true`、`engine="LOCAL_KEYWORD"` | `EV-SC` + `EV-HTTP` |
| DEG-08 | P0 | 已登录 U1，`DEEPSEEK_API_KEY` 未设（`/api/match` 用外部引擎配置） | `POST /api/match` 合法 body | 503 / 30002；`data=null`；`t_match_result` 无新增行（失败不留快照）；**不**返回 200 + 关键词分数 | `EV-HTTP` + `EV-SQL` |
| DEG-09 | P0 | mock 延时 20s | 同 DEG-08 | 504 / 30003；`t_match_result` 无新增行 | `EV-HTTP` + `EV-SQL` |
| DEG-10 | P1 | 前端已登录，mock 返回 503 | 详情页发起匹配 | 匹配区显示「匹配服务暂不可用」；页面**不出现**「已降级」字样；详情主体（标题/描述/地点）仍渲染 | `EV-SC` |
| DEG-11 | P1 | mock 连续返回 401 六次（5 次阈值 + 1） | 连续触发 6 次重算，再触发第 7 次 | 第 6 次起 `degraded_reason="CIRCUIT_OPEN"`；第 7 次的 mock 收到请求数 = 5（熔断期内 0 外呼）；`engine` 仍 `LOCAL_KEYWORD` | `EV-SQL` + `EV-NET` |
| DEG-12 | P0 | 取 DEG-01 前后快照 | 对比 `SELECT status, completed_at, updated_at FROM t_record WHERE id=R-A` 与 `t_pair_score.status` | `t_record` 三列完全一致；`P-1.status` 与降级前一致（降级只写分数/引擎/耗时/tokens/哈希/原因） | `EV-SQL` ×2 |

---

### 3.7 `PAIR` · 多对独立、无占用语义（7 条）

| ID | P | 前置 | 步骤 | 期望 | 证据 |
|---|---|---|---|---|---|
| PAIR-01 | P0 | `R-A`、`R-B`（两条 LOST，不同 owner）、`R-F1`（一条 FOUND） | 用背景配对（或 `ops/pair-scan`）让 `R-F1` 与两条 LOST 各成一对 | `SELECT lost_id, found_id, score FROM t_pair_score WHERE found_id=R-F1` 返回 **2 行**，`lost_id` 分别为 `R-A`、`R-B`；两行 `id` 不同；`UNIQUE KEY uk_pair_lost_found` 存在（`SHOW INDEX` 命中 1 个唯一索引，列序 `lost_id,found_id`） | `EV-SQL` |
| PAIR-02 | P0 | `P-1`=NEW（R-A↔R-F1），`P-2`=NEW（R-B↔R-F1） | ① U1 对 `P-1` 发 `{"action":"CONTACT"}` ② 查 `P-2` | ① 200 / 0，`P-1.status="CONTACTED"` ② `P-2.status` 仍 `"NEW"`、`P-2.updated_at` 与调用前相等、`P-2.lost_confirmed_at`/`found_confirmed_at` 仍 NULL；`t_record`（R-A/R-B/R-F1）三行 `status` 均 `PENDING` | `EV-HTTP` + `EV-SQL` ×2 |
| PAIR-03 | P0 | `P-1` 已 `CONTACTED`（同 PAIR-02 之后） | U2 对 `P-2` 发 `{"action":"PUBLISH","note":"我的 iPhone 有刻字"}` | 200 / 0；`found_unlocked_at`/`lost_unlocked_at` 中 U2 那一端非空；`P-2.status` 仍 `NEW`（可解锁资格不受 `P-1` 影响） | `EV-HTTP` + `EV-SQL` |
| PAIR-04 | P0 | `P-1` 已 `CONTACTED` | U2 `POST /api/match` body `{"text":"操场跑步忘拿了白色 iPhone，透明壳带小熊贴纸","type":"FOUND"}` | 200 / 0；`items[].recordId` 集合包含 `R-F1`（招领仍在候选池，未被摘掉） | `EV-HTTP` |
| PAIR-05 | P0 | `P-1`=NEW，`P-2`=NEW，`P-3`=NEW；`R-A.status=PENDING` 且记录 `updated_at` 快照 | ① U1 对 `P-1` CONFIRM ② U3 对 `P-1` CONFIRM ③ 查三张表 | `P-1.status="MATCHED"`、两端 `confirmed_at` 均非空；`P-2.status` 仍 `NEW`、`P-3.status` 仍 `NEW`；`R-A.status` 仍 `PENDING`、`R-A.updated_at` 与快照相等；`R-B`/`R-F1` 的 `status`/`updated_at` 均不变 | `EV-HTTP` ×2 + `EV-SQL` |
| PAIR-06 | P0 | `P-1`=NEW | ① `SELECT DISTINCT status FROM t_pair_score` ② `PATCH /api/pairs/{P-1}` body `{"status":"MATCHED"}` ③ `PATCH` body `{"status":"RESOLVED"}` ④ 全库检索 `已解决\|RESOLVED\|DONE`（`git grep` + `SELECT * FROM t_pair_score WHERE status NOT IN (...)`） | ① 结果 ⊆ `{NEW,CONTACTED,MATCHED,IGNORED}` ② 400 / 10001 ③ 400 / 10001 ④ 接口与库内命中数 = 0；`P-1` 行不变 | `EV-SQL` + `EV-HTTP` ×2 + `EV-GREP` |
| PAIR-07 | P0 | `P-2`：先 `PUBLISH`（`lost_unlocked_at` 非空、note 非空），再 `IGNORE` | ① U2 `{"action":"IGNORE"}` ② 查 `P-2` ③ U2 `GET /api/pairs/{P-2}/contact` ④ 再跑 `/api/match` | ① 200 / 0、`status="IGNORED"` ② `lost_unlocked_at` 与 note 仍非空（`IGNORE` 不撤销授权）③ 200 / 0（`available` 由对方授权决定，不因 `IGNORED` 变 409/403）④ `R-F1` 仍在候选池 | `EV-HTTP` ×3 + `EV-SQL` |

### 3.8 `ST` · 三态流转与「不自动置位」（12 条）

> 这 12 条全部同时断言 `t_record` 的 `status` / `completed_at` / `updated_at` 三列，是 vF 判据 ②的落地点。每条执行前重建夹具（`beforeEach`），不复用上一条的写入。

| ID | P | 前置 | 步骤 | 期望 | 证据 |
|---|---|---|---|---|---|
| ST-01 | P0 | U1 已登录，`R-A` 为 PENDING，记下三列快照 | `POST /api/records` 新建一条 LOST；轮询 `GET /api/my/records` 直到该记录 `pairState ∈ {READY, DEGRADED}`（上限 60s） | 自动化链路跑完后：该记录 `status="PENDING"`、`completed_at IS NULL`、`updated_at` 与插入时刻相等；只有 `pair_state` 由 `PENDING` 变为 `READY`/`DEGRADED`、`pair_scored_at` 非空 | `EV-SQL` ×2 + `EV-HTTP` |
| ST-02 | P0 | `R-A`/`R-B`/`R-F1` 为 PENDING，`R-G` 为已到期 COMPLETED，`R-H` 为 CLOSED；记下四行快照 | 带 `X-Ops-Token` 调 `POST /api/ops/pair-scan` | 200 / 0；随后四行的 `status`/`completed_at`/`updated_at` 与快照逐列相等；`R-H`/`R-G` 的 `deleted_at` 仍 NULL | `EV-SQL` ×2 |
| ST-03 | P0 | `R-A` owner U1，`P-1` score 0.95 | U3 对 `P-1` `PUBLISH`（触发对 U1 的 `PAIR_UNLOCK` 通知）；查 `R-A` | 通知行数 = 1（`user_id=U1,type='PAIR_UNLOCK'`）；`R-A.status="PENDING"`、`updated_at`/`completed_at` 不变 | `EV-SQL` ×2 |
| ST-04 | P0 | `R-A.status="PENDING"`，记 `updated_at`；`P-1` 两端 owner 分别为 U1/U3 | U1 `CONFIRM` → U3 `CONFIRM` | 第二次调用 200 / 0 且 `data.status="MATCHED"`、`lostConfirmedAt`/`foundConfirmedAt` 均非空；`R-A.status` 仍 `"PENDING"`、`updated_at` 与快照相等（记录状态不随 pair 进展变化） | `EV-HTTP` ×2 + `EV-SQL` |
| ST-05 | P0 | `R-A` 的 `P-1`=NEW、`P-3`=NEW（无任何 MATCHED），记三列快照 | U1 `PATCH /api/records/{R-A}/status` body `{"status":"COMPLETED"}` | 409 / 20005；`message="需双方确认匹配后才能标记完成"`；`data.currentStatus="PENDING"`、`data.required="MATCHED_PAIR"`、`data.matchedPairCount=0`；三列与快照逐列相等（零写入） | `EV-HTTP` + `EV-SQL` ×2 |
| ST-06 | P0 | `P-1` 仅 `lost_confirmed_at` 非空（`status="CONTACTED"`），`found_confirmed_at` 为 NULL，`R-A` 仍 PENDING | 同 ST-05 | 409 / 20005；`matchedPairCount=0`；三列与快照相等（单端确认不算 MATCHED） | `EV-HTTP` + `EV-SQL` ×2 |
| ST-07 | P0 | `R-A`=PENDING；`R-E`=COMPLETED（未到期）；`R-H`=CLOSED | ① `R-A` body `{"status":"PENDING"}` ② `R-E` body `{"status":"COMPLETED"}` ③ `R-H` body `{"status":"CLOSED"}`（均由所有者操作） | 三次全部 409 / 20002；`data` 含 `current`/`allowed` 两字段；三次调用后三行 `status`/`updated_at` 均不变 | `EV-HTTP` ×3 + `EV-SQL` |
| ST-08 | P0 | `R-B`=PENDING，`P-4`（R-B↔R-E）`status="MATCHED"`、两端 `confirmed_at` 非空，`R-E` 未到期 | U2 `PATCH /api/records/{R-B}/status` body `{"status":"COMPLETED"}` | 200 / 0；`data.status="COMPLETED"`、`data.completedAt` 非空、`data.allowedStatuses=[]`、`data.hasMatchedPair=true`、`data.matchedPairCount=1`；`t_record.status="COMPLETED"`、`completed_at` 与 `data.completedAt` 相等、`updated_at` = 请求时刻 | `EV-HTTP` + `EV-SQL` |
| ST-09 | P0 | `R-A`=PENDING，无 MATCHED | U1 `PATCH .../status` body `{"status":"CLOSED"}` | 200 / 0；`t_record.status="CLOSED"`；`completed_at IS NULL`；`updated_at` 变为请求时刻 | `EV-SQL` |
| ST-10 | P0 | `R-A`=PENDING | 依次发 body `{"status":"CLAIMED"}`、`{"status":"RESOLVED"}`、`{"status":"PENDING"}`、`{}` | 四次全部 400 / 10001；`data.fieldErrors[0].field="status"`；四次后 `R-A.status` 仍 `"PENDING"`、`updated_at` 不变 | `EV-HTTP` ×4 + `EV-SQL` |
| ST-11 | P0 | `R-G`：`status="COMPLETED"`、`completed_at = NOW() - INTERVAL 40 DAY`（已到期） | 触发 D1 每日任务（离线 `--pair-scan-once` 或 cron 时刻）后查库 | `R-G.status` 仍 `"COMPLETED"`、`completed_at` 与触发前逐秒相等、`deleted_at` 仍 NULL；日志出现到期计数（`expired` 字段为整数） | `EV-SQL` ×2 + `EV-LOG` |
| ST-12 | P0 | `R-E`=COMPLETED（U3 所有），`deleted_at` NULL | U3 `DELETE /api/records/{R-E}` | 200 / 0；`deleted_at` 非空、`status` 仍 `"COMPLETED"`、`completed_at` 不变（删除与三态正交） | `EV-HTTP` + `EV-SQL` |

---

### 3.9 `PHONE` · 手机号只做格式校验（8 条）

| ID | P | 前置 | 步骤 | 期望 | 证据 |
|---|---|---|---|---|---|
| PHONE-01 | P0 | 已登录 U1 | `POST /api/records` body `contact="13812345678"` | 200 / 0；`SELECT contact FROM t_record WHERE id=<新id>` = `13812345678`（原值落库，无掩码） | `EV-HTTP` + `EV-SQL` |
| PHONE-02 | P0 | 已登录 U1 | body `contact="1380000000"`（10 位） | 400 / 10001；`data.fieldErrors` 中 `field="contact"`；无新行落库 | `EV-HTTP` + `EV-SQL` |
| PHONE-03 | P0 | 已登录 U1 | 依次 body `contact="138abcd0001"`、`contact="138 0000 0001"`、`contact="138-0000-0001"` | 三次全部 400 / 10001，`field="contact"`；无新行落库（字母/空格/短横线都不接受） | `EV-HTTP` ×3 + `EV-SQL` |
| PHONE-04 | P1 | 已登录 U1 | 依次 `contact="12800000001"`（第二段非 3–9）、`contact="138000000001"`（12 位）、`contact="+8613800000001"` | 三次全部 400 / 10001，`field="contact"` | `EV-HTTP` ×3 |
| PHONE-05 | P0 | 已登录 U1；记录 mock/出站连接计数 | body `contact="13900009999"`（格式合法、未验证、无短信） | 200 / 0 且落库；请求期间后端对外部短信/邮件域名的出站连接数 = 0；响应体无 `verificationCode`/`smsSent` 等字段 | `EV-HTTP` + `EV-NET` + `EV-SQL` |
| PHONE-06 | P0 | 库已建 | `SHOW COLUMNS FROM t_user`；`SHOW TABLES LIKE '%sms%'`；`SHOW TABLES LIKE '%verify%'` | `t_user` 列集合 = `{id,student_no,password_hash,display_name,is_active,created_at,updated_at}`（无 `phone`/`mobile`/`email`）；两个 `SHOW TABLES` 结果为空集 | `EV-SQL` |
| PHONE-07 | P1 | 直插 `R-L1`：`contact='QQ123456'`（历史非手机号值） | U1（所有者）`GET /api/records/{R-L1}`；再匿名同请求 | 所有者：200 / 0 且 `data.contact="QQ123456"`（历史值可读、不触发 400）；匿名：`contact=null` | `EV-HTTP` ×2 |
| PHONE-08 | P1 | 前端已登录，打开 `/records/new` | 观察 `contact` 输入框附近文案 | 出现「自行填写，请自行核实」；错误提示文案只在提交后出现（不在初始态出现红色错误） | `EV-SC` |

---

### 3.10 `RET` · 不自动下架与保留期窗口（6 条）

| ID | P | 前置 | 步骤 | 期望 | 证据 |
|---|---|---|---|---|---|
| RET-01 | P0 | `R-C`：PENDING，`created_at = NOW() - INTERVAL 40 DAY`，`deleted_at` NULL | ① 匿名 `GET /api/records`（`pageSize=50`）② 匿名 `GET /api/records/{R-C}` | ① `data.list` 含 `R-C` ② 200 / 0 且 `data.status="PENDING"`（PENDING 记录不因时间下架） | `EV-HTTP` ×2 |
| RET-02 | P0 | `R-D`：PENDING，`created_at = NOW() - INTERVAL 400 DAY` | 同 RET-01 两步 | `data.list` 含 `R-D`；详情 200 / 0；`SELECT deleted_at, status FROM t_record WHERE id=R-D` → `NULL, 'PENDING'`（无任何任务写 `deleted_at`） | `EV-HTTP` ×2 + `EV-SQL` |
| RET-03 | P0 | `R-E`：COMPLETED，`completed_at = NOW() - INTERVAL 5 DAY`；断言前先跑一次 `ops/pair-scan` | ① 匿名 `GET /api/records` ② 已登录 U2 `POST /api/match` body 命中 `R-E` 标题的文本 | ① `data.list` 中 `recordId=R-E` 命中数 = 0、`total` 不含它 ② `items[].recordId` 含 `R-E`（列表隐藏 ≠ 匹配隐藏） | `EV-HTTP` ×2 |
| RET-04 | P0 | `R-G`：COMPLETED，`completed_at = NOW() - INTERVAL 40 DAY` | U2 `POST /api/match`（同 RET-03 的 body） | 200 / 0 且 `items[].recordId` 中 `R-G` 命中数 = 0（到期后退出召回窗口） | `EV-HTTP` |
| RET-05 | P0 | 同 RET-04，触发前记 `status`/`completed_at` | 跑一次 `ops/pair-scan` + D1 清扫后查 `R-G` | `status` 仍 `"COMPLETED"`、`completed_at` 与快照逐秒相等、`deleted_at` 仍 NULL（到期是有副作用也**不改写**状态） | `EV-SQL` ×2 |
| RET-06 | P1 | 两条记录：`R-T1.completed_at = NOW() - INTERVAL 30 DAY`（整 30 天）、`R-T2.completed_at = NOW() - INTERVAL 29 DAY`（差 1 天） | U2 各调一次 `/api/match`（body 分别命中两者标题） | `R-T1` 命中数 = 0（窗口谓词是 `completed_at > NOW() - INTERVAL 30 DAY`，等于边界不召回）；`R-T2` 命中数 = 1 | `EV-HTTP` ×2 + `EV-SQL` |

---

### 3.11 `TERM` · 终态组：COMPLETED / CLOSED 的三套可见性（14 条）

| ID | P | 前置 | 步骤 | 期望 | 证据 |
|---|---|---|---|---|---|
| TERM-01 | P0 | `R-E`=COMPLETED（未到期）；`R-H`=CLOSED | ① 匿名 `GET /api/records` ② U2 登录同请求 ③ U3（所有者）同请求 ④ 三者各带 `status=COMPLETED` 与 `status=CLOSED` 再调一次 | 七次全部 200 / 0；`data.list` 中 `R-E`/`R-H` 命中数 = 0；`total` 只计入 PENDING 行；显式 `status=COMPLETED` 时 `data.list=[]`、`total=0`（200 空列表，不是 400/403） | `EV-HTTP` ×7 |
| TERM-02 | P0 | `R-E.title="图书馆拾到黑色钱包"` | 匿名 `GET /api/records?keyword=黑色钱包` | 200 / 0；`items`/`list` 中 `R-E` 命中数 = 0；只返回 PENDING 且标题/描述命中的行 | `EV-HTTP` |
| TERM-03 | P0 | `R-E`=COMPLETED 未到期 | U2 `POST /api/match`（命中 `R-E`） | `items[]` 中 `R-E` 项：`recordStatus="COMPLETED"`、`detailAccess="RESTRICTED"`、`title`/`location` 已打码、无 `contact`/`publisherName` 键 | `EV-HTTP` |
| TERM-04 | P0 | U4 无任何记录与 `R-E` 成 pair | U4 `GET /api/records/{R-E}` | 404 / 20001；`data=null`（不区分「不存在」与「不够格」，也不返回 403） | `EV-HTTP` |
| TERM-05 | P0 | `R-U4`（U4 的 LOST）与 `R-E` 成 `P-8`，`score=0.8500`、`status="NEW"` | U4 `GET /api/records/{R-E}` | 404 / 20001（分数低于 `completed_visible_threshold=0.90`，有 pair 也不够格） | `EV-HTTP` + `EV-SQL` |
| TERM-06 | P0 | `P-4`（R-B↔R-E）`score=0.9500`、`MATCHED`，U2 是 `R-B` 所有者 | U2 `GET /api/records/{R-E}` | 200 / 0；`data.status="COMPLETED"`、`isMine=false`、`contact=null`、`publisherName="张**"`、`description` 已打码、`location` 已截断、`Object.keys` 不含 `pairs`、`completedAt=null`、`allowedStatuses=[]` | `EV-HTTP` |
| TERM-07 | P0 | `R-E` 所有者 U3，未到期 | U3 `GET /api/records/{R-E}` | 404 / 20001（A9 字面：所有者不享有 COMPLETED 详情例外；若门禁 0 改判，本条随之翻转，见 §5-1） | `EV-HTTP` |
| TERM-08 | P0 | `R-H`=CLOSED | ① 匿名 `GET /api/records` ② U2 `POST /api/match`（body 含「深蓝雨伞」） | ① `R-H` 命中数 = 0 ② `items[].recordId` 中 `R-H` 与 `R-F2`（其 pair 另一端）命中数 = 0（CLOSED 不召回、不显示） | `EV-HTTP` ×2 |
| TERM-09 | P0 | `R-H`=CLOSED，`P-5`（R-H↔R-F2）`score=0.9200` | U1 `GET /api/my/records`；取 `R-H` 行的 `pairs` 与计数 | `data.list[]` 中 `R-H` 行存在（`/my` 三态都返回）、`detailVisible=false`；该行 `pairs=[]`、`pairCount` 与 `relatedCount` 均不含 `P-5`（失效 pair 不出卡片、不计入计数） | `EV-HTTP` |
| TERM-10 | P0 | `P-5` 存在，`R-H`=CLOSED | U1 `GET /api/pairs/{P-5}/contact` | 403 / 50005；`message="记录已关闭或已完成到期，配对已失效"`；**不是** 404/20004；响应体无号码 | `EV-HTTP` |
| TERM-11 | P0 | `P-5`，`R-H`=CLOSED | ① U1 `PATCH /api/pairs/{P-5}` `{"action":"CONTACT"}` ② U1 `POST /api/pairs/{P-5}/contact-unlock` `{"action":"PUBLISH"}` ③ `{"action":"REVOKE"}` | 三次全部 403 / 50005；`t_pair_score` 中 `P-5` 的 `status`/`lost_unlocked_at`/`found_unlocked_at` 均不变 | `EV-HTTP` ×3 + `EV-SQL` |
| TERM-12 | P0 | `R-G`=COMPLETED，`completed_at = NOW() - INTERVAL 40 DAY`；`R-U4` 与 `R-G` 有 `P-9`（score 0.95） | ① U4 `GET /api/records/{R-G}` ② 匿名同请求 ③ U3（所有者）同请求 ④ U4 `POST /api/match`（命中 `R-G`） | ①②③ 均 404 / 20001；④ `items[].recordId` 中 `R-G` 命中数 = 0；`t_record` 的 `status`/`completed_at` 不变 | `EV-HTTP` ×4 + `EV-SQL` |
| TERM-13 | P0 | `P-9` 存在，`R-G` 已到期 | U4 `GET /api/pairs/{P-9}/contact`；U4 `PATCH /api/pairs/{P-9}` `{"action":"CONFIRM"}` | 两次均 403 / 50005（到期后 pair 整条失效，分数 0.95 也不能取号） | `EV-HTTP` ×2 |
| TERM-14 | P0 | `P-5` 已产生过 `PAIR_MATCH`/`PAIR_UNLOCK` 通知；`R-H` 已 CLOSED | U1 `GET /api/notifications` | 200 / 0；历史通知仍返回（`list` 含 `pairId=P-5` 的行、`read` 保留原值）；点入该 pair 的接口得 403 / 50005（通知是投递事实，不随 pair 失效删除） | `EV-HTTP` |

---

### 3.12 `SCHEMA` · 数据层（5 条）

| ID | P | 前置 | 步骤 | 期望 | 证据 |
|---|---|---|---|---|---|
| SCHEMA-01 | P0 | 空库执行 `db/schema.sql` + `db/seed.sql` | `SHOW TABLES`；`SHOW CREATE TABLE t_record` | 表集合 = `{t_user,t_category,t_record,t_match_result,t_pair_score,t_notification,t_access_log}`（7 张，无多余表）；`t_record` 含 `completed_at`、`pair_state`、`pair_scored_at`、`publisher_id`；`publisher_id` 外键指向 `t_user(id)` | `EV-SQL` |
| SCHEMA-02 | P0 | 同 SCHEMA-01 | `SHOW COLUMNS FROM t_user` | 列集合见 `PHONE-06`；不存在 `phone`/`mobile`/`tel`/`email` 任一同义列 | `EV-SQL` |
| SCHEMA-03 | P0 | 同 SCHEMA-01 | `SHOW COLUMNS FROM t_pair_score`；`SHOW INDEX FROM t_pair_score WHERE Key_name='uk_pair_lost_found'` | 列包含 `lost_id,found_id,score,engine,latency_ms,prompt_tokens,completion_tokens,lost_hash,found_hash,lost_unlocked_at,found_unlocked_at,lost_unlock_note,found_unlock_note,lost_confirmed_at,found_confirmed_at,status`（16 列全覆盖）；唯一索引 `uk_pair_lost_found` 的 `Non_unique=0` 且 `Column_name` 依次为 `lost_id`、`found_id` | `EV-SQL` |
| SCHEMA-04 | P0 | 同 SCHEMA-01 | 分别执行三条非法 INSERT：① `t_record.status='CLAIMED'` ② `t_record.status='COMPLETED'` 且 `completed_at=NULL` ③ `t_pair_score.status='MATCHED'` 且只有 `lost_confirmed_at` | 三次全部被数据库拒绝（`ERROR 3819` CHECK 约束失败）；随后 `SELECT COUNT(*)` 三处非法行计数 = 0（`ck_record_status`/`ck_record_completed_at`/`ck_pair_matched` 生效） | `EV-SQL` ×6 |
| SCHEMA-05 | P0 | `t_record` 中一条 PENDING 记录，记 `updated_at` | 直接 `UPDATE t_record SET pair_state='SCORING' WHERE id=?`（模拟系统任务）；等 1 秒后再查 | `pair_state` 变为 `SCORING`，`updated_at` 与快照逐秒相等（该列无 `ON UPDATE CURRENT_TIMESTAMP`，系统任务不改它） | `EV-SQL` ×2 |

---

### 3.13 `AUDIT` · 取号审计（4 条）

| ID | P | 前置 | 步骤 | 期望 | 证据 |
|---|---|---|---|---|---|
| AUDIT-01 | P0 | `P-1.score=0.95`，`t_access_log` 计数快照 | U1 `GET /api/pairs/{P-1}/contact`（成功） | 新增 1 行：`pair_id=P-1`、`viewer_id=U1`、`viewer_side="LOST"`、`target_side="FOUND"`、`score=0.9500`、`result="GRANTED"`、`request_id` = 响应体 `requestId` 逐字相等 | `EV-SQL` + `EV-HTTP` |
| AUDIT-02 | P0 | 分别构造：`P-3`（0.62）、`P-2` 未授权、`P-2` 已授权但对方 `contact IS NULL`、`P-1` 用 U2（非参与方）调 | 四次取号 | 新增 4 行且 `result` 依次为 `DENIED_LOW_SCORE`、`DENIED_NOT_PUBLIC`、`DENIED_NOT_PROVIDED`、`DENIED_NOT_OWNER`（一行一次调用，`pair_id`/`viewer_id` 与实际请求一致） | `EV-SQL` ×5 + `EV-HTTP` ×4 |
| AUDIT-03 | P0 | 令 `t_access_log` 插入失败（临时收回 INSERT 权限或加 `BEFORE INSERT` 触发器 `SIGNAL SQLSTATE '45000'`） | U1 对 `P-1` 取号 | 500 / 10005；`data=null`；响应体 `1[3-9]\d{9}` 命中数 = 0（fail-closed：审计失败不出号） | `EV-HTTP` + `EV-SQL`（`SHOW TRIGGERS`） |
| AUDIT-04 | P0 | 库已建 | `SHOW COLUMNS FROM t_access_log` | 列集合不含 `contact`/`note`/`prompt`/`response`；`result` 的 CHECK 约束枚举含 6 个值（`GRANTED` + 5 个 `DENIED_*`） | `EV-SQL` |

---

### 3.14 `NOTI` · 通知（5 条）

| ID | P | 前置 | 步骤 | 期望 | 证据 |
|---|---|---|---|---|---|
| NOTI-01 | P0 | `P-1.score=0.95`（≥`notify_threshold`=0.75），`P-3.score=0.62`（<0.75） | 触发两对重算后查 `t_notification` | `(pair_id=P-1,type='PAIR_MATCH')` 行数 = 2（U1 与 U3 各 1 条）；`pair_id=P-3` 的 `PAIR_MATCH` 行数 = 0 | `EV-SQL` |
| NOTI-02 | P0 | `P-2.score=0.88`，`count(*)` 快照 | U2 `PUBLISH`；再重复一次 | 新增 `(user_id=U3, pair_id=P-2, type='PAIR_UNLOCK')` 1 行；第二次调用后该组行数仍 1（唯一键幂等） | `EV-SQL` ×2 |
| NOTI-03 | P1 | U1 有 3 条未读 + 7 条已读通知 | U1 `GET /api/notifications?page=1&pageSize=5` | `data.unreadCount=3`（全部未读，不是本页未读）；`data.total=10`、`data.pageSize=5`、`data.hasNext=true`；`list` 长度 = 5 | `EV-HTTP` |
| NOTI-04 | P1 | 同 NOTI-03，取 `list[0].id` | `PATCH /api/notifications/{id}/read` | 200 / 0；`data.read=true`、`data.readAt` 非空、`data.unreadCount=2`（比调用前减 1） | `EV-HTTP` |
| NOTI-05 | P1 | U1 的通知列表含 `type` 三类的行 | 统计 `list[].type` 取值；并检查 `body` 长度 | 取值集合 ⊆ `{PAIR_MATCH,PAIR_UNLOCK,SYSTEM}`；每条 `body` 长度 ≤ 500 且 `1[3-9]\d{9}` 命中数 = 0；空列表时前端显示「暂无通知」 | `EV-HTTP` + `EV-SC` |

---

### 3.15 `MY` · 我的启事（4 条）

| ID | P | 前置 | 步骤 | 期望 | 证据 |
|---|---|---|---|---|---|
| MY-01 | P0 | U1 拥有 `R-A`(PENDING)、`R-H`(CLOSED)、`R-E`? 否（U3 所有）；补建 `R-M1`(COMPLETED, `completed_at` 非空) | U1 `GET /api/my/records?status=ALL` | `data.list` 三态行都在；`R-A` 行 `detailVisible=true`、`R-H` 行 `detailVisible=false`、`R-M1` 行 `detailVisible=false`；`R-M1` 行 `completedAt` 非空、`R-A`/`R-H` 行 `completedAt=null` | `EV-HTTP` |
| MY-02 | P0 | `R-A`：`P-1`(0.95)、`P-3`(0.62)；`P-4` 属 `R-B` | U1 `GET /api/my/records`；再 `GET /api/records/{R-A}` | `R-A` 行 `pairCount=2`、`relatedCount=1`、`lowConfidenceCount=1`、`pairs` 长度 = 1；详情 `matchedPairCount=0`、`hasMatchedPair=false`；三个计数互不相等且各自等于对应集合大小 | `EV-HTTP` ×2 |
| MY-03 | P1 | 前端 U1 登录，`/my` 含三态行 | 观察每行操作区 | PENDING 行渲染「详情」入口且可点击到达 `/records/{id}`；COMPLETED 与 CLOSED 行不渲染可点击的详情链接（`a[href^="/records/"]` 计数 = 0）；三态行都渲染「删除」按钮 | `EV-SC` + `EV-NET`（点击后无 404 请求） |
| MY-04 | P0 | 无 Cookie | `GET /api/my/records` | 401 / 10006；`data=null` | `EV-HTTP` |

---

### 3.16 `API` · 通用契约（6 条）

| ID | P | 前置 | 步骤 | 期望 | 证据 |
|---|---|---|---|---|---|
| API-01 | P0 | 分别构造 401（无 Cookie 调 `/api/my/records`）、403（U2 调 `PATCH /api/pairs/{P-1}`）、404（`GET /api/records/999999`）、409（无 MATCHED 标完成）、503（mock 引擎不可用时 `/api/match`） | 五个请求 | 五次响应的键集合都恰为 `{code,message,data,requestId}`；`requestId` 为非空字符串且五次互不相同 | `EV-HTTP` ×5 |
| API-02 | P0 | — | 按 §4.3 交叉索引表逐码触发一次 | 每个错误码的 `HTTP` 与 `code` 与契约 §6 表逐行一致；无「表里有、代码未用」的码（索引表 24 行全覆盖） | `EV-HTTP` ×24 |
| API-03 | P1 | 任一含时间的成功响应 | 检查 `createdAt`、`occurredAt`、`completedAt`、`updatedAt`、`unlockedAt` | 格式全部匹配 `^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$`（无毫秒、无 `T`、无时区后缀）；布尔值为 `true`/`false` 字面量；`id`/`pairId` 为 JSON number | `EV-HTTP` |
| API-04 | P0 | 分别构造 20002/20003/20005 与 50002/50005 | 五次请求 | 409 组：`20002`（同值流转）、`20003`（COMPLETED 调 PUT）、`20005`（无 MATCHED 标完成）三次 `code` 互不相同、`message` 互不相同；403 组：`50002`（0.62 取号）`message="匹配度不足，无法查看联系方式"`、`50005`（CLOSED 取号）`message="记录已关闭或已完成到期，配对已失效"` | `EV-HTTP` ×5 |
| API-05 | P1 | 空结果场景（`keyword=不存在的关键词xyz`） | `GET /api/records?keyword=不存在的关键词xyz&pageSize=10` | 200 / 0；`data={page:1,pageSize:10,total:0,totalPages:0,hasNext:false,list:[]}`（`totalPages=0`，不是 1；不是 404） | `EV-HTTP` |
| API-06 | P0 | 服务已启动 | `GET /api/health`；`POST /api/ops/pair-scan`（无 token）；`POST /api/auth/register`；`POST /api/auth/sms`；`POST /api/records/1/expire` | ① 200 / 0 且 `data` 含 `status="UP"`、`db="UP"`、`version`、`engine` ② 404 / 10003（入口隐藏）③④⑤ 均 404 / 10003（无注册、无短信、无下架接口） | `EV-HTTP` ×5 |
| API-07 | P1 | 已登录 U1；`t_notification` 最大 id 记下 | ① `POST /api/records` body 约 3MB（`description` 填 300 万字符）② `PATCH /api/notifications/999999/read` | ① 413 / 10002 ② 404 / 50004；两次响应都只含四字段，`data=null` | `EV-HTTP` ×2 |

---

### 3.17 `CONF` · 配置集中与启动校验（5 条）

| ID | P | 前置 | 步骤 | 期望 | 证据 |
|---|---|---|---|---|---|
| CONF-01 | P0 | 仓库工作区 | `git grep -n "completed_retention_days\|completed_visible_threshold\|contact_direct_threshold\|contact_unlock_threshold"` 与 `git grep -n "direct-view-threshold\|contact-auth-threshold"` | 前一组命中集中在 `application.yml` 与**一个** `@ConfigurationProperties` 类（`PairPolicyProperties`）；后一组命中数 = 0；`server/src/main/java` 下 `0.90`/`0.75`/`30` 作为阈值的字面量命中数 = 0（注释除外，逐行人工确认并在证据里标行号） | `EV-GREP` |
| CONF-02 | P0 | 临时注释掉 `findit.pair.completed_retention_days` | 启动服务 | 启动失败：进程退出码 ≠ 0；日志含键名 `completed_retention_days`；端口 18080 上无监听（不出现「用默认值起来了」） | `EV-LOG` + `EV-NET`（`netstat` 无 18080） |
| CONF-03 | P0 | 临时把 `contact_direct_threshold` 改 `0.70`（小于 `contact_unlock_threshold=0.75`） | 启动服务 | 启动失败：退出码 ≠ 0；日志含 `contact_direct_threshold` 与 `contact_unlock_threshold` 两个键名 | `EV-LOG` |
| CONF-04 | P0 | 临时把 `completed_retention_days` 改 `0` | 启动服务 | 启动失败：退出码 ≠ 0；日志含取值范围的上下界（`1` 与 `3650`） | `EV-LOG` |
| CONF-05 | P1 | DB 可用（health 返回 db="UP"）；随后停 MySQL | ① `GET /api/health` ② 停库后 3 秒内再调 | ① 200 / 0，`data={status:"UP",db:"UP",version:<非空>,engine:<非空>}` ② 503 / 10005，`data.db="DOWN"`，响应在 5s 内返回（不挂起） | `EV-HTTP` ×2 + `EV-LOG` |

### 3.18 `SEC` · 零明文与证据白名单（5 条）

| ID | P | 前置 | 步骤 | 期望 | 证据 |
|---|---|---|---|---|---|
| SEC-01 | P0 | 仓库全量 | `git grep -nE "sk-[A-Za-z0-9]{16,}"`；`git grep -n "api-key" -- server/src/main/resources` | 两个命令命中数均为 0；`application.yml` 内无 `api-key` 字段（Key 只从环境变量读） | `EV-GREP` |
| SEC-02 | P0 | 分别以「未设 Key」与「设了 Key」启动 | 取启动日志首 50 行 | 两次各出现一行 `llm key configured=true` / `configured=false`；日志全文不含 Key 值、不含 `Authorization` 头原文 | `EV-LOG` |
| SEC-03 | P0 | 一轮完整业务链路（发布 → 配对 → 取号 → 通知）跑完 | `grep -E "1[3-9][0-9]{9}" server.log`；再查 `t_access_log` 列 | 日志中 11 位手机号形态命中数 = 0；`t_access_log` 无 `contact` 列（与 `AUDIT-04` 同一断言，此处不重复贴 SQL） | `EV-LOG` + `EV-SQL` |
| SEC-04 | P0 | mock LLM 已就绪，`R-A`（含 contact 与 publisherName）触发重算 | 保存 mock 收到的请求体；对其跑 `1[3-9]\d{9}` 与 `publisherName` 值两条正则 | 手机号命中数 = 0；请求体不含 `publisherName` 字段；请求体含 `【寻物】`/`【招领】` 两个标签（证明用的是打码文本而非原文） | `EV-NET`（mock 收到的 body 副本） |
| SEC-05 | P1 | 任一次成功配对 + 取号之后 | 检查 `t_pair_score` 的 LLM 相关列与证据文件字段 | `t_pair_score` 的证据列 ⊆ `{engine,latency_ms,prompt_tokens,completion_tokens,prompt_version,degraded_reason,score}`；证据文件（`docs/testing/v2/` 下）不含 prompt 全文、响应全文、Key、Cookie | `EV-SQL` + `EV-GREP` |

---

## 4. 对账

### 4.1 分组条数

| 组 | 条数 | 其中 P0 | 覆盖的硬判据 |
|---|---|---|---|
| `AUTH` | 8 | 5 | 登录态 |
| `AUTHZ` | 6 | 3 | 越权（终态分支） |
| `PERM` | 51 | 51 | 越权（17×3 逐格） |
| `MASK` | 19 | 13 | 遮蔽边界 |
| `TIER` | 14 | 8 | 分档 |
| `IDEM` | 12 | 6 | 幂等 |
| `DEG` | 12 | 9 | 降级 |
| `PAIR` | 7 | 7 | 新增判据 ① |
| `ST` | 12 | 11 | 新增判据 ② |
| `PHONE` | 8 | 5 | 新增判据 ③ |
| `RET` | 6 | 5 | 新增判据 ④ / vF-5·6 |
| `TERM` | 14 | 14 | 新增判据 ⑤（终态组） |
| `SCHEMA` | 5 | 5 | 数据层 |
| `AUDIT` | 4 | 4 | 审计 |
| `NOTI` | 5 | 3 | 通知 |
| `MY` | 4 | 3 | 我的启事 |
| `API` | 7 | 5 | 通用契约 |
| `CONF` | 5 | 4 | 配置集中 |
| `SEC` | 5 | 4 | 零明文红线 |
| **合计** | **204** | **163** | — |

> 复算命令：`grep -oE "\b(AUTH|AUTHZ|PERM|MASK|TIER|IDEM|DEG|PAIR|ST|PHONE|RET|TERM|SCHEMA|AUDIT|NOTI|MY|API|CONF|SEC)-[0-9]{2}[a-c]?\b" docs/testing/v2/acceptance-matrix.md | sort -u | wc -l` → `204`。

### 4.2 需求反向核对（每条用例至少挂一条 REQ）

| 组 | 挂靠 REQ | 组内无 REQ 的用例数 |
|---|---|---|
| `AUTH` | REQ-45、REQ-16、REQ-26、REQ-35 | 0 |
| `AUTHZ` | REQ-46、REQ-27、REQ-14 | 0 |
| `PERM` | REQ-29、REQ-46、REQ-07、REQ-37、REQ-40 | 0 |
| `MASK` | REQ-41、REQ-22、REQ-25 | 0 |
| `TIER` | REQ-42、REQ-21、REQ-32 | 0 |
| `IDEM` | REQ-43、REQ-30、REQ-03 | 0 |
| `DEG` | REQ-44、REQ-24、REQ-38 | 0 |
| `PAIR` | REQ-01、REQ-02、REQ-03、REQ-04 | 0 |
| `ST` | REQ-05、REQ-06、REQ-08、REQ-09、REQ-15、REQ-37 | 0 |
| `PHONE` | REQ-17、REQ-18 | 0 |
| `RET` | REQ-19、REQ-13、REQ-11 | 0 |
| `TERM` | REQ-10、REQ-11、REQ-12、REQ-13、REQ-14、REQ-33 | 0 |
| `SCHEMA` | REQ-20、REQ-18、REQ-02、REQ-04、REQ-27、REQ-38 | 0 |
| `AUDIT` | REQ-34 | 0 |
| `NOTI` | REQ-33 | 0 |
| `MY` | REQ-39、REQ-38 | 0 |
| `API` | REQ-36、REQ-32、REQ-26、REQ-05 | 0 |
| `CONF` | REQ-23 | 0 |
| `SEC` | REQ-28、REQ-31、REQ-25 | 0 |

### 4.3 错误码交叉索引（22 个码，每码至少 1 条用例或 1 条保留用例）

| code | HTTP | 覆盖用例 | 备注 |
|---|---|---|---|
| 0 | 200 | `AUTH-02` `PERM-01a` 等全部成功路径 | — |
| 10001 | 400 | `ST-10` `PHONE-02` `PHONE-03` `PHONE-04` `TIER-11` `PAIR-06` | `fieldErrors` 断言在 `PHONE-02` |
| 10002 | 413 | `API-07`① | 3MB 请求体 |
| 10003 | 404 | `API-06`②③④⑤ | 未知路径沿用 5.1 `API-CON-02`（保留用例） |
| 10004 | 405 | 5.1 `API-CON-03`（保留） | v2 未改方法集合，不重复造用例 |
| 10005 | 500/503 | `AUDIT-03` `CONF-05` | DB DOWN → 503 |
| 10006 | 401 | `AUTH-01` `PERM-01a`…`PERM-17a`（17 格） | — |
| 10007 | 403 | `AUTHZ-03` `PERM-04b` `PERM-13b` `PERM-14b` `PERM-16b` `PERM-17b` | — |
| 20001 | 404 | `TERM-04` `TERM-05` `TERM-07` `TERM-12` `AUTHZ-02` | 记录不可见一律此码 |
| 20002 | 409 | `ST-07` | 同值 / 终态再流转 |
| 20003 | 409 | `API-04`（COMPLETED 调 PUT） | — |
| 20004 | 404 | `AUTHZ-05` | 记录已删 |
| 20005 | 409 | `ST-05` `ST-06` | 零写入 |
| 30001 | 400 | 5.1 `API-M-10`（保留） | 匹配入参非法 |
| 30002 | 503 | `DEG-08` | 不降级 |
| 30003 | 504 | `DEG-09` | 不降级 |
| 40001 | 404 | 5.1 `API-C-03`（改写后保留断言） | 分类不存在 |
| 40002 | 409 | 5.1 `API-C-03`（改写后保留断言） | 分类停用 |
| 50002 | 403 | `TIER-04` `TIER-06` `TIER-10` | 分数不够 |
| 50003 | 409 | `IDEM-09` | MATCHED/IGNORED 再改 |
| 50004 | 404 | `API-07`② | 通知不存在 |
| 50005 | 403 | `TERM-10` `TERM-11` `TERM-13` | 记录终态失效 |

### 4.4 五类新增判据的可执行性（每类 ≥2 条，含期望值与证据）

| 判据 | 条数 | 用例 | 关键期望值 |
|---|---|---|---|
| ① 多对 pair 独立 | 7 | `PAIR-01`…`PAIR-07` | `WHERE found_id=R-F1` 返回 2 行；`P-2.status` 保持 `NEW`；`R-A.updated_at` 不变 |
| ② 状态不自动置位 | 12 | `ST-01`…`ST-12` | 三列快照逐列相等；409/20005 零写入 |
| ③ 手机号仅格式校验 | 8 | `PHONE-01`…`PHONE-08` | 10 位/含字母/12 位 → 400/10001；合法未验证 → 200；`t_user` 无 `phone` 列 |
| ④ 不自动下架 | 6 | `RET-01`…`RET-06` | 40 天/400 天 PENDING 仍 200；`deleted_at IS NULL` |
| ⑤ 终态组 | 14 | `TERM-01`…`TERM-14` | 列表 0 命中 + 匹配保留期内命中 + 门槛 0.90 + CLOSED 取号 403/50005 |

---

## 5. 不可判定项（需求未写清 / 只能人眼判断，本文不自行补规则）

> 处理规则：每项给出「本文按什么写」「若改判影响哪几条」。**影响范围内的用例在门禁 0 / 门禁 3 前不得当作判据使用。**

| # | 事项 | 需求原文的缺口 | 本文的写法 | 改判影响 | 需谁定 |
|---|---|---|---|---|---|
| 1 | 所有者能否看自己的 COMPLETED 详情 | 正文只写「仅当某位已登录用户自己的启事与该记录匹配且 score ≥ 0.90 时可看详情，其余 404」，未排除所有者；A1 §1.3 A9 把它列为**假设**（按字面 404） | 按字面：404（`TERM-07`） | 仅 `TERM-07` 一条翻转（改 200 遮蔽视图）；其余 16 格不变 | 人类（门禁 0） |
| 2 | `IGNORED` 的 pair 是否算「已匹配」 | 正文只写「匹配且 score ≥ 0.90」，未说 pair.status 是否参与 | 按 A1 A10：只看 `pairUsable` + `score`，不看 `status`；`IGNORED` 仍可看详情 | 若排除 IGNORED：`TERM-06` 增加 `AND status<>'IGNORED'` 断言，`PAIR-07` ④ 的详情结论需重算 | 人类 |
| 3 | 保留期边界（恰 30 天） | 「保留期内（30 天，从 completed_at 起算）」未定义闭/开区间 | 按 A1 谓词 `completed_at > NOW() - INTERVAL 30 DAY`：**等于 30 天整不召回**（`RET-06`） | `RET-06` 的 `R-T1` 期望翻转（0 → 1） | 人类 |
| 4 | 手机号正则 | 只写「只做格式校验」，未给正则；A1 定为 `^1[3-9]\d{9}$` | 按 A1 契约 | `PHONE-02`…`PHONE-04` 的边界值全部随之改；若允许座机/国际号，需重写 6 条 | 人类 |
| 5 | 「召回」是否含 `/my` 的 pair 卡片 | 正文只提「仍进召回」，未说卡片是否出现 | 按 A1：卡片按 `pairUsable` 出，COMPLETED 未到期仍出卡片（`MY-02`、`TERM-09` 只断言 CLOSED 的移除） | `MY-02` 的 `relatedCount` 期望需重算 | 人类 |
| 6 | `notify_threshold=0.75` | 需求未提通知阈值，A1 自行提出与 `contact_unlock_threshold` 同值 | 按 A1 | `NOTI-01` 的「<0.75 不通知」翻转 | 人类 |
| 7 | UI 文案与视觉效果 | 「已降级」「对方尚未公开」「暂无通知」等文案属 A3 基线，视觉截断（省略号是否可见）只能人眼判 | 只在 `DEG-07`、`TIER-02`、`NOTI-05` 用文案字符串断言；不判字号/间距/颜色 | 文案改动只影响这 3 条的字符串 | A3 / 人类 |
| 8 | 「无短信验证」的证明方式 | 需求未规定证据形式 | 三证并用：`EV-NET` 零出站 + `EV-GREP` 无 sms 依赖 + `PHONE-06` 无验证码表 | 若只认抓包，`PHONE-06` 降为 P2 | 人类 |
| 9 | 演示账号口令落在仓库 | H9/A8 允许写在 README/seed 注释，与「密钥零明文」红线相邻但对象不同（演示口令 ≠ 密钥）；需求未明确边界 | 本文只要求「口令不进证据文件」 | 若人类要求口令也不进仓库，seed 与演示脚本要改，`AUTH-02` 的前置随之变 | 人类 |
| 10 | 到期清扫「派生数据清理」的范围 | A1 写「如已到期的 pair 通知补写队列」，未定义清理对象 | 本文只断言「不改 status/completed_at/deleted_at」（`ST-11`、`RET-05`），不断言清理了哪些行 | 若人类要求清理范围可判，`ST-11` 需增加行数断言 | 人类 |

---

## 6. 自检

以下 6 条都能用命令复算，结果写在「实测」列，不接受「已检查」这种表述。

| # | 检查项 | 命令 | 判据 | 实测 |
|---|---|---|---|---|
| 1 | 用例数 | `grep -oE "\b(AUTH|AUTHZ|PERM|MASK|TIER|IDEM|DEG|PAIR|ST|PHONE|RET|TERM|SCHEMA|AUDIT|NOTI|MY|API|CONF|SEC)-[0-9]{2}[a-c]?\b" docs/testing/v2/acceptance-matrix.md \| sort -u \| wc -l` | = 204 | `204` |
| 2 | 需求条目数 | `grep -oE "^\| REQ-[0-9]{2} " docs/testing/v2/acceptance-matrix.md \| wc -l` | = 46，且编号 01–46 无缺号无重号 | `46`，`REQ-01…REQ-46` 连续，重号 0 |
| 3 | 模糊词 | `grep -cE "合[理]\|正[常]\|正[确]\|友[好]" docs/testing/v2/acceptance-matrix.md`（字符类写法与直接写四词的检索等价，只为避免命令自匹配） | = 0（全文，不只期望列） | `0` |
| 4 | 「视情况」类措辞 | `grep -c "视情况\|看情况\|酌情\|酌情处理" docs/testing/v2/acceptance-matrix.md` | = 0 | `0` |
| 5 | 每条用例的期望都含状态码/字段/数值 | 人工逐行读 §3 的「期望」列 | 无空期望列 | 204 条全部含 `HTTP xxx` + `code` 或 `字段=值` 或 SQL 结果值 |
| 6 | 五类新增判据各 ≥2 条 | 见 §4.4 | ①7 ②12 ③8 ④6 ⑤14 | 全部 ≥2 |

**已知边界（不隐藏）：**

- 本文的用例数 204 与 `qa/` 现有 64 条接口用例不是同一层：本文是**验收判据清单**，F1 落地后会按组复用/改写现有用例，最终条数以 F1 交付的 `qa/` 为准；若 F1 落地后条数与本文对不上，按任务约定**先报差异**再下结论。
- `PERM` 的 51 条共用同一套步骤模板（§3.2 已写死），F1 落地时写成表驱动（`cases = [{id, method, path, identity, expectStatus, expectCode}]`），不写 51 个独立函数。
- `DEG-01`…`DEG-06`、`DEG-11` 需要 mock LLM 服务；mock 的响应体不含任何用户数据，脚本放 `qa/lib/`，不进 `server/`。




