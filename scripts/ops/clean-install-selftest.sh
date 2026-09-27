#!/usr/bin/env bash
# =============================================================================
# 天枢 干净机自助安装自测脚本 (Linux / WSL, 无需 root)
#
# 目标：以「陌生用户 + 隔离环境」身份，从零把离线包装起来并跑通，验证
#       docs/one-person-roadmap.md M2-1 —— 客户能否自助安装、自助使用、自助排障。
#
# 与官方 install.sh 的关系：
#   install.sh 面向正式交付，需要 root + systemd（或 docker）。本脚本面向
#   【一人公司内部自测】，在没有 root/systemd/docker 的 WSL/容器里，用隔离
#   HOME + 独立 PG/Redis 数据目录 + 内置 JDK 直接起 jar，跑同一条配置/迁移/
#   验收链路，从而低成本发现卡点。能拿到 root 的机器可直接改用 install.sh 复测。
#
# 前置（脚本会自动检测并给出安装提示，不替你改系统）：
#   - PostgreSQL 16 服务端二进制（含 pgvector）、Redis 服务端
#   - 或提供 --pgbin/--redisbin 指向解压好的二进制目录
#
# 用法:
#   ./clean-install-selftest.sh --jar /abs/tianshu-app-*.jar [选项]
#
# 选项:
#   --jar PATH         应用 fat jar（必填）
#   --work DIR         隔离工作目录（默认 /tmp/tianshu-selftest-<rand>）
#   --port N           应用端口（默认 18080）
#   --pg-port N        PostgreSQL 端口（默认 15432）
#   --redis-port N     Redis 端口（默认 16379）
#   --jdk PATH|URL     JDK tar.gz 或下载地址（不提供则要求系统已有 java 25）
#   --keep             结束后保留进程与目录（默认清理）
#   --ee               企业版组装
# =============================================================================
set -euo pipefail

JAR=""
WORK=""
PORT=18080
PG_PORT=15432
REDIS_PORT=16379
JDK_SRC=""
KEEP=0
EE=0

log()  { printf '\033[1;34m[selftest]\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m[ ok ]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; cleanup 2>/dev/null || true; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --jar) JAR="$2"; shift 2 ;;
    --work) WORK="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --pg-port) PG_PORT="$2"; shift 2 ;;
    --redis-port) REDIS_PORT="$2"; shift 2 ;;
    --jdk) JDK_SRC="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    --ee) EE=1; shift ;;
    -h|--help) sed -n '1,36p' "$0"; exit 0 ;;
    *) die "未知参数: $1" ;;
  esac
done

[ -n "$JAR" ] || die "必须提供 --jar"
[ -f "$JAR" ] || die "fat jar 不存在: $JAR"
JAR=$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK="${WORK:-/tmp/tianshu-selftest-$(head -c4 /dev/urandom | od -An -tx1 | tr -d ' \n')}"

PGDIR="$WORK/pg"; REDISDIR="$WORK/redis"
APPDIR="$WORK/app"; CONF="$WORK/tianshu.env"; LOGDIR="$WORK/logs"
PG_PID=""; REDIS_PID=""; APP_PID=""
mkdir -p "$PGDIR" "$REDISDIR" "$APPDIR" "$LOGDIR"
ISSUES="$WORK/ISSUES.txt"; : > "$ISSUES"
T0=$(date +%s)
issue() { printf '[%ss] %s\n' "$(( $(date +%s)-T0 ))" "$*" | tee -a "$ISSUES"; }

