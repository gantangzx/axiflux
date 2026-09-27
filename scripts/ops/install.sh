#!/usr/bin/env bash
# =============================================================================
# 天枢 离线一键安装 (Linux, x86_64/aarch64)
#
# 目标：陌生运维在无外网环境、30 分钟内完成安装并通过验收。
#
# 用法:
#   sudo ./install.sh --package tianshu-offline-1.0.0.tar.gz [选项]
#   sudo ./install.sh --dir /opt/tianshu-offline-1.0.0          # 已解包的目录
#
# 选项:
#   --install-dir DIR    安装目录（默认 /opt/tianshu）
#   --port PORT          应用端口（默认 8080）
#   --mode docker|native 基础设施模式（默认 docker：compose 起 PG+Redis；native：使用已就绪的 PG/Redis）
#   --pg-host/--pg-port/--pg-user/--pg-password/--pg-db   native 模式数据库参数
#   --redis-host/--redis-port/--redis-password            native 模式 Redis 参数
#   --public-url URL     对外访问地址（默认 http://<本机IP>:<port>）
#   --admin-password PWD 初始管理员口令（默认随机生成并打印一次）
#   --ee                 安装企业版（分发包内含 tianshu-ee-*.jar 时）
#   --skip-verify        安装后不跑验收脚本
#   --dry-run            只打印动作，不落盘
#
# 幂等：重复执行会保留既有 env/config 与数据库数据，只更新程序与迁移。
# =============================================================================
set -euo pipefail

INSTALL_DIR=/opt/tianshu
PORT=8080
MODE=docker
PACKAGE=""
SRC_DIR=""
PUBLIC_URL=""
ADMIN_PASSWORD=""
EE=0
SKIP_VERIFY=0
DRY=0
PG_HOST=127.0.0.1; PG_PORT=5432; PG_USER=postgres; PG_PASSWORD=""; PG_DB=tianshu
REDIS_HOST=127.0.0.1; REDIS_PORT=6379; REDIS_PASSWORD=""

log()  { printf '\033[1;34m[install]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }
run()  { if [ "$DRY" = "1" ]; then echo "  + $*"; else "$@"; fi; }

while [ $# -gt 0 ]; do
  case "$1" in
    --package) PACKAGE="$2"; shift 2 ;;
    --dir) SRC_DIR="$2"; shift 2 ;;
    --install-dir) INSTALL_DIR="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --mode) MODE="$2"; shift 2 ;;
    --public-url) PUBLIC_URL="$2"; shift 2 ;;
    --admin-password) ADMIN_PASSWORD="$2"; shift 2 ;;
    --pg-host) PG_HOST="$2"; shift 2 ;;
    --pg-port) PG_PORT="$2"; shift 2 ;;
    --pg-user) PG_USER="$2"; shift 2 ;;
    --pg-password) PG_PASSWORD="$2"; shift 2 ;;
    --pg-db) PG_DB="$2"; shift 2 ;;
    --redis-host) REDIS_HOST="$2"; shift 2 ;;
    --redis-port) REDIS_PORT="$2"; shift 2 ;;
    --redis-password) REDIS_PASSWORD="$2"; shift 2 ;;
    --ee) EE=1; shift ;;
    --skip-verify) SKIP_VERIFY=1; shift ;;
    --dry-run) DRY=1; shift ;;
    -h|--help) sed -n '1,30p' "$0"; exit 0 ;;
    *) die "未知参数: $1" ;;
  esac
done

# ---------- 0. 前置检查 -------------------------------------------------------
[ "$(id -u)" = "0" ] || die "请用 root 或 sudo 执行"
command -v java >/dev/null 2>&1 || [ -x "$SRC_DIR/runtime/jdk/bin/java" ] || warn "未检测到 java，将使用分发包内 runtime/jdk"
case "$MODE" in docker|native) ;; *) die "--mode 只能是 docker 或 native" ;; esac

log "0/9 前置检查通过（模式=$MODE，端口=$PORT，安装目录=$INSTALL_DIR）"

