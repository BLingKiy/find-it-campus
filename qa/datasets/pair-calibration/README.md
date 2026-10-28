# pair-calibration —— 配对阈值校准标注集（ZHYYKFX-84）

20 对人工标注的 LOST/FOUND 样本，用来回答「`findit.pair.*` 该取什么值」，以及
「LOCAL_KEYWORD 与 DEEPSEEK 能不能共用一套阈值」。结论与逐对分数见
[`docs/testing/v2/threshold-calibration.md`](../../../docs/testing/v2/threshold-calibration.md)。

## 文件

| 文件 | 作用 |
|---|---|
| `pairs.json` | 标注集本体：每对含模型可发布的字段 + `expect`（MATCH / NO_MATCH）+ `class` + 一句理由 |
| `run.mjs` | 采集器：逐对发布 → 等两端打分完成 → 记录 score/engine/tier → 关闭两端 |
| `verify.mjs` | 验证器：用**配置文件里的真实阈值**跑一正一负，断言「出卡片 / 只计数」 |
| `analyze.mjs` | 统计器：分位数、正负分离、满足判据的 unlock 区间、逐对对照表 |
| `results/` | 两次采集 + 两次验证 + fail-fast 日志；`*-db.txt` 是 `t_pair_score` 原始行 |

## 样本构成

| 类别 | 含义 | 对数 | 人工判定 |
|---|---|---|---|
| A | 完全同物、措辞不同 | 5 | 全部应匹配 |
| B | 近义、但可判为同物 | 4 | 全部应匹配 |
| C | 部分要素相同（含同类别不同物） | 5 | 1 应匹配 / 4 不应匹配 |
| D | 明确无关（含同色同地点不同物） | 6 | 全部不应匹配 |

合计 20 对：正例 10、负例 10。负例中 4 对与正例同类别、同地点，专门用来暴露
「关键词算法把同类别当同物」的问题。

## 重跑步骤（本机 Windows / MySQL 8）

```bash
# 1. 空库（schema + seed 后清空业务表，只留 3 账号 9 分类）
mysql -u root -e "CREATE DATABASE findit_z84_pair CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
mysql -u root findit_z84_pair < db/schema.sql   # 注意 schema.sql 里有自己的 CREATE/USE
mysql -u root findit_z84_pair < db/seed.sql
mysql -u root -e "SET FOREIGN_KEY_CHECKS=0; TRUNCATE findit_z84_pair.t_access_log; TRUNCATE findit_z84_pair.t_notification; TRUNCATE findit_z84_pair.t_pair_score; TRUNCATE findit_z84_pair.t_match_result; TRUNCATE findit_z84_pair.t_record; SET FOREIGN_KEY_CHECKS=1;"

# 2. 采集轮：两套阈值都临时全放行（仅采集；不要写进 application.yml）。
#    自 2026-10-28 起 local 有 overrides，只压默认那套会把本地引擎的低分对判成「没出卡片」。
java -jar server/target/find-it-campus-0.0.1-SNAPSHOT.jar --server.port=18086 \
  --findit.pair.contact_unlock_threshold=0.00 --findit.pair.contact_direct_threshold=0.01 \
  --findit.pair.notify_threshold=0.00 --findit.pair.completed_visible_threshold=0.00 \
  --findit.pair.overrides.local.contact_unlock_threshold=0.00 \
  --findit.pair.overrides.local.contact_direct_threshold=0.01 \
  --findit.pair.overrides.local.notify_threshold=0.00 \
  --findit.pair.overrides.local.completed_visible_threshold=0.00
FINDIT_API_BASE=http://localhost:18086 node qa/datasets/pair-calibration/run.mjs local

# 3. 语义引擎：同一 shell 内注入 key 后换端口重启，再跑一遍
FINDIT_API_BASE=http://localhost:18087 node qa/datasets/pair-calibration/run.mjs deepseek

# 4. 统计
node qa/datasets/pair-calibration/analyze.mjs

# 5. 新默认值下的可见性验证（不加任何 --findit.pair.* 覆盖）
FINDIT_API_BASE=http://localhost:18088 node qa/datasets/pair-calibration/verify.mjs local
FINDIT_API_BASE=http://localhost:18089 node qa/datasets/pair-calibration/verify.mjs deepseek
```

> `DEEPSEEK_API_KEY` 只允许放在启动进程的环境变量里（`$env:DEEPSEEK_API_KEY=...`），
> 不进命令行参数、不进仓库、不进 `results/`。采集器与验证器都不读 key。

## 纪律

1. **不改标注集去凑结果**：分数先落盘，阈值后算；`pairs.json` 的 `expect` 是人工判定，与引擎输出无关。
2. **不用单测冒充 live 证据**：`DEEPSEEK` 必须真实调用（`results/deepseek-db.txt` 有 `degraded_reason=NULL` 与 token 列）。没有 key 时如实记「不可判定」。
3. **每次样本改动都要重跑 `analyze.mjs`**：`results/table.md` 是文档 §4 的数据源，手抄会漂移。
4. 采集轮的「全放行阈值」只是读分手段，**不是**建议值；建议值只认 `application.yml` 与 `analyze.mjs` 里的 `RECOMMENDED`。
