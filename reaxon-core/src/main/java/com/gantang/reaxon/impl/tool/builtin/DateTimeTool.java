package com.gantang.reaxon.impl.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.tool.Tool;
import com.gantang.reaxon.api.tool.ToolResult;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import com.gantang.reaxon.api.tool.policy.RiskLevel;

/**
 * Built-in tool: return current date/time or parse date operations.
 * <p>
 * Supported operations:
 * <ul>
 *   <li>{@code "now"} — current wall-clock time in given zone (default {@code Asia/Shanghai})</li>
 *   <li>{@code "diff"} — difference between two ISO-8601 instants</li>
 *   <li>{@code "add"} — add duration to an instant</li>
 * </ul>
 */
public class DateTimeTool implements Tool {

    private static final JsonNode SCHEMA = SchemaSupport.parse("""
        {
          "type": "object",
          "properties": {
            "operation": {
              "type": "string",
              "enum": ["now", "diff", "add"],
              "description": "Operation to perform"
            },
            "zone": {
              "type": "string",
              "description": "IANA time zone id (default Asia/Shanghai)"
            },
            "format": {
              "type": "string",
              "description": "DateTimeFormatter pattern; default ISO_OFFSET_DATE_TIME"
            },
            "from": { "type": "string", "description": "ISO-8601 instant (for diff/add)" },
            "to":   { "type": "string", "description": "ISO-8601 instant (for diff)" },
            "amount": { "type": "integer", "description": "Amount for add (positive/negative)" },
            "unit":   {
              "type": "string",
              "enum": ["seconds", "minutes", "hours", "days"],
              "description": "Unit for add"
            }
          },
          "required": ["operation"]
        }
        """);

    @Override public String name()        { return "date_time"; }
    @Override public String description() { return "Get current date/time, compute differences, or add durations."; }
    @Override public JsonNode parameters(){ return SCHEMA; }
    @Override public String group()       { return "builtin"; }
    @Override public RiskLevel riskLevel() { return RiskLevel.SAFE; }

    @Override
    public ToolResult execute(String callId, Map<String, Object> params, AgentContext context) {
        String op = String.valueOf(params.getOrDefault("operation", "now"));
        try {
            return switch (op) {
                case "now"  -> now(callId, params);
                case "diff" -> diff(callId, params);
                case "add"  -> add(callId, params);
                default -> ToolResult.failure(callId, "Unknown operation: " + op);
            };
        } catch (DateTimeParseException e) {
            return ToolResult.failure(callId, "Invalid ISO-8601 date: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.failure(callId, "date_time error: " + e.getMessage());
        }
    }

    private ToolResult now(String callId, Map<String, Object> params) {
        String zone = (String) params.getOrDefault("zone", "Asia/Shanghai");
        String fmt = (String) params.getOrDefault("format", "");
        ZonedDateTime zdt = ZonedDateTime.now(ZoneId.of(zone));
        DateTimeFormatter f = fmt.isBlank()
            ? DateTimeFormatter.ISO_OFFSET_DATE_TIME
            : DateTimeFormatter.ofPattern(fmt);
        String formatted = zdt.format(f);
        Map<String, Object> meta = new HashMap<>();
        meta.put("epochSecond", zdt.toEpochSecond());
        meta.put("zone", zone);
        return ToolResult.success(callId, formatted, meta);
    }

    private ToolResult diff(String callId, Map<String, Object> params) {
        Instant from = Instant.parse(requireStr(params, "from"));
        Instant to   = Instant.parse(requireStr(params, "to"));
        Duration d = Duration.between(from, to);
        Map<String, Object> meta = new HashMap<>();
        meta.put("seconds", d.getSeconds());
        meta.put("minutes", d.toMinutes());
        meta.put("hours",   d.toHours());
        meta.put("days",    d.toDays());
        return ToolResult.success(callId,
            "diff: " + d.toDays() + "d " + (d.toHours() % 24) + "h "
                     + (d.toMinutes() % 60) + "m " + (d.getSeconds() % 60) + "s",
            meta);
    }

    private ToolResult add(String callId, Map<String, Object> params) {
        Instant from = Instant.parse(requireStr(params, "from"));
        long amount = ((Number) params.get("amount")).longValue();
        String unit = String.valueOf(params.getOrDefault("unit", "seconds"));
        Instant result = switch (unit) {
            case "seconds" -> from.plusSeconds(amount);
            case "minutes" -> from.plus(Duration.ofMinutes(amount));
            case "hours"   -> from.plus(Duration.ofHours(amount));
            case "days"    -> from.plus(Duration.ofDays(amount));
            default -> throw new IllegalArgumentException("Unknown unit: " + unit);
        };
        return ToolResult.success(callId, result.toString());
    }

    private static String requireStr(Map<String, Object> p, String k) {
        Object v = p.get(k);
        if (v == null) throw new IllegalArgumentException("Missing required param: " + k);
        return v.toString();
    }
}
