package com.gantang.reaxon.impl.llm.router;

import com.gantang.reaxon.api.llm.CompletionRequest;
import com.gantang.reaxon.api.llm.CompletionResponse;
import com.gantang.reaxon.api.llm.LlmClient;
import com.gantang.reaxon.api.llm.ModelCost;
import com.gantang.reaxon.api.llm.RoutingContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the config-driven cost model: cheapest wins on simple turns, complex turns
 * clear a capability floor, an unpriced provider is never assumed free, and an empty
 * price table degrades to capability routing instead of pretending to optimize.
 */
class CostOptimizedStrategyTest {

    private static LlmClient stub(String provider, String model) {
        return new LlmClient() {
            @Override public String provider() { return provider; }
            @Override public String primaryModel() { return model; }
            @Override public CompletionResponse complete(CompletionRequest r) { return null; }
            @Override public Flux<String> completeStream(CompletionRequest r) { return Flux.empty(); }
            @Override public CompletionResponse completeWithTools(CompletionRequest r,
                                                                 com.fasterxml.jackson.databind.JsonNode t) {
                return null;
            }
        };
    }

    private static RoutingContext turn(String query) {
        return new RoutingContext(query, List.of(), null);
    }

    @Test
    void simpleTurnTakesTheCheapestPricedProvider() {
        LlmClient cheap = stub("cheap", "m-cheap");
        LlmClient pricey = stub("pricey", "m-pricey");
        var strategy = new CostOptimizedStrategy(Map.of(
            "cheap", new ModelCost(0.1, 0.2),
            "pricey", new ModelCost(3.0, 9.0)));

        LlmClient picked = strategy.select(turn("hello"),
            Map.of("cheap", cheap, "pricey", pricey));

        assertSame(cheap, picked);
    }

    @Test
    void complexTurnEscalatesAboveTheMedianPrice() {
        // "分析" is a reasoning marker, so the turn needs a stronger model; with price
        // as the only capability signal, the cheapest tier is no longer eligible.
        LlmClient cheap = stub("cheap", "m-cheap");
        LlmClient mid = stub("mid", "m-mid");
        LlmClient pricey = stub("pricey", "m-pricey");
        var strategy = new CostOptimizedStrategy(Map.of(
            "cheap", new ModelCost(0.1, 0.2),
            "mid", new ModelCost(1.0, 2.0),
            "pricey", new ModelCost(9.0, 27.0)));

        LlmClient picked = strategy.select(turn("请分析这段逻辑"),
            Map.of("cheap", cheap, "mid", mid, "pricey", pricey));

        // Cheapest candidate at or above the median — mid, not pricey: escalation
        // clears the floor without buying the most expensive tier available.
        assertSame(mid, picked);
    }

    @Test
    void unpricedProviderIsNotTreatedAsFree() {
        LlmClient priced = stub("priced", "m-priced");
        LlmClient unknown = stub("unknown", "m-unknown");
        var strategy = new CostOptimizedStrategy(Map.of("priced", new ModelCost(5.0, 10.0)));

        LlmClient picked = strategy.select(turn("hello"),
            Map.of("priced", priced, "unknown", unknown));

        // A missing price means "cost unknown". Reading it as zero would make every
        // unpriced provider win every comparison.
        assertSame(priced, picked);
    }

    @Test
    void forcedModelOverridesCost() {
        LlmClient cheap = stub("cheap", "m-cheap");
        LlmClient pricey = stub("pricey", "m-pricey");
        var strategy = new CostOptimizedStrategy(Map.of(
            "cheap", new ModelCost(0.1, 0.2),
            "pricey", new ModelCost(3.0, 9.0)));

        LlmClient picked = strategy.select(new RoutingContext("hello", List.of(), "pricey"),
            Map.of("cheap", cheap, "pricey", pricey));

        assertSame(pricey, picked, "a deliberate model choice must not be overridden by cost");
    }

    @Test
    void emptyPriceTableDegradesToCapabilityRouting() {
        LlmClient deepseek = stub("deepseek", "deepseek-chat");
        LlmClient openai = stub("openai", "gpt-4o");
        var strategy = new CostOptimizedStrategy(Map.of());

        LlmClient picked = strategy.select(turn("write code for me"),
            Map.of("deepseek", deepseek, "openai", openai));

        // CapabilityStrategy prefers deepseek for code markers.
        assertSame(deepseek, picked);
    }

    @Test
    void noPricedProviderRegisteredDegradesToCapabilityRouting() {
        LlmClient deepseek = stub("deepseek", "deepseek-chat");
        // Prices exist, but for a provider nobody registered.
        var strategy = new CostOptimizedStrategy(Map.of("absent", new ModelCost(0.1, 0.1)));

        LlmClient picked = strategy.select(turn("write code for me"), Map.of("deepseek", deepseek));

        assertSame(deepseek, picked);
    }

    @Test
    void routerHonoursTheStrategyItWasBuiltWith() {
        CostOptimizedRouter router = new CostOptimizedRouter(Map.of(
            "cheap", new ModelCost(0.1, 0.2),
            "pricey", new ModelCost(3.0, 9.0)));
        LlmClient cheap = stub("cheap", "m-cheap");
        router.register(cheap);
        router.register(stub("pricey", "m-pricey"));

        assertEquals("cost_optimized", router.getStrategy().name());
        assertSame(cheap, router.route(turn("hello")));
    }

    @Test
    void negativePriceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ModelCost(-1.0, 0.0));
    }
}
