# 面 B：审核闸门

被测：候选包（`30002de`），真实 `deepseek-chat`（本机 secret store 的 35 字符 key，仅注入进程环境变量），测试库 `findit_m6`。原始行：[`raw/part1-evidence.txt`](raw/part1-evidence.txt)；提交体：[`raw/review-cases-ai.json`](raw/review-cases-ai.json)。

## 1. `review.mode=ai`：真实模型

| 用例 | id | 发布响应 | 最终 DB | 公共列表 | 他人详情 | 判定 |
|---|---|---|---|---|---|---|
| 正常黑色钱包 | 3 | 200 / `PENDING` | `APPROVED / DEEPSEEK / deepseek-chat / attempts=1 / manual=0 / is_visible=1` | 在列 | 200 | **通过并上架** |
| 身份信息为主、无物品特征 | 4 | 200 / `PENDING` | `REJECTED / DEEPSEEK / attempts=1 / is_visible=0`，理由「改写后未保留任何物品特征，无法完成去身份化」 | 不在列 | 404 / `20001` | **拒绝，不上架** |
| 仅「找东西」、无特征 | 5 | 200 / `PENDING` | `REJECTED / DEEPSEEK / is_visible=0`，理由「规则双检命中：手机号/证件号/邮箱/QQ」 | 不在列 | 404 / `20001` | **拒绝，不上架** |

正常用例的展示文本（落库值）：

```
title       = 图书馆丢失黑色钱包
description = 在图书馆三楼自习区靠窗位置丢失一个黑色皮质钱包，内有工行卡一张，请捡到的同学联系。
raw_title   = 图书馆丢失黑色钱包
```

- 展示文本不含手机号/姓名；`review_engine=DEEPSEEK`、`review_model=deepseek-chat`。
- 审核审计 `t_review_audit` 该记录 1 行（决策/引擎/模型/耗时/token，无原文）。
- 审核通过后触发配对打分（`t_pair_score` 有该记录的行）。
- 两条 REJECTED 的公共列表均不可见、他人详情 404；`is_visible=0`。

## 2. AI 连续失败 → 转人工

真实 key 保留，`FINDIT_LLM_BASE_URL=http://127.0.0.1:19099/v1`（本机未监听端口），制造真实 HTTP 连接失败：

| id | 最终状态 | attempts | manual_required | is_visible | 理由 | pair 行 |
|---|---|---|---|---|---|---|
| 6 | `PENDING` | 3 | 1 | 0 | `AI 审核连续失败（HTTP_ERROR），记录保持待审并转人工；不会自动上架` | 0 |

**结论：通过。** 失败不自动上架、不触发配对，停在待审并转人工。

## 3. `review.mode=off` 回归

同一候选包，显式 `FINDIT_REVIEW_MODE=off`（默认值也是 off，本单未改默认）：

| id | 发布响应 | DB | 公共列表 | 他人详情 |
|---|---|---|---|---|
| 7 | 200 / `APPROVED`（同步） | `APPROVED / OFF / is_visible=1 / raw_title=NULL / match_title=NULL` | 在列 | 200 |

发布体含姓名「张三」、手机号 `13800000009`、门牌 `201`，mode=off 下按 v2.0 原路径原样上架，**无审核改写、无 raw/match 双通道**——与 v2.0.0 行为一致。

**结论：通过。** `mode=off` 新提交仍走原路径上架，无回归；`review.mode` 默认值保持 `off`。
