# Z85 发布闸门 · 真包验证与 A/B 证据

- 任务：ZHYYKFX-85
- 分支：`feat/v2-Z85-review-gate`（基于 `origin/feat/v2-integration @ 6ce5700`）
- 设计：`docs/design/adr/AI-REVIEW-GATE.md`
- 执行日期：2026-10-29（Asia/Shanghai）
- 运行方式：单个前台 PowerShell 脚本启动 MySQL 8.0.29、本地 stub LLM、打包 jar，跑完所有断言后停止；脚本与 stub 在 `tools/`。

## 1. 产物

| 文件 | 内容 |
|---|---|
| `run-evidence.txt` | 真包断言输出（环境、DB 状态、四面 0 命中、通过/拒绝/失败、双通道、审计、mode=off） |
| `run-console.txt` | 同一次运行的完整控制台输出 |
| `ab/ab-summary.md` | 真实 DeepSeek 的 15 对 A/B 汇总 |
| `ab/ab-result.json` | 每个引擎 × 通道的逐对分数 |
| `ab/ab-samples.csv` | 逐对分数明细 |
| `migration-check.txt` | v2.0 → v2.1（up）→ v2.0（down）迁移实测 |
| `tools/verify-z85.ps1` | 可复跑的真包脚本（会重置 `findit_z85` 测试库） |
| `tools/stub-llm.cjs` | 确定性 stub LLM（只用于闸门路径，不用于 A/B 数据） |
| `../server/src/test/java/com/findit/campus/review/ab/ReviewAbHarnessTest.java` | 真实 LLM A/B 脚本（无 key 自动 skip，不伪造） |

## 2. 被测产物

| 项 | 值 |
|---|---|
| jar | `server/target/find-it-campus-0.0.1-SNAPSHOT.jar` |
| jar SHA-256（本次运行） | `F9AC6703AD70E282946A9140ADFC9880AB9AE16B68D0387DBC33ABBA97E7E30D` |
| Java | 17.0.20.1 |
| MySQL | 8.0.29（`findit_z85`，schema.sql + seed.sql 全量重建） |
| 应用端口 | 18085 |
| 模式 | 先 `findit.review.mode=ai`（stub key），再 `off`（stub 已停） |

## 3. 闸门路径结果（`run-evidence.txt`）

| 场景 | 断言 | 结果 |
|---|---|---|
| 发布（ai） | 5 条均 200，响应 `reviewState=PENDING` | PASS（id 27–31） |
| AI 通过 | 28 `APPROVED`、`is_visible=1`、生成 10 条 pair | PASS |
| 规则双检 | 29 模型 APPROVE 但输出含手机号 → `REJECTED`，理由含「规则双检命中」 | PASS |
| 模型拒绝 | 30 `REJECTED`，理由落库 | PASS |
| AI 连续失败 | 27/31 `PENDING`、`review_attempts=3`、`review_manual_required=1`、`is_visible=0`、0 条 pair | PASS |
| 公共列表 | 27 的 keyword 查询 total=0 | PASS |
| `/api/match` | 候选 12 条，27 命中 0 | PASS |
| 他人详情 | demo02 读 27 → 404/20001 | PASS |
| 他人「我的启事」 | demo02 列表不含 27 | PASS |
| 本人「我的启事」 | demo01 看到 27 `PENDING + manual=true + 理由` | PASS（设计：本人必须看到审核态） |
| 通过后可见 | 28 在公共列表 1 命中；demo02 详情 200；响应无 `raw*/match*` | PASS |
| 拒绝后不可见 | 29 公共列表 0 | PASS |
| 双通道 | 28 的 display 不含 `13800000009/张三/201`；`raw_*` 保留手机号；`match_*` 无手机号但保留 `201` | PASS |
| 审计 | 28 一条 REVIEW（APPROVE/DEEPSEEK/stub-model/pair-postprocess-v1/4ms/128+64）；31 四次 ERROR+转人工；审计表无正文列 | PASS |
| mode=off | stub 已停，发布仍 200 + `APPROVED` + 立即可见 + 他人详情 200；`review_engine=OFF`、`raw/match=NULL` | PASS |
| 全量单测 | `mvn test` 266 run / 0 failed / 1 skipped（A/B 无 key） | PASS（见交付说明） |

> 说明：闸门路径用本地 stub 是为了**可复现地**触发 APPROVE / REJECT / 规则命中 / 连续失败四种分支；
> stub 只在 `127.0.0.1:19090`，不碰真实模型。A/B 使用真实 `deepseek-chat`，两者不混用数据。

## 4. A/B（真实 DeepSeek）

- 样本：10 正例（同一物品）+ 5 负例（不同物品），共 15 对；排序子集 5 个查询 × 3 候选。
- 口径：`BEFORE=match_text`（生产出分）；`AFTER=display_text`（反事实单通道）。
- 结果（详见 `ab/ab-summary.md`）：

| 引擎 | 通道 | 正例一致率 | 负例误配率 | top1 |
|---|---|---|---|---|
| DEEPSEEK | BEFORE（生产） | 100% | 0% | 100% |
| DEEPSEEK | AFTER（反事实） | 100% | **20%** | 100% |
| LOCAL_KEYWORD | BEFORE | 20% | 0% | 100% |
| LOCAL_KEYWORD | AFTER | 0% | 0% | 100% |

关键发现：单通道（用 display 出分）会让 N04「学生证 vs 校园卡」误配到 0.85，并把 P01 分数从 0.85 拉到 0.98（差 0.13）。
这证明 **双通道是硬需求**：生产用 `match_text` 出分，改写不改变分数；`display_text` 只负责公示。

## 5. 复跑

```powershell
# 需要：MySQL 8 bin、JDK17、node、已打包 jar
powershell.exe -NoProfile -ExecutionPolicy Bypass -File docs/testing/v2/Z85-review-gate/tools/verify-z85.ps1

# A/B（真实 key，不写进任何文件）
$env:DEEPSEEK_API_KEY = (Get-Content "$env:USERPROFILE\.hifox-secrets\deepseek_api_key.txt" -Raw).Trim()
mvn -f server/pom.xml -Dtest=ReviewAbHarnessTest test
```

注意：`verify-z85.ps1` 会 `DROP` 并重建 `findit_z85`，只用于测试库；不要指向正式库。
A/B 无 key 时 JUnit `Assumptions` 直接 skip，不会生成假数据。

## 6. 未覆盖/待人类确认

- 真实 DeepSeek 的闸门路径未做全链路（只做了 A/B 的 15 对后处理 + 60 次打分）；闸门路径用 stub 是为分支可复现，真实模型可用同一脚本把 `stub-llm.cjs` 换成 `FINDIT_LLM_BASE_URL=https://api.deepseek.com/v1` 重跑。
- 人工审核 ops 接口没有用真实 ops token 做端到端（沿用现有 `OPS_SCAN_TOKEN` 口径，未在本机设置该变量）。
- `raw_*` 明文落库、审计保留期、多实例兜底调度：见 ADR §15 开放项。
