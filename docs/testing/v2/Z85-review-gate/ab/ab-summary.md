# Z85 改写 A/B 实测

| 引擎 | 通道 | 正例一致率 | 负例误配率 | top1 命中率 |
|---|---|---|---|---|
| DEEPSEEK | BEFORE | 1.0000 | 0.0000 | 1.0000 |
| DEEPSEEK | AFTER | 1.0000 | 0.2000 | 1.0000 |
| LOCAL_KEYWORD | BEFORE | 0.2000 | 0.0000 | 1.0000 |
| LOCAL_KEYWORD | AFTER | 0.0000 | 0.0000 | 1.0000 |

> BEFORE=match_text（生产出分口径）；AFTER=display_text（反事实单通道）。原始分数见 ab-result.json / ab-samples.csv。
