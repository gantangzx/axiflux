#!/usr/bin/env bash
# =============================================================================
# 天枢 多租户隔离启动校验 实机脚本 (M2-4)
#
# 验证三种启动组合（同一隔离 PG/Redis）：
#   A. deployment.mode=saas  + workspaces-enabled 未开 -> 启动必须 FAIL（fail-fast）
#   B. deployment.mode=saas  + workspaces-enabled=true -> 启动成功，health UP
#   C. deployment.mode=standalone（默认）+ 不开 workspaces -> 启动成功（私有化不受影响）
#
# 用法:
#   ./tenant-isolation-guard-selftest.sh --jar /abs/tianshu-app-*.jar [选项]
# 选项: --work DIR --port N --pg-port N --redis-port N --keep
# =============================================================================
set -euo pipefail

JAR=""; WORK=""; PORT=18095; PG_PORT=15434; REDIS_PORT=16385; KEEP=0
while [ $# -gt 0 ]; do
  case "$1" in
    --jar) JAR="$2"; shift 2 ;;
    --work) WORK="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --pg-port) PG_PORT="$2"; shift 2 ;;
    --redis-port) REDIS_PORT="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    -h|--help) sed -n '1,18p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done
[ -n "$JAR" ] && [ -f "$JAR" ] || { echo "必须提供存在的 --jar" >&2; exit 2; }
JAR=$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")
WORK="${WORK:-/tmp/tianshu-ten-$(head -c4 /dev/urandom | od -An -tx1 | tr -d ' \n')}"
PGDIR="$WORK/pg"; REDISDIR="$WORK/redis"; LOGDIR="$WORK/logs"; WSDIR="$WORK/workspaces"
PG_PID=""; REDIS_PID=""; APP_PID=""
mkdir -p "$PGDIR" "$REDISDIR" "$LOGDIR"

PASS=0; FAIL=0
rec() {
  if [ "$1" = "$2" ]; then printf '  \033[1;32mPASS\033[0m %s\n' "$3"; PASS=$((PASS+1));
  else printf '  \033[1;31mFAIL\033[0m %s (期望 %s 实际 %s)\n' "$3" "$1" "$2"; FAIL=$((FAIL+1)); fi
}
cleanup() {
  if [ "$KEEP" = "0" ]; then
    # Kill by tracked pid; if stop_app already cleared APP_PID (normal finish),
    # fall back to whatever still listens on our app port so no JVM is orphaned.
    if [ -n "$APP_PID" ]; then
      kill "$APP_PID" 2>/dev/null || true
    else
      for p in $(ss -lntp 2>/dev/null | grep ":$PORT " | grep -oE 'pid=[0-9]+' | cut -d= -f2); do
        kill "$p" 2>/dev/null || true
      done
    fi
    sleep 2
    for p in $(ss -lntp 2>/dev/null | grep ":$PORT " | grep -oE 'pid=[0-9]+' | cut -d= -f2); do
      kill -9 "$p" 2>/dev/null || true
    done
    [ -n "$REDIS_PID" ] && kill "$REDIS_PID" 2>/dev/null || true
    [ -n "$PG_PID" ] && "$(dirname "$PG_BIN")/pg_ctl" -D "$PGDIR" stop -m fast >/dev/null 2>&1 || true
    sleep 1; rm -rf "$WORK"
  else echo "保留环境: $WORK"; fi
}
trap cleanup EXIT
log() { printf '\033[1;34m[ten-test]\033[0m %s\n' "$*"; }

