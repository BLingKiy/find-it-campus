# 复审工具（review/recheck-2026-11-18）

本目录只放**复审用的只读 harness**，不参与业务构建。所有命令从仓库根目录执行；先跑一次
`mvn` 让 `server/target/classes` 存在。

环境：JDK 17、Node 22（与 `README.md` 的基线一致）；不需要 MySQL、不需要 DeepSeek key、不需要 Spring 上下文。

## 1. 后端全量测试（报告 §1.2）

```powershell
cd server
.\mvnw.cmd -o -B test
# 期望：Tests run: 313, Failures: 0, Errors: 0, Skipped: 1；BUILD SUCCESS
```

## 2. MaskProbe：遮蔽规则（报告 P1-2、C-1）

```powershell
# 仓库根目录
cd server; .\mvnw.cmd -o -B -DskipTests compile; cd ..
javac -encoding UTF-8 -cp server\target\classes -d build\review-tools docs\review\tools\MaskProbe.java
java -cp ".;server\target\classes;build\review-tools" MaskProbe
```

关注：

- `mobile-space` / `mobile-dash` / `mobile-zero` / `mobile-full` → `changed=true`（分隔符/全角/零宽手机号已修）；
- `qq-space` / `qq-dash` / `qq-cn` / `wx` → `changed=false`（报告 P1-2 的漏检）；
- `图书馆三楼` → `图书馆…`、`三教一楼女厕所` → 原样（报告 C-1）。

## 3. GateProbe：发布闸门规则双检（报告 P1-1、P1-2）

```powershell
javac -encoding UTF-8 -cp server\target\classes -d build\review-tools docs\review\tools\GateProbe.java
java -cp ".;server\target\classes;build\review-tools" GateProbe
```

关注：

- `时间：昨天 14:30` → `hits=[手机号/证件号/邮箱/QQ]`（误拒，P1-1）；`时间:昨天 14:30` → `hits=[]`（对照）；
- `QQ 123 456` / `微信 abc_123456` → `hits=[]`（漏检，P1-2）；
- `第三教学楼 201` → `精准门牌/房间/座位`；`第三教学楼三楼` → `[]`（与 C-1 的「楼层口径」对照）。

## 4. Node 数据核对（报告 P0-B、P1-5、P1-6）

```powershell
node docs\review\tools\schema-count.mjs   # TABLES 8 COLS 117 CHECK 29 FK 10
node docs\review\tools\calibrate.mjs      # 0.40: 8/10, 6/10；0.45: 8/10, 9/10
node docs\review\tools\pair-state.mjs     # local 20/20、syn 47/48 degraded=true + READY
```

## 说明

- 这些 harness 只依赖 JDK/Node 标准库与已编译的 `server/target/classes`，不写库、不联网、不改仓库。
- 报告中的行号以 `main@d21081b` 为准；若后续分支改动导致行号漂移，以符号名（类/方法/键）为准。
