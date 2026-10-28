# pair-synonyms：近似同义转换样本（ZHYYKFX-84 / ZHYYKFX-85 共用）

这组样本回答一个问题：**同一个人两次描述同一件物品、措辞不同时，打分还稳不稳？**
它是 `pair-calibration`（原文对原文阈值校准）的补充，不是替换。

## 文件

| 文件 | 作用 |
|---|---|
| `variants.json` | **唯一**样本来源：6 件物品 × 4 种措辞（O 原文 / C 口语化 / F 书面 / S 简称或错别字），另加 6 对「不同物但描述高度重叠」的陷阱负例 |
| `run-synonyms.mjs` | 采集器：逐对发布 → 等打分完成 → 记 `score/engine/tier` → 关闭两端；输出 `results/syn-<label>.json` |
| `analyze-synonyms.mjs` | 纯计算：分数保持率、分引擎差距、过阈比例、陷阱最高分、单套阈值反证；输出 `results/syn-analysis.md` 与 `syn-table.md` |
| `verify-synonyms.mjs` | 用 `application.yml` 的真实默认值跑可见性：卡片是否出、档位、`GET /api/pairs/{id}/contact` 的 `available` |
| `results/` | 两套引擎的逐对原始 JSON、DB 原始行、分析结果、可见性证据 |

## 与 ZHYYKFX-85 的共用约定

- `variants.json` 是 Z84 与 Z85 A/B 的**同一批数据**。Z85 必须读本文件，不得另造 15 对。
- `items[].phrasings.O` 是原文基线，对应 Z85 的 BEFORE（`match_text`）通道；`C/F/S` 是同一物品的三种改写。
- Z85 的 AFTER（`display_text`）通道可以复用同一批 item，不需要再写一套 LOST/FOUND 文案。
- 任何一侧改了样本，另一方必须同步；改样本后必须重跑 `analyze-synonyms.mjs`，不能手抄旧数字。
- **迁移状态（2026-10-28）**：Z85 现有 `ReviewAbHarnessTest.fixtures()` 里的 15 对硬编码样本早于本文件，尚未迁移。下一次改 Z85 的 A/B 时必须改为读本文件、旧 15 对作废；在那之前，本文档与 `variants.json` 是 Z84 侧的权威口径，不能拿 Z85 旧 15 对的分数与本文件混算。

## 跑法（Windows / MySQL 8）

```bash
# 1) 两个空库，schema/seed 后清空业务表（只留 3 账号 + 9 分类）
#    注意 schema.sql / seed.sql 内写死了 find_it_campus，需先替换库名，见 pair-calibration/README.md
mysql -u root -e "CREATE DATABASE findit_z84_syn_local CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
# ... schema/seed/truncate 步骤与 pair-calibration 相同 ...

# 2) 采集轮：LOCAL 与 DEFAULT 两套阈值都必须压到全放行。
#    只压默认那套不够 —— 自 2026-10-28 起 local 有 overrides，漏了就会把 0.45 以下的同义对判成「没出卡片」。
java -jar server/target/find-it-campus-0.0.1-SNAPSHOT.jar --server.port=18086 \
  --findit.pair.contact_unlock_threshold=0.00 --findit.pair.contact_direct_threshold=0.01 \
  --findit.pair.notify_threshold=0.00 --findit.pair.completed_visible_threshold=0.00 \
  --findit.pair.overrides.local.contact_unlock_threshold=0.00 \
  --findit.pair.overrides.local.contact_direct_threshold=0.01 \
  --findit.pair.overrides.local.notify_threshold=0.00 \
  --findit.pair.overrides.local.completed_visible_threshold=0.00

FINDIT_API_BASE=http://localhost:18086 node qa/datasets/pair-synonyms/run-synonyms.mjs local

# 3) 语义引擎：同一条 shell 内注入 key 后换端口重启，再跑一遍
FINDIT_API_BASE=http://localhost:18087 node qa/datasets/pair-synonyms/run-synonyms.mjs deepseek

# 4) 统计
node qa/datasets/pair-synonyms/analyze-synonyms.mjs

# 5) 默认值下的可见性（不加任何 --findit.pair.* 覆盖）
FINDIT_API_BASE=http://localhost:18088 node qa/datasets/pair-synonyms/verify-synonyms.mjs local
FINDIT_API_BASE=http://localhost:18089 node qa/datasets/pair-synonyms/verify-synonyms.mjs deepseek
```

> `DEEPSEEK_API_KEY` 只放进程环境变量，不进命令行参数、不进仓库、不进 `results/`。

## 纪律

1. **先落分、后算阈值**：采集轮的全放行阈值只影响可见性，不影响分数；不要用建议值去采分。
2. **保持率必须用同一物品的 O↔O 做分母**，不要用「另一对的分数」或写死 1.0 —— 本地引擎的原文基线并不总是 1.00（S4 是 0.87）。
3. **没出卡片 ≠ 0 分**：采集轮已把两套阈值压到 0；如果仍然没卡片，说明 pair 根本没建（召回漏），要单独记，不能当成 0 分混进均值。
4. 每次改样本都必须同时重跑两套引擎；只跑一套的保持率对比没有意义。
