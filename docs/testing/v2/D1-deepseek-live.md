# D1 配对打分 · 真实 DeepSeek 调用证据

任务：[ZHYYKFX-54](https://app.hifox.com/nhnok/task/ZHYYKFX-54) · 分支 `feat/v2-D1-pair-scoring`
复核（第 7 组重跑）：[ZHYYKFX-64](https://app.hifox.com/nhnok/task/ZHYYKFX-64) · 证据分支 `feat/v2-gate2-evidence`

## 结论

**已执行（2026-10-28 07:35，Asia/Shanghai）—— 9 行真实调用，判据全部满足。**

| 判据 | 要求 | 实测 |
|---|---|---|
| 至少 1 行真实调用 | `engine='DEEPSEEK'` | **9 行**（三档样本两两成对，无一行降级） |
| 延迟 | `latency_ms > 0` | 457–1459ms，9/9 |
| token | `prompt_tokens > 0` 且 `completion_tokens > 0` | prompt 206–245、completion 25–33，9/9 |
| 分数 | `score ∈ [0,1]` | 0.0000–1.0000，9/9 |
| 未降级 | `degraded_reason IS NULL` | 9/9 为 NULL |
| 提示词版本 | `prompt_version='pair-score-v1'` | 9/9 |

原始输出：`docs/testing/v2/gate2-review/raw/g7-live-deepseek.txt`（阶段 A，真实调用）
对照输出：`docs/testing/v2/gate2-review/raw/g7-no-key-degraded.txt`（阶段 B，去掉密钥后同内容复跑）

## 本轮执行记录（2026-10-28 · 第 7 组重跑）

### 环境与密钥

| 项 | 值 |
|---|---|
| 密钥来源 | `%USERPROFILE%\.hifox-secrets\deepseek_api_key.txt`（**只写路径，值与任何片段都不出现**） |
| 可读性 | `test -s` 通过；`wc -c` = **35**；注入后 `key-length=35`（`raw/g7-key-read.txt`） |
| 注入方式 | **同一条 shell 进程内** `export DEEPSEEK_API_KEY="$(cat <上面的路径>)"` → 立刻 `exec java -jar … --server.port=18082`；中间不换会话、不用后台守护、不写脚本参数 |
| LLM 端点 | 默认 `https://api.deepseek.com/v1`（`findit.llm.base-url`） |
| 模型 | `deepseek-chat`（`findit.llm.model` 配置值；打分器不把响应里的 `model` 落库，故本列填配置值，不臆造回显值） |
| 数据库 | 新建 `findit_gate2_64_g7`：`db/schema.sql` + `db/seed.sql` 后清空业务表，仅留 3 账号 / 9 分类，`t_record`、`t_pair_score` 均为 0 行 |
| 服务端 | `find-it-campus-0.0.1-SNAPSHOT.jar`，端口 18082，`/api/health` → `status=UP / db=UP` |

### 三档结果（诊断：同档 LOST ↔ 同档 FOUND）

| 档位 | model | 时间（Asia/Shanghai） | latency_ms | prompt_tokens | completion_tokens | score | engine |
|---|---|---|---|---|---|---|---|
| 高相似（充电宝：同标题同描述） | deepseek-chat | 2026-10-28 07:35:38 | 1459 | 245 | 29 | 1.0000 | DEEPSEEK |
| 中相似（雨伞：同物不同措辞） | deepseek-chat | 2026-10-28 07:35:39 | 600 | 206 | 28 | 0.8500 | DEEPSEEK |
| 低相似（保温杯 ↔ 蓝牙耳机） | deepseek-chat | 2026-10-28 07:35:42 | 775 | 215 | 28 | 0.0000 | DEEPSEEK |

全部 9 行（3 条 LOST × 3 条 FOUND，异步打分按 worker 并发执行）见 `raw/g7-live-deepseek.txt`。
三档分差（1.00 / 0.85 / 0.00）说明真实模型对「同物同描述」「同物不同说法」「完全不同的物品」做了区分，不是常数分。

### 与 LOCAL_KEYWORD 的同内容对照

停掉进程、**去掉密钥重启**（`KEY_STATE=empty(len=0)`），用**完全相同的三份 LOST 正文**再发一次，新记录与上一轮同一条 FOUND 成 pair：

| 档位 | DEEPSEEK（阶段 A） | LOCAL_KEYWORD（阶段 B） | 差值 |
|---|---|---|---|
| 高相似 | 1.0000（latency 1459 / 245+29） | 1.0000（`EMPTY_KEY`，latency/tokens 均 NULL） | 0 |
| 中相似 | **0.8500**（600 / 206+28） | **0.1600**（`EMPTY_KEY`） | **+0.69** |
| 低相似 | 0.0000（775 / 215+28） | 0.0100（`EMPTY_KEY`） | −0.01 |

- 高相似档两者都满分：同标题同描述时关键词路径本来就能命中，**不是**模型在偷懒。
- 中相似档差距最大：关键词只共享「伞 / 体育馆」这类字面，模型能识别「同一把折叠伞」的语义；这一格是本轮「真实调用有质量增量」的直接证据。
- 阶段 B 仍是 `HTTP 200`（3 次发布 0.167s / 0.054s / 0.061s）、`engine=LOCAL_KEYWORD`、`degraded_reason=EMPTY_KEY`、`latency_ms`/`tokens` 全 NULL（不估算），记录侧 `pairState=DEGRADED`、`pairDegraded=true`。

## 历史记录（2026-10-27，已由本轮满足）

D1 收口时本机三处 `DEEPSEEK_API_KEY` 均为空（机器级 / 用户级 / 进程级），当时只能记为「未执行 · 环境缺 key」。
用本机假端点（`llm-stub.js`）得到的 `engine=DEEPSEEK / latency_ms=1 / tokens=100+10` 一行只证明「解析 → 校验 → 落库」链路通，
**不能**代替真实调用；本轮已用真实端点补上，那行假端点数据仅作为链路自测记录保留，不进本表。

## 零明文约束（本轮已核）

- 证据文件里没有 `Authorization` 头、完整 prompt、完整 response、用户 `contact`、附言原文。
- `raw/g7-*.txt` 四个数据文件中宽松前缀 `sk-` 命中数 = 0；真实密钥形态 `sk-[A-Za-z0-9]{24,}` 对**全库**（tracked + 未跟踪）扫描 0 命中，命令与输出见 `raw/g7-secret-scan.txt`。密钥只以「路径 + 字节数 35 + 长度 35」出现。
- 手机号一律为虚构的 `139xxxxxxxx`；账号为 seed 的 `demo01~03`。