# ---------- 1. 解包 ----------------------------------------------------------
if [ -n "$PACKAGE" ]; then
  [ -f "$PACKAGE" ] || die "分发包不存在: $PACKAGE"
  WORK=$(mktemp -d)
  log "1/9 解包 $PACKAGE"
  run tar -xzf "$PACKAGE" -C "$WORK"
  SRC_DIR=$(find "$WORK" -maxdepth 1 -mindepth 1 -type d | head -1)
  [ -n "$SRC_DIR" ] || die "分发包结构异常（未找到顶层目录）"
else
  [ -n "$SRC_DIR" ] || die "必须提供 --package 或 --dir"
  log "1/9 使用已解包目录 $SRC_DIR"
fi

# ---------- 2. 校验和（防传输损坏，交付审计证据） -----------------------------
if [ -f "$SRC_DIR/SHA256SUMS" ]; then
  log "2/9 校验包完整性"
  if run sha256sum -c "$SRC_DIR/SHA256SUMS" --quiet; then log "      校验通过"; else die "SHA256 校验失败，分发包已损坏，请重新获取"; fi
else
  warn "2/9 分发包缺少 SHA256SUMS，跳过完整性校验（不建议）"
fi

# ---------- 3. 用户与目录 ----------------------------------------------------
log "3/9 创建用户与目录"
if ! id tianshu >/dev/null 2>&1; then run useradd -r -m -d "$INSTALL_DIR" -s /usr/sbin/nologin tianshu; fi
for d in "$INSTALL_DIR" "$INSTALL_DIR/bin" "$INSTALL_DIR/lib" "$INSTALL_DIR/conf" "$INSTALL_DIR/data" \
         "$INSTALL_DIR/data/workspace" "$INSTALL_DIR/data/skills" "$INSTALL_DIR/data/license" "$INSTALL_DIR/logs" \
         "$INSTALL_DIR/backup"; do run install -d -o tianshu -g tianshu "$d"; done

# ---------- 4. 程序 ----------------------------------------------------------
log "4/9 安装程序与迁移脚本"
JAR=$(find "$SRC_DIR" -maxdepth 2 -name 'tianshu-app-*.jar' | head -1)
[ -n "$JAR" ] || die "分发包内未找到 tianshu-app-*.jar"
if [ "$EE" = "1" ]; then
  EE_COUNT=$(find "$SRC_DIR" -maxdepth 2 -name 'tianshu-ee-*.jar' | wc -l | tr -d ' ')
  [ "$EE_COUNT" = "0" ] && die "指定了 --ee 但包内没有 tianshu-ee-*.jar（EE 分发请勿混用社区包）"
  log "      企业版 jar 数量: $EE_COUNT"
fi
run cp -f "$JAR" "$INSTALL_DIR/lib/tianshu-app.jar"
[ -d "$SRC_DIR/db/migration" ] && run cp -rf "$SRC_DIR/db/migration" "$INSTALL_DIR/lib/"
[ -d "$SRC_DIR/runtime/jdk" ] && run cp -rf "$SRC_DIR/runtime" "$INSTALL_DIR/"
run cp -f "$SRC_DIR/LICENSE" "$INSTALL_DIR/LICENSE" 2>/dev/null || true
run cp -f "$SRC_DIR/NOTICE" "$INSTALL_DIR/NOTICE" 2>/dev/null || true
for f in "$SRC_DIR"/LICENSE-EE.md "$SRC_DIR"/tianshu-ee-*.jar; do [ -e "$f" ] && run cp -f "$f" "$INSTALL_DIR/lib/" 2>/dev/null || true; done
[ -d "$SRC_DIR/scripts" ] && run cp -rf "$SRC_DIR/scripts" "$INSTALL_DIR/scripts"
run chown -R tianshu:tianshu "$INSTALL_DIR"

# ---------- 5. 密钥与配置（只生成一次，幂等保留） -----------------------------
ENVFILE="$INSTALL_DIR/conf/tianshu.env"
log "5/9 生成配置与随机密钥"
if [ -f "$ENVFILE" ]; then
  log "      已存在 $ENVFILE，保留既有配置（幂等）"
  # shellcheck disable=SC1090
  . "$ENVFILE"
