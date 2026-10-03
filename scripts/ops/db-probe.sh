#!/usr/bin/env bash
# =============================================================================
# AxiFlux Agent 平台 —— 数据库探测脚本（信创/POC 现场用）
#
# 用途：在目标环境回答「这个数据库能不能跑AxiFlux」这个前置问题，并给出结论与建议。
#   1) TCP 可达性（任意数据库）
#   2) 若为 PostgreSQL：版本、pgvector 扩展、必需扩展、字符集、时区、连接数
#   3) 若提供 JDBC 驱动：返回 DatabaseProductName/Version（识别达梦/金仓/其他）
#   4) 输出结论：可否直接部署 / 需要替代方案 / 必须改造
#
# 用法：
#   ./db-probe.sh --host 10.0.0.11 --port 5432 --mode auto
#   ./db-probe.sh --host 10.0.0.11 --port 5432 --mode psql --user Axiflux --password '***' --db Axiflux
#   ./db-probe.sh --jdbc-url 'jdbc:dm://10.0.0.11:5236' --user SYSDBA --password '***' \
#                 --driver-jar /opt/drivers/DmJdbcDriver18.jar --mode jdbc
#
# 说明：脚本只读探测，不修改任何数据库对象；--jdbc 模式会在临时目录编译一个只读探针类。
# =============================================================================
set -uo pipefail

HOST=""
PORT="5432"
MODE="auto"
USER=""
PASSWORD=""
DB=""
JDBC_URL=""
DRIVER_JAR=""
JSON_OUT=""

usage() {
  sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --host) HOST="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --mode) MODE="$2"; shift 2 ;;
    --user) USER="$2"; shift 2 ;;
    --password) PASSWORD="$2"; shift 2 ;;
    --db) DB="$2"; shift 2 ;;
    --jdbc-url) JDBC_URL="$2"; shift 2 ;;
    --driver-jar) DRIVER_JAR="$2"; shift 2 ;;
    --json) JSON_OUT="$2"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "未知参数: $1"; usage ;;
  esac
done

[ -n "$HOST" ] || [ -n "$JDBC_URL" ] || { echo "必须提供 --host 或 --jdbc-url"; usage; }

PASS=0; WARN=0; FAIL=0
RESULTS=""
note() { RESULTS="${RESULTS}$1\n"; }
ok()   { PASS=$((PASS+1)); printf '  [ PASS ] %s\n' "$1"; note "PASS|$1"; }
warn() { WARN=$((WARN+1)); printf '  [ WARN ] %s\n' "$1"; note "WARN|$1"; }
fail() { FAIL=$((FAIL+1)); printf '  [ FAIL ] %s\n' "$1"; note "FAIL|$1"; }

# --- 1) TCP 可达性 ---------------------------------------------------------
tcp_check() {
  local h="$1" p="$2"
  if command -v timeout >/dev/null 2>&1; then
    timeout 5 bash -c "</dev/tcp/${h}/${p}" 2>/dev/null && return 0
  else
    (exec 3<>"/dev/tcp/${h}/${p}") 2>/dev/null && { exec 3<&- 3>&-; return 0; }
  fi
  return 1
}

