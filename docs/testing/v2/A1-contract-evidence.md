# A1 架构与契约 v2 · 交付证据（v2.1 返工轮）

> 任务：ZHYYKFX-45 · 磐石石 · dsh
> 分支：`feat/v2-A1-arch-contract`
> 基线：`origin/main` @ `21784f6`（2026-10-27 检出）；上一轮 `849e07a` 验收不通过，本轮为**追加 commit**。
> 口径来源：任务正文末尾【返工要求 · 2026-10-27 第二轮】+ 人类 08:45「终态口径 vF」评论。
> 说明：A1 只出文档，`server/`、`web/` 0 行改动。本文件记录门禁 0 可复核的检查项与输出。

## 1. 交付物（v2.1）

| 文件 | 行数 | 内容 |
|---|---|---|
| `docs/design/v2-architecture.md` | 1275 | 7 表 ER/DDL（三态 + 四态 + `completed_at` + 两端 `confirmed_at`）、模块与数据所有权、关键链路、遮蔽算法与正反例、分档判定、LLM 契约、三态/四态状态机、三态×召回/列表/详情、扫描与到期清扫、错误码、可靠性与安全、可观测性、前端归属、ADR、演进路线 |
| `docs/api-contract-v2.md` | 899 | 17 个业务接口 + 2 个运维接口、17×3 权限矩阵（51 格）、逐接口入参/出参/错误码/幂等、response schema、前端接入注意、验收对照 |
| `docs/design/diagrams/er-v2.mmd` | 88 | 7 表 Mermaid ER 图（关系 + 关键列 + 约束注释） |

## 2. 只出文档

```text
$ git -C <repo> diff --stat origin/main...HEAD
 docs/api-contract-v2.md                 |  899 +
 docs/api-contract.md                    |    2 +
 docs/design/api-list.md                 |    2 +
 docs/design/diagrams/er-v2.mmd          |   88 +
 docs/design/v2-architecture.md          | 1275 +
 docs/testing/v2/A1-contract-evidence.md |  157 +
 6 files changed, 2423 insertions(+)
```

`server/`、`web/` 下改动行数 = 0；`git diff --name-only origin/main...HEAD` 只含 `docs/`。

## 3. 17 × 3 权限矩阵逐格检查

解析 `docs/api-contract-v2.md` §3：只统计 `| <数字> |` 起头的行，每行必须 5 列（编号 + 接口 + 匿名 + 登录非所有者 + 所有者），每格必须含 `✅/❌` 且含 3 位状态码。

```text
matrix rows=17 cells=51 problems=0
business interface rows=17
```

- 17 行：`GET /api/records` … `PATCH /api/notifications/{id}/read`。
- 51 个状态格全部非空且逐格有状态码；#1/#2/#4/#5/#6/#8/#12/#13/#14/#15 已按三态/四态重算。
- `⚠️` 只用于同请求内分支（#2/#4/#5/#13/#14/#15），分支条件与状态码在格内写死，不是“视情况”。

## 4. 三态 × 召回 / 列表 / 详情

解析 `docs/design/v2-architecture.md` §9.3 的三列表格（表头 `| 记录状态 | 召回` 起）：

```text
three-state rows=4 cells=12 problems=0
```

- 四行：`PENDING`、`COMPLETED`（保留期内）、`COMPLETED`（已到期）、`CLOSED`；三列全部铺满。
- `COMPLETED` 保留期内：召回 ✅、列表 0 命中、详情仅 `score ≥ completed_visible_threshold` 的合格对端 200，其余 404。
- `COMPLETED` 到期：召回 0、列表 0、详情任何身份 404，且 **不改写 `status`/`completed_at`**。
- `CLOSED`：召回 0、列表 0、详情任何身份 404；其 pair 取号 403/50005，pair 一并失效。

## 5. 未达 MATCHED 调「标记完成」→ 409 且零写入

`docs/api-contract-v2.md` §4.5 给出：

```text
| PENDING | COMPLETED | 无有效 MATCHED pair | 409 | 20005 | 不执行 UPDATE：status、completed_at、updated_at 全不变 |
```