else
  [ -n "$PG_PASSWORD" ] || PG_PASSWORD=$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 24)
  [ -n "$REDIS_PASSWORD" ] || REDIS_PASSWORD=$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 24)
  AUTH_SECRET=$(head -c 48 /dev/urandom | base64 | tr -d '/+=' | head -c 48)
  SCIM_TOKEN=$(head -c 32 /dev/urandom | base64 | tr -d '/+=' | head -c 32)
  [ -n "$ADMIN_PASSWORD" ] || ADMIN_PASSWORD=$(head -c 18 /dev/urandom | base64 | tr -d '/+=' | head -c 18)
  [ -n "$PUBLIC_URL" ] || PUBLIC_URL="http://$(hostname -I 2>/dev/null | awk '{print $1}'):$PORT"
  if [ "$DRY" = "0" ]; then
    cat > "$ENVFILE" <<EOF
# 天枢生产环境变量（安装时自动生成，请勿提交到版本库）
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=$PORT
TIANSHU_PUBLIC_URL=$PUBLIC_URL
PG_URL=jdbc:postgresql://$PG_HOST:$PG_PORT/$PG_DB
PG_USER=$PG_USER
PG_PASSWORD=$PG_PASSWORD
REDIS_HOST=$REDIS_HOST
REDIS_PORT=$REDIS_PORT
REDIS_PASSWORD=$REDIS_PASSWORD
AUTH_ENABLED=true
AUTH_SECRET=$AUTH_SECRET
SESSION_PROVIDER=jpa
VECTOR_PROVIDER=pgvector
TIANSHU_AUTH_BOOTSTRAPADMIN_PASSWORD=$ADMIN_PASSWORD
# 企业版开关（未购买 EE 时可全部置 false）
TIANSHU_EE_ENABLED=$([ "$EE" = "1" ] && echo true || echo false)
TIANSHU_EE_SAML_ENABLED=false
TIANSHU_EE_SCIM_ENABLED=false
TIANSHU_EE_SCIM_TOKEN=$SCIM_TOKEN
TIANSHU_EE_AUDIT_ENABLED=false
TIANSHU_EE_AUDIT_SYSLOG_HOST=
TIANSHU_EE_AUDIT_WEBHOOK_URL=
# LLM / 向量模型密钥（现场填写，或用 sysConfig 覆盖）
ARK_API_KEY=
EMBED_API_KEY=
EOF
    chmod 600 "$ENVFILE"; chown tianshu:tianshu "$ENVFILE"
    echo "$ADMIN_PASSWORD" > "$INSTALL_DIR/conf/initial-admin-password.txt"
    chmod 600 "$INSTALL_DIR/conf/initial-admin-password.txt"; chown tianshu:tianshu "$INSTALL_DIR/conf/initial-admin-password.txt"
  fi
fi

# ---------- 6. 基础设施 ------------------------------------------------------
if [ "$MODE" = "docker" ]; then
  log "6/9 启动 PostgreSQL + Redis（docker compose）"
  command -v docker >/dev/null 2>&1 || die "未检测到 docker；可改用 --mode native"
  COMPOSE="$INSTALL_DIR/conf/docker-compose.prod.yml"
  if [ ! -f "$COMPOSE" ] && [ -f "$SRC_DIR/docker-compose.prod.yml" ]; then run cp -f "$SRC_DIR/docker-compose.prod.yml" "$COMPOSE"; fi
  [ -f "$COMPOSE" ] || die "缺少 docker-compose.prod.yml"
  run env $(grep -v '^#' "$ENVFILE" | xargs) docker compose -f "$COMPOSE" --env-file "$ENVFILE" up -d postgres redis
else
  log "6/9 使用外部 PostgreSQL/Redis（--mode native）"
  command -v psql >/dev/null 2>&1 && psql "postgresql://$PG_USER:$PG_PASSWORD@$PG_HOST:$PG_PORT/$PG_DB" -tAc 'select 1' >/dev/null \
    && log "      PostgreSQL 连通" || warn "      PostgreSQL 未验证通过，请确认网络与凭据"
  command -v redis-cli >/dev/null 2>&1 && redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -a "$REDIS_PASSWORD" ping >/dev/null 2>&1 \
    && log "      Redis 连通" || warn "      Redis 未验证通过"
fi

# ---------- 7. systemd --------------------------------------------------------
log "7/9 安装 systemd 服务"
UNIT=/etc/systemd/system/tianshu.service
if [ "$DRY" = "0" ]; then
  cat > "$UNIT" <<EOF
[Unit]
Description=Tianshu Agent Platform
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=simple
User=tianshu
Group=tianshu
WorkingDirectory=$INSTALL_DIR
EnvironmentFile=$INSTALL_DIR/conf/tianshu.env
ExecStart=$INSTALL_DIR/runtime/jdk/bin/java -Xms1g -Xmx2g -XX:+UseG1GC -XX:MaxRAMPercentage=75 \\
  -Dfile.encoding=UTF-8 -jar $INSTALL_DIR/lib/tianshu-app.jar
SuccessExitStatus=143
Restart=always
RestartSec=5
TimeoutStopSec=60
StandardOutput=append:$INSTALL_DIR/logs/tianshu.out.log
StandardError=append:$INSTALL_DIR/logs/tianshu.err.log

[Install]
WantedBy=multi-user.target
EOF
fi
run systemctl daemon-reload

# ---------- 8. 启动 ----------------------------------------------------------
log "8/9 启动服务（首次启动会执行 Flyway 迁移，通常 30~60s）"
run systemctl enable --now tianshu.service
if [ "$DRY" = "0" ]; then
  for i in $(seq 1 60); do
    C=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1:$PORT/actuator/health/liveness" || true)
    [ "$C" = "200" ] && break
    sleep 3
  done
  [ "$C" = "200" ] || die "服务 180s 内未就绪，请查看 $INSTALL_DIR/logs/tianshu.err.log"
  log "      服务已就绪"
fi

# ---------- 9. 验收 ----------------------------------------------------------
if [ "$SKIP_VERIFY" = "0" ] && [ "$DRY" = "0" ] && [ -x "$INSTALL_DIR/scripts/ops/verify-install.sh" ]; then
  log "9/9 运行交付验收脚本"
  set +e
  "$INSTALL_DIR/scripts/ops/verify-install.sh" --url "http://127.0.0.1:$PORT" \
     --user tianshu --password "$(cat "$INSTALL_DIR/conf/initial-admin-password.txt" 2>/dev/null)" \
     --json "$INSTALL_DIR/logs/verify-install.json" $([ "$EE" = "1" ] && echo --expect-ee)
  RC=$?
  set -e
  [ "$RC" = "0" ] || warn "验收存在硬性失败项，请查看上方表格与 logs/verify-install.json"
else
  log "9/9 跳过验收（--skip-verify 或缺少脚本）"
fi

cat <<EOF

=====================================================================
 安装完成
---------------------------------------------------------------------
 安装目录 : $INSTALL_DIR
 配置     : $INSTALL_DIR/conf/tianshu.env
 初始管理员: tianshu / $(cat "$INSTALL_DIR/conf/initial-admin-password.txt" 2>/dev/null || echo '（既有环境，请用原口令）')
 访问地址 : ${PUBLIC_URL:-http://127.0.0.1:$PORT}
 日志     : $INSTALL_DIR/logs/tianshu.out.log
 版本     : $(basename "$JAR")
 后续步骤 :
   1) 在 conf/tianshu.env 填入 ARK_API_KEY / EMBED_API_KEY
   2) systemctl restart tianshu
   3) 首次登录后立即修改管理员口令，并启用 MFA（如已接入 IdP）
   4) 备份：scripts/ops/backup.sh --install-dir $INSTALL_DIR
=====================================================================
EOF