cleanup() {
  if [ "$KEEP" = "0" ]; then
    [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null || true
    [ -n "$REDIS_PID" ] && kill "$REDIS_PID" 2>/dev/null || true
    [ -n "$PG_PID" ] && "$(dirname "$PG_BIN")/pg_ctl" -D "$PGDIR" stop -m fast >/dev/null 2>&1 || true
    sleep 1
    rm -rf "$WORK"
  else
    warn "保留环境: $WORK（进程可能仍在运行）"
  fi
}
trap cleanup EXIT

elapsed() { echo "$(( $(date +%s)-T0 ))s"; }

# ---------- 0. 干净环境声明 ---------------------------------------------------
log "工作目录(隔离): $WORK"
log "干净性：PATH 中探测工具，不依赖 shell 历史/全局缓存"

# ---------- 1. 基础设施探测 ---------------------------------------------------
log "1/7 探测 PostgreSQL / Redis / Java"
PG_BIN=$(command -v postgres || true)
if [ -z "$PG_BIN" ]; then
  # Debian/Ubuntu 将版本化 PG 放在 /usr/lib/postgresql/<ver>/bin，不在普通 PATH
  PG_BIN=$(ls -1 /usr/lib/postgresql/*/bin/postgres 2>/dev/null | sort -V | tail -1 || true)
fi
REDIS_BIN=$(command -v redis-server || true)
[ -n "$PG_BIN" ] || { issue "卡点: 未找到 postgres/pg_ctl（Ubuntu: sudo apt-get install -y postgresql-16 postgresql-16-pgvector）"; }
[ -n "$REDIS_BIN" ] || { issue "卡点: 未找到 redis-server（Ubuntu: sudo apt-get install -y redis-server）"; }
[ -n "$PG_BIN" ] && [ -n "$REDIS_BIN" ] || die "基础设施缺失，先按提示安装（模拟客户自备 PG/Redis）"
PG_CTL="$(dirname "$PG_BIN")/pg_ctl"; INITDB="$(dirname "$PG_BIN")/initdb"

JAVA_BIN="java"
if [ -n "$JDK_SRC" ]; then
  JDK_TGZ="$WORK/jdk.tar.gz"
  case "$JDK_SRC" in
    http*) curl -fsSL --retry 3 -o "$JDK_TGZ" "$JDK_SRC" ;;
    *) cp -f "$JDK_SRC" "$JDK_TGZ" ;;
  esac
  JW=$(mktemp -d); tar -xzf "$JDK_TGZ" -C "$JW"
  JHOME=$(find "$JW" -path '*/bin/java' -type f | head -1 | sed 's#/bin/java##')
  [ -n "$JHOME" ] || die "JDK 包结构异常"
  JAVA_BIN="$JHOME/bin/java"
fi
if ! "$JAVA_BIN" -version 2>&1 | head -1 | grep -Eq '"(25|[3-9][0-9])'; then
  issue "卡点: 未检测到 JDK 25（当前: $("$JAVA_BIN" -version 2>&1 | head -1)）—— 用 --jdk 内置"
fi
ok "基础设施就绪 ($(elapsed))"

# ---------- 2. 组装离线包 -----------------------------------------------------
log "2/7 用 build-offline-package.sh 组装离线包"
JDK_ARG=""
[ -n "$JDK_SRC" ] && JDK_ARG="--jdk $JDK_SRC"
bash "$ROOT/scripts/ops/build-offline-package.sh" --jar "$JAR" --out "$WORK/dist" \
     $JDK_ARG $([ "$EE" = "1" ] && echo --ee) --no-tar \
  || die "离线包组装失败"
PKG_DIR=$(find "$WORK/dist" -maxdepth 1 -mindepth 1 -type d | head -1)
[ -n "$PKG_DIR" ] || die "未找到组装产物目录"
(cd "$PKG_DIR" && sha256sum -c SHA256SUMS --quiet) || { issue "卡点: SHA256SUMS 校验失败"; die "完整性失败"; }
ok "离线包组装并校验通过 ($(elapsed))"

# ---------- 3. 初始化并启动 PG(pgvector) --------------------------------------
log "3/7 初始化独立 PostgreSQL 集群（端口 $PG_PORT）"
export LD_LIBRARY_PATH="$(dirname "$(dirname "$PG_BIN")")/lib:${LD_LIBRARY_PATH:-}"
"$INITDB" -D "$PGDIR" -U postgres --encoding=UTF8 --locale=C -A trust >"$LOGDIR/initdb.log" 2>&1 \
  || die "initdb 失败，见 $LOGDIR/initdb.log"
"$PG_CTL" -D "$PGDIR" -o "-p $PG_PORT -k $PGDIR -h 127.0.0.1" -w -l "$LOGDIR/pg.log" start \
  || die "PostgreSQL 启动失败，见 $LOGDIR/pg.log"
PG_PID=$(head -1 "$PGDIR/postmaster.pid" 2>/dev/null || echo "")
PSQL="$(dirname "$PG_BIN")/psql"
"$PSQL" -h 127.0.0.1 -p "$PG_PORT" -U postgres -d postgres -c "CREATE DATABASE tianshu ENCODING 'UTF8'" >/dev/null
if "$PSQL" -h 127.0.0.1 -p "$PG_PORT" -U postgres -d tianshu -c "CREATE EXTENSION vector" >/dev/null 2>&1; then
  ok "pgvector 扩展可用"
else
  issue "卡点: pgvector 扩展创建失败（需安装 postgresql-16-pgvector / pgvector.io 包）"
fi

# ---------- 4. 启动 Redis ------------------------------------------------------
log "4/7 启动独立 Redis（端口 $REDIS_PORT）"
REDIS_PASS=$(head -c 18 /dev/urandom | base64 | tr -d '/+=' | head -c 18)
"$REDIS_BIN" --port "$REDIS_PORT" --bind 127.0.0.1 --daemonize yes \
  --requirepass "$REDIS_PASS" --dir "$REDISDIR" --pidfile "$REDISDIR/redis.pid" \
  --appendonly yes >/dev/null
REDIS_PID=$(cat "$REDISDIR/redis.pid" 2>/dev/null || echo "")
ok "Redis 已启动"

# ---------- 5. 生成配置（对齐 install.sh 口径） --------------------------------
log "5/7 生成环境变量配置（模拟 install.sh 第 5 步）"
PG_PASS="selftest_pg"; AUTH_SECRET=$(head -c 48 /dev/urandom | base64 | tr -d '/+=' | head -c 48)
ADMIN_PASS=$(head -c 18 /dev/urandom | base64 | tr -d '/+=' | head -c 18)
cat > "$CONF" <<EOF
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=$PORT
TIANSHU_PUBLIC_URL=http://127.0.0.1:$PORT
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
TIANSHU_EE_ENABLED=$([ "$EE" = "1" ] && echo true || echo false)
EOF
chmod 600 "$CONF"

# ---------- 6. 启动应用（Flyway 迁移） -----------------------------------------
log "6/7 启动应用（首次 Flyway 迁移，观察启动耗时）"
JAR_IN_PKG=$(find "$PKG_DIR" -maxdepth 1 -name 'tianshu-app-*.jar' | head -1)
APP_START_T=$(date +%s)
set -a; . "$CONF"; set +a
nohup "$JAVA_BIN" -Xms512m -Xmx1500m -Dfile.encoding=UTF-8 -jar "$JAR_IN_PKG" \
   > "$LOGDIR/app.out.log" 2>&1 &
APP_PID=$!

CODE=000
for i in $(seq 1 60); do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1:$PORT/actuator/health/liveness" 2>/dev/null || echo 000)
  [ "$CODE" = "200" ] && break
  kill -0 "$APP_PID" 2>/dev/null || { issue "卡点: 应用进程提前退出，见 $LOGDIR/app.out.log"; break; }
  sleep 3
done
if [ "$CODE" = "200" ]; then
  ok "应用就绪，启动耗时 $(( $(date +%s)-APP_START_T ))s（总 $(elapsed)）"
else
  issue "卡点: 180s 内未就绪（最后 HTTP=$CODE），见 $LOGDIR/app.out.log"
fi

# ---------- 7. 验收 -----------------------------------------------------------
log "7/7 运行 verify-install.sh 出具验收结论"
VR=0
bash "$ROOT/scripts/ops/verify-install.sh" --url "http://127.0.0.1:$PORT" \
   --user tianshu --password "$ADMIN_PASS" \
   --json "$LOGDIR/verify-install.json" $([ "$EE" = "1" ] && echo --expect-ee) \
   | tee "$LOGDIR/verify.out.log" || VR=$?

echo
echo "====================================================================="
echo " 自测总结  总耗时 $(elapsed)"
echo "---------------------------------------------------------------------"
if [ -s "$ISSUES" ]; then
  echo "发现卡点（$(grep -c . "$ISSUES") 项）："
  cat "$ISSUES"
else
  echo "未发现卡点：隔离环境可从零自助安装并通过验收。"
fi
echo " 产物/日志: $LOGDIR（--keep 可保留）"
echo " 验收结果 : verify rc=$VR"
echo "====================================================================="
[ "$VR" = "0" ] && [ ! -s "$ISSUES" ]
