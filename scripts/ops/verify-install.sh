#!/usr/bin/env bash
# =============================================================================
# AxiFlux 私有化交付验收脚本 (Linux)
#
# 用途：在客户现场完成安装后，30 分钟内跑完本脚本即可出具验收结论。
#      全程只读：不写库、不改配置、不触发任何计费动作。
#
# 用法:
#   ./verify-install.sh --url http://127.0.0.1:8080 \
#                       --user admin --password '***' [--json out.json] [--expect-ee]
#
# 退出码: 0=全部硬性检查通过  1=有硬性检查失败  2=用法错误
# =============================================================================
set -uo pipefail

BASE_URL="http://127.0.0.1:8080"
USER=""
PASSWORD=""
JSON_OUT=""
EXPECT_EE=0
ADMIN_TOKEN="${AXIFLUX_ADMIN_TOKEN:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --url) BASE_URL="$2"; shift 2 ;;
    --user) USER="$2"; shift 2 ;;
    --password) PASSWORD="$2"; shift 2 ;;
    --json) JSON_OUT="$2"; shift 2 ;;
    --expect-ee) EXPECT_EE=1; shift ;;
    -h|--help) sed -n '1,20p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

PASS=0; FAIL=0; WARN=0; ROWS=""

record() { # record <level> <name> <detail>
  local level="$1" name="$2" detail="${3:-}"
  case "$level" in
    PASS) PASS=$((PASS+1)) ;;
    FAIL) FAIL=$((FAIL+1)) ;;
    WARN) WARN=$((WARN+1)) ;;
  esac
  printf '%-4s | %-34s | %s\n' "$level" "$name" "$detail"
  ROWS="$ROWS{\"level\":\"$level\",\"check\":\"$name\",\"detail\":\"$(printf '%s' "$detail" | tr -d '\"')\"},"
}

code() { # code <path> -> http status (000 when unreachable)
  local c
  c=$(curl -s -o /tmp/_ts_body.$$ -w '%{http_code}' --max-time 10 "$1" 2>/dev/null) || true
  [ -n "$c" ] || c=000
  printf '%s' "$c"
}

json_get() { # json_get <file> <dot-path>  例: json_get f.json data.token
  python3 -c "import json,sys
try:
 d=json.load(open(sys.argv[1]))
 for k in sys.argv[2].split('.'):
  d=d.get(k) if isinstance(d,dict) else None
 print(d if d is not None else '')
except Exception:
 print('')" "$1" "$2" 2>/dev/null || echo ""
}

have() { command -v "$1" >/dev/null 2>&1; }

echo "====================================================================="
echo " AxiFlux 私有化交付验收  $(date '+%Y-%m-%d %H:%M:%S')"
echo " 目标: $BASE_URL"
echo "====================================================================="
printf '%-4s | %-34s | %s\n' LEVEL CHECK DETAIL
echo "---------------------------------------------------------------------"

# ---- 1. 进程与端口 ----------------------------------------------------------
PORT_NUM=$(printf '%s' "$BASE_URL" | sed -E 's#.*:([0-9]+).*#\1#')
[ -n "$PORT_NUM" ] || PORT_NUM=80
LISTEN=0
if [ -r /proc/net/tcp ]; then
  HEXPORT=$(printf '%04X' "$PORT_NUM")
  grep -qi ":$HEXPORT " /proc/net/tcp && LISTEN=1
  grep -qi ":$HEXPORT " /proc/net/tcp6 2>/dev/null && LISTEN=1
elif have ss; then
  ss -lnt 2>/dev/null | grep -q ":$PORT_NUM " && LISTEN=1
fi
if [ "$LISTEN" = "1" ]; then
  record PASS "port-$PORT_NUM-listening" "listening"
elif [ "$(code "$BASE_URL/")" = "200" ]; then
  record PASS "port-$PORT_NUM-listening" "不可从本机 /proc 判定，但 HTTP 可达"
else
  record WARN "port-$PORT_NUM-listening" "未能确认监听状态（可能部署在本机之外）"
fi

# ---- 2. 健康检查 ------------------------------------------------------------
HEALTH=$(code "$BASE_URL/actuator/health")
if [ "$HEALTH" = "200" ]; then
  STATUS=$(json_get /tmp/_ts_body.$$ status)
  if [ "$STATUS" = "UP" ]; then record PASS "actuator-health" "UP"; else record FAIL "actuator-health" "HTTP 200 but status=$STATUS"; fi
