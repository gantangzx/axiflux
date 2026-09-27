package com.gantang.tianshu.impl.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.llm.CompletionRequest;
import com.gantang.tianshu.api.llm.CompletionResponse;
import com.gantang.tianshu.api.llm.LlmClient;
import com.gantang.tianshu.api.llm.ModelRouter;
import com.gantang.tianshu.api.llm.RoutingContext;
import com.gantang.tianshu.api.session.Session;
import com.gantang.tianshu.impl.memory.DefaultContextAssembler;
import com.gantang.tianshu.impl.session.InMemorySessionManager;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.net.ConnectException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct unit tests for {@link LlmRecoveryChain}: when the routed provider
 * fails with a transient/unavailable error, the chain must fall through to the
 * next provider in routing order and return its response.
 */
class LlmRecoveryChainDirectTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static LlmClient failingClient(String provider, AtomicInteger calls) {
        return new StubLlmClient(provider) {
            @Override
            public CompletionResponse completeWithTools(CompletionRequest request, JsonNode tools) {
                calls.incrementAndGet();
                // Simulates a dead endpoint (classified UNAVAILABLE -> fallback eligible).
                throw new RuntimeException(new ConnectException("Connection refused"));
            }
        };
    }

    private static LlmClient okClient(String provider, String text, AtomicInteger calls) {
        return new StubLlmClient(provider) {
            @Override
            public CompletionResponse completeWithTools(CompletionRequest request, JsonNode tools) {
                calls.incrementAndGet();
                return CompletionResponse.builder()
                    .content(text)
                    .toolCalls(List.of())
                    .finishReason("stop")
                    .build();
            }
        };
    }

    private abstract static class StubLlmClient implements LlmClient {
        private final String provider;
        StubLlmClient(String provider) { this.provider = provider; }
        @Override public String provider() { return provider; }
        @Override public String primaryModel() { return provider + "-model"; }
        @Override public CompletionResponse complete(CompletionRequest request) {
            return completeWithTools(request, null);
        }
        @Override public Flux<String> completeStream(CompletionRequest request) {
            return Flux.just("");
        }
    }

    @Test
    void unavailableProvider_fallsThroughToNextInChain() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        LlmClient primary = failingClient("primary", primaryCalls);
        LlmClient fallback = okClient("backup", "from-backup", fallbackCalls);

        ModelRouter router = new ModelRouter() {
            @Override public LlmClient route(RoutingContext ctx) { return primary; }
            @Override public List<LlmClient> routeChain(RoutingContext ctx) {
                return List.of(primary, fallback);
            }
        };

        DefaultContextAssembler assembler = new DefaultContextAssembler(null, 50, 5);
        LlmRecoveryChain chain = new LlmRecoveryChain(router, assembler, OM, "test-agent", null);

        InMemorySessionManager mgr = new InMemorySessionManager();
        Session session = mgr.getOrCreate("s1", "u1", "test-agent", Map.of());
        session.addUserMessage("ping", Map.of());
        AgentContext ctx = AgentContext.builder()
            .sessionId("s1").userId("u1").currentQuery("ping").systemPrompt("sys").build();

        StepVerifier.create(chain.nextResponse(session, ctx, List.of()))
            .assertNext(resp -> {
                assertTrue(resp.isText());
                assertEquals("from-backup", resp.content(),
                    "response must come from the fallback provider after primary failure");
            })
            .verifyComplete();

        assertEquals(1, primaryCalls.get(), "primary provider must have been tried once");
        assertEquals(1, fallbackCalls.get(), "fallback provider must have been tried once");
    }

    @Test
    void forcedModel_isPassedToPrimary_butDroppedOnFallbackProvider() {
        // P1-7: a forced model name belongs to the provider it was forced for.
        // Passing it verbatim to a fallback provider makes the fallback fail
        // with "model not found" — fallback calls must drop the override.
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        java.util.List<String> primaryForced = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.List<String> fallbackForced = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        LlmClient primary = new StubLlmClient("openai") {
            @Override public CompletionResponse completeWithTools(CompletionRequest request, JsonNode tools) {
                primaryCalls.incrementAndGet();
                primaryForced.add(request.forcedModel());
                throw new RuntimeException(new ConnectException("Connection refused"));
            }
        };
        LlmClient fallback = new StubLlmClient("deepseek") {
            @Override public CompletionResponse completeWithTools(CompletionRequest request, JsonNode tools) {
                fallbackCalls.incrementAndGet();
                fallbackForced.add(request.forcedModel());
                return CompletionResponse.builder()
                    .content("from-backup").toolCalls(List.of()).finishReason("stop").build();
            }
        };

        ModelRouter router = new ModelRouter() {
            @Override public LlmClient route(RoutingContext ctx) { return primary; }
            @Override public List<LlmClient> routeChain(RoutingContext ctx) {
                return List.of(primary, fallback);
            }
        };

        DefaultContextAssembler assembler = new DefaultContextAssembler(null, 50, 5);
        LlmRecoveryChain chain = new LlmRecoveryChain(router, assembler, OM, "test-agent", null);

        InMemorySessionManager mgr = new InMemorySessionManager();
        Session session = mgr.getOrCreate("s3", "u1", "test-agent", Map.of());
        session.addUserMessage("ping", Map.of());
        AgentContext ctx = AgentContext.builder()
            .sessionId("s3").userId("u1").currentQuery("ping").systemPrompt("sys")
            .forcedModel("gpt-4o")
            .build();

        StepVerifier.create(chain.nextResponse(session, ctx, List.of()))
            .assertNext(resp -> assertEquals("from-backup", resp.content()))
            .verifyComplete();

        assertEquals(List.of("gpt-4o"), primaryForced,
            "the routed primary must receive the forced model");
        assertEquals(1, fallbackForced.size());
        assertNull(fallbackForced.get(0),
            "the fallback provider must NOT receive a foreign forced model name");
    }

    @Test
    void emptyChain_errorsExplicitly() {
        ModelRouter emptyRouter = new ModelRouter() {
            @Override public LlmClient route(RoutingContext ctx) { return null; }
            @Override public List<LlmClient> routeChain(RoutingContext ctx) { return List.of(); }
        };
        DefaultContextAssembler assembler = new DefaultContextAssembler(null, 50, 5);
        LlmRecoveryChain chain = new LlmRecoveryChain(emptyRouter, assembler, OM, "test-agent", null);

        InMemorySessionManager mgr = new InMemorySessionManager();
        Session session = mgr.getOrCreate("s2", "u1", "test-agent", Map.of());
        session.addUserMessage("ping", Map.of());
        AgentContext ctx = AgentContext.builder()
            .sessionId("s2").userId("u1").currentQuery("ping").systemPrompt("sys").build();

        StepVerifier.create(chain.nextResponse(session, ctx, List.of()))
            .expectErrorMatches(e -> e.getMessage() != null
                && e.getMessage().contains("No LLM client available"))
            .verify();
    }
}