PG_BIN=$(command -v postgres || true)
[ -n "$PG_BIN" ] || PG_BIN=$(ls -1 /usr/lib/postgresql/*/bin/postgres 2>/dev/null | sort -V | tail -1 || true)
REDIS_BIN=$(command -v redis-server || true)
[ -n "$PG_BIN" ] && [ -n "$REDIS_BIN" ] || { echo "缺少 PG/Redis" >&2; exit 2; }
PG_CTL="$(dirname "$PG_BIN")/pg_ctl"; INITDB="$(dirname "$PG_BIN")/initdb"; PSQL="$(dirname "$PG_BIN")/psql"
T0=$(date +%s)

export LD_LIBRARY_PATH="$(dirname "$(dirname "$PG_BIN")")/lib:${LD_LIBRARY_PATH:-}"
"$INITDB" -D "$PGDIR" -U postgres --encoding=UTF8 --locale=C -A trust >"$LOGDIR/initdb.log" 2>&1
"$PG_CTL" -D "$PGDIR" -o "-p $PG_PORT -k $PGDIR -h 127.0.0.1" -w -l "$LOGDIR/pg.log" start
PG_PID=$(head -1 "$PGDIR/postmaster.pid")
"$PSQL" -h 127.0.0.1 -p "$PG_PORT" -U postgres -d postgres -c "CREATE DATABASE tianshu ENCODING 'UTF8'" >/dev/null
"$PSQL" -h 127.0.0.1 -p "$PG_PORT" -U postgres -d tianshu -c "CREATE EXTENSION vector" >/dev/null

REDIS_PASS=$(head -c 18 /dev/urandom | base64 | tr -d '/+=' | head -c 18)
"$REDIS_BIN" --port "$REDIS_PORT" --bind 127.0.0.1 --daemonize yes \
  --requirepass "$REDIS_PASS" --dir "$REDISDIR" --pidfile "$REDISDIR/redis.pid" \
  --appendonly yes >/dev/null
REDIS_PID=$(cat "$REDISDIR/redis.pid")

AUTH_SECRET=$(head -c 48 /dev/urandom | base64 | tr -d '/+=' | head -c 48)
ADMIN_PASS="Tentest_$(head -c9 /dev/urandom | base64 | tr -d '/+=' | head -c9)"

# write_env <mode> <wsEnabled true|false>
write_env() {
  cat > "$WORK/run.env" <<EOF
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=$PORT
PG_URL=jdbc:postgresql://127.0.0.1:$PG_PORT/tianshu
PG_USER=postgres
PG_PASSWORD=
REDIS_HOST=127.0.0.1
REDIS_PORT=$REDIS_PORT
REDIS_PASSWORD=$REDIS_PASS
AUTH_ENABLED=true
AUTH_SECRET=$AUTH_SECRET
SESSION_PROVIDER=jpa
VECTOR_PROVIDER=pgvector
TIANSHU_AUTH_BOOTSTRAPADMIN_PASSWORD=$ADMIN_PASS
TIANSHU_DEPLOYMENT_MODE=$1
TIANSHU_TOOLS_WORKSPACESENABLED=$2
TIANSHU_TOOLS_WORKSPACESROOT=$WSDIR
EOF
  chmod 600 "$WORK/run.env"
}

# Attempt a start; echoes "UP" if health becomes UP within timeout, else "DOWN".
attempt_start() {
  set -a; . "$WORK/run.env"; set +a
  local out; out="$LOGDIR/app.$1.log"
  # Start java directly (no wrapping subshell) so $! is the real JVM pid and
  # stop_app/cleanup can reap it. cd first so logback's file appender writes
  # logs/tianshu.log inside the sandbox rather than the repo root.
  cd "$WORK"
  java -jar "$JAR" > "$out" 2>&1 &
  APP_PID=$!
  cd - >/dev/null
  local i
  for i in $(seq 1 25); do
    if ! kill -0 "$APP_PID" 2>/dev/null; then
      wait "$APP_PID" 2>/dev/null || true; APP_PID=""; echo "EXITED"; return 0
    fi
    local c; c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 \
      "http://127.0.0.1:$PORT/actuator/health/liveness" 2>/dev/null || echo 000)
    if [ "$c" = "200" ]; then echo "UP"; return 0; fi
    sleep 2
  done
  echo "TIMEOUT"
}
stop_app() {
  [ -n "$APP_PID" ] || return 0
  kill "$APP_PID" 2>/dev/null || true
  sleep 3
  kill -9 "$APP_PID" 2>/dev/null || true
  APP_PID=""
}

# ---- A: saas + no workspaces -> must fail (process exits with guard message) ----
log "场景A saas 不开隔离 -> 必须 fail-fast"
write_env saas false
RA=$(attempt_start A)
rec EXITED "$RA" "SaaS 无隔离时启动应被拒绝（进程退出）"
# The guard message goes through logback's FILE appender ($WORK/logs/tianshu.log);
# stdout ($LOGDIR/app.A.log) only carries the boot banner.
if grep -q "saas requires per-user workspace isolation" "$LOGDIR/tianshu.log"; then
  rec MSG MSG "日志包含明确的 fail-fast 修复指引"
else rec MSG "$(grep -i exception "$LOGDIR/tianshu.log" | head -1)" "应包含隔离缺失说明"; fi

# ---- B: saas + workspaces -> UP ----
log "场景B saas 开启隔离 -> 启动成功"
write_env saas true
RB=$(attempt_start B)
rec UP "$RB" "SaaS 开启隔离后启动成功"
if [ "$RB" = "UP" ]; then
  TOKEN=$(curl -s --max-time 8 -H 'Content-Type: application/json' -X POST \
    -d "{\"login\":\"tianshu\",\"password\":\"$ADMIN_PASS\"}" \
    "http://127.0.0.1:$PORT/api/v1/auth/login" \
    | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['token'])")
  HC=$(curl -s --max-time 5 -H "Authorization: Bearer $TOKEN" \
    "http://127.0.0.1:$PORT/actuator/health" \
    | python3 -c "import json,sys;d=json.load(sys.stdin);c=d.get('components',{});tw=c.get('tenantWorkspaces',{});print(tw.get('status','MISSING'))")
  rec UP "$HC" "tenantWorkspaces 健康组件 UP"
fi
stop_app

# ---- C: standalone + no workspaces -> UP (private install unaffected) ----
log "场景C standalone 不开隔离 -> 启动成功（私有化不受影响）"
write_env standalone false
RC=$(attempt_start C)
rec UP "$RC" "standalone 无隔离仍启动成功（私有化零影响）"
stop_app

echo
echo "====================================================================="
echo " 多租户隔离启动校验结果  总耗时 $(( $(date +%s)-T0 ))s"
echo "  PASS=$PASS  FAIL=$FAIL"
[ "$FAIL" = "0" ] && echo " SaaS fail-fast / 开启即通过 / 私有化不受影响，全部符合预期。"
echo "====================================================================="
[ "$FAIL" = "0" ]