elif [ "$HEALTH" = "401" ] || [ "$HEALTH" = "403" ]; then
  record WARN "actuator-health" "protected (HTTP $HEALTH) — 需认证探活，非故障"
else
  record FAIL "actuator-health" "HTTP $HEALTH"
fi

for sub in liveness readiness; do
  C=$(code "$BASE_URL/actuator/health/$sub")
  if [ "$C" = "200" ]; then record PASS "actuator-health-$sub" "200"; else record WARN "actuator-health-$sub" "HTTP $C"; fi
done

# ---- 3. 静态前端 ------------------------------------------------------------
C=$(code "$BASE_URL/")
if [ "$C" = "200" ]; then
  if grep -qi 'Axiflux\|AxiFlux' /tmp/_ts_body.$$ 2>/dev/null; then
    record PASS "console-index" "200 + 品牌标识"
  else
    record PASS "console-index" "200"
  fi
else
  record FAIL "console-index" "HTTP $C"
fi

# ---- 4. 版本与迁移 ----------------------------------------------------------
INFO=$(code "$BASE_URL/actuator/info")
if [ "$INFO" = "200" ]; then record PASS "actuator-info" "200"; else record WARN "actuator-info" "HTTP $INFO"; fi

if have docker && docker ps --format '{{.Names}}' 2>/dev/null | grep -qi postgres; then
  SCHEMA=$(docker ps --format '{{.Names}}' | grep -i postgres | head -1)
  V=$(docker exec "$SCHEMA" psql -U "${PG_USER:-postgres}" -d "${PG_DB:-Axiflux}" -tAc \
      'select version from flyway_schema_history where success order by installed_rank desc limit 1' 2>/dev/null | tr -d '\r')
  if [ -n "$V" ]; then record PASS "flyway-schema-version" "V$V"; else record WARN "flyway-schema-version" "无法读取（容器名/凭据不符）"; fi
else
  record WARN "flyway-schema-version" "非 Docker 部署，跳过"
fi

# ---- 5. 版本标识（应用内） ---------------------------------------------------
if have jar && have unzip; then :; fi

# ---- 6. 登录与令牌 ----------------------------------------------------------
TOKEN=""
USER_ID=""
if [ -n "$USER" ] && [ -n "$PASSWORD" ]; then
  LOGIN_BODY=$(printf '{"login":"%s","password":"%s"}' "$USER" "$PASSWORD")
  curl -s -o /tmp/_ts_login.$$ -w '%{http_code}' --max-time 15 \
    -H 'Content-Type: application/json' -X POST \
    -d "$LOGIN_BODY" "$BASE_URL/api/v1/auth/login" > /tmp/_ts_login_code.$$ 2>/dev/null
  LC=$(cat /tmp/_ts_login_code.$$ 2>/dev/null)
  TOKEN=$(json_get /tmp/_ts_login.$$ data.token)
  USER_ID=$(json_get /tmp/_ts_login.$$ data.userId)
  if [ "$LC" = "200" ] && [ -n "$TOKEN" ]; then
    record PASS "login" "token_len=${#TOKEN} userId=${USER_ID:-?}"
  else
    record FAIL "login" "HTTP $LC token_len=${#TOKEN}"
  fi
else
  record WARN "login" "未提供 --user/--password"
fi

auth_curl() { # auth_curl <path> -> prints "<status> <file>"
  local p="$1" c
  if [ -n "$TOKEN" ]; then
    c=$(curl -s -o /tmp/_ts_a.$$ -w '%{http_code}' --max-time 10 -H "Authorization: Bearer $TOKEN" "$BASE_URL$p" 2>/dev/null) || true
  else
    c=$(curl -s -o /tmp/_ts_a.$$ -w '%{http_code}' --max-time 10 "$BASE_URL$p" 2>/dev/null) || true
  fi
  [ -n "$c" ] || c=000
  printf '%s' "$c"
}

# ---- 7. 核心业务接口（只读） -------------------------------------------------
check_list() { # check_list <name> <path> <python-list-expr-doc>
  local name="$1" path="$2"
  local c; c=$(auth_curl "$path")
  case "$c" in
    2*) record PASS "$name" "$c $(wc -c < /tmp/_ts_a.$$ | tr -d ' ')B" ;;
    401|403) record WARN "$name" "HTTP $c（权限受限，登录账号缺少 scope）" ;;
    000) record FAIL "$name" "无法连接 $BASE_URL（服务未启动或网络不通）" ;;
    *) record FAIL "$name" "HTTP $c" ;;
  esac
}

