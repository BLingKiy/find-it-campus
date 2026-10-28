#!/usr/bin/env bash
# v2 · F3-R 项 4：P2-2 缓存头实测（curl -i 原始响应头，不经包装库）。
#
# 口径（ZHYYKFX-74 范围项 4）：/api/records/{id}、/api/my/records、/api/notifications、
# /api/match、/api/pairs/{id} 逐个断言 Cache-Control 含 no-store 与 Vary: Cookie；
# 401 短路响应同样要带；/api/pairs/{id}/contact 的 no-store 不回退。
#
# 前置：后端 18080 已起、库为 seed 初始状态。pairId=1 是 seed 里 demo01(lost#1)↔demo02(found#13)
# 的 0.96 CONTACTED 对，两端身份都能打 contact。
# 运行：bash qa/f3r/cache-headers.sh > qa/reports/f3r-cache-headers.txt
#
# 判据：任何一行 Cache-Control 缺 no-store 或 Vary 缺 Cookie 记 FAIL。
# 请求体走 UTF-8 文件而不是命令行 -d：Git Bash 会把命令行中文按本机代码页送出，
# 服务端收到就是 "Invalid UTF-8 middle byte"（本轮实测撞到，400/10001）。

set -u
API="${API:-http://localhost:18080}"
J1=$(mktemp); J2=$(mktemp); BF=$(mktemp); BF2=$(mktemp)
trap 'rm -f "$J1" "$J2" "$BF" "$BF2"' EXIT
printf '%s' '{"text":"图书馆 黑色钱包 银行卡","type":"FOUND","topN":5,"minScore":0}' >"$BF"
printf '%s' '{"text":"操场 白色 iPhone 透明壳","type":"LOST","topN":5,"minScore":0}' >"$BF2"

login() { curl -s -c "$2" -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"studentNo\":\"$1\",\"password\":\"Demo@123456\"}" -o /dev/null -w '%{http_code}'; }

C1=$(login demo01 "$J1"); C2=$(login demo02 "$J2")
echo "# F3-R 项4 · P2-2 缓存头实测 · $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "# API=$API  pairId=${PAIR_ID:-1}"
echo "# 登录 demo01 → HTTP $C1 / demo02 → HTTP $C2"
echo

ROWS=()
show() { # $1=label $2=method $3=path $4=jar(optional) $5=bodyfile(optional)
  local label="$1" method="$2" path="$3" jar="${4:-}" bodyfile="${5:-}"
  local f; f=$(mktemp)
  # -i 把原始响应头与报文一起收到文件（Git Bash 上 -D /dev/stderr 收不到头）
  if [ -n "$bodyfile" ]; then
    curl -sS -i -X "$method" "$API$path" ${jar:+-b "$jar"} -H 'Content-Type: application/json; charset=utf-8' --data-binary "@$bodyfile" >"$f"
  elif [ -n "$jar" ]; then
    curl -sS -i -X "$method" "$API$path" -b "$jar" >"$f"
  else
    curl -sS -i -X "$method" "$API$path" >"$f"
  fi
  local status cc vary
  status=$(head -1 "$f" | tr -d '\r' | awk '{print $2}')
  cc=$(sed -n '/^\r*$/q;p' "$f" | grep -i '^cache-control:' | tr -d '\r' | paste -sd '; ' -)
  vary=$(sed -n '/^\r*$/q;p' "$f" | grep -i '^vary:' | tr -d '\r' | paste -sd '; ' -)

  echo "----- $label"
  echo "\$ curl -sS -i -X $method '$API$path'${jar:+ -b <会话 Cookie>}${bodyfile:+ --data-binary @<UTF-8 请求体>}"
  cat "$f"
  rm -f "$f"
  echo
  local ccok=nostore varyok=Cookie verdict=PASS
  printf '%s' "${cc:-}" | grep -qi 'no-store' || { ccok=MISSING; verdict=FAIL; }
  printf '%s' "${vary:-}" | grep -qi 'cookie' || { varyok=MISSING; verdict=FAIL; }
  echo "→ HTTP $status | ${cc:-Cache-Control: <缺失>} | ${vary:-Vary: <缺失>} | 判定 $verdict（no-store=${ccok} / Vary Cookie=${varyok}）"
  echo
  ROWS+=("$verdict|$status|$label|${cc:-<缺失>}|${vary:-<缺失>}")
}

echo "================= 原始响应头逐条 ================="
echo
show 'A1 匿名 GET /api/records/1（200 遮蔽详情）'           GET '/api/records/1'
show 'A2 本人 GET /api/records/1（200 明文详情）'            GET '/api/records/1' "$J1"
show 'A3 非所有者 GET /api/records/1（200 遮蔽详情）'         GET '/api/records/1' "$J2"
show 'B1 本人 GET /api/my/records（200 个人集合）'            GET '/api/my/records' "$J1"
show 'B2 非所有者 GET /api/my/records（200 另一人集合）'       GET '/api/my/records' "$J2"
show 'C1 本人 GET /api/notifications（200）'               GET '/api/notifications' "$J1"
show 'C2 非所有者 GET /api/notifications（200）'             GET '/api/notifications' "$J2"
show 'D1 本人 POST /api/match（200）'                       POST '/api/match' "$J1" "$BF"
show 'D2 非所有者 POST /api/match（200）'                   POST '/api/match' "$J2" "$BF2"
show 'E1 参与者 GET /api/pairs/{id}/contact（不回退断言）'    GET "/api/pairs/${PAIR_ID:-1}/contact" "$J1"
show 'E2 对方 GET /api/pairs/{id}/contact（不回退断言）'      GET "/api/pairs/${PAIR_ID:-1}/contact" "$J2"
show 'E3 匿名 GET /api/pairs/{id}/contact（401 短路带头）'    GET "/api/pairs/${PAIR_ID:-1}/contact"
show 'E4 不存在 pairId GET /api/pairs/999999/contact（404）'  GET '/api/pairs/999999/contact' "$J1"
show 'F1 匿名 GET /api/my/records（401 短路）'               GET '/api/my/records'
show 'F2 匿名 GET /api/notifications（401 短路）'            GET '/api/notifications'
show 'F3 匿名 POST /api/match（401 短路）'                   POST '/api/match' '' "$BF"
show 'F4 匿名 GET 不存在的记录（404 统一体）'                 GET '/api/records/999999'
show 'G1 匿名 GET /api/records（200 公共列表）'              GET '/api/records'
show 'H1 非所有者 DELETE /api/records/1（403 越权短路）'      DELETE '/api/records/1' "$J2"
show 'H2 匿名 GET /api/health（/api/** 前缀覆盖）'           GET '/api/health'

echo "================= 汇总表 ================="
printf '%s\n' "${ROWS[@]}" | awk -F'|' '{printf "| %s | %s | %s | %s | %s |\n", $1, $2, $3, $4, $5}' \
  | sed '1i | 判定 | HTTP | 用例 | Cache-Control | Vary |\n|---|---|---|---|---|'
FAILS=$(printf '%s\n' "${ROWS[@]}" | grep -c '^FAIL')
echo
echo "断言用例数=${#ROWS[@]}，FAIL=$FAILS"
