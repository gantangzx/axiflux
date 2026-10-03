package com.gantang.axiflux.spring.adapter;

import com.gantang.reaxon.api.llm.LlmClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BYOK P0-1: two callers carrying different byokApiKey values must end up on
 * adapters that authenticate with DIFFERENT keys, while a turn without a key
 * must use the statically configured client untouched (backward compatibility).
 *
 * <p>No network: the factories build LangChain4j model objects without opening
 * a connection, and the package-private *ForTest hooks expose which key each
 * adapter would send on the wire (the native SSE path sets the Authorization
 * header from streamApiKey; the LangChain4j blocking path bakes the key into
 * the ChatModel built with that key).
 */
class LangChain4jLlmClientAdapterByokTest {

    private static LangChain4jLlmClientAdapter adapter() {
        return LangChain4jLlmClientAdapter.openAi("static-key", "http://127.0.0.1:1/v1", "test-model");
    }

    @Nested
    @DisplayName("withApiKey: per-caller key injection")
    class WithApiKey {

        @Test
        void twoCallersGetDifferentKeys() {
            LangChain4jLlmClientAdapter root = adapter();
            LlmClient alice = root.withApiKey("sk-alice");
            LlmClient bob = root.withApiKey("sk-bob");

            assertNotSame(alice, bob, "different BYOK keys must not share a client");
            LangChain4jLlmClientAdapter a = (LangChain4jLlmClientAdapter) alice;
            LangChain4jLlmClientAdapter b = (LangChain4jLlmClientAdapter) bob;
            assertEquals("sk-alice", a.streamApiKeyForTest(),
                "native SSE Authorization header must carry Alice's key");
            assertEquals("sk-bob", b.streamApiKeyForTest(),
                "native SSE Authorization header must carry Bob's key");
            assertNotSame(a.chatModelForTest(), b.chatModelForTest(),
                "blocking path: each caller's ChatModel is built with her own key");
            assertNotSame(root.chatModelForTest(), a.chatModelForTest(),
                "BYOK client must not reuse the static-key ChatModel");
        }

        @Test
        void sameKeyReturnsCachedInstance() {
            LangChain4jLlmClientAdapter root = adapter();
            LlmClient first = root.withApiKey("sk-alice");
            LlmClient again = root.withApiKey("sk-alice");
            assertSame(first, again, "same key must hit the bounded cache (no model rebuild per call)");
            assertEquals(1, root.byokCacheSizeForTest());
        }

        @Test
        void nullOrBlankKeyReturnsThis() {
            LangChain4jLlmClientAdapter root = adapter();
            assertSame(root, root.withApiKey(null));
            assertSame(root, root.withApiKey(""));
            assertSame(root, root.withApiKey("   "));
            assertEquals(0, root.byokCacheSizeForTest(), "no BYOK → nothing cached, static path untouched");
        }

        @Test
        void staticallyConfiguredKeyReturnsThis() {
            // A caller "bringing" the same key the deployment already uses must
            // not fork a duplicate client.
            LangChain4jLlmClientAdapter root = adapter();
            assertSame(root, root.withApiKey("static-key"));
            assertEquals(0, root.byokCacheSizeForTest());
        }

        @Test
        void derivedAdapterKeepsRoutingIdentity() {
            LangChain4jLlmClientAdapter root = adapter().withThinkingEnabled(true)
                .withContextWindowTokens(200_000);
            LangChain4jLlmClientAdapter derived =
                (LangChain4jLlmClientAdapter) root.withApiKey("sk-alice");
            assertEquals(root.provider(), derived.provider());
            assertEquals(root.primaryModel(), derived.primaryModel());
            assertEquals(200_000, derived.contextWindowTokens(),
                "wiring-time settings must propagate to BYOK-derived clients");
            assertTrue(derived.supportsStreaming(),
                "OpenAI-compatible derived client keeps the native SSE path");
        }
    }

    @Nested
    @DisplayName("provider families")
    class ProviderFamilies {

        @Test
        void anthropicDerivedClientRekeysAndStaysBlocking() {
            LangChain4jLlmClientAdapter root =
                LangChain4jLlmClientAdapter.anthropic("static-anthropic", null, "claude-test");
            LangChain4jLlmClientAdapter derived =
                (LangChain4jLlmClientAdapter) root.withApiKey("sk-ant-alice");
            assertNotSame(root, derived);
            assertEquals("sk-ant-alice", derived.configuredApiKeyForTest());
            assertNotSame(root.chatModelForTest(), derived.chatModelForTest(),
                "Anthropic ChatModel is rebuilt with the BYOK key");
            assertFalse(derived.supportsStreaming(),
                "Anthropic has no native SSE path — streaming still downgrades to blocking");
        }

        @Test
        void openAiCompatibleDerivedClientRekeys() {
            LangChain4jLlmClientAdapter root = LangChain4jLlmClientAdapter.openAiCompatible(
                "ark", "static-ark", "http://127.0.0.1:1/v1", "ark-model");
            LangChain4jLlmClientAdapter derived =
                (LangChain4jLlmClientAdapter) root.withApiKey("sk-ark-alice");
            assertEquals("ark", derived.provider());
            assertEquals("sk-ark-alice", derived.streamApiKeyForTest());
            assertEquals("static-ark", root.streamApiKeyForTest(),
                "root client keeps the static key");
        }
    }

    @Nested
    @DisplayName("bounded cache")
    class BoundedCache {

        @Test
        void manyDistinctKeysStayBounded() {
            LangChain4jLlmClientAdapter root = adapter();
            int distinct = LangChain4jLlmClientAdapter.ByokClientCache.BYOK_MAX_ENTRIES + 50;
            for (int i = 0; i < distinct; i++) {
                root.withApiKey("sk-caller-" + i);
            }
            assertTrue(root.byokCacheSizeForTest() <= LangChain4jLlmClientAdapter.ByokClientCache.BYOK_MAX_ENTRIES + 1,
                "cache must evict instead of growing without bound, was " + root.byokCacheSizeForTest());
        }
    }
}
