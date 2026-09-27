#!/usr/bin/env bash
# =============================================================================
# 天枢 恢复 (Linux)
#
# 用法:
#   sudo ./restore.sh --file /backup/tianshu/tianshu-backup-20260924-120000.tar.gz \
#                     --install-dir /opt/tianshu [--yes] [--skip-db]
#
# 行为: 校验和验证 -> 停止服务 -> 恢复数据库(pg_restore --clean) -> 还原数据卷/授权 -> 启动 -> 探活
# 危险操作，默认要求 --yes 确认；恢复前会把当前状态做一次快照备份。
# =============================================================================
set -euo pipefail

FILE=""
INSTALL_DIR=/opt/tianshu
YES=0
SKIP_DB=0

log() { printf '[restore] %s\n' "$*"; }
die() { printf '[restore][error] %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --file) FILE="$2"; shift 2 ;;
    --install-dir) INSTALL_DIR="$2"; shift 2 ;;
    --skip-db) SKIP_DB=1; shift ;;
    --yes) YES=1; shift ;;
    -h|--help) sed -n '1,12p' "$0"; exit 0 ;;
    *) die "未知参数: $1" ;;
  esac
done

[ -n "$FILE" ] || die "必须指定 --file"
[ -f "$FILE" ] || die "备份包不存在: $FILE"

if [ -f "$FILE.sha256" ]; then
  log "校验备份包完整性"
  sha256sum -c "$FILE.sha256" --quiet || die "SHA256 校验失败"
fi

if [ "$YES" != "1" ]; then
  echo "即将用 $FILE 覆盖 $INSTALL_DIR 的数据库与数据卷。"
  read -r -p "确认？输入 yes 继续: " a
  [ "$a" = "yes" ] || die "已取消"
fi

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
log "解包备份"
tar -xzf "$FILE" -C "$STAGE"
( cd "$STAGE" && sha256sum -c SHA256SUMS --quiet ) || log "警告：包内清单校验有差异（可能为旧包），继续"
cat "$STAGE/MANIFEST.txt" || true

ENVFILE="$INSTALL_DIR/conf/tianshu.env"
[ -f "$ENVFILE" ] || die "找不到 $ENVFILE"
# shellcheck disable=SC1090
. "$ENVFILE"
URL=${PG_URL#jdbc:postgresql://}; PG_HOSTPORT=${URL%%/*}; PG_DB=${URL##*/}
PG_HOST=${PG_HOSTPORT%%:*}; PG_PORT=${PG_HOSTPORT##*:}; PG_PORT=${PG_PORT:-5432}

log "恢复前快照（安全网）"
[ -x "$INSTALL_DIR/scripts/ops/backup.sh" ] && "$INSTALL_DIR/scripts/ops/backup.sh" --install-dir "$INSTALL_DIR" --out "$INSTALL_DIR/backup" || true

log "停止服务"
systemctl stop tianshu.service || true

if [ "$SKIP_DB" != "1" ] && [ -f "$STAGE/db/tianshu.dump" ]; then
  log "恢复数据库（--clean --if-exists，保留其他库）"
  if command -v pg_restore >/dev/null 2>&1; then
    PGPASSWORD="$PG_PASSWORD" pg_restore -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" \
      --clean --if-exists --no-owner --no-privileges -j 4 "$STAGE/db/tianshu.dump" \
      || log "警告：pg_restore 返回非零（常见于对象已存在），请核对日志"
  elif command -v docker >/dev/null 2>&1; then
    C=$(docker ps -a --format '{{.Names}}' | grep -i postgres | head -1)
    docker exec -i "$C" pg_restore -U "$PG_USER" -d "$PG_DB" --clean --if-exists --no-owner --no-privileges \
      < "$STAGE/db/tianshu.dump" || log "警告：pg_restore 返回非零"
  else
    die "既无 pg_restore 也无 postgres 容器"
  fi
fi

log "还原数据卷"
for t in "$STAGE"/data-*.tar "$STAGE"/vol-*.tar; do
  [ -e "$t" ] || continue
  case "$t" in
    *data-*) tar -xf "$t" -C "$INSTALL_DIR/data" ;;
    *vol-*)  tar -xf "$t" -C "$INSTALL_DIR" ;;
  esac
  log "  $(basename "$t")"
done
[ -f "$STAGE/conf/license.lic" ] && install -d "$INSTALL_DIR/data/license" && cp -f "$STAGE/conf/license.lic" "$INSTALL_DIR/data/license/license.lic"
chown -R tianshu:tianshu "$INSTALL_DIR/data" 2>/dev/null || true

log "启动服务"
systemctl start tianshu.service
for i in $(seq 1 60); do
  C=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1:8080/actuator/health/liveness" || true)
  [ "$C" = "200" ] && break; sleep 3
done
if [ "$C" = "200" ]; then log "恢复完成，服务已就绪"; else die "服务未就绪，请检查日志（已保留恢复前快照）"; fi
