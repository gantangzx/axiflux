package com.gantang.tianshu.impl.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.llm.CompletionRequest;
import com.gantang.tianshu.api.llm.CompletionResponse;
import com.gantang.tianshu.api.llm.LlmClient;
import com.gantang.tianshu.api.llm.ModelRouter;
import com.gantang.tianshu.api.llm.RoutingContext;
import com.gantang.tianshu.api.llm.StreamChunk;
import com.gantang.tianshu.api.session.Session;
import com.gantang.tianshu.impl.memory.DefaultContextAssembler;
import com.gantang.tianshu.impl.session.InMemorySessionManager;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-2: main-conversation sampling temperature used to be a hard-coded {@code 0.7}
 * inside {@link LlmRecoveryChain}; it is now a {@code volatile} knob that the
 * Spring wiring binds to {@code tianshu.agent.temperature}. These tests pin the
 * configuration contract — default value, override propagation on both blocking
 * and streaming paths, and range guard — so the hot path can't silently regress
 * to a magic literal.
 */
class LlmRecoveryChainTemperatureTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** Records the {@link CompletionRequest} the chain hands to the provider. */
    private static LlmClient recordingProvider(AtomicReference<CompletionRequest> sink) {
        return new LlmClient() {
            @Override public String provider() { return "test"; }
            @Override public String primaryModel() { return "test-model"; }
            @Override public CompletionResponse complete(CompletionRequest request) {
                sink.set(request);
                return CompletionResponse.builder()
                    .content("ok").toolCalls(List.of()).finishReason("stop").build();
            }
            @Override public reactor.core.publisher.Flux<String> completeStream(CompletionRequest request) {
                sink.set(request);
                return reactor.core.publisher.Flux.just("ok");
            }
            @Override public CompletionResponse completeWithTools(CompletionRequest request, JsonNode tools) {
                sink.set(request);
                return CompletionResponse.builder()
                    .content("ok").toolCalls(List.of()).finishReason("stop").build();
            }
            @Override public reactor.core.publisher.Flux<StreamChunk>
                completeWithToolsStream(CompletionRequest request, JsonNode tools) {
                sink.set(request);
                return reactor.core.publisher.Flux.just(
                    StreamChunk.text("ok"),
                    StreamChunk.done(CompletionResponse.builder()
                        .content("ok").toolCalls(List.of()).finishReason("stop").build()));
            }
        };
    }

    private static ModelRouter single(LlmClient llm) {
        return new ModelRouter() {
            @Override public List<LlmClient> routeChain(RoutingContext ctx) { return List.of(llm); }
            @Override public LlmClient route(RoutingContext ctx) { return llm; }
        };
    }

    private static LlmRecoveryChain newChain(LlmClient llm) {
        return new LlmRecoveryChain(single(llm),
            new DefaultContextAssembler(null, 50, 5), OM, "test-agent", null);
    }

    private static Session newSession() {
        InMemorySessionManager mgr = new InMemorySessionManager();
        return mgr.getOrCreate("s1", "u1", "test-agent", Map.of());
    }

    private static AgentContext ctx() {
        return AgentContext.builder().sessionId("s1").userId("u1").currentQuery("hi").build();
    }

    @Test
    void defaultTemperature_isCreative() {
        LlmRecoveryChain chain = newChain(recordingProvider(new AtomicReference<>()));
        // The field default mirrors the legacy hard-coded 0.7 — operators get a
        // creative main conversation out of the box, no config required.
        assertEquals(0.7, chain.getTemperature(), 1e-9);
    }

    @Test
    void setTemperature_propagatesToBlockingRequest() {
        AtomicReference<CompletionRequest> sink = new AtomicReference<>();
        LlmRecoveryChain chain = newChain(recordingProvider(sink));
        chain.setTemperature(0.2);

        StepVerifier.create(chain.nextResponse(newSession(), ctx(), List.of()))
            .assertNext(resp -> assertEquals("ok", resp.content()))
            .verifyComplete();

        CompletionRequest req = sink.get();
        assertNotNull(req, "provider must have been called");
        assertEquals(0.2, req.temperature(), 1e-9);
    }

    @Test
    void setTemperature_propagatesToStreamingRequest() {
        AtomicReference<CompletionRequest> sink = new AtomicReference<>();
        LlmRecoveryChain chain = newChain(recordingProvider(sink));
        chain.setTemperature(0.55);

        StepVerifier.create(chain.nextResponseStream(newSession(), ctx(), List.of()))
            .expectNextCount(2)
            .verifyComplete();

        CompletionRequest req = sink.get();
        assertNotNull(req, "provider must have been called");
        assertEquals(0.55, req.temperature(), 1e-9);
    }

    @Test
    void setTemperature_rejectsOutOfRange() {
        LlmRecoveryChain chain = newChain(recordingProvider(new AtomicReference<>()));
        assertThrows(IllegalArgumentException.class, () -> chain.setTemperature(-0.01));
        assertThrows(IllegalArgumentException.class, () -> chain.setTemperature(2.01));
        // Boundary values are accepted.
        chain.setTemperature(0.0);
        assertEquals(0.0, chain.getTemperature(), 1e-9);
        chain.setTemperature(2.0);
        assertEquals(2.0, chain.getTemperature(), 1e-9);
    }
}