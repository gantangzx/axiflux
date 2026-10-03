package com.gantang.reaxon.impl.llm.router;

import com.gantang.reaxon.api.llm.ByokKeyResolver;
import com.gantang.reaxon.api.llm.CompletionRequest;
import com.gantang.reaxon.api.llm.CompletionResponse;
import com.gantang.reaxon.api.llm.LlmClient;
import com.gantang.reaxon.api.llm.RoutingContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BYOK P0-1: DefaultModelRouter applies the per-turn key override through
 * {@link ByokKeyResolver} + {@link LlmClient#withApiKey(String)}, and leaves
 * the statically registered client untouched when the turn carries no key
 * (backward compatibility).
 */
class DefaultModelRouterByokTest {

    /** Stub that records withApiKey invocations and hands out marked derivatives. */
    private static final class ByokAwareStub implements LlmClient {
        final String provider;
        final AtomicInteger withApiKeyCalls = new AtomicInteger();

        ByokAwareStub(String provider) { this.provider = provider; }

        @Override public String provider() { return provider; }
        @Override public String primaryModel() { return provider + "-model"; }
        @Override public CompletionResponse complete(CompletionRequest r) { return null; }
        @Override public Flux<String> completeStream(CompletionRequest r) { return Flux.empty(); }
        @Override public CompletionResponse completeWithTools(CompletionRequest r,
                                                              com.fasterxml.jackson.databind.JsonNode t) {
            return null;
        }

        @Override
        public LlmClient withApiKey(String apiKey) {
            withApiKeyCalls.incrementAndGet();
            return new Derived(provider, apiKey);
        }
    }

    /** A derivative bound to a caller key; provider() matches the root so chains dedupe. */
    private static final class Derived implements LlmClient {
        final String provider;
        final String key;
        Derived(String provider, String key) { this.provider = provider; this.key = key; }
        @Override public String provider() { return provider; }
        @Override public String primaryModel() { return provider + "-model"; }
        @Override public CompletionResponse complete(CompletionRequest r) { return null; }
        @Override public Flux<String> completeStream(CompletionRequest r) { return Flux.empty(); }
        @Override public CompletionResponse completeWithTools(CompletionRequest r,
                                                              com.fasterxml.jackson.databind.JsonNode t) {
            return null;
        }
    }

    private static RoutingContext byokCtx(String key) {
        return new RoutingContext("hi", List.of(), "openai", "user-1", null, key);
    }

    private static RoutingContext plainCtx() {
        return new RoutingContext("hi", List.of(), "openai");
    }

    @Test
    void route_withByokKey_returnsDerivedClient() {
        DefaultModelRouter r = new DefaultModelRouter();
        r.setDefaultProvider("openai");
        ByokAwareStub openai = new ByokAwareStub("openai");
        r.register(openai);

        LlmClient picked = r.route(byokCtx("sk-caller-a"));
        assertEquals(1, openai.withApiKeyCalls.get(), "router must ask the client to re-key");
        assertNotSame(openai, picked);
        assertInstanceOf(Derived.class, picked);
        assertEquals("sk-caller-a", ((Derived) picked).key);
        assertEquals("openai", picked.provider());
    }

    @Test
    void route_withoutByokKey_returnsStaticClientUntouched() {
        // Backward compatibility: no key on the turn → exact pre-BYOK behaviour.
        DefaultModelRouter r = new DefaultModelRouter();
        r.setDefaultProvider("openai");
        ByokAwareStub openai = new ByokAwareStub("openai");
        r.register(openai);

        LlmClient picked = r.route(plainCtx());
        assertSame(openai, picked, "null byokApiKey must return the registered client instance");
        assertEquals(0, openai.withApiKeyCalls.get());
    }

    @Test
    void routeChain_withByokKey_derivesPrimaryAndKeepsProviderDedupe() {
        DefaultModelRouter r = new DefaultModelRouter();
        r.setDefaultProvider("openai");
        ByokAwareStub openai = new ByokAwareStub("openai");
        ByokAwareStub anthropic = new ByokAwareStub("anthropic");
        r.register(openai);
        r.register(anthropic);

        // Explicit resolver returns the key for every provider; the chain must
        // still list each provider exactly once (derived primary + fallbacks).
        List<LlmClient> chain = r.routeChain(byokCtx("sk-caller-a"));
        assertEquals(List.of("openai", "anthropic"), chain.stream().map(LlmClient::provider).toList());
        assertInstanceOf(Derived.class, chain.get(0));
        assertEquals("sk-caller-a", ((Derived) chain.get(0)).key);
    }

    @Test
    void routeChain_withoutByokKey_identicalToPreByokBehaviour() {
        DefaultModelRouter r = new DefaultModelRouter();
        r.setDefaultProvider("openai");
        ByokAwareStub openai = new ByokAwareStub("openai");
        ByokAwareStub anthropic = new ByokAwareStub("anthropic");
        r.register(openai);
        r.register(anthropic);

        List<LlmClient> chain = r.routeChain(plainCtx());
        assertSame(openai, chain.get(0));
        assertSame(anthropic, chain.get(1));
        assertEquals(0, openai.withApiKeyCalls.get());
        assertEquals(0, anthropic.withApiKeyCalls.get());
    }

    @Test
    void resolverReturningEmpty_disablesByok() {
        DefaultModelRouter r = new DefaultModelRouter();
        r.setDefaultProvider("openai");
        ByokAwareStub openai = new ByokAwareStub("openai");
        r.register(openai);
        r.setByokKeyResolver((provider, ctx) -> java.util.Optional.empty());

        LlmClient picked = r.route(byokCtx("sk-caller-a"));
        assertSame(openai, picked, "resolver with no opinion → static client, key ignored");
        assertEquals(0, openai.withApiKeyCalls.get());
    }

    @Test
    void throwingResolver_fallsBackToStaticClient() {
        DefaultModelRouter r = new DefaultModelRouter();
        r.setDefaultProvider("openai");
        ByokAwareStub openai = new ByokAwareStub("openai");
        r.register(openai);
        r.setByokKeyResolver((provider, ctx) -> { throw new RuntimeException("vault down"); });

        LlmClient picked = r.route(byokCtx("sk-caller-a"));
        assertSame(openai, picked, "a broken resolver must not break routing");
    }

    @Test
    void nullResolverInstall_resetsToExplicitDefault() {
        DefaultModelRouter r = new DefaultModelRouter();
        r.setDefaultProvider("openai");
        ByokAwareStub openai = new ByokAwareStub("openai");
        r.register(openai);
        r.setByokKeyResolver((provider, ctx) -> java.util.Optional.empty());
        r.setByokKeyResolver(null); // reset

        LlmClient picked = r.route(byokCtx("sk-caller-b"));
        assertInstanceOf(Derived.class, picked, "null install resets to the explicit default resolver");
        assertEquals("sk-caller-b", ((Derived) picked).key);
    }
}
