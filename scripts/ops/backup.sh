#!/usr/bin/env bash
# =============================================================================
# AxiFlux 备份 (Linux) —— 数据库 + 数据卷 + 配置与授权文件，带校验清单
#
# 用法:
#   sudo ./backup.sh --install-dir /opt/Axiflux [--out /backup/Axiflux] \
#                    [--keep 14] [--encrypt-recipient key.gpg] [--no-db]
#
# 产物: <out>/axiflux-backup-YYYYmmdd-HHMMSS.tar.gz
#       内部: db/axiflux.dump (pg_dump -Fc) / data/（workspace, skills, license, registry-blobs）
#             conf/（axiflux.env 脱敏副本） / MANIFEST.txt / SHA256SUMS
# 恢复: ./restore.sh --file <备份包>
# =============================================================================
set -euo pipefail

INSTALL_DIR=/opt/Axiflux
OUT=/backup/Axiflux
KEEP=14
RECIPIENT=""
WITH_DB=1

log() { printf '[backup] %s\n' "$*"; }
die() { printf '[backup][error] %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --install-dir) INSTALL_DIR="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --keep) KEEP="$2"; shift 2 ;;
    --encrypt-recipient) RECIPIENT="$2"; shift 2 ;;
    --no-db) WITH_DB=0; shift ;;
    -h|--help) sed -n '1,16p' "$0"; exit 0 ;;
    *) die "未知参数: $1" ;;
  esac
done

ENVFILE="$INSTALL_DIR/conf/axiflux.env"
[ -f "$ENVFILE" ] || die "找不到 $ENVFILE"
# shellcheck disable=SC1090
. "$ENVFILE"

STAMP=$(date '+%Y%m%d-%H%M%S')
STAGE=$(mktemp -d)
mkdir -p "$OUT" "$STAGE/db" "$STAGE/conf"

db_creds() { # 从 PG_URL 解析 host/port/db
  local url="${PG_URL:-}"
  url=${url#jdbc:postgresql://}
  PG_HOSTPORT=${url%%/*}; PG_DB=${url##*/}
  PG_HOST=${PG_HOSTPORT%%:*}; PG_PORT=${PG_HOSTPORT##*:}; PG_PORT=${PG_PORT:-5432}
}
db_creds

if [ "$WITH_DB" = "1" ]; then
  log "导出数据库 $PG_DB@$PG_HOST:$PG_PORT"
  if command -v pg_dump >/dev/null 2>&1; then
    PGPASSWORD="$PG_PASSWORD" pg_dump -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" \
      -Fc --no-owner --no-privileges -f "$STAGE/db/axiflux.dump" || die "pg_dump 失败"
    log "  数据库大小: $(du -h "$STAGE/db/axiflux.dump" | cut -f1)"
  elif command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' | grep -qi postgres; then
    C=$(docker ps --format '{{.Names}}' | grep -i postgres | head -1)
    docker exec "$C" pg_dump -U "$PG_USER" -d "$PG_DB" -Fc --no-owner --no-privileges > "$STAGE/db/axiflux.dump" \
      || die "容器内 pg_dump 失败"
    log "  （容器 $C）数据库大小: $(du -h "$STAGE/db/axiflux.dump" | cut -f1)"
  else
    die "既无 pg_dump 也无 postgres 容器可用"
  fi
fi

log "归档数据卷"
for d in workspace skills license; do
  [ -d "$INSTALL_DIR/data/$d" ] && tar -cf "$STAGE/data-$d.tar" -C "$INSTALL_DIR/data" "$d"
done
for d in registry-blobs registry-blobs2 registry-keys registry-keys2; do
  [ -d "$INSTALL_DIR/$d" ] && tar -cf "$STAGE/vol-$d.tar" -C "$INSTALL_DIR" "$d"
done

log "归档配置（脱敏）"
sed -E 's/^(.*(PASSWORD|SECRET|API_KEY|TOKEN)=).*/\1***REDACTED***/' "$ENVFILE" > "$STAGE/conf/axiflux.env.redacted"
for f in "$INSTALL_DIR/lib/LICENSE-EE.md" "$INSTALL_DIR/conf/initial-admin-password.txt"; do
  [ -f "$f" ] && cp -f "$f" "$STAGE/conf/" 2>/dev/null || true
done
[ -f "$INSTALL_DIR/data/license/license.lic" ] && cp -f "$INSTALL_DIR/data/license/license.lic" "$STAGE/conf/license.lic"

JARV=$(ls -1 "$INSTALL_DIR/lib"/axiflux-app*.jar 2>/dev/null | head -1)
DBV=$(command -v psql >/dev/null 2>&1 && PGPASSWORD="$PG_PASSWORD" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" -tAc \
  "select coalesce(max(version),'unknown') from flyway_schema_history where success" 2>/dev/null | tr -d ' \r' || echo unknown)

cat > "$STAGE/MANIFEST.txt" <<EOF
backupTime=$STAMP
hostname=$(hostname)
installDir=$INSTALL_DIR
appJar=$(basename "${JARV:-unknown}")
flywayVersion=${DBV:-unknown}
os=$(cat /etc/os-release 2>/dev/null | grep '^PRETTY_NAME=' | cut -d= -f2- | tr -d '"')
withDatabase=$WITH_DB
pgHost=$PG_HOST
pgPort=$PG_PORT
pgDb=$PG_DB
EOF

cp -f "$INSTALL_DIR/LICENSE" "$STAGE/" 2>/dev/null || true

log "生成校验清单"
( cd "$STAGE" && find . -type f ! -name SHA256SUMS -print0 | xargs -0 sha256sum > SHA256SUMS )

ARCHIVE="$OUT/axiflux-backup-$STAMP.tar.gz"
tar -czf "$ARCHIVE" -C "$STAGE" .
if [ -n "$RECIPIENT" ]; then
  command -v gpg >/dev/null 2>&1 || die "未安装 gpg，无法加密"
  gpg --yes --batch --encrypt --recipient "$RECIPIENT" "$ARCHIVE" && rm -f "$ARCHIVE" && ARCHIVE="$ARCHIVE.gpg"
fi
sha256sum "$ARCHIVE" > "$ARCHIVE.sha256"
rm -rf "$STAGE"

# 保留策略
COUNT=$(ls -1 "$OUT"/axiflux-backup-*.tar.gz* 2>/dev/null | grep -v '\.sha256$' | wc -l | tr -d ' ')
if [ "$COUNT" -gt "$KEEP" ]; then
  ls -1t "$OUT"/axiflux-backup-*.tar.gz* | grep -v '\.sha256$' | tail -n +$((KEEP+1)) | while read -r old; do
    log "清理旧备份 $(basename "$old")"; rm -f "$old" "$old.sha256"
  done
fi

log "完成: $ARCHIVE ($(du -h "$ARCHIVE" | cut -f1))"
log "恢复命令: ./restore.sh --file $ARCHIVE"