# --- 2) psql 探测（PostgreSQL） --------------------------------------------
psql_probe() {
  if ! command -v psql >/dev/null 2>&1; then
    warn "psql 未安装，跳过 PostgreSQL 深度探测（可安装 postgresql-client 后重跑）"
    return 0
  fi
  export PGPASSWORD="$PASSWORD"
  local base=( -h "$HOST" -p "$PORT" -U "${USER:-postgres}" -tAX -c )
  [ -n "$DB" ] && base=( -h "$HOST" -p "$PORT" -U "${USER:-postgres}" -d "$DB" -tAX -c )

  local ver
  ver=$(psql "${base[@]}" 'select version()' 2>/dev/null | head -1)
  if [ -z "$ver" ]; then
    fail "无法连接到 PostgreSQL（认证失败或数据库不可达）：检查 PG_URL/PG_USER/PG_PASSWORD 与 pg_hba.conf"
    return 0
  fi
  ok "PostgreSQL 版本：${ver}"

  case "$ver" in
    *"PostgreSQL 16"*|*"PostgreSQL 17"*|*"PostgreSQL 18"*) ok "主版本满足要求（>= 16）" ;;
    *) warn "主版本不是 16/17/18，建议升级后部署（Flyway V1-V26 在 16 上验证通过）" ;;
  esac

  if psql "${base[@]}" "select 1 from pg_extension where extname='vector'" 2>/dev/null | grep -q 1; then
    ok "pgvector 扩展已安装（长期记忆可用）"
    local vv
    vv=$(psql "${base[@]}" "select extversion from pg_extension where extname='vector'" 2>/dev/null | head -1)
    [ -n "$vv" ] && note "INFO|pgvector version=${vv}"
  else
    fail "未安装 pgvector 扩展 —— 长期记忆（向量检索）不可用。处理：安装 pgvector 并 CREATE EXTENSION vector，或设置 VECTOR_PROVIDER=none 关闭该能力"
  fi

  if psql "${base[@]}" "select 1 from pg_extension where extname='pg_trgm'" 2>/dev/null | grep -q 1; then
    ok "pg_trgm 扩展已安装（模糊检索可用）"
  else
    warn "未安装 pg_trgm（可选）：CREATE EXTENSION pg_trgm"
  fi

  local enc tz maxconn
  enc=$(psql "${base[@]}" "show server_encoding" 2>/dev/null | tr -d '[:space:]')
  [ "$enc" = "UTF8" ] && ok "服务端字符集 UTF8" || warn "服务端字符集为 ${enc:-未知}，建议 UTF8"
  tz=$(psql "${base[@]}" "show timezone" 2>/dev/null | tr -d '[:space:]')
  note "INFO|server timezone=${tz:-unknown}"
  maxconn=$(psql "${base[@]}" "show max_connections" 2>/dev/null | tr -d '[:space:]')
  if [ -n "$maxconn" ] && [ "$maxconn" -ge 100 ] 2>/dev/null; then
    ok "max_connections=${maxconn}（>= 100，满足单实例；多实例需上调或引入 PgBouncer）"
  else
    warn "max_connections=${maxconn:-未知}，偏低（建议 >= 100，多实例场景 >= 300）"
  fi
  unset PGPASSWORD
}

