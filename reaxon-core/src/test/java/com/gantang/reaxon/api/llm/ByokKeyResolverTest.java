package com.gantang.reaxon.api.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BYOK P0-1: the resolver decides which key a single LLM call authenticates
 * with. Priority: an explicitly turn-carried key wins; anything else falls
 * back to empty (the router then uses the statically configured key — the
 * pre-BYOK behaviour).
 */
class ByokKeyResolverTest {

    private static RoutingContext ctx(String byokKey) {
        return new RoutingContext("hi", List.of(), null, "user-1", null, byokKey);
    }

    @Test
    void explicitCarriedKeyWins() {
        ByokKeyResolver r = ByokKeyResolver.explicit();
        Optional<String> key = r.resolveApiKey("openai", ctx("sk-user-own-key"));
        assertTrue(key.isPresent());
        assertEquals("sk-user-own-key", key.get());
    }

    @Test
    void nullKeyFallsBackToEmpty() {
        ByokKeyResolver r = ByokKeyResolver.explicit();
        assertTrue(r.resolveApiKey("openai", ctx(null)).isEmpty(),
            "no BYOK key on the turn → resolver has no opinion (static key applies)");
    }

    @Test
    void blankKeyFallsBackToEmpty() {
        ByokKeyResolver r = ByokKeyResolver.explicit();
        assertTrue(r.resolveApiKey("openai", ctx("   ")).isEmpty());
        assertTrue(r.resolveApiKey("openai", ctx("")).isEmpty());
    }

    @Test
    void nullContextFallsBackToEmpty() {
        ByokKeyResolver r = ByokKeyResolver.explicit();
        assertTrue(r.resolveApiKey("openai", null).isEmpty());
    }

    @Test
    void explicitKeyAppliesToAnyProvider() {
        // Documented default-resolver contract: the carried key is returned for
        // whichever provider it is resolved against. Multi-provider deployments
        // install a provider-aware resolver instead.
        ByokKeyResolver r = ByokKeyResolver.explicit();
        RoutingContext c = ctx("sk-shared");
        assertEquals("sk-shared", r.resolveApiKey("openai", c).orElseThrow());
        assertEquals("sk-shared", r.resolveApiKey("anthropic", c).orElseThrow());
    }

    @Test
    void routingContextMasksKeyInToString() {
        RoutingContext c = ctx("sk-super-secret-value");
        assertFalse(c.toString().contains("sk-super-secret-value"),
            "RoutingContext.toString must never print the BYOK key");
        assertTrue(c.toString().contains("***"));
    }

    @Test
    void routingContextBackCompatible3ArgAndOf() {
        // Pre-BYOK construction sites keep working and carry no identity/key.
        RoutingContext a = new RoutingContext("q", List.of(), "gpt-4o");
        assertNull(a.callerId());
        assertNull(a.orgId());
        assertNull(a.byokApiKey());
        assertFalse(a.hasByokKey());
        assertEquals("gpt-4o", a.forcedModel());
        assertTrue(a.hasForcedModel());

        RoutingContext b = RoutingContext.of("q");
        assertNull(b.byokApiKey());
        assertFalse(b.hasByokKey());
        assertEquals(List.of(), b.messages());
    }
}
