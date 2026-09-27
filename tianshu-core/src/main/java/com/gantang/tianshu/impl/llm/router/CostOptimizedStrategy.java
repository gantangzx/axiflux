package com.gantang.tianshu.impl.llm.router;

import com.gantang.tianshu.api.llm.LlmClient;
import com.gantang.tianshu.api.llm.ModelCost;
import com.gantang.tianshu.api.llm.RoutingContext;
import com.gantang.tianshu.api.session.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Cost-optimized routing driven by a configured price table.
 *
 * <p>Policy:
 * <ol>
 *   <li>An explicit {@code forcedModel} always wins — cost never overrides an
 *       operator's or user's deliberate choice.</li>
 *   <li>Estimate the turn's prompt size, add an assumed completion size, and price
 *       every candidate with {@link ModelCost#estimate}.</li>
 *   <li>Simple turns take the cheapest candidate. Turns that need a stronger model
 *       take the cheapest candidate priced at or above the median — declared price is
 *       the only capability signal a price table carries, so it doubles as a
 *       capability proxy. Deployments needing real capability metadata implement
 *       {@link com.gantang.tianshu.api.llm.RoutingStrategy} directly; that SPI is public.</li>
 * </ol>
 *
 * <p>Providers with no declared price are <em>excluded</em> from comparison — an
 * unpriced provider means "cost unknown", never "free". When no provider has a
 * declared price the strategy degrades to {@link CapabilityStrategy} rather than
 * pretending to optimize, and says so once at construction time.
 */
public final class CostOptimizedStrategy extends AbstractRoutingStrategy {

    private static final Logger log = LoggerFactory.getLogger(CostOptimizedStrategy.class);

    /**
     * Completion length assumed when pricing a turn. The real length is unknown before
     * the call, and every candidate is charged the same assumption, so this only needs
     * to be representative enough to weight output price against input price.
     */
    private static final long DEFAULT_ASSUMED_COMPLETION_TOKENS = 800;

    private final Map<String, ModelCost> prices;
    private final long assumedCompletionTokens;
    private final CapabilityStrategy fallback = new CapabilityStrategy();

    public CostOptimizedStrategy(Map<String, ModelCost> prices) {
        this(prices, DEFAULT_ASSUMED_COMPLETION_TOKENS);
    }

    public CostOptimizedStrategy(Map<String, ModelCost> prices, long assumedCompletionTokens) {
        this.prices = prices == null ? Map.of() : Map.copyOf(prices);
        this.assumedCompletionTokens = assumedCompletionTokens > 0
            ? assumedCompletionTokens : DEFAULT_ASSUMED_COMPLETION_TOKENS;
        if (this.prices.isEmpty()) {
            log.warn("Cost-optimized routing selected but no prices are configured "
                + "(tianshu.llm.costs.<provider>.input-per-1k/output-per-1k). "
                + "Falling back to capability-based routing.");
        } else {
            log.info("Cost-optimized routing active for {} priced provider(s): {}",
                this.prices.size(), this.prices.keySet());
        }
    }

    @Override
    public String name() {
        return "cost_optimized";
    }

    @Override
    public LlmClient select(RoutingContext ctx, Map<String, LlmClient> clients) {
        LlmClient forced = resolveForced(ctx, clients);
        if (forced != null) {
            return forced;
        }
        if (prices.isEmpty() || clients.isEmpty()) {
            return fallback.select(ctx, clients);
        }

        long promptTokens = estimatePromptTokens(ctx);
        List<Priced> candidates = new ArrayList<>();
        for (Map.Entry<String, LlmClient> e : clients.entrySet()) {
            ModelCost cost = prices.get(e.getKey());
            if (cost == null) {
                continue; // cost unknown — not eligible for a cost decision
            }
            candidates.add(new Priced(e.getValue(), cost.estimate(promptTokens, assumedCompletionTokens)));
        }
        if (candidates.isEmpty()) {
            log.debug("No registered provider has a declared price; using capability routing");
            return fallback.select(ctx, clients);
        }
        candidates.sort(Comparator.comparingDouble(Priced::estimatedCost));

        List<Priced> eligible = fallback.needsStrongModel(ctx)
            ? atOrAboveMedian(candidates)
            : candidates;
        Priced chosen = eligible.get(0);
        if (log.isDebugEnabled()) {
            log.debug("Cost routing: promptTokens~{} chose {} at ~{} (candidates={})",
                promptTokens, chosen.client().provider(), chosen.estimatedCost(), candidates.size());
        }
        return chosen.client();
    }

    /**
     * The upper half of the price-sorted candidates (median inclusive) — the cheapest
     * of these is the cost-optimal choice that still clears the capability floor.
     */
    private List<Priced> atOrAboveMedian(List<Priced> sortedAscending) {
        if (sortedAscending.size() < 2) {
            return sortedAscending;
        }
        int medianIndex = sortedAscending.size() / 2;
        return sortedAscending.subList(medianIndex, sortedAscending.size());
    }

    /** A candidate provider with its estimated cost for the current turn. */
    private record Priced(LlmClient client, double estimatedCost) {}

    /** Visible for tests: the turn size this strategy would price. */
    long estimatedPromptTokens(List<Message> messages, String query) {
        return estimatePromptTokens(new RoutingContext(query, messages, null));
    }
}
