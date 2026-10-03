package com.gantang.reaxon.impl.llm.router;

import com.gantang.reaxon.api.llm.ModelCost;

import java.util.Map;

/**
 * A {@link DefaultModelRouter} preconfigured with {@link CostOptimizedStrategy}.
 *
 * <p>Previously this class was an empty alias of {@link DefaultModelRouter}, so selecting
 * {@code axiflux.llm.routing.strategy=cost_optimized} changed nothing. It now carries the
 * configured price table and performs real cost-based selection; with an empty table it
 * degrades to capability routing and logs that pricing is unconfigured.
 */
public class CostOptimizedRouter extends DefaultModelRouter {

    /**
     * @param prices provider name → token pricing, from {@code axiflux.llm.costs.*}.
     *               May be empty, in which case routing degrades to capability-based.
     */
    public CostOptimizedRouter(Map<String, ModelCost> prices) {
        setStrategy(new CostOptimizedStrategy(prices));
    }

    /** No pricing configured: behaves as capability routing and warns once. */
    public CostOptimizedRouter() {
        this(Map.of());
    }
}
