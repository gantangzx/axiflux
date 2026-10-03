package com.gantang.axiflux.spring.config;

import com.gantang.reaxon.api.llm.ModelCost;
import com.gantang.reaxon.impl.llm.router.CostOptimizedRouter;
import com.gantang.reaxon.impl.llm.router.DefaultModelRouter;
import com.gantang.axiflux.spring.config.props.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Construction logic for LLM clients and model routing, factored out of the
 * auto-configuration so wiring stays declarative.
 *
 * <p>Covers three things that were inline in {@code AxifluxAutoConfiguration}:
 * the API-key resolution chain (provider env var → property → ARK plan key
 * fallback), the best-known context-window sizing per model id, and the
 * configuration-driven router/price-table construction.
 */
public final class LlmClientFactory {

    private static final Logger log = LoggerFactory.getLogger(LlmClientFactory.class);

    /** Fallback context window for unknown models: 128k, a safe upper bound for most hosted models. */
    public static final int DEFAULT_CONTEXT_WINDOW = 128_000;

    private LlmClientFactory() {}

    /**
     * Resolve a provider API key: the provider's own environment variable wins,
     * then the explicit property value; otherwise the ARK plan key activates every
     * compatible provider as a fallback. Returns "" when nothing is configured.
     */
    public static String resolveApiKey(String envKey, String propValue) {
        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) return env;
        if (propValue != null && !propValue.isBlank()) return propValue;
        String ark = System.getenv("ARK_API_KEY");
        return (ark != null && !ark.isBlank()) ? ark : "";
    }

    /**
     * Best-known context window for a model id, used to size the per-turn token
     * budget so history is trimmed to the routed model rather than a global default.
     */
    public static int contextWindowFor(String model) {
        if (model == null || model.isBlank()) return DEFAULT_CONTEXT_WINDOW;
        String m = model.toLowerCase();
        if (m.startsWith("claude") || m.startsWith("ark-") || m.contains("doubao")) return 200_000;
        if (m.startsWith("deepseek")) return 64_000;
        if (m.startsWith("gpt-4o") || m.startsWith("gpt-4-turbo")) return 128_000;
        if (m.startsWith("gpt-3.5")) return 16_385;
        if (m.contains("llama-3") || m.contains("llama3")) return 8_000;
        return DEFAULT_CONTEXT_WINDOW;
    }

    /** Pick the routing strategy named in configuration; unknown names fall back to capability. */
    public static DefaultModelRouter newRouter(
            LlmProperties.RoutingProperties routing) {
        String strategy = routing.getStrategy() == null
            ? "" : routing.getStrategy().trim().toLowerCase();
        return switch (strategy) {
            case "cost_optimized", "cost-optimized", "cost" ->
                new CostOptimizedRouter(priceTable(routing));
            case "", "capability", "default" -> new DefaultModelRouter();
            default -> {
                log.warn("Unknown axiflux.llm.routing.strategy='{}'; using capability routing. "
                    + "Known values: capability, cost_optimized.", routing.getStrategy());
                yield new DefaultModelRouter();
            }
        };
    }

    /**
     * Build the provider → price map, skipping partially declared entries. A
     * half-specified price would silently be read as zero for the missing half and
     * make that provider look free.
     */
    public static Map<String, ModelCost> priceTable(
            LlmProperties.RoutingProperties routing) {
        Map<String, ModelCost> out = new LinkedHashMap<>();
        routing.getCosts().forEach((name, cost) -> {
            if (name == null || name.isBlank() || cost == null) return;
            if (!cost.isComplete()) {
                log.warn("Ignoring incomplete cost entry for provider '{}': "
                    + "both input-per-1k and output-per-1k are required.", name);
                return;
            }
            try {
                out.put(name.trim(), new ModelCost(cost.getInputPer1k(), cost.getOutputPer1k(),
                    cost.getCachedInputPer1k()));
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring invalid cost entry for provider '{}': {}", name, e.getMessage());
            }
        });
        return Map.copyOf(out);
    }
}
