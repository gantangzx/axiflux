#!/usr/bin/env bash
# =============================================================================
# AxiFlux License enforce 实机验证脚本 (Linux / WSL, 无需 root)
#
# 目标：验证 docs/one-person-roadmap.md M2-2 —— 离线企业 License 的
#       enforce + grace 行为，以及签发 / 续签自助闭环。
#
# 在隔离环境（独立 PG/Redis/配置/jar）中实跑以下断言：
#   1. MISSING + ENFORCE        -> 受保护接口 503；管理端点仍可达
#   2. apply VALID license      -> 接口 200，状态 VALID
#   3. apply GRACE license      -> 仍放行（续签可在宽限期内完成），状态 GRACE
#   4. MISSING + WARN           -> 放行 + 响应头 X-License-State=MISSING
#   5. EXPIRED(越过宽限) + ENFORCE -> 503
#   6. 续签恢复                 -> apply VALID 后回到 200
#   7. 运行期撤销 + ENFORCE     -> fail-closed 503
#
# 前置：与 clean-install-selftest.sh 相同（PG 服务端含 pgvector、Redis）。
# License 由 fat jar 内的 LicenseTool（厂商工具）实时签发。
#
# 用法:
#   ./license-enforce-selftest.sh --jar /abs/axiflux-app-*.jar [选项]
# 选项:
#   --jar PATH         应用 fat jar（必填）
#   --work DIR         隔离工作目录（默认 /tmp/axiflux-lic-<rand>）
#   --port N           应用端口（默认 18090）
#   --pg-port N        PostgreSQL 端口（默认 15433）
#   --redis-port N     Redis 端口（默认 16380）
#   --keep             结束后保留现场（默认清理）
# =============================================================================
set -euo pipefail

JAR=""
WORK=""
PORT=18090
PG_PORT=15433
REDIS_PORT=16380
KEEP=0

while [ $# -gt 0 ]; do
  case "$1" in
    --jar) JAR="$2"; shift 2 ;;
    --work) WORK="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --pg-port) PG_PORT="$2"; shift 2 ;;
    --redis-port) REDIS_PORT="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    -h|--help) sed -n '1,34p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

[ -n "$JAR" ] && [ -f "$JAR" ] || { echo "必须提供存在的 --jar" >&2; exit 2; }
JAR=$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")
WORK="${WORK:-/tmp/axiflux-lic-$(head -c4 /dev/urandom | od -An -tx1 | tr -d ' \n')}"

PGDIR="$WORK/pg"; REDISDIR="$WORK/redis"; LOGDIR="$WORK/logs"
KEYDIR="$WORK/keys"; LICDIR="$WORK/licenses"
PG_PID=""; REDIS_PID=""; APP_PID=""
mkdir -p "$PGDIR" "$REDISDIR" "$LOGDIR" "$KEYDIR" "$LICDIR"

DEPLOYMENT_ID="dep-$(head -c6 /dev/urandom | od -An -tx1 | tr -d ' \n')"
LICENSE_FILE="$LICDIR/enterprise.lic"
PUBLIC_KEY="$KEYDIR/public.pem"
PRIVATE_KEY="$KEYDIR/private.pem"
CONF="$WORK/axiflux.env"

PASS=0; FAIL=0
rec() { # rec <expected|got...>
  if [ "$1" = "$2" ]; then printf '  \033[1;32mPASS\033[0m %s\n' "$3"; PASS=$((PASS+1));
  else printf '  \033[1;31mFAIL\033[0m %s (期望 %s 实际 %s)\n' "$3" "$1" "$2"; FAIL=$((FAIL+1)); fi
}