并给出可复核 SQL：调用前后各查一次 `SELECT status, completed_at, updated_at FROM t_record WHERE id=?`，两行必须完全一致。前置检查在 UPDATE 之前，409 分支事务里没有任何 DML。

## 6. 遮蔽正反例复现（沿用上一轮，未改动）

按架构 §6 的算法（location 8 码点 + 末尾定位正则；description 四条正则按 ID→手机号→QQ→相对时间顺序）用 Node 20 跑出：

```text
P1 | 第三教学楼… | [时间已隐藏] 在三楼捡到，联系 [手机号已隐藏]
P2 | 东区3号宿舍楼… | QQ[QQ号已隐藏]，证件号 [证件号已隐藏]
P3 | 图书馆三楼自习区… | [时间已隐藏] 放在 3 号楼 201，电话 [手机号已隐藏]
N1 | 图书馆 | 3 号楼 201 教室，无联系方式
N2 | 大学生活动中心 | 编号 12345，另一个 12345678，均非 QQ；时间 13:30 未写日期
N3 | 操场 | 2026-10-21 13:30 在操场捡到校园卡，卡号 201
```

- P1/P2/P3 = 正例；N1/N2/N3 = 反例；`3 号楼 201`、`12345`、`13:30`、`2026-10-21 13:30` 均未被误打码。
- `contact` / `publisherName` 的身份维度：非所有者 `contact=null`、`李同学→李**`；所有者四项原样（O1–O3，见架构 §6.4）。

## 7. 配置项名与旧键

```text
$ grep -n "contact_direct_threshold\|contact_unlock_threshold\|completed_retention_days\|completed_visible_threshold" docs/design/v2-architecture.md docs/api-contract-v2.md
  -> 配置表、YAML 片段、判定式、矩阵、ADR 均有冻结名
$ grep -n "direct-view-threshold\|contact-auth-threshold" docs/design/v2-architecture.md docs/api-contract-v2.md
  -> 3 处，全部在「旧键删除/不设别名」说明句；配置表与 YAML 片段 0 命中
```

- 同一配置类：`PairPolicyProperties`（prefix `findit.pair`）持有五阈值（含 `notify_threshold`）；缺失/越界 fail-fast。
- 旧键不读取、不设别名；B1/D1 改 yml 后启动，旧配置直接失败。

## 8. 禁项扫描

```text
$ grep -n "phone" docs/design/v2-architecture.md
  -> 只出现在“t_user 没有 phone 列/无 phone 列”的否定句；DDL 无 phone 字段
$ grep -n "短信" docs/design/v2-architecture.md docs/api-contract-v2.md
  -> 只出现在“无短信验证/无短信表/无短信接口”的否定句
$ grep -n "status ∈ {PENDING,CLAIMED,CLOSED}\|ck_record_status.*CLAIMED" docs/design/v2-architecture.md docs/api-contract-v2.md
  -> 0 命中；CLAIMED 只出现在迁移映射（v1→v2）与“已作废/传即 400”说明
$ grep -n "UPDATE t_record SET status" docs/design/v2-architecture.md
  -> 只出现在“不得写/没有这一行”的约束句
$ grep -n "sk-\|DEEPSEEK_API_KEY\s*=" docs/design/v2-architecture.md docs/api-contract-v2.md
  -> 0 命中（只出现 env 变量名，无值）
```

## 9. 本轮 8 项返工的落点

| # | 返工项 | 落点 |
|---|---|---|
| 1 | pair 四态 + 两端 `confirmed_at` | 架构 §1.1 H1、§4.2.5 DDL、§9.1、契约 §4.13、ER |
| 2 | `t_record` 三态 + `completed_at` | 架构 §1.1 H2、§4.2.3 DDL、§4.6 迁移、§9.2、契约 §4.5/§4.6、ER |
| 3 | 三态 × 召回/列表/详情 | 架构 §9.3（4 行 × 3 列全满） |
| 4 | 未达 MATCHED 标完成 409 | 架构 §9.2、契约 §4.5（409/20005 + 零写入 SQL） |
| 5 | COMPLETED 语义 | 架构 §1.1 H11、§9.3；契约 §4.1/§4.2/§4.8/§4.12（`detailAccess`/`detailVisible`） |
| 6 | 配置项冻结名 | 架构 §7.1（YAML + 类 + 旧键删除）、契约 §0.2 #12、§3 #13–#15 |
| 7 | 矩阵 #1/#2/#5/#8/#14 重算 | 契约 §3（另 #4/#6/#12/#13/#15 随新模型同步） |
| 8 | CLOSED 全链路 | 架构 §4.4 第 9 条、§9.3；契约 §4.1/§4.8/§4.14（403/50005） |

