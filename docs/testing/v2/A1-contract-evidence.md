# A1 架构与契约 v2 · 交付证据

> 任务：ZHYYKFX-45 · 磐石石 · dsh
> 分支：`feat/v2-A1-arch-contract`
> 基线：`origin/main` @ `21784f6`（2026-10-27 检出）
> 说明：A1 只出文档，`server/`、`web/` 0 行改动。本文件记录门禁 0 可复核的检查项与输出。

## 1. 交付物

| 文件 | 行数 | 内容 |
|---|---|---|
| `docs/design/v2-architecture.md` | 1123 | 7 表 ER/DDL 草案、模块与数据所有权、关键链路、遮蔽算法与正反例、分档判定、LLM 契约、pair/record 状态机、扫描、错误码、可靠性与安全、可观测性、前端归属、ADR、演进路线 |
| `docs/api-contract-v2.md` | 767 | 17 个业务接口 + 2 个运维接口、17×3 权限矩阵、逐接口入参/出参/错误码/幂等、response schema、前端接入注意、验收对照 |
| `docs/design/diagrams/er-v2.mmd` | 84 | 7 表 Mermaid ER 图（关系 + 关键列 + 约束） |
| `docs/api-contract.md` | +2 | 顶部加 v2 指向，避免下游误读 v1 |
| `docs/design/api-list.md` | +2 | 顶部加 v2 指向，标注该表只是 v1 代码核对附录 |

## 2. 只出文档

```text
$ git -C <repo> diff --stat origin/main...HEAD
 docs/api-contract.md                    |   2 +
 docs/api-contract-v2.md                 | 767 +++++++++++++++++++++++++++++
 docs/design/api-list.md                 |   2 +
 docs/design/diagrams/er-v2.mmd          |  84 +++++
 docs/design/v2-architecture.md          |1123 +++++++++++++++++++++++++++++
 docs/testing/v2/A1-contract-evidence.md |  98 ++++
 6 files changed, ... insertions(+)
```

`server/`、`web/` 下改动行数 = 0；`git diff --name-only` 只含 `docs/`。

## 3. 17 × 3 权限矩阵逐格检查

```text
$ 解析 docs/api-contract-v2.md 的 §3 矩阵（仅统计 `| <数字> |` 行）
matrix data rows=17
no empty cells / wrong column count
```

- 17 行：`GET /api/records` … `PATCH /api/notifications/{id}/read`。
- 每行 5 列（编号 + 接口 + 匿名 + 登录非所有者 + 所有者），51 个状态格全部非空。
- 两处 `⚠️` 是 `contact` / `contact-unlock` 的同请求内分支，分支条件（`score≥0.90`、`0.75–0.90`、`<0.75`、`PUBLISH/REVOKE`）在格内写死，不是“视情况”。

## 4. 遮蔽正反例复现

按 `docs/design/v2-architecture.md` §6 的算法（location 8 码点 + 末尾定位正则；description 四条正则按 ID→手机号→QQ→相对时间顺序）用 Node 20 跑出：

```text
P1 | 第三教学楼… | [时间已隐藏] 在三楼捡到，联系 [手机号已隐藏]
P2 | 东区3号宿舍楼… | QQ[QQ号已隐藏]，证件号 [证件号已隐藏]
P3 | 图书馆三楼自习区… | [时间已隐藏] 放在 3 号楼 201，电话 [手机号已隐藏]
N1 | 图书馆 | 3 号楼 201 教室，无联系方式
N2 | 大学生活动中心 | 编号 12345，另一个 12345678，均非 QQ；时间 13:30 未写日期
N3 | 操场 | 2026-10-21 13:30 在操场捡到校园卡，卡号 201
```

- P1/P2/P3 = 正例（应命中），N1/N2/N3 = 反例（不应命中）；`3 号楼 201`、`12345`、`13:30`、`2026-10-21 13:30` 均未被误打码。
- `contact` / `publisherName` 的身份维度：非所有者 `contact=null`、`李同学→李**`；所有者四项原样（O1–O3，见架构 §6.4）。

## 5. 禁项扫描

```text
$ grep -n "phone" docs/design/v2-architecture.md
  -> 只出现在“t_user 没有 phone 列/无 phone 列”的否定句；DDL 无 phone 字段
$ grep -n "短信" docs/design/v2-architecture.md docs/api-contract-v2.md
  -> 只出现在“无短信验证/无短信表/无短信接口”的否定句
$ grep -n "10008\|SELF_PAIR\|Retry-After" docs/design/v2-architecture.md docs/api-contract-v2.md
  -> 0 命中（登录不做 429/锁定；审计无 SELF_PAIR）
$ grep -n "sk-\|DEEPSEEK_API_KEY\s*=" docs/design/v2-architecture.md docs/api-contract-v2.md
  -> 0 命中（只出现 env 变量名，无值）
$ grep -n "UPDATE t_record SET status" docs/design/v2-architecture.md
  -> 只出现在“不得写/没有这一行”的约束句
```

## 6. 下游契约锚点

| 下游 | 本文件给出的锚点 |
|---|---|
| A2 | `docs/api-contract-v2.md` §3 矩阵、§9 验收对照；架构 §6.4 遮蔽正反例 |
| B1 | 架构 §4.2 七表 DDL 草案、§4.6 迁移/回滚；ER 图 |
| B2 | 契约 §4.9–§4.11、§1.1（Cookie/CORS/401）；架构 §5.3 |
| B3 | 契约 §1.1、§8 前端注意；架构 §13 文件归属 |
| C1 | 架构 §4.4 一致性边界、§6 遮蔽；契约 §4.1/§4.2 字段可见性 |
| D1 | 架构 §5.1、§8（LLM/幂等/降级）、§5.4 扫描；契约 §4.12 pairState |
| D2 | 契约 §4.12（`pairCount/relatedCount/lowConfidenceCount`）、§4.16/§4.17；架构 §13 |
| E1 | 契约 §4.14/§4.15；架构 §7.2 判定式、§4.2.7 审计表 |
| E2 | 契约 §4.2/§4.13/§4.14、§5.1 PairCard；架构 §7.1 配置、§13 文件归属 |
| F1/F2/F3 | 契约 §3 矩阵、§7 幂等、§6 错误码；架构 §9 状态机、§11 安全 |

## 7. 门禁 0 自检

- [x] 17 个业务接口 × 匿名 / 登录非所有者 / 所有者，逐格有状态码。
- [x] 遮蔽四类输出（location/description/contact/publisherName）正反例各 3 条，输出可复现。
- [x] pair 链路降级与 `/api/match` 不降级（ADR-009）对照表，无矛盾。
- [x] pair 状态只有 NEW/CONTACTED/IGNORED；显式写“确认 A 不影响 B”；无「已解决」状态。
- [x] `t_user` 无 phone 列；无短信校验；无自动关闭/自动下架。
- [x] `server/`、`web/` 0 行改动。
