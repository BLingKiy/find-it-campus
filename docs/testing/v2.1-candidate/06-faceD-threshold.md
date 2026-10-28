# 面 D：阈值与卡片可见性

被测：候选包（`30002de`），`qa/datasets/pair-calibration/pairs.json` 原样使用（未改标注集）。每跑一个样本前清空业务表，保证 `lowConfidenceCount` 不被其他样本污染。原始行：[`raw/part1-evidence.txt`](raw/part1-evidence.txt)。

## 1. 配置口径（`server/src/main/resources/application.yml`，未改）

| 引擎 | notify | contact_unlock | contact_direct | completed_visible |
|---|---|---|---|---|
| 默认（DEEPSEEK） | 0.90 | **0.80** | **0.95** | 0.95 |
| `overrides.local`（LOCAL_KEYWORD） | 0.90 | **0.45** | **0.90** | 0.90 |

卡片可见性 = `score >= contact_unlock`；`DIRECT` = `score >= contact_direct`；`AUTHORIZABLE` = unlock ≤ score < direct；低于 unlock 只给 `lowConfidenceCount`，不渲染卡片。

## 2. 实测

| 样本 | 引擎 | 分数 | tier | 卡片 | lowConfidenceCount | 判定 |
|---|---|---|---|---|---|---|
| P01 丢失/捡到小米充电宝 | LOCAL_KEYWORD | **0.73** | `AUTHORIZABLE` | **可见 1 张** | 0 | 通过：**不再「匹配结果看不到」** |
| P13 黑色双肩包 vs 女士手提包 | LOCAL_KEYWORD | 低于 0.45（未取具体分） | 低于门槛 | **不可见** | 1 | 通过：只计数 |
| P01 丢失/捡到小米充电宝 | DEEPSEEK | **0.98** | `DIRECT` | 可见 1 张 | 0 | 通过：直看档 |
| P06 移动电源 vs 充电宝 | DEEPSEEK | **0.92** | `AUTHORIZABLE` | 可见 1 张 | 0 | 通过：需对方授权档 |
| P13 黑色双肩包 vs 女士手提包 | DEEPSEEK | 低于 0.80（未取具体分） | 低于门槛 | **不可见** | 1 | 通过：只计数 |

- 截图：
  - [`shots/06-faceD-local-authorizable-73.png`](shots/06-faceD-local-authorizable-73.png)：LOCAL 0.73，卡片可见，文案「可能相关 / 73% / 已降级」。
  - [`shots/07-faceD-deepseek-direct-98.png`](shots/07-faceD-deepseek-direct-98.png)：DEEPSEEK 0.98，卡片可见，文案「高置信匹配 / 98%」。
- 低分样本的详情页只出现「存在 1 条可能相关 / 匹配度未达到展示门槛，仅显示数量，不展示详情与联系方式」，不渲染任何卡片或号码片段。

## 3. 三档结论

- **DIRECT**：DEEPSEEK P01 0.98 ≥ 0.95（截图 07）。
- **AUTHORIZABLE**：LOCAL P01 0.73 ∈ [0.45, 0.90)（截图 06）；DEEPSEEK P06 0.92 ∈ [0.80, 0.95)。
- **HIDDEN**：LOCAL P13 / DEEPSEEK P13 均 `card=null`、`lowConfidenceCount=1`，分数低于各自 unlock 门槛（本轮只断言「低于门槛 + 无卡片」，未把历史分数当本轮实测值）。

LOCAL 标注集最高分 0.73，按 Z-84 的 local `direct=0.90` 本来就不应出现 DIRECT 档；这是校准结论（本地关键词没有「免授权直看」的语义置信度），不是缺陷。DEEPSEEK 档位完整覆盖三档。

**结论：通过。** 三档分数与卡片可见性符合 Z-84 校准结论，P01 在 LOCAL 降级态下 73% 仍可见（0.45 门槛），不再出现「匹配结果看不到」。
