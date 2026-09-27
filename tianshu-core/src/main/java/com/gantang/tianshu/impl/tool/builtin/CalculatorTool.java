package com.gantang.tianshu.impl.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.googlecode.aviator.AviatorEvaluator;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolResult;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import com.gantang.tianshu.api.tool.policy.RiskLevel;

/**
 * Built-in tool: safe arithmetic calculator, powered by AviatorScript.
 * <p>
 * Whitelist ensures only mathematical expressions are evaluated. AviatorScript
 * natively supports math functions and doesn't require a JS engine.
 * <p>
 * Supported: {@code + - * / % ( ) . ,} plus identifiers
 * {@code sqrt / pow / abs / floor / ceil / round / min / max / log / exp / pi / e}
 * (Aviator identifiers are case-sensitive; use {@code pi}/{@code e} lowercase).
 */
public class CalculatorTool implements Tool {

    private static final JsonNode SCHEMA = SchemaSupport.parse("""
        {
          "type": "object",
          "properties": {
            "expression": {
              "type": "string",
              "description": "Math expression, e.g. '1 + 2 * (3 + 4)' or 'sqrt(16) + pow(2, 8)'"
            }
          },
          "required": ["expression"]
        }
        """);

    private static final Pattern SAFE_EXPR = Pattern.compile(
        "^[\\s0-9+\\-*/%().,]*"
        + "(?:(?:sqrt|pow|abs|floor|ceil|round|min|max|log|exp|pi|e)"
        + "[\\s0-9+\\-*/%().,]*)*$"
    );

    @Override public String name()        { return "calculator"; }
    @Override public String description() { return "Evaluate a math expression and return the numeric result."; }
    @Override public JsonNode parameters(){ return SCHEMA; }
    @Override public String group()       { return "builtin"; }
    @Override public RiskLevel riskLevel() { return RiskLevel.SAFE; }

    @Override
    public ToolResult execute(String callId, Map<String, Object> params, AgentContext context) {
        String expr = String.valueOf(params.getOrDefault("expression", "")).trim();
        if (expr.isEmpty()) {
            return ToolResult.failure(callId, "expression is required");
        }
        if (!SAFE_EXPR.matcher(expr).matches()) {
            return ToolResult.failure(callId, "Unsafe or unsupported characters in expression");
        }
        try {
            String normalized = expr
                .replaceAll("\\bsqrt\\b",  "math.sqrt")
                .replaceAll("\\bpow\\b",   "math.pow")
                .replaceAll("\\babs\\b",   "math.abs")
                .replaceAll("\\bfloor\\b", "math.floor")
                .replaceAll("\\bceil\\b",  "math.ceil")
                .replaceAll("\\bround\\b", "math.round")
                .replaceAll("\\bmin\\b",   "math.min")
                .replaceAll("\\bmax\\b",   "math.max")
                .replaceAll("\\blog\\b",   "math.log")
                .replaceAll("\\bexp\\b",   "math.exp")
                .replaceAll("\\bPI\\b",    "math.pi")
                .replaceAll("\\bE\\b",     "math.e")
                .replaceAll("\\bpi\\b",    "math.pi")
                .replaceAll("\\be\\b",     "math.e");
            Object result = AviatorEvaluator.execute(normalized);
            Map<String, Object> meta = new HashMap<>();
            meta.put("expression", expr);
            meta.put("value", result);
            return ToolResult.success(callId, String.valueOf(result), meta);
        } catch (Exception e) {
            return ToolResult.failure(callId, "Evaluation error: " + e.getMessage());
        }
    }
}
