#!/usr/bin/env bash
# =============================================================================
# 天枢 一键升级 (Linux) —— 备份 -> 灰度校验 -> 替换 -> Flyway 迁移 -> 探活 -> 失败自动回滚
#
# 用法:
#   sudo ./upgrade.sh --package tianshu-offline-1.1.0.tar.gz [--install-dir /opt/tianshu]
#                     [--ee] [--no-backup] [--timeout 300]
#
# 前置: 目标版本 jar 内已含全部 Flyway 迁移；升级不做破坏性回退（数据库向前兼容）。
# 回滚: jar 会保留上一版本，探活失败自动恢复旧 jar 并重启；数据库不做自动回退
#       （Flyway 社区版无 undo），如需回退数据库请用 backup.sh/restore.sh 的整体恢复。
# =============================================================================
set -euo pipefail

INSTALL_DIR=/opt/tianshu
PACKAGE=""
EE=0
DO_BACKUP=1
TIMEOUT=300

log()  { printf '\033[1;34m[upgrade]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[upgrade]\033[0m %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --package) PACKAGE="$2"; shift 2 ;;
    --install-dir) INSTALL_DIR="$2"; shift 2 ;;
    --ee) EE=1; shift ;;
    --no-backup) DO_BACKUP=0; shift ;;
    --timeout) TIMEOUT="$2"; shift 2 ;;
    -h|--help) sed -n '1,14p' "$0"; exit 0 ;;
    *) die "未知参数: $1" ;;
  esac
done
[ -n "$PACKAGE" ] || die "必须指定 --package"
[ -f "$PACKAGE" ] || die "包不存在: $PACKAGE"
[ "$(id -u)" = "0" ] || die "请用 sudo 执行"

PORT=$(grep -E '^SERVER_PORT=' "$INSTALL_DIR/conf/tianshu.env" | cut -d= -f2)
PORT=${PORT:-8080}
OLD_JAR=$(ls -1 "$INSTALL_DIR/lib"/tianshu-app*.jar | head -1)
OLD_VER=$(basename "$OLD_JAR")

WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
log "0/7 解包并校验新版本"
tar -xzf "$PACKAGE" -C "$WORK"
SRC=$(find "$WORK" -maxdepth 1 -mindepth 1 -type d | head -1)
[ -n "$SRC" ] || die "包结构异常"
if [ -f "$SRC/SHA256SUMS" ]; then ( cd "$SRC" && sha256sum -c SHA256SUMS --quiet ) || die "新包校验失败"; fi
NEW_JAR=$(find "$SRC" -maxdepth 2 -name 'tianshu-app-*.jar' | head -1)
[ -n "$NEW_JAR" ] || die "包内无应用 jar"
log "      当前版本: $OLD_VER -> 新版本: $(basename "$NEW_JAR")"

if [ "$DO_BACKUP" = "1" ] && [ -x "$INSTALL_DIR/scripts/ops/backup.sh" ]; then
  log "1/7 升级前备份"
  "$INSTALL_DIR/scripts/ops/backup.sh" --install-dir "$INSTALL_DIR" --out "$INSTALL_DIR/backup" || die "备份失败，终止升级"
else
  log "1/7 跳过备份"
fi

log "2/7 停止服务"
systemctl stop tianshu.service || true

log "3/7 替换程序（旧 jar 保留为 .prev）"
cp -f "$OLD_JAR" "$OLD_JAR.prev"
mkdir -p "$INSTALL_DIR/lib.prev-$(date +%Y%m%d%H%M%S)"
cp -f "$NEW_JAR" "$INSTALL_DIR/lib/tianshu-app.jar.new"
if [ "$EE" = "1" ]; then
  for j in "$SRC"/tianshu-ee-*.jar; do [ -e "$j" ] && cp -f "$j" "$INSTALL_DIR/lib/" || true; done
fi
mv -f "$INSTALL_DIR/lib/tianshu-app.jar.new" "$INSTALL_DIR/lib/$(basename "$NEW_JAR")"
rm -f "$INSTALL_DIR/lib/$OLD_VER"
chown tianshu:tianshu "$INSTALL_DIR/lib"/*.jar

log "4/7 启动并等待 Flyway 迁移完成"
systemctl start tianshu.service
OK=0
for i in $(seq 1 $((TIMEOUT/3))); do
  C=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1:$PORT/actuator/health/liveness" || true)
  if [ "$C" = "200" ]; then OK=1; break; fi
  sleep 3
done

if [ "$OK" != "1" ]; then
  log "5/7 探活失败，执行回滚"
  systemctl stop tianshu.service || true
  cp -f "$OLD_JAR.prev" "$OLD_JAR"
  chown tianshu:tianshu "$OLD_JAR"
  systemctl start tianshu.service
  sleep 15
  C=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1:$PORT/actuator/health/liveness" || true)
  [ "$C" = "200" ] && log "回滚成功（已恢复 $OLD_VER）" || log "回滚后仍未就绪，请人工介入：$INSTALL_DIR/logs/tianshu.err.log"
  die "升级失败并已回滚；如需整体回退数据库请用 restore.sh"
fi

log "5/7 迁移与健康检查通过"
if command -v psql >/dev/null 2>&1; then
  # shellcheck disable=SC1090
  . "$INSTALL_DIR/conf/tianshu.env"
  U=${PG_URL#jdbc:postgresql://}; H=${U%%/*}; D=${U##*/}; HOST=${H%%:*}; P=${H##*:}
  V=$(PGPASSWORD="$PG_PASSWORD" psql -h "$HOST" -p "${P:-5432}" -U "$PG_USER" -d "$D" -tAc \
    'select max(version) from flyway_schema_history where success' 2>/dev/null | tr -d ' \r')
  log "      当前 schema 版本: V${V:-unknown}"
fi

log "6/7 验收"
if [ -x "$INSTALL_DIR/scripts/ops/verify-install.sh" ]; then
  "$INSTALL_DIR/scripts/ops/verify-install.sh" --url "http://127.0.0.1:$PORT" --json "$INSTALL_DIR/logs/verify-after-upgrade.json" || log "验收有失败项，请人工确认"
fi

log "7/7 写入版本记录"
{
  echo "upgradedAt=$(date -Iseconds)"
  echo "from=$OLD_VER"
  echo "to=$(basename "$NEW_JAR")"
  echo "flywayVersion=${V:-unknown}"
} >> "$INSTALL_DIR/logs/upgrade-history.txt"

log "升级完成: $OLD_VER -> $(basename "$NEW_JAR")"
log "回滚方式: cp $OLD_JAR.prev $OLD_JAR && systemctl restart tianshu"
