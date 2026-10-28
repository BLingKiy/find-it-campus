# 近似同义转换分析（pair-synonyms）

生成时间：2026-10-28T18:19:33.059Z

样本：6 件物品 × 4 种措辞，原文↔改写 18 对（O-C/O-F/O-S），交叉改写 18 对，陷阱负例 6 对。

分数保持率 = 改写对分数 / 同一物品原文对原文（O↔O）分数。

## LOCAL_KEYWORD

- 阈值：unlock 0.45 / direct 0.9
- 保持率（18 对）：min 0.41 / p25 0.61 / median 0.64 / p75 0.67 / max 0.74 / mean 0.628
  - OC（n=6）：median 0.64 / mean 0.646 / min 0.52
  - OF（n=6）：median 0.63 / mean 0.592 / min 0.41
  - OS（n=6）：median 0.64 / mean 0.647 / min 0.61
- 过 unlock 比例：94.4%（17/18）；过 direct 比例：0.0%
- 交叉改写（n=18）：min 0.22 / median 0.50 / max 0.76，过 unlock 72.2%
- 陷阱：min 0.33 / max 0.85；过 unlock 83.3%；过 direct 0.0%

## DEEPSEEK

- 阈值：unlock 0.8 / direct 0.95
- 保持率（18 对）：min 0.95 / p25 0.95 / median 0.98 / p75 0.98 / max 1.00 / mean 0.972
  - OC（n=6）：median 0.98 / mean 0.973 / min 0.95
  - OF（n=6）：median 0.98 / mean 0.977 / min 0.95
  - OS（n=6）：median 0.96 / mean 0.965 / min 0.95
- 过 unlock 比例：100.0%（18/18）；过 direct 比例：100.0%
- 交叉改写（n=18）：min 0.95 / median 0.98 / max 0.98，过 unlock 100.0%
- 陷阱：min 0.10 / max 0.72；过 unlock 0.0%；过 direct 0.0%

## 单套阈值反证

| 场景 | unlock | 同义对过阈 | 陷阱过阈 | 同义对直看 | 陷阱直看 |
|---|---|---|---|---|---|
| deepseekThresholdsOnLocal | 0.8 | 0.0% | 50.0% | 0.0% | 0.0% |
| oldSharedThresholdsOnLocal | 0.75 | 0.0% | 83.3% | 0.0% | 0.0% |
| deliveredLocalOnLocal | 0.45 | 94.4% | 83.3% | 44.4% | 83.3% |
| proposedLocalOnLocal | 0.45 | 94.4% | 83.3% | 0.0% | 0.0% |
| localThresholdsOnDeepseek | 0.45 | 100.0% | 83.3% | 100.0% | 50.0% |
| localHardFloorOnLocal | 0.86 | 0.0% | 0.0% | 0.0% | 0.0% |

