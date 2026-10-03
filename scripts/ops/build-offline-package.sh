#!/usr/bin/env bash
# =============================================================================
# AxiFlux 离线分发包组装脚本 (Linux)
#
# 目标：把已构建合格的 fat jar 组装成 docs/offline-install.md §0 规定的标准
#       离线交付目录，并生成 SHA256SUMS 与可分发 tar.gz。
#       本脚本【只组装、不编译】——编译/前端构建请先按 offline-install.md §8 完成。
#
# 用法:
#   ./build-offline-package.sh --jar ../../axiflux-app/target/axiflux-app-0.1.0-SNAPSHOT.jar \
#       --version 1.0.0 [--out /tmp/dist] [--jdk /path/to/jdk.tar.gz] [--ee]
#
# 选项:
#   --jar PATH       应用 fat jar（必填，必须已通过 check-package.py）
#   --version VER    分发包版本号（默认从 jar 文件名推断，如 0.1.0-SNAPSHOT）
#   --out DIR        产物输出根目录（默认 ./target/dist）
#   --jdk PATH|URL   内置 JDK：可为本地 tar.gz 或下载地址；解压后须含 bin/java
#   --ee             企业版组装（同目录需能找到 axiflux-ee-*.jar，并带 LICENSE-EE.md）
#   --no-tar         只产出目录与 SHA256SUMS，不打 tar.gz
#   --skip-check     跳过组装前的 check-package.py 质量门（不建议）
# =============================================================================
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
JAR=""
VERSION=""
OUT=""
JDK_SRC=""
EE=0
DO_TAR=1
SKIP_CHECK=0

log()  { printf '\033[1;34m[build]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --jar) JAR="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --jdk) JDK_SRC="$2"; shift 2 ;;
    --ee) EE=1; shift ;;
    --no-tar) DO_TAR=0; shift ;;
    --skip-check) SKIP_CHECK=1; shift ;;
    -h|--help) sed -n '1,28p' "$0"; exit 0 ;;
    *) die "未知参数: $1" ;;
  esac
done

[ -n "$JAR" ] || die "必须提供 --jar"
[ -f "$JAR" ] || die "fat jar 不存在: $JAR"
JAR=$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")

if [ -z "$VERSION" ]; then
  VERSION=$(basename "$JAR" | sed -E 's/^axiflux-app-(.*)\.jar$/\1/')
fi
[ -n "$VERSION" ] || die "无法推断版本号，请用 --version"
OUT="${OUT:-$ROOT/target/dist}"

JAR_NAME=$(basename "$JAR")
PKG="axiflux-offline-$VERSION"
STAGE="$OUT/$PKG"

# ---------- 0. 组装前质量门 ---------------------------------------------------
if [ "$SKIP_CHECK" = "0" ]; then
  log "0/7 质量门 check-package.py（EE=$EE）"
  EE_FLAG=$([ "$EE" = "1" ] && echo --require-ee || echo --no-ee)
  python3 "$ROOT/scripts/ops/check-package.py" "$JAR" $EE_FLAG
else
  warn "0/7 已跳过 check-package.py（--skip-check）"
fi

# ---------- 1. 干净的暂存目录 -------------------------------------------------
log "1/7 准备暂存目录 $STAGE"
rm -rf "$STAGE"
install -d "$STAGE"/{images,scripts,db}

# ---------- 2. 应用与 EE 模块 -------------------------------------------------
log "2/7 拷贝应用 jar"
cp -f "$JAR" "$STAGE/$JAR_NAME"
if [ "$EE" = "1" ]; then
  JAR_DIR=$(dirname "$JAR")
  EE_COUNT=$(find "$JAR_DIR" -maxdepth 1 -name 'axiflux-ee-*.jar' | wc -l | tr -d ' ')
  [ "$EE_COUNT" != "0" ] || die "--ee 但 $JAR_DIR 下没有 axiflux-ee-*.jar"
  find "$JAR_DIR" -maxdepth 1 -name 'axiflux-ee-*.jar' -exec cp -f {} "$STAGE/" \;
  [ -f "$ROOT/axiflux-ee-core/LICENSE-EE.md" ] && cp -f "$ROOT/axiflux-ee-core/LICENSE-EE.md" "$STAGE/"
  log "      EE 模块数: $EE_COUNT"
fi