## 10. 待门禁 0 拍板的两处字面口径

- **A9（COMPLETED 详情对所有者）**：正文写「仅当某位已登录用户自己的启事与它匹配且 score ≥ 0.90 时可见，其余 404」。所有者不满足“自己的启事与它匹配”，故按字面 **所有者也是 404**；所有者从 `/my` 看摘要（`detailVisible=false`）。若门禁要求所有者例外，只改 `DETAIL_VIEWABLE` 第一行（owner→200），其余 16 格不变。
- **A10（COMPLETED 详情的 pair 状态）**：正文只写“匹配且 score ≥ 0.9”，判定式只要求 `pairUsable`（两端未删、非 CLOSED、未到期），**不排除 `IGNORED`**。若要求排除，加 `AND p.status <> 'IGNORED'` 一处，仅影响 COMPLETED 详情。

这两条已写进架构 §1.3 与契约 §4.2，**不是缺省或视情况**；门禁若改口径，按上面一行改动，不影响矩阵其他格。

## 11. 下游契约锚点

| 下游 | 本文件给出的锚点 |
|---|---|
| A2 | 契约 §3 矩阵、§9 验收对照；架构 §6.4 遮蔽正反例、§9.3 三态表 |
| B1 | 架构 §4.2 七表 DDL（`ck_record_completed_at`/`ck_pair_matched`）、§4.6 迁移（`CLAIMED→CLOSED` + 快照）；ER 图 |
| B2 | 契约 §4.5（三态流转/409）、§4.9–§4.11；架构 §5.3 |
| B3 | 契约 §1.1、§8 前端注意（`detailVisible`/`detailAccess`）；架构 §13 |
| C1 | 架构 §4.4 一致性边界、§6 遮蔽、§9.2 状态机；契约 §4.1/§4.2/§4.5 |
| D1 | 架构 §5.1、§5.4（MATCHABLE + 到期清扫）、§8；契约 §4.8/§4.12/§4.13 |
| D2 | 契约 §4.12（三态文案/计数）、§4.16/§4.17；架构 §13 |
| E1 | 契约 §4.13（CONFIRM）/§4.14（50005）/§4.15；架构 §7.2、§4.2.7 审计 |
| E2 | 契约 §4.2/§4.5/§4.13/§4.14、§5.1 PairCard；架构 §13 文件归属 |
| F1/F2/F3 | 契约 §3 矩阵、§6 错误码、§7 幂等；架构 §9 状态机、§11 安全 |

## 12. 门禁 0 自检

- [x] 17 个业务接口 × 匿名 / 登录非所有者 / 所有者，51 格逐格有状态码（脚本核对 0 problems）。
- [x] 三态 × 召回 / 列表 / 详情 4 行 × 3 列全满（脚本核对 0 problems）；COMPLETED/CLOSED 的 404、409 条件可复现。
- [x] 未达 MATCHED 标完成 → 409/20005，`status`/`completed_at`/`updated_at` 均不变（零写入验收 SQL）。
- [x] pair 四态含 MATCHED；`MATCHED ⇔ 两端 confirmed_at 非空`；显式写“确认 A 不影响 B、无占用/锁定”；无「已解决」状态。
- [x] `t_record` 三态；`COMPLETED` 列表 0 命中、保留期内可召回/匹配、详情 0.90 门槛、到期不改写 status。
- [x] 配置项四项冻结名 + `notify_threshold`，同一 `PairPolicyProperties`；旧键删除。
- [x] `t_user` 无 phone 列；无短信校验；无自动关闭/下架。
- [x] 遮蔽四类输出正反例各 3 条，输出可复现（未改动）。
- [x] pair 链路降级与 `/api/match` 不降级（ADR-009）对照表，无矛盾（未改动）。
- [x] `server/`、`web/` 0 行改动；diff 只含 `docs/`。
