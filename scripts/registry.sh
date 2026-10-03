#!/usr/bin/env bash
# Axiflux Skill Registry launcher (Linux / macOS).
#
# Usage: scripts/registry.sh [-f]
#   default: background, logs to logs/registry.log
#   -f:      foreground
#
# Env file (gitignored): env/env.registry.sh
#   (copy env/env.registry.sh.example and fill it)
set -euo pipefail

FOREGROUND=0
[[ "${1:-}" == "-f" ]] && FOREGROUND=1

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ENVFILE="env/env.registry.sh"
if [[ -f "$ENVFILE" ]]; then
  # shellcheck disable=SC1090
  source "$ENVFILE"
else
  echo "[ERROR] $ENVFILE not found. Copy env/env.registry.sh.example and fill it." >&2
  exit 1
fi

: "${JAVA_HOME:=}"
if [[ -n "$JAVA_HOME" ]]; then JAVA_BIN="$JAVA_HOME/bin/java"; else JAVA_BIN="java"; fi

APP_JAR="axiflux-registry/target/axiflux-registry-0.1.0-SNAPSHOT.jar"
if [[ ! -f "$APP_JAR" ]]; then
  echo "[ERROR] jar not found: $APP_JAR" >&2
  echo "Build first:  mvn clean install -DskipTests -pl axiflux-registry -am" >&2
  exit 1
fi

export REGISTRY_PORT="${REGISTRY_PORT:-8090}"
mkdir -p logs
LOG="logs/registry.log"

if [[ "$FOREGROUND" -eq 1 ]]; then
  echo "starting registry on port $REGISTRY_PORT in foreground"
  exec "$JAVA_BIN" -jar "$APP_JAR"
else
  if command -v nohup >/dev/null 2>&1; then
    nohup "$JAVA_BIN" -jar "$APP_JAR" > "$LOG" 2>&1 &
  else
    "$JAVA_BIN" -jar "$APP_JAR" > "$LOG" 2>&1 &
  fi
  echo "launched registry on port $REGISTRY_PORT detached (pid $!); logs -> $LOG"
fi
