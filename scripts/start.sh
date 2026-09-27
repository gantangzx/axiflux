#!/usr/bin/env bash
# ============================================================================
# Tianshu unified launcher (Linux / macOS).
#
# Usage:
#   scripts/start.sh [local|dev|test|prod] [-f]
#     profile defaults to local.
#     -f  run in foreground (default: background, logs to logs/boot-<profile>.log).
#
# Env files (gitignored), sourced automatically when present:
#   env/env.local.sh  env/env.dev.sh  env/env.test.sh  env/env.prod.sh
# Copy the corresponding template in env/ and fill in secrets.
# ============================================================================

set -euo pipefail

PROFILE="local"
FOREGROUND=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    -f) FOREGROUND=1; shift ;;
    local|dev|test|prod) PROFILE="$1"; shift ;;
    *) echo "[ERROR] unknown arg '$1'; expected local, dev, test or prod" >&2; exit 2 ;;
  esac
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ENVFILE="env/env.${PROFILE}.sh"
if [[ -f "$ENVFILE" ]]; then
  # shellcheck disable=SC1090
  source "$ENVFILE"
elif [[ "$PROFILE" != "local" ]]; then
  echo "[ERROR] $ENVFILE not found. Copy env/env.${PROFILE}.sh.example and fill it." >&2
  exit 1
fi

: "${JAVA_HOME:=}"
# An inherited JAVA_HOME that does not contain a usable java must be ignored so
# the app does not crash on an incompatible runtime (mirrors start.bat behavior).
if [[ -n "$JAVA_HOME" && ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "[WARN] JAVA_HOME '$JAVA_HOME' has no bin/java; falling back to PATH" >&2
  JAVA_HOME=""
fi
if [[ -n "$JAVA_HOME" ]]; then JAVA_BIN="$JAVA_HOME/bin/java"; else JAVA_BIN="java"; fi
if ! command -v "$JAVA_BIN" >/dev/null 2>&1; then
  echo "[ERROR] no usable java runtime found (JAVA_HOME='$JAVA_HOME')" >&2
  exit 1
fi

APP_JAR="tianshu-app/target/tianshu-app-0.1.0-SNAPSHOT.jar"
if [[ ! -f "$APP_JAR" ]]; then
  echo "[ERROR] jar not found: $APP_JAR" >&2
  echo "Build first:  mvn clean install -DskipTests" >&2
  exit 1
fi

export SPRING_PROFILES_ACTIVE="$PROFILE"
mkdir -p logs
LOG="logs/boot-${PROFILE}.log"

if [[ "$FOREGROUND" -eq 1 ]]; then
  echo "starting '$PROFILE' in foreground"
  exec "$JAVA_BIN" -jar "$APP_JAR"
else
  if command -v nohup >/dev/null 2>&1; then
    nohup "$JAVA_BIN" -jar "$APP_JAR" > "$LOG" 2>&1 &
  else
    "$JAVA_BIN" -jar "$APP_JAR" > "$LOG" 2>&1 &
  fi
  echo "launched profile '$PROFILE' detached (pid $!); logs -> $LOG"
fi
