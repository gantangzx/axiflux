package com.gantang.tianshu.impl.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Built-in tool: read-only SQL query executor.
 * <p>
 * Rejects anything that looks like a mutation ({@code INSERT/UPDATE/DELETE/DROP/…}).
 * Only executes queries starting with {@code SELECT} or {@code WITH}. Row count and
 * response size are bounded to protect the LLM context.
 */
public class DatabaseQueryTool implements Tool {

    private static final JsonNode SCHEMA = SchemaSupport.parse("""
        {
          "type": "object",
          "properties": {
            "sql":       { "type": "string",  "description": "SELECT or WITH statement" },
            "params":    { "type": "array",   "description": "Positional query parameters (bound with '?')" },
            "maxRows":   { "type": "integer", "description": "Max rows to return (default 100)" },
            "timeoutSec":{ "type": "integer", "description": "Query timeout in seconds (default 15)" }
          },
          "required": ["sql"]
        }
        """);

    private static final Pattern MUTATION = Pattern.compile(
        "\\b(insert|update|delete|drop|alter|truncate|create|grant|revoke|merge|call|exec)\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern READ_START = Pattern.compile(
        "^\\s*(select|with)\\b", Pattern.CASE_INSENSITIVE
    );

    private final DataSource dataSource;
    private final int defaultMaxRows;

    public DatabaseQueryTool(DataSource dataSource) {
        this(dataSource, 100);
    }

    public DatabaseQueryTool(DataSource dataSource, int defaultMaxRows) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.defaultMaxRows = defaultMaxRows;
    }

    @Override public String name()        { return "database_query"; }
    @Override public String description() { return "Execute a read-only SQL query (SELECT/WITH) and return rows as JSON."; }
    @Override public JsonNode parameters(){ return SCHEMA; }
    @Override public String group()       { return "builtin"; }

    @Override
    public ToolResult execute(String callId, Map<String, Object> params, AgentContext context) {
        String sql = String.valueOf(params.getOrDefault("sql", "")).trim();
        if (sql.isEmpty()) return ToolResult.failure(callId, "sql is required");
        if (!READ_START.matcher(sql).find()) {
            return ToolResult.failure(callId, "Only SELECT / WITH statements are allowed");
        }
        if (MUTATION.matcher(sql).find()) {
            return ToolResult.failure(callId, "SQL contains a forbidden mutation keyword");
        }

        int maxRows = intOr(params, "maxRows", defaultMaxRows);
        int timeoutSec = intOr(params, "timeoutSec", 15);

        @SuppressWarnings("unchecked")
        List<Object> args = params.get("params") instanceof List<?> raw
            ? (List<Object>) raw
            : List.of();

        // Database-level hard bound: wrap the query and force a LIMIT one larger
        // than the page size (the extra row signals truncation). This caps result
        // transfer at the database even if the driver ignores setMaxRows, and the
        // read-only connection + mutation-keyword check remain defence in depth.
        String cleaned = sql.replaceAll("[;\\s]+$", "");
        String bounded = "SELECT * FROM (" + cleaned + ") AS oc_sub LIMIT " + (maxRows + 1L);

        try (Connection conn = dataSource.getConnection()) {
            conn.setReadOnly(true);
            try (PreparedStatement ps = conn.prepareStatement(bounded)) {
                ps.setQueryTimeout(timeoutSec);
                ps.setMaxRows(maxRows + 1);
                for (int i = 0; i < args.size(); i++) {
                    ps.setObject(i + 1, args.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    return renderResultSet(callId, rs, maxRows);
                }
            }
        } catch (SQLException e) {
            return ToolResult.failure(callId, "SQL error: " + e.getMessage());
        }
    }

    private ToolResult renderResultSet(String callId, ResultSet rs, int maxRows) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        List<String> headers = new ArrayList<>(cols);
        for (int i = 1; i <= cols; i++) headers.add(md.getColumnLabel(i));

        List<Map<String, Object>> rows = new ArrayList<>();
        int count = 0;
        boolean truncated = false;
        while (rs.next()) {
            if (count >= maxRows) {
                truncated = true; // the (maxRows+1)-th row proves there is more
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= cols; i++) {
                row.put(headers.get(i - 1), rs.getObject(i));
            }
            rows.add(row);
            count++;
        }

        StringBuilder text = new StringBuilder("rows=").append(count);
        if (truncated) text.append(" (truncated at maxRows=").append(maxRows).append(")");
        text.append('\n');
        if (!rows.isEmpty()) {
            text.append(String.join(" | ", headers)).append('\n');
            for (Map<String, Object> row : rows) {
                text.append(row.values().stream()
                    .map(v -> v == null ? "NULL" : v.toString())
                    .reduce((a, b) -> a + " | " + b).orElse(""))
                    .append('\n');
            }
        }

        Map<String, Object> meta = new HashMap<>();
        meta.put("rowCount", count);
        meta.put("truncated", truncated);
        meta.put("columns", headers);
        meta.put("rows", rows);
        return ToolResult.success(callId, text.toString(), meta);
    }

    private static int intOr(Map<String, Object> p, String key, int fallback) {
        Object v = p.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }
}