check_list "api-agents" "/api/v1/agents"
check_list "api-tools" "/api/v1/tools"
check_list "api-templates" "/api/v1/templates"
check_list "api-sessions" "/api/v1/sessions"
check_list "api-approvals" "/api/v1/approvals"
check_list "api-scheduler" "/api/v1/scheduler/tasks"
check_list "api-skills" "/api/v1/skills"
check_list "api-audits" "/api/v1/audits/tool-executions"
check_list "api-orgs-mine" "/api/v1/orgs/mine"
check_list "api-billing-me" "/api/v1/billing/me"
check_list "api-usage-me" "/api/v1/usage/me"
check_list "api-usage-quota" "/api/v1/usage/quota"
check_list "api-config-settings" "/api/v1/config/settings"
# License 默认 enabled=false（向后兼容；M2-2 才做实机 enforce）。未启用时接口
# 404 属预期，记 WARN 而非 FAIL；启用后应 200。
LC_LIC=$(auth_curl "/api/v1/admin/license")
case "$LC_LIC" in
  2*) record PASS "api-license" "$LC_LIC" ;;
  404) record WARN "api-license" "404 license 子系统未启用（axiflux.license.enabled=false，M2-2 再验）" ;;
  401|403) record WARN "api-license" "HTTP $LC_LIC（账号缺 scope）" ;;
  *) record FAIL "api-license" "HTTP $LC_LIC" ;;
esac
check_list "api-admin-api-keys" "/api/v1/admin/api-keys"

# ---- 8. 企业版能力探针 -------------------------------------------------------
EC=$(auth_curl "/api/v1/edition")
if [ "$EXPECT_EE" = "1" ]; then
  if [ "$EC" = "200" ]; then
    record PASS "ee-edition-endpoint" "200（企业版分发）"
  else
    record FAIL "ee-edition-endpoint" "期望企业版但 HTTP $EC（404=未包含 axiflux-ee-*.jar，401=已登录账号无权限）"
  fi
elif [ "$EC" = "200" ]; then
  record PASS "ee-edition-endpoint" "200（企业版分发）"
else
  record WARN "ee-edition-endpoint" "HTTP $EC（社区版分发正常）"
fi

# ---- 9. 向量库 / 长期记忆 ---------------------------------------------------
if [ -n "$USER_ID" ]; then
  C=$(auth_curl "/api/v1/memory/$USER_ID")
  case "$C" in
    200|204) record PASS "long-term-memory" "200（用户 $USER_ID）" ;;
    402) record WARN "long-term-memory" "402 long_term_memory 不在当前套餐（产品门禁，非故障）" ;;
    400|404) record WARN "long-term-memory" "HTTP $C（向量库未配置）" ;;
    401|403) record WARN "long-term-memory" "HTTP $C" ;;
    *) record FAIL "long-term-memory" "HTTP $C" ;;
  esac
else
  record WARN "long-term-memory" "未登录，跳过"
fi

# ---- 10. 资源与容量 ---------------------------------------------------------
if have free; then record PASS "memory" "$(free -m | awk '/Mem:/{print $3"MB used / "$2"MB total"}')"; fi
DISK=$(df -h / 2>/dev/null | awk 'NR==2{print $5" used on "$6}')
[ -n "$DISK" ] && record PASS "disk-root" "$DISK"

# ---- 11. 时间同步（审计与 license 有效期敏感） -------------------------------
if have timedatectl; then
  SYNC=$(timedatectl show -p NTPSynchronized --value 2>/dev/null)
  [ "$SYNC" = "yes" ] && record PASS "ntp-synchronized" "yes" || record WARN "ntp-synchronized" "$SYNC（license/审计依赖时钟）"
fi

echo "---------------------------------------------------------------------"
echo " 结果: PASS=$PASS  WARN=$WARN  FAIL=$FAIL"
echo "====================================================================="

if [ -n "$JSON_OUT" ]; then
  printf '{"url":"%s","at":"%s","pass":%d,"warn":%d,"fail":%d,"checks":[%s]}\n' \
    "$BASE_URL" "$(date -Iseconds)" "$PASS" "$WARN" "$FAIL" "${ROWS%,}" > "$JSON_OUT"
  echo "报告已写入: $JSON_OUT"
fi

rm -f /tmp/_ts_body.$$ /tmp/_ts_login.$$ /tmp/_ts_login_code.$$ /tmp/_ts_a.$$

[ "$FAIL" -eq 0 ] && exit 0 || exit 1
