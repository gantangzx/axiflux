package com.gantang.reaxon.impl.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.tool.Tool;
import com.gantang.reaxon.api.tool.ToolResult;
import com.gantang.reaxon.api.tool.policy.RiskLevel;
import com.gantang.reaxon.api.tool.StoredToolResult;
import com.gantang.reaxon.api.tool.ToolResultStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code result_read}: retrieve slices of tool output that was parked in the
 * {@link ToolResultStore} because it exceeded the context cap (roadmap P1-3).
 *
 * <p>Truncated results carry a {@code ref://tool-result/<id>} handle in their
 * omission marker. Two retrieval modes:
 * <ul>
 *   <li><b>range</b> (default): {@code offset} (1-based line) + {@code limit}
 *       lines, returned with line-number prefixes so the model can page;</li>
 *   <li><b>keyword</b>: case-insensitive substring search over the whole
 *       content, returning matches with a few context lines each.</li>
 * </ul>
 *
 * <p>The tool is {@link RiskLevel#SAFE}: it only reads session-scoped parked
 * data, never leaves the process, and sessions cannot see each other's records.
 */
public final class ResultReadTool implements Tool {

    public static final String NAME = "result_read";

    static final int DEFAULT_LIMIT = 200;
    static final int MAX_LIMIT = 2_000;
    static final int KEYWORD_CONTEXT_LINES = 3;
    static final int MAX_KEYWORD_MATCHES = 50;

    private final ToolResultStore store;

    public ResultReadTool(ToolResultStore store) {
        this.store = store;
    }

    @Override public String name() { return NAME; }
    @Override public String group() { return "builtin"; }
    @Override public RiskLevel riskLevel() { return RiskLevel.SAFE; }
    @Override public boolean requiresApproval() { return false; }

    @Override
    public String description() {
        return "Read a slice of a large tool result that was truncated in context. The truncated output "
            + "contains a handle of the form ref://tool-result/<id>. Pass that handle in 'ref'. Use "
            + "offset (1-based starting line) and limit (lines, default 200, max 2000) to page through; "
            + "or pass keyword to search case-insensitively (matches are returned with a few context "
            + "lines). Returned lines are prefixed with their line numbers. Records are session-private.";
    }

    private static final JsonNode SCHEMA = SchemaSupport.parse("""
        {
          "type": "object",
          "properties": {
            "ref": {
              "type": "string",
              "description": "Handle from the truncation marker, e.g. ref://tool-result/tr_1a2b..."
            },
            "offset": {
              "type": "integer",
              "description": "1-based starting line number (default 1). Ignored when keyword is set.",
              "minimum": 1
            },
            "limit": {
              "type": "integer",
              "description": "Maximum number of lines to return (default 200, max 2000).",
              "minimum": 1,
              "maximum": 2000
            },
            "keyword": {
              "type": "string",
              "description": "Optional case-insensitive substring to search for; returns matching windows."
            }
          },
          "required": ["ref"]
        }
        """);

    @Override public JsonNode parameters() { return SCHEMA; }

    @Override
    public ToolResult execute(String callId, Map<String, Object> params, AgentContext ctx) {
        if (params == null || params.get("ref") == null || params.get("ref").toString().isBlank()) {
            return ToolResult.failure(callId, "ref is required (ref://tool-result/<id>)");
        }
        String handle = params.get("ref").toString().trim();
        String id = ToolResultStore.idOf(handle);
        Optional<StoredToolResult> recOpt = store.fetch(ctx.sessionId(), id);
        if (recOpt.isEmpty()) {
            return ToolResult.failure(callId,
                "stored tool result not found or expired: " + handle
                    + " (it belongs to another session, was evicted, or the session was deleted); "
                    + "re-run the original tool if you still need the data");
        }
        StoredToolResult rec = recOpt.get();
        // A trailing newline is a line terminator, not an extra empty line.
        String body = rec.content();
        if (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
        String[] lines = body.split("\n", -1);
        String total = rec.length() + " chars / " + lines.length + " lines";

        Object kw = params.get("keyword");
        if (kw != null && !kw.toString().isBlank()) {
            return keywordSearch(callId, rec, lines, kw.toString(), total);
        }

        int offset = intArg(params, "offset", 1);
        int limit = Math.min(Math.max(intArg(params, "limit", DEFAULT_LIMIT), 1), MAX_LIMIT);
        if (offset < 1) offset = 1;
        int start = offset - 1;
        if (start >= lines.length) {
            return ToolResult.failure(callId,
                "offset " + offset + " is past the end (" + lines.length + " lines) for " + handle);
        }
        int end = Math.min(start + limit, lines.length);
        StringBuilder sb = header(handle, rec, "lines " + (start + 1) + "-" + end
            + " of " + lines.length + " (" + (end - start) + " returned, source: " + total + ")");
        appendRange(sb, lines, start, end);
        if (end < lines.length) {
            sb.append("\n...continue with offset=").append(end + 1)
              .append(" (").append(lines.length - end).append(" more lines)...\n");
        }
        return ToolResult.success(callId, sb.toString(),
            meta(rec, lines.length, start + 1, end));
    }

    private ToolResult keywordSearch(String callId, StoredToolResult rec, String[] lines,
                                     String keyword, String total) {
        String needle = keyword.toLowerCase();
        StringBuilder sb = header(ToolResultStore.HANDLE_PREFIX + rec.id(), rec,
            "keyword " + quote(keyword) + " over " + total);
        int matches = 0;
        int lastShownEnd = -1;
        for (int i = 0; i < lines.length && matches < MAX_KEYWORD_MATCHES; i++) {
            if (!lines[i].toLowerCase().contains(needle)) continue;
            matches++;
            int winFrom = Math.max(0, i - KEYWORD_CONTEXT_LINES);
            int winTo = Math.min(lines.length, i + KEYWORD_CONTEXT_LINES + 1);
            if (matches > 1 && winFrom > lastShownEnd + 1) {
                sb.append("  ...\n");
            }
            int from = Math.max(winFrom, lastShownEnd + 1);
            if (from < winTo) {
                appendRange(sb, lines, from, winTo);
            }
            lastShownEnd = winTo - 1;
        }
        if (matches == 0) {
            return ToolResult.success(callId,
                header(ToolResultStore.HANDLE_PREFIX + rec.id(), rec,
                    "keyword " + quote(keyword) + " over " + total)
                    + "(no matches)\n",
                meta(rec, lines.length, null, null));
        }
        sb.append('\n').append(matches).append(matches == MAX_KEYWORD_MATCHES ? "+ match groups" : " match group(s)")
          .append(" shown; refine the keyword or page by offset for more.\n");
        return ToolResult.success(callId, sb.toString(),
            meta(rec, lines.length, null, null));
    }

    private static StringBuilder header(String handle, StoredToolResult rec, String summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("[result_read ").append(handle);
        if (rec.originTool() != null) sb.append(" origin=").append(rec.originTool());
        sb.append(' ').append(summary).append("]\n");
        return sb;
    }

    /** Append lines [from, to) with {@code N\t} prefixes. */
    private static void appendRange(StringBuilder sb, String[] lines, int from, int to) {
        String fmt = "%" + Math.max(4, Integer.toString(lines.length).length()) + "d\t%s\n";
        for (int i = from; i < to; i++) {
            sb.append(String.format(fmt, i + 1, lines[i]));
        }
    }

    private static Map<String, Object> meta(StoredToolResult rec, int linesTotal,
                                            Integer from, Integer to) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("originTool", rec.originTool());
        m.put("storedLength", rec.length());
        m.put("linesTotal", linesTotal);
        if (from != null) m.put("returnedFrom", from);
        if (to != null) m.put("returnedTo", to);
        return m;
    }

    private static int intArg(Map<String, Object> params, String key, int dflt) {
        Object v = params.get(key);
        if (v == null) return dflt;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\"", "\\\"") + "\"";
    }
}
