# D1 配对打分 · 真实 DeepSeek 调用证据

任务：[ZHYYKFX-54](https://app.hifox.com/nhnok/task/ZHYYKFX-54) · 分支 `feat/v2-D1-pair-scoring`

## 结论

**未执行 · 环境缺 key。**

本机运行环境没有 DeepSeek 凭据：机器级、用户级、进程级三处环境变量 `DEEPSEEK_API_KEY` 均为空
（已用 `[Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY','Machine'/'User')` 与 `$env:` 实读，
输出为空字符串）。

按派工单要求：**不编造真实调用证据**。本文件只保留"补测时需要做什么、怎么判定通过"，
以及在不使用真实密钥的前提下已经验证到的部分（解析/校验/落库路径）。

## 已经验证到的部分（不是真实调用）

用本机假端点（`llm-stub.js`，127.0.0.1:18099）返回一个合法 JSON 响应，走同一条
`DeepSeekPairScorer` 代码路径，得到的行：

```text
33 | lost=27 | found=28 | score=0.8800 | engine=DEEPSEEK | degraded_reason=NULL
   | latency_ms=1 | prompt_tokens=100 | completion_tokens=10 | prompt_version=pair-score-v1 | status=IGNORED
```

这证明：**响应解析 → 分数范围校验 → usage 落库 → prompt_version 写入 → 不覆盖 pair.status** 这条链路是通的。
但它**不能**代替真实调用证据：模型名、真实延迟、真实 token 数、真实分数分布都必须由验收方在有 key 的环境补测。

## 补测步骤（有 key 时，10 分钟内可完成）

1. 准备环境（**不要把密钥写进任何文件**）：由部署环境/CI secret 把 `DEEPSEEK_API_KEY` 注入当前进程
   （只在该进程内可见；不要 echo、不要写进脚本或配置文件），然后启动应用：

   ```powershell
   java -jar server/target/find-it-campus-0.0.1-SNAPSHOT.jar --server.port=18082
   ```

   启动日志里不应出现任何密钥痕迹（代码只把它放进 `Authorization` 请求头）。

2. 用三档样本各发布一对（LOST + FOUND），内容分别是「高相似 / 中等相似 / 低相似」。
   高相似可用同标题同描述，低相似可用完全不同的物品。
3. 等待异步打分（默认 `worker-threads=2`，3 对约 2–5 秒），然后查库：

   ```sql
   SELECT id, lost_id, found_id, score, engine, degraded_reason,
          latency_ms, prompt_tokens, completion_tokens, prompt_version, status
   FROM t_pair_score
   WHERE lost_id IN (…) AND found_id IN (…);
   ```

4. **验收判据**（来自任务书）：
   - 至少 1 行 `engine='DEEPSEEK'`；
   - 该行 `latency_ms > 0`；
   - `prompt_tokens + completion_tokens > 0`；
   - `score` 在 `[0,1]`；
   - `degraded_reason IS NULL`、`prompt_version='pair-score-v1'`；
   - 其余两档允许降级（网络/额度），但若降级必须 `engine='LOCAL_KEYWORD'` 且 `degraded_reason` 非空。

5. 把结果填进下面的表格（**只填这些字段**；不要贴响应体、不要贴请求头、不要贴密钥）：

   | 档位 | model | 时间（Asia/Shanghai） | latency_ms | prompt_tokens | completion_tokens | score | engine |
   |---|---|---|---|---|---|---|---|
   | 高相似 | deepseek-chat | 待补测 | | | | | |
   | 中相似 | deepseek-chat | 待补测 | | | | | |
   | 低相似 | deepseek-chat | 待补测 | | | | | |

   模型名以实际配置 `findit.llm.model` 为准（默认 `deepseek-chat`）。

## 零明文约束（补测时同样适用）

- 不记录 `Authorization` 头、完整 prompt、完整 response、用户 `contact`、附言原文。
- 证据文件里 `sk-` 与「密钥变量名 = 值」必须 0 命中；手机号一律用假号。
- 若需要贴请求/响应状态码，只贴状态码（200/401/504），不贴 body。

## 与降级证据的关系

- 真实调用不可用时，`D1-degradation.md` 已证明：空 Key / 401 / 超时 / 非 JSON / 越界 / 5xx / 熔断
  七种情形都会落 `LOCAL_KEYWORD` + 原因 + 分数，接口不失败、前端能显示「已降级」。
- 因此**缺这一格不影响幂等与降级两条链路的验收**；它只影响"打分质量"这一项的证据完整性。