# ---------- 3. 内置 JDK（可选，离线必备） -------------------------------------
if [ -n "$JDK_SRC" ]; then
  log "3/7 内置 JDK"
  JDK_TGZ=""
  case "$JDK_SRC" in
    http://*|https://*)
      JDK_TGZ=$(mktemp --suffix=.tar.gz)
      log "      下载 JDK: $JDK_SRC"
      curl -fsSL --retry 3 -o "$JDK_TGZ" "$JDK_SRC"
      ;;
    *)
      [ -f "$JDK_SRC" ] || die "JDK 文件不存在: $JDK_SRC"
      JDK_TGZ="$JDK_SRC"
      ;;
  esac
  JDK_WORK=$(mktemp -d)
  tar -xzf "$JDK_TGZ" -C "$JDK_WORK"
  JDK_HOME=$(find "$JDK_WORK" -maxdepth 2 -name java -type f -path '*/bin/*' \
              | head -1 | sed 's#/bin/java##')
  [ -n "$JDK_HOME" ] || die "JDK 包结构异常（未找到 bin/java）"
  mkdir -p "$STAGE/runtime"
  cp -a "$JDK_HOME" "$STAGE/runtime/jdk"
  "$STAGE/runtime/jdk/bin/java" -version >/dev/null 2>&1 || die "内置 JDK 无法执行"
  log "      内置 JDK: $("$STAGE/runtime/jdk/bin/java" -version 2>&1 | head -1)"
  rm -rf "$JDK_WORK"
  case "$JDK_SRC" in http*://*) rm -f "$JDK_TGZ" ;; esac
else
  warn "3/7 未提供 --jdk：目标机需自行安装 JDK 25（离线环境建议内置）"
fi

# ---------- 4. 编排 / 迁移 / 许可 ---------------------------------------------
log "4/7 拷贝编排、迁移与许可文件"
cp -f "$ROOT/docker-compose.prod.yml" "$STAGE/" 2>/dev/null || warn "缺 docker-compose.prod.yml"
cp -f "$ROOT/docker-compose.offline.yml" "$STAGE/" 2>/dev/null || true
cp -f "$ROOT/LICENSE" "$STAGE/" 2>/dev/null || warn "缺 LICENSE"
cp -f "$ROOT/NOTICE" "$STAGE/" 2>/dev/null || true

# Flyway 迁移副本：优先从已构建的 storage jar 中解出（与交付物一致）
MIG_WORK=$(mktemp -d)
STORAGE_JAR=$(find "$ROOT/axiflux-storage/target" -maxdepth 1 -name 'axiflux-storage-*.jar' ! -name '*.original' | head -1 || true)
if [ -n "$STORAGE_JAR" ] && [ -f "$STORAGE_JAR" ]; then
  (cd "$MIG_WORK" && jar -xf "$STORAGE_JAR" db/migration) 2>/dev/null \
    || (cd "$MIG_WORK" && unzip -q "$STORAGE_JAR" 'db/migration/*')
fi
if [ -d "$MIG_WORK/db/migration" ]; then
  cp -a "$MIG_WORK/db" "$STAGE/"
else
  SRC_MIG="$ROOT/axiflux-storage/src/main/resources/db/migration"
  [ -d "$SRC_MIG" ] && cp -a "$SRC_MIG" "$STAGE/db/migration" || warn "未找到 Flyway 迁移副本"
fi
rm -rf "$MIG_WORK"

# ---------- 5. ops 脚本 -------------------------------------------------------
log "5/7 拷贝 ops 脚本"
mkdir -p "$STAGE/scripts/ops"
cp -f "$ROOT/scripts/ops/"{install,upgrade,backup,restore,verify-install}.sh "$STAGE/scripts/ops/" 2>/dev/null || true
cp -f "$ROOT/scripts/ops/check-package.py" "$STAGE/scripts/ops/" 2>/dev/null || true
chmod +x "$STAGE/scripts/ops/"*.sh 2>/dev/null || true

# ---------- 6. 校验和 ---------------------------------------------------------
log "6/7 生成 SHA256SUMS"
( cd "$STAGE" && find . -type f ! -name SHA256SUMS -print0 \
    | sort -z | xargs -0 sha256sum > SHA256SUMS )

# ---------- 7. 打包 -----------------------------------------------------------
if [ "$DO_TAR" = "1" ]; then
  log "7/7 生成 $OUT/$PKG.tar.gz"
  tar -C "$OUT" -czf "$OUT/$PKG.tar.gz" "$PKG"
  TARBALL="$OUT/$PKG.tar.gz"
else
  warn "7/7 跳过 tar.gz（--no-tar）"
  TARBALL=""
fi

cat <<EOF

=====================================================================
 离线分发包组装完成
---------------------------------------------------------------------
 目录   : $STAGE
 包文件 : ${TARBALL:-（未打包）}
 版本   : $VERSION ｜ EE=$EE
 校验   : (cd $STAGE && sha256sum -c SHA256SUMS)
 交付前 : python3 scripts/ops/check-package.py $STAGE/$JAR_NAME $([ "$EE" = 1 ] && echo --require-ee || echo --no-ee)
=====================================================================
EOF