# --- 3) JDBC 探测（达梦/金仓/其他） ---------------------------------------
jdbc_probe() {
  command -v java >/dev/null 2>&1 || { warn "未找到 java，跳过 JDBC 探测"; return 0; }
  [ -n "$JDBC_URL" ] || { warn "未提供 --jdbc-url，跳过 JDBC 探测"; return 0; }
  if [ -n "$DRIVER_JAR" ] && [ ! -f "$DRIVER_JAR" ]; then
    fail "驱动 jar 不存在：$DRIVER_JAR"; return 0
  fi

  local dir; dir=$(mktemp -d)
  cat > "$dir/DbProbe.java" <<'JAVA'
import java.sql.*;

public class DbProbe {
    public static void main(String[] args) throws Exception {
        String url = args[0], user = args[1], pass = args[2];
        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            DatabaseMetaData md = c.getMetaData();
            out("INFO|product=" + md.getDatabaseProductName());
            out("INFO|productVersion=" + md.getDatabaseProductVersion());
            out("INFO|driver=" + md.getDriverName() + " " + md.getDriverVersion());
            out("INFO|jdbcCompliant=" + md.supportsTransactions());
            String p = md.getDatabaseProductName().toLowerCase();
            if (p.contains("postgres")) {
                if (scalar(c, "select count(*) from pg_extension where extname='vector'").equals("1"))
                    out("PASS|pgvector 可用");
                else
                    out("FAIL|未安装 pgvector 扩展（长期记忆不可用）");
            } else if (p.contains("dm") || p.contains("dameng")) {
                out("WARN|检测到达梦：需使用 DM 方言与 DM 版 Flyway 迁移脚本（见 docs/xinchuang-adaptation.md）");
                out("FAIL|达梦无 pgvector 等价能力：需独立 PostgreSQL 承载向量表，或关闭长期记忆");
            } else if (p.contains("kingbase") || p.contains("kingbasees")) {
                out("WARN|检测到金仓：PG 兼容模式下多数语法可用，需逐条验证迁移脚本");
                out("FAIL|金仓无 pgvector 等价能力：需独立 PostgreSQL 承载向量表，或关闭长期记忆");
            } else {
                out("WARN|未识别的数据库产品：" + md.getDatabaseProductName() + "，需人工评估");
            }
        } catch (SQLException e) {
            out("FAIL|连接失败：" + e.getMessage() + " (SQLState=" + e.getSQLState() + ")");
        }
    }
    static String scalar(Connection c, String sql) {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            return r.next() ? String.valueOf(r.getObject(1)) : "";
        } catch (SQLException e) { return "err:" + e.getMessage(); }
    }
    static void out(String s) { System.out.println("PROBE|" + s); }
}
JAVA
  local cp="$dir"
  [ -n "$DRIVER_JAR" ] && cp="$dir${PATH_SEP:-:}$DRIVER_JAR"
  echo "  · 编译并执行 JDBC 探针（临时目录 $dir）"
  local out
  out=$( (cd "$dir" && java "$cp" "$dir/DbProbe.java" "$JDBC_URL" "$USER" "$PASSWORD") 2>&1 )
  if echo "$out" | grep -q '^PROBE|'; then
    while IFS= read -r line; do
      case "$line" in
        PROBE\|INFO\|*) note "${line#PROBE|}"; echo "    · ${line#PROBE|INFO|}" ;;
        PROBE\|PASS\|*) ok "${line#PROBE|PASS|}" ;;
        PROBE\|WARN\|*) warn "${line#PROBE|WARN|}" ;;
        PROBE\|FAIL\|*) fail "${line#PROBE|FAIL|}" ;;
      esac
    done <<< "$out"
  else
    fail "JDBC 探针未能获得结果：$(echo "$out" | tail -2 | tr '\n' ' ')"
  fi
  rm -rf "$dir"
}

echo "AxiFlux数据库探测 (db-probe)"
echo "  目标: ${JDBC_URL:-$HOST:$PORT}  模式: $MODE"
echo ""
if [ -n "$HOST" ]; then
  if tcp_check "$HOST" "$PORT"; then ok "TCP 可达 ${HOST}:${PORT}"; else fail "TCP 不可达 ${HOST}:${PORT}（检查网络策略/防火墙/服务是否启动）"; fi
fi
case "$MODE" in
  auto) [ -n "$JDBC_URL" ] && jdbc_probe || psql_probe ;;
  psql) psql_probe ;;
  jdbc) jdbc_probe ;;
  tcp)  : ;;
  *) echo "未知 --mode: $MODE"; usage ;;
esac

echo ""
echo "----------------------------------------"
printf '结果: PASS=%d WARN=%d FAIL=%d\n' "$PASS" "$WARN" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
  echo "结论: 存在阻断项（见上方 FAIL）。请按提示处理后再部署；向量能力缺失可改用 VECTOR_PROVIDER=none 降级。"
else
  echo "结论: 数据库侧无阻断项，可继续部署。"
fi

if [ -n "$JSON_OUT" ]; then
  {
    echo '{'
    printf '  "target": "%s",\n' "${JDBC_URL:-$HOST:$PORT}"
    printf '  "mode": "%s",\n' "$MODE"
    printf '  "pass": %d, "warn": %d, "fail": %d,\n' "$PASS" "$WARN" "$FAIL"
    echo '  "items": ['
    first=1
    printf '%b' "$RESULTS" | while IFS='|' read -r lvl msg; do
      [ -n "$msg" ] || continue
      [ "$first" -eq 0 ] && printf ',\n'
      first=0
      printf '    {"level": "%s", "message": %s}' "$lvl" "$(printf '%s' "$msg" | sed 's/"/\\"/g; s/^/"/; s/$/"/')"
    done
    echo ''
    echo '  ]'
    echo '}'
  } > "$JSON_OUT" 2>/dev/null || warn "JSON 写入失败：$JSON_OUT"
  echo "报告: $JSON_OUT"
fi

[ "$FAIL" -eq 0 ]