cleanup() {
  if [ "$KEEP" = "0" ]; then
    [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null || true
    [ -n "$REDIS_PID" ] && kill "$REDIS_PID" 2>/dev/null || true
    [ -n "$PG_PID" ] && "$(dirname "$PG_BIN")/pg_ctl" -D "$PGDIR" stop -m fast >/dev/null 2>&1 || true
    sleep 1; rm -rf "$WORK"
  else
    echo "保留环境: $WORK"
  fi
}
trap cleanup EXIT

log() { printf '\033[1;34m[lic-test]\033[0m %s\n' "$*"; }

# ---------- 基础设施探测 -------------------------------------------------------
PG_BIN=$(command -v postgres || true)
[ -n "$PG_BIN" ] || PG_BIN=$(ls -1 /usr/lib/postgresql/*/bin/postgres 2>/dev/null | sort -V | tail -1 || true)
REDIS_BIN=$(command -v redis-server || true)
[ -n "$PG_BIN" ] && [ -n "$REDIS_BIN" ] || { echo "缺少 PG/Redis，参见 clean-install-selftest.sh" >&2; exit 2; }
PG_CTL="$(dirname "$PG_BIN")/pg_ctl"; INITDB="$(dirname "$PG_BIN")/initdb"; PSQL="$(dirname "$PG_BIN")/psql"
JAVA_BIN="java"
T0=$(date +%s)

# ---------- 启动 PG(pgvector) -------------------------------------------------
export LD_LIBRARY_PATH="$(dirname "$(dirname "$PG_BIN")")/lib:${LD_LIBRARY_PATH:-}"
"$INITDB" -D "$PGDIR" -U postgres --encoding=UTF8 --locale=C -A trust >"$LOGDIR/initdb.log" 2>&1
"$PG_CTL" -D "$PGDIR" -o "-p $PG_PORT -k $PGDIR -h 127.0.0.1" -w -l "$LOGDIR/pg.log" start
PG_PID=$(head -1 "$PGDIR/postmaster.pid")
"$PSQL" -h 127.0.0.1 -p "$PG_PORT" -U postgres -d postgres -c "CREATE DATABASE Axiflux ENCODING 'UTF8'" >/dev/null
"$PSQL" -h 127.0.0.1 -p "$PG_PORT" -U postgres -d Axiflux -c "CREATE EXTENSION vector" >/dev/null

# ---------- 启动 Redis ---------------------------------------------------------
REDIS_PASS=$(head -c 18 /dev/urandom | base64 | tr -d '/+=' | head -c 18)
"$REDIS_BIN" --port "$REDIS_PORT" --bind 127.0.0.1 --daemonize yes \
  --requirepass "$REDIS_PASS" --dir "$REDISDIR" --pidfile "$REDISDIR/redis.pid" \
  --appendonly yes >/dev/null
REDIS_PID=$(cat "$REDISDIR/redis.pid")

# ---------- 解包 fat jar 以运行厂商工具 LicenseTool ------------------------------
# Spring Boot fat jar 的类在 BOOT-INF/classes，不能直接 -cp；解包后拼 classpath。
EXTRACT="$WORK/extracted"; mkdir -p "$EXTRACT"
( cd "$EXTRACT" && "$JAVA_BIN" -jar "$JAR" --list 2>/dev/null ) >/dev/null 2>&1 || true
# 用 jar 工具解包（JDK 自带）；若无 jar 命令则用 unzip
if command -v jar >/dev/null 2>&1; then ( cd "$EXTRACT" && jar xf "$JAR" ); else ( cd "$EXTRACT" && unzip -q "$JAR" ); fi
TOOL_CP="$EXTRACT/BOOT-INF/classes"
for j in "$EXTRACT"/BOOT-INF/lib/*.jar; do TOOL_CP="$TOOL_CP:$j"; done
LICENSE_TOOL="com.gantang.axiflux.spring.license.LicenseTool"

# ---------- 生成厂商密钥对（LicenseTool keygen） --------------------------------
log "生成厂商 RSA 密钥对"
"$JAVA_BIN" -cp "$TOOL_CP" "$LICENSE_TOOL" keygen \
  --out-dir "$KEYDIR" >/dev/null

# issue 包装： issue_lic <outFile> <validity-seconds>
issue_lic() {
  "$JAVA_BIN" -cp "$TOOL_CP" "$LICENSE_TOOL" issue \
    --private-key "$PRIVATE_KEY" --out "$1" \
    --customer "Acme 客户" --deployment-id "$DEPLOYMENT_ID" \
    --edition enterprise --seats 50 \
    --entitlements "scheduler,audit.export" \
    --validity-seconds "$2" >/dev/null
}

# ---------- 配置（license 启用，enforce） ---------------------------------------
AUTH_SECRET=$(head -c 48 /dev/urandom | base64 | tr -d '/+=' | head -c 48)
ADMIN_PASS="Lictest_$(head -c9 /dev/urandom | base64 | tr -d '/+=' | head -c9)"
write_conf() { # write_conf <ENFORCE|WARN>
  local mode; mode=$(echo "$1" | tr 'A-Z' 'a-z')
  cat > "$CONF" <<EOF
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=$PORT
AXIFLUX_PUBLIC_URL=http://127.0.0.1:$PORT
PG_URL=jdbc:postgresql://127.0.0.1:$PG_PORT/Axiflux
PG_USER=postgres
PG_PASSWORD=
REDIS_HOST=127.0.0.1
REDIS_PORT=$REDIS_PORT
REDIS_PASSWORD=$REDIS_PASS
AUTH_ENABLED=true
AUTH_SECRET=$AUTH_SECRET
SESSION_PROVIDER=jpa
VECTOR_PROVIDER=pgvector
AXIFLUX_AUTH_BOOTSTRAPADMIN_PASSWORD=$ADMIN_PASS
AXIFLUX_LICENSE_ENABLED=true
AXIFLUX_LICENSE_PATH=$LICENSE_FILE
AXIFLUX_LICENSE_PUBLICKEYPATH=$PUBLIC_KEY
AXIFLUX_LICENSE_DEPLOYMENTID=$DEPLOYMENT_ID
AXIFLUX_LICENSE_ENFORCEMENT=$mode
AXIFLUX_LICENSE_GRACEDAYS=7
EOF
  chmod 600 "$CONF"
}

start_app() {
  set -a; . "$CONF"; set +a
  nohup "$JAVA_BIN" -Xms512m -Xmx1500m -Dfile.encoding=UTF-8 -jar "$JAR" \
     > "$LOGDIR/app.out.log" 2>&1 &
  APP_PID=$!
  for i in $(seq 1 60); do
    local c; c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
       "http://127.0.0.1:$PORT/actuator/health/liveness" 2>/dev/null || echo 000)
    [ "$c" = "200" ] && return 0
    sleep 2
  done
  return 1
}
stop_app() { [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null || true; sleep 3; APP_PID=""; }

# 登录拿 token
login_token() {
  curl -s --max-time 8 -H 'Content-Type: application/json' -X POST \
    -d "{\"login\":\"Axiflux\",\"password\":\"$ADMIN_PASS\"}" \
    "http://127.0.0.1:$PORT/api/v1/auth/login" \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['token'])"
}
# 受保护接口的 HTTP 码
prot_code() { curl -s -o /dev/null -w '%{http_code}' --max-time 8 -H "Authorization: Bearer $1" \
   "http://127.0.0.1:$PORT/api/v1/agents"; }
# 管理端点（自带头）
admin_state() { curl -s --max-time 8 -H "Authorization: Bearer $1" \
   "http://127.0.0.1:$PORT/api/v1/admin/license" \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['state'])"; }
apply_key() { # apply_key <token> <file>
  python3 - "$2" <<'PY' > "$WORK/apply.resp"
import json,sys
print(json.dumps({"licenseKey": open(sys.argv[1]).read().strip()}))
PY
  curl -s -o /dev/null -w '%{http_code}' --max-time 10 -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' -X POST --data-binary "@$WORK/apply.resp" \
    "http://127.0.0.1:$PORT/api/v1/admin/license"
}
revoke_code() { curl -s -o /dev/null -w '%{http_code}' --max-time 8 -H "Authorization: Bearer $1" \
   -X DELETE "http://127.0.0.1:$PORT/api/v1/admin/license"; }

# =============================================================================
# 场景 1：MISSING + ENFORCE -> 受保护接口 503
# =============================================================================
log "场景1 MISSING + ENFORCE"
write_conf ENFORCE
start_app || { echo "应用启动失败，见 $LOGDIR/app.out.log" >&2; exit 1; }
TOKEN=$(login_token)
rec 503 "$(prot_code "$TOKEN")" "MISSING+ENFORCE 受保护接口应 503"
rec MISSING "$(admin_state "$TOKEN")" "管理端点可达且状态 MISSING"

# =============================================================================
# 场景 2：apply VALID -> 200
# =============================================================================
log "场景2 apply VALID license"
issue_lic "$LICENSE_FILE" 3600
rec 200 "$(apply_key "$TOKEN" "$LICENSE_FILE")" "apply VALID 返回 200"
rec VALID "$(admin_state "$TOKEN")" "状态 VALID"
rec 200 "$(prot_code "$TOKEN")" "受保护接口恢复 200"

# =============================================================================
# 场景 3：apply GRACE（expired 1h 前，仍在 7 天宽限）-> 放行
# =============================================================================
log "场景3 apply GRACE license（宽限期内续签）"
GRACE_LIC="$LICDIR/grace.lic"
issue_lic "$GRACE_LIC" -3600
rec 200 "$(apply_key "$TOKEN" "$GRACE_LIC")" "apply GRACE 返回 200（宽限期可续签）"
rec GRACE "$(admin_state "$TOKEN")" "状态 GRACE"
rec 200 "$(prot_code "$TOKEN")" "GRACE 期间受保护接口仍放行 200"

# =============================================================================
# 场景 4：切换 WARN（仍 MISSING 场景用坏证） -> 放行 + X-License-State
# =============================================================================
log "场景4 WARN 模式对坏证放行并记录"
stop_app
write_conf WARN
# 保留 GRACE 文件作为当前 license（WARN 下非健康态）
cp "$GRACE_LIC" "$LICENSE_FILE"
start_app || { echo "WARN 应用启动失败" >&2; exit 1; }
TOKEN=$(login_token)
rec 200 "$(prot_code "$TOKEN")" "WARN 下坏证仍放行 200"
HDR=$(curl -s -D - -o /dev/null --max-time 8 -H "Authorization: Bearer $TOKEN" \
   "http://127.0.0.1:$PORT/api/v1/agents" | grep -i 'X-License-State' | tr -d '\r' | awk '{print $2}')
rec GRACE "$HDR" "WARN 响应头 X-License-State=GRACE"

# =============================================================================
# 场景 5：EXPIRED（越过宽限）+ ENFORCE -> 503
# =============================================================================
log "场景5 越过宽限 EXPIRED + ENFORCE"
stop_app
write_conf ENFORCE
DEAD_LIC="$LICDIR/dead.lic"
# expired 30 天前，已越过 7 天宽限
issue_lic "$DEAD_LIC" -2592000
cp "$DEAD_LIC" "$LICENSE_FILE"
start_app || { echo "ENFORCE(dead) 启动失败" >&2; exit 1; }
TOKEN=$(login_token)
rec EXPIRED "$(admin_state "$TOKEN")" "状态 EXPIRED"
rec 503 "$(prot_code "$TOKEN")" "越过宽限受保护接口 503"

# =============================================================================
# 场景 6：续签恢复（apply VALID） -> 200
# =============================================================================
log "场景6 续签恢复"
issue_lic "$LICENSE_FILE" 7200
rec 200 "$(apply_key "$TOKEN" "$LICENSE_FILE")" "续签 apply VALID 返回 200"
rec VALID "$(admin_state "$TOKEN")" "状态回到 VALID"
rec 200 "$(prot_code "$TOKEN")" "受保护接口恢复 200"

# =============================================================================
# 场景 7：运行期撤销 + ENFORCE -> fail-closed 503
# =============================================================================
log "场景7 运行期撤销 fail-closed"
rec 200 "$(revoke_code "$TOKEN")" "撤销接口返回 200"
rec MISSING "$(admin_state "$TOKEN")" "撤销后状态 MISSING"
rec 503 "$(prot_code "$TOKEN")" "ENFORCE 撤销后受保护接口立即 503"

# =============================================================================
echo
echo "====================================================================="
echo " License enforce 实机结果  总耗时 $(( $(date +%s)-T0 ))s"
echo "  PASS=$PASS  FAIL=$FAIL"
if [ "$FAIL" = "0" ]; then echo "  License enforce + grace + 续签/撤销 全部符合预期。"; fi
echo "====================================================================="
[ "$FAIL" = "0" ]
