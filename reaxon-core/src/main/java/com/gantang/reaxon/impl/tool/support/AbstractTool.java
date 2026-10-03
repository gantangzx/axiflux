package com.gantang.reaxon.impl.tool.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.tool.Tool;
import com.gantang.reaxon.api.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * Template Method base for {@link Tool} implementations.
 *
 * <p>Handles cross-cutting concerns so subclasses focus on business logic:
 * <ul>
 *   <li>Required-parameter validation against JSON Schema</li>
 *   <li>Typed parameter extraction with sensible defaults</li>
 *   <li>Uniform exception handling → {@link ToolResult#failure}</li>
 *   <li>Group and approval defaults</li>
 * </ul>
 *
 * <p>Subclasses implement:
 * <ul>
 *   <li>{@link #name()}, {@link #description()}, {@link #parameters()}</li>
 *   <li>{@link #doExecute(String, Params, AgentContext)} — the actual work</li>
 * </ul>
 *
 * <p>Design pattern: <b>Template Method</b> — {@code execute()} defines the
 * skeleton (validate → extract → delegate → handle errors), subclasses fill
 * in the variable parts.
 */
public abstract class AbstractTool implements Tool {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Override
    public String group() {
        return "builtin";
    }

    /**
     * Template method: validate → extract → delegate → handle errors.
     * Subclasses should NOT override this; override {@link #doExecute} instead.
     */
    @Override
    public final ToolResult execute(String callId, Map<String, Object> rawParams, AgentContext context) {
        Params params = new Params(rawParams != null ? rawParams : Map.of());

        // Validate required fields from JSON schema
        List<String> missing = params.validateRequired(parameters());
        if (!missing.isEmpty()) {
            return ToolResult.failure(callId,
                "Missing required parameter(s): " + String.join(", ", missing));
        }

        try {
            return doExecute(callId, params, context);
        } catch (IllegalArgumentException e) {
            log.debug("Tool {} argument error: {}", name(), e.getMessage());
            return ToolResult.failure(callId, "Invalid argument: " + e.getMessage());
        } catch (Exception e) {
            log.warn("Tool {} failed: {}", name(), e.getMessage(), e);
            return ToolResult.failure(callId,
                e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Reactive template: same validation and error wrapping as the synchronous
     * {@link #execute}, then delegates to {@link #doExecuteReactive}. Subclasses
     * should NOT override this; override {@link #doExecuteReactive} instead.
     */
    @Override
    public final Mono<ToolResult> executeReactive(String callId, Map<String, Object> rawParams,
                                                  AgentContext context) {
        Params params = new Params(rawParams != null ? rawParams : Map.of());

        List<String> missing = params.validateRequired(parameters());
        if (!missing.isEmpty()) {
            return Mono.just(ToolResult.failure(callId,
                "Missing required parameter(s): " + String.join(", ", missing)));
        }

        return doExecuteReactive(callId, params, context)
            .onErrorResume(IllegalArgumentException.class, e -> {
                log.debug("Tool {} argument error: {}", name(), e.getMessage());
                return Mono.just(ToolResult.failure(callId, "Invalid argument: " + e.getMessage()));
            })
            .onErrorResume(e -> {
                log.warn("Tool {} failed: {}", name(), e.getMessage(), e);
                return Mono.just(ToolResult.failure(callId,
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
            });
    }

    /**
     * Subclass-specific execution logic.
     *
     * @param callId  unique tool call ID
     * @param params  typed parameter accessor
     * @param context agent context
     * @return the tool result (success or failure)
     */
    protected abstract ToolResult doExecute(String callId, Params params, AgentContext context);

    /**
     * Reactive execution hook. Default bridges {@link #doExecute} onto
     * boundedElastic. Tools with non-blocking IO override this to return a
     * proper {@link Mono} (e.g. an async HTTP call) so no thread is held
     * while waiting. Parameter validation and exception-to-failure wrapping
     * are handled by {@link #executeReactive}.
     */
    protected Mono<ToolResult> doExecuteReactive(String callId, Params params, AgentContext context) {
        return Mono.fromCallable(() -> doExecute(callId, params, context))
            .subscribeOn(Schedulers.boundedElastic());
    }

    // ─── Typed parameter accessor ──────────────────────────────────────────

    /**
     * Type-safe wrapper around the raw parameter map.
     * Provides convenient getters with type coercion and default values.
     */
    public static final class Params {
        private final Map<String, Object> raw;

        Params(Map<String, Object> raw) {
            this.raw = raw;
        }

        public String getString(String key) {
            Object v = raw.get(key);
            return v != null ? String.valueOf(v) : null;
        }

        public String getString(String key, String defaultValue) {
            String v = getString(key);
            return v != null && !v.isBlank() ? v : defaultValue;
        }

        public int getInt(String key, int defaultValue) {
            Object v = raw.get(key);
            if (v instanceof Number n) return n.intValue();
            if (v instanceof String s && !s.isBlank()) {
                try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
            }
            return defaultValue;
        }

        public long getLong(String key, long defaultValue) {
            Object v = raw.get(key);
            if (v instanceof Number n) return n.longValue();
            if (v instanceof String s && !s.isBlank()) {
                try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) {}
            }
            return defaultValue;
        }

        public double getDouble(String key, double defaultValue) {
            Object v = raw.get(key);
            if (v instanceof Number n) return n.doubleValue();
            if (v instanceof String s && !s.isBlank()) {
                try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) {}
            }
            return defaultValue;
        }

        public boolean getBool(String key, boolean defaultValue) {
            Object v = raw.get(key);
            if (v instanceof Boolean b) return b;
            if (v instanceof String s) return Boolean.parseBoolean(s);
            return defaultValue;
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> getMap(String key) {
            Object v = raw.get(key);
            return v instanceof Map ? (Map<String, Object>) v : Map.of();
        }

        @SuppressWarnings("unchecked")
        public List<Object> getList(String key) {
            Object v = raw.get(key);
            return v instanceof List ? (List<Object>) v : List.of();
        }

        public boolean has(String key) {
            return raw.containsKey(key) && raw.get(key) != null;
        }

        public Object get(String key) {
            return raw.get(key);
        }

        public Map<String, Object> raw() {
            return raw;
        }

        /**
         * Validate that all fields listed in the JSON Schema "required" array
         * are present and non-blank in the parameter map.
         *
         * @return list of missing field names (empty if all present)
         */
        List<String> validateRequired(JsonNode schema) {
            if (schema == null || !schema.has("required")) return List.of();
            java.util.List<String> missing = new java.util.ArrayList<>();
            for (JsonNode field : schema.get("required")) {
                String name = field.asText();
                Object val = raw.get(name);
                if (val == null || (val instanceof String s && s.isBlank())) {
                    missing.add(name);
                }
            }
            return missing;
        }
    }
}
