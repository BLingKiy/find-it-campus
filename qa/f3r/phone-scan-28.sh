#!/usr/bin/env bash
# v2 · F3-R 组1（任务项 2）：28 探针手机号泄漏全局扫描 · F3-R 固定入口。
#
# 用途：在 F3-R 修复后的基线上复跑 F2 的 28 条探针（匿名列表 2 页、匿名详情 11 个 id、
#      匿名 POST /api/match、非所有者详情 6 个 id、双方 /api/match 候选、三账号通知正文），
#      断言非本人 / 匿名响应体与通知里 1[3-9]\d{9} 0 命中。
#
# 为什么是个 shim：这 28 条探针的实现在 qa/api/f2-scan.mjs（F2 资产），F3-R 是复跑同一份脚本，
# 不另写一份 copy —— 两份探针会漂移，F3-R 的读数就不再等于 F2 的口径。本文件只做入口与转发，
# 不改变任何断言、不改变输出路径。
#
# 前置：后端 18080 已起（FINDIT_API_BASE 可改），库为 db/schema.sql + db/seed.sql 的初始态；
#      账号 demo01/demo02/demo03（密码 Demo@123456）在 seed 内。
# 运行：bash qa/f3r/phone-scan-28.sh
#       （等价于 node qa/api/f2-scan.mjs，参数原样转发）
# 期望输出：首行「# F2 手机号泄漏全局扫描 · <ISO 时间>」，逐条「PASS … 命中 0」共 28 条，
#          末尾「- 探针：28 个；命中：0」「- 结论：PASS（0 命中）」。
#          落盘 qa/reports/f2-phone-scan.{txt,json} 与 docs/testing/v2/F2-phone-scan.txt
#          —— **重跑会覆盖 F2 那轮的原始证据文件**；2026-10-28 F3-R 那轮的冻结归档见
#          qa/reports/f3r-2026-10-28-phone-scan-28.txt。覆盖后可用
#          `git checkout -- docs/testing/v2/F2-phone-scan.txt qa/reports/f2-phone-scan.*` 还原。
# 退出码：沿用 qa/api/f2-scan.mjs —— 0 = 0 命中；1 = 有命中。
#
# 注：本脚本用 bash 启动 node，Windows 下请用 Git Bash（与 cache-headers.sh 同一套环境）。
set -u
here="$(cd "$(dirname "$0")" && pwd)"
exec node "$here/../api/f2-scan.mjs" "$@"
