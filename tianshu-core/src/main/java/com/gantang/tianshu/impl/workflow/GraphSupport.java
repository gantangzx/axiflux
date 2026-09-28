package com.gantang.tianshu.impl.workflow;

import com.googlecode.aviator.AviatorEvaluator;
import com.gantang.tianshu.api.workflow.GraphState;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expression/template helpers shared by the graph engine: {@code ${var}} template
 * substitution and Aviator boolean condition evaluation, both over a
 * {@link GraphState}'s variables.
 */
final class GraphSupport {

    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{([^}]+)}");

    private GraphSupport() {}

    /**
     * Replace every {@code ${key}} in {@code template} with the matching state
     * variable (stringified). Missing keys are left as the literal placeholder.
     */
    static String render(String template, GraphState state) {
        if (template == null || template.indexOf('$') < 0) {
            return template;
        }
        Matcher m = TEMPLATE.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1).trim();
            Object value = state.var(key);
            String replacement = value == null ? Matcher.quoteReplacement(m.group())
                : Matcher.quoteReplacement(value.toString());
            m.appendReplacement(sb, replacement);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Evaluate an Aviator expression expected to return a boolean. Non-boolean
     * results are interpreted by truthiness; a null result is false.
     */
    static boolean evaluate(String expression, GraphState state) {
        Object result = AviatorEvaluator.execute(expression, new HashMap<>(state.variables()));
        if (result instanceof Boolean b) {
            return b;
        }
        if (result == null) {
            return false;
        }
        if (result instanceof Number n) {
            return n.doubleValue() != 0;
        }
        return true;
    }

    /**
     * Resolve a static parameter map whose values may be templates (strings) or
     * nested maps/lists, against the current state.
     */
    static Map<String, Object> resolveParams(Map<String, Object> params, GraphState state) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            resolved.put(e.getKey(), resolveValue(e.getValue(), state));
        }
        return resolved;
    }

    private static Object resolveValue(Object value, GraphState state) {
        if (value instanceof String s) {
            return render(s, state);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                nested.put(String.valueOf(e.getKey()), resolveValue(e.getValue(), state));
            }
            return nested;
        }
        return value;
    }
}
