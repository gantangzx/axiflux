package com.gantang.tianshu.impl.llm.router;

import com.gantang.tianshu.api.llm.CompletionRequest;
import com.gantang.tianshu.api.llm.CompletionResponse;
import com.gantang.tianshu.api.llm.LlmClient;
import com.gantang.tianshu.api.llm.RoutingContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-8: routeChain's fallback order must be stable (registration order), not
 * hash-dependent. P2-9: CapabilityStrategy falls back to the router's default
 * provider, not an arbitrary client.
 */
class DefaultModelRouterOrderTest {

    private static LlmClient stub(String provider) {
        return new LlmClient() {
            @Override public String provider() { return provider; }
            @Override public String primaryModel() { return provider + "-model"; }
            @Override public CompletionResponse complete(CompletionRequest r) { return null; }
            @Override public Flux<String> completeStream(CompletionRequest r) { return Flux.empty(); }
            @Override public CompletionResponse completeWithTools(CompletionRequest r,
                                                                 com.fasterxml.jackson.databind.JsonNode t) {
                return null;
            }
        };
    }

    @Test
    void routeChain_fallbackFollowsRegistrationOrder() {
        DefaultModelRouter r = new DefaultModelRouter();
        r.setDefaultProvider("openai");
        // Register in a known order. The strategy routes a short "hi" query to
        // deepseek (its short-conversation preference), so the primary is
        // deepseek; the rest of the chain must follow first-registration order
        // (openai, anthropic, qwen), not hash order (P2-8).
        r.register(stub("openai"));
        r.register(stub("deepseek"));
        r.register(stub("anthropic"));
        r.register(stub("qwen"));

        List<LlmClient> chain = r.routeChain(new RoutingContext("hi", List.of(), null));
        assertEquals(List.of("deepseek", "openai", "anthropic", "qwen"),
            chain.stream().map(LlmClient::provider).toList(),
            "primary first, then fallbacks in first-registration order, not hash order (P2-8)");
    }

    @Test
    void routeChain_fallbackOrder_matchesRegistration_afterForcedPrimary() {
        // Force the primary via forcedModel so the fallback tail order is
        // directly observable: primary = qwen, fallbacks in registration order.
        DefaultModelRouter r = new DefaultModelRouter();
        r.setDefaultProvider("openai");
        r.register(stub("openai"));
        r.register(stub("deepseek"));
        r.register(stub("anthropic"));
        r.register(stub("qwen"));

        List<LlmClient> chain = r.routeChain(new RoutingContext("hi", List.of(), "qwen"));
        assertEquals(List.of("qwen", "openai", "deepseek", "anthropic"),
            chain.stream().map(LlmClient::provider).toList(),
            "fallback tail must follow first-registration order once the primary is fixed (P2-8)");
    }

    @Test
    void routeChain_isDeterministicAcrossCalls() {
        DefaultModelRouter r = new DefaultModelRouter();
        r.setDefaultProvider("openai");
        r.register(stub("openai"));
        r.register(stub("beta"));
        r.register(stub("alpha"));
        r.register(stub("zeta"));

        List<String> first = r.routeChain(new RoutingContext("hi", List.of(), null))
            .stream().map(LlmClient::provider).toList();
        for (int i = 0; i < 20; i++) {
            List<String> again = r.routeChain(new RoutingContext("hi", List.of(), null))
                .stream().map(LlmClient::provider).toList();
            assertEquals(first, again, "routeChain order must be identical across calls (P2-8)");
        }
    }

    @Test
    void capabilityStrategy_fallsBackToDefaultProvider_notArbitrary() {
        // None of the preferred names (deepseek/anthropic/claude/openai) are
        // registered, so the strategy must fall back to the router's default
        // provider (qwen) rather than an arbitrary map value (P2-9).
        DefaultModelRouter r = new DefaultModelRouter();
        r.setDefaultProvider("qwen");
        LlmClient qwen = stub("qwen");
        r.register(qwen);
        r.register(stub("ark"));

        // A reasoning-marker query prefers anthropic/claude — neither registered.
        LlmClient picked = r.route(new RoutingContext("分析一下这个", List.of(), null));
        assertSame(qwen, picked,
            "with no preferred provider present, must fall back to the default provider (P2-9)");
    }
}
