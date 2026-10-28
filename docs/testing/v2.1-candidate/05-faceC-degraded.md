# 面 C：LLM 降级、10 分钟重扫与恢复升级

被测：候选包（`30002de`），测试库 `findit_m6`。原始行：[`raw/part2-evidence.txt`](raw/part2-evidence.txt)；调度日志：[`raw/faceC-backend-log.txt`](raw/faceC-backend-log.txt)；截图：[`shots/08-faceC-degraded-banner.png`](shots/08-faceC-degraded-banner.png)。

## 1. 无 key：LOCAL_KEYWORD 降级 + 页面提示

发布 qa 标注样本 P01（LOST/FOUND 两端）后：

```
pair: score=0.7300  engine=LOCAL_KEYWORD  degraded_reason=EMPTY_KEY  status=NEW
record(lost): pair_state=DEGRADED  status=PENDING
record(found): pair_state=DEGRADED  status=PENDING
```

真实浏览器（demo02 所有者）打开 `/records/1`：

- `data-testid="pair-degraded-banner"` 存在；
- 文案：`已降级 / 当前为关键词匹配，同义/近义描述可能匹配不到，也可能出现不相关的结果，结果显示仅供参考。`；
- 卡片 73%、`可能相关`、`已降级` 徽标均渲染。

**结论：LLM 不可用时落 `engine=LOCAL_KEYWORD`（`EMPTY_KEY`），页面有降级提示。**

## 2. 恢复：手动降级重扫 → DEEPSEEK

重启为真实 key（`review.mode=off`），两端真实确认 + 公开联系方式：

| 项 | 恢复前 | 恢复后 |
|---|---|---|
| pair score | 0.7300 | **0.9800** |
| engine | `LOCAL_KEYWORD` | **`DEEPSEEK`** |
| degraded_reason | `EMPTY_KEY` | **`NULL`** |
| status | `MATCHED` | `MATCHED`（保留） |
| lost_confirmed_at / found_confirmed_at | `2026-10-29 03:41:27` / `03:41:28` | 同左 |
| lost_unlocked_at / found_unlocked_at | `03:41:28` / `03:41:28` | 同左 |
| lost_unlock_note / found_unlock_note | `m6 保留性验证 lost` / `... found` | 同左 |
| record pair_state | `DEGRADED` | **`READY`** |

为让 60 分钟逐对冷却到期，测试库把 `t_pair_score.updated_at` 与 `t_record.pair_scored_at` 回拨 61 分钟（数据准备，非代码改动），然后：

```
POST http://127.0.0.1:8088/api/ops/pair-scan?scope=degraded
X-Ops-Token: <本轮进程环境变量 OPS_SCAN_TOKEN>
HTTP/1.1 200
{"code":0,"message":"降级重扫完成","data":{"scanned":2,"rescored":1,"degraded":0,
 "skipped":1,"limited":false,"elapsedMs":2569,"trigger":"http-degraded",
 "scope":"DEGRADED","skipReason":null,...}}
```

**结论：手动降级重扫可跑，恢复后引擎升级 `DEEPSEEK`，MATCHED / 确认时间 / 解锁时间 / 附言逐字段保留。**

## 3. 配置开关可回退

先把同一 pair 行重置为 `LOCAL_KEYWORD + EMPTY_KEY`、记录置 `DEGRADED`，仅通过本次进程的 `SPRING_APPLICATION_JSON` 改调度（**`application.yml` 默认值未改**：`degraded-scan-enabled=true`、`degraded-scan-cron=0 */10 * * * *`）：

| 进程配置 | 25 秒观察 | pair 结果 |
|---|---|---|
| `degraded-scan-enabled=false`，cron `*/10 * * * * *` | `trigger=degraded-scheduled` 日志 **0** 条 | 仍 `0.7300 / LOCAL_KEYWORD / EMPTY_KEY` |
| `degraded-scan-enabled=true`，cron `*/10 * * * * *` | 定时重扫日志 **9** 条；升级日志 3 条 | `0.9800 / DEEPSEEK / NULL`，`MATCHED` + 确认/解锁字段保留 |

启用后的真实日志（节选）：

```
pair score upgraded event=pair_score_upgraded recordId=1 pairId=1
  trigger=degraded-scheduled oldEngine=LOCAL_KEYWORD oldScore=0.7300
  newEngine=DEEPSEEK newScore=0.98 degradedReason=null latencyMs=1196
pair scan done scope=DEGRADED trigger=degraded-scheduled scanned=2 rescored=1
  degraded=0 skipped=1 limited=false
```

**结论：配置开关可回退；关闭时定时重扫不执行、引擎不升级；重新打开后 10 秒级调度可完成升级，且不破坏确认/解锁状态。**

> 说明：`application.yml` 的默认调度是 `0 */10 * * * *`；本轮为了在 25 秒内可观察，用 `SPRING_APPLICATION_JSON` 把 cron 临时改为 10 秒、并切换 enabled，未写入任何配置文件或包内默认值。
