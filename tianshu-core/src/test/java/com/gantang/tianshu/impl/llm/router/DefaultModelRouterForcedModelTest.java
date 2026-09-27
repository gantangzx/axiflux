package com.gantang.tianshu.impl.llm.router;

import com.gantang.tianshu.api.llm.CompletionRequest;
import com.gantang.tianshu.api.llm.CompletionResponse;
import com.gantang.tianshu.api.llm.LlmClient;
import com.gantang.tianshu.api.llm.RoutingContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that a client-supplied forcedModel (UI model selector / API field)
 * overrides the cost-optimized strategy, matching either a provider name or
 * a registered client's primary model name.
 */
class DefaultModelRouterForcedModelTest {

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

    @Test
    void routesByProviderName() {
        DefaultModelRouter r = new DefaultModelRouter();
        r.setDefaultProvider("openai");
        LlmClient qwen = stub("qwen", "qwen-plus");
        r.register(stub("openai", "ark-code-latest"));
        r.register(qwen);

        LlmClient picked = r.route(new RoutingContext("hi", List.of(), "qwen"));
        assertSame(qwen, picked);
    }

    @Test
    void routesByModelNameWhenProviderNotMatched() {
        DefaultModelRouter r = new DefaultModelRouter();
        LlmClient ds = stub("deepseek", "deepseek-reasoner");
        r.register(stub("openai", "gpt-4o"));
        r.register(ds);

        // UI may send the model id rather than the provider name.
        LlmClient picked = r.route(new RoutingContext("hi", List.of(), "deepseek-reasoner"));
        assertSame(ds, picked);
    }

    @Test
    void fallsBackToStrategyWhenForcedModelBlank() {
        DefaultModelRouter r = new DefaultModelRouter();
        LlmClient openai = stub("openai", "gpt-4o");
        r.register(openai);
        r.register(stub("anthropic", "claude"));
        r.setDefaultProvider("openai");

        LlmClient picked = r.route(new RoutingContext("hi", List.of(), "  "));
        assertNotNull(picked);
    }
}
