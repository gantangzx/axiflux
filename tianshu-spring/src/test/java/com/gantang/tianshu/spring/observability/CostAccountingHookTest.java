package com.gantang.tianshu.spring.observability;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.agent.AgentResponse;
import com.gantang.tianshu.api.llm.ModelCost;
import com.gantang.tianshu.api.session.Session;
import com.gantang.tianshu.impl.session.InMemorySessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CostAccountingHookTest {

    private AgentResponse response(int in, int cached, int out, String model) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("inputTokens", in);
        meta.put("cachedInputTokens", cached);
        meta.put("outputTokens", out);
        meta.put("model", model);
        return AgentResponse.builder().status(AgentResponse.Status.SUCCESS).metadata(meta).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> usage(Session s) {
        Object raw = s.metadata().get(CostAccountingHook.META_USAGE);
        assertNotNull(raw, "usage accumulator must be written");
        return (Map<String, Object>) raw;
    }

    @Test
    void accumulatesTokensAndAppliesCachedInputPrice() {
        InMemorySessionManager mgr = new InMemorySessionManager();
        Session s = mgr.getOrCreate("s1", "u1", "coder", Map.of());
        Map<String, ModelCost> prices = Map.of("m1", new ModelCost(1.0, 3.0, 0.1));
        CostAccountingHook hook = new CostAccountingHook(mgr, () -> prices);

        hook.onTurnEnd(
            AgentContext.builder().sessionId("s1").userId("u1").currentQuery("hi").build(),
            response(1000, 800, 100, "m1"));

        Map<String, Object> u = usage(s);
        assertEquals(1L, u.get("turns"));
        assertEquals(1000L, u.get("inputTokens"));
        assertEquals(800L, u.get("cachedInputTokens"));
        assertEquals(100L, u.get("outputTokens"));
        assertEquals(1100L, u.get("totalTokens"));
        assertEquals(0.8, ((Number) u.get("cacheHitRatio")).doubleValue(), 1e-9);
        // uncached 200 * 1/1000 = 0.2 ; cached 800 * 0.1/1000 = 0.08 ; output 100 * 3/1000 = 0.3
        assertEquals(0.58, ((Number) u.get("estimatedCost")).doubleValue(), 1e-9);
    }

    @Test
    void accumulatesAcrossTurns_andUnpricedModelStillCountsTokens() {
        InMemorySessionManager mgr = new InMemorySessionManager();
        Session s = mgr.getOrCreate("s2", "u1", "coder", Map.of());
        CostAccountingHook hook = new CostAccountingHook(mgr, Map::of);
        AgentContext ctx = AgentContext.builder().sessionId("s2").userId("u1").currentQuery("hi").build();

        hook.onTurnEnd(ctx, response(100, 0, 50, "unknown-model"));
        hook.onTurnEnd(ctx, response(100, 50, 50, "unknown-model"));

        Map<String, Object> u = usage(s);
        assertEquals(2L, u.get("turns"));
        assertEquals(200L, u.get("inputTokens"));
        assertEquals(50L, u.get("cachedInputTokens"));
        assertEquals(100L, u.get("outputTokens"));
        assertEquals(0.25, ((Number) u.get("cacheHitRatio")).doubleValue(), 1e-9);
        assertEquals(0.0, ((Number) u.get("estimatedCost")).doubleValue(), 1e-9);
    }

    @Test
    void swallowsMissingSessionAndBadMetadata() {
        InMemorySessionManager mgr = new InMemorySessionManager();
        CostAccountingHook hook = new CostAccountingHook(mgr, () -> {
            throw new IllegalStateException("prices unavailable");
        });
        assertDoesNotThrow(() -> hook.onTurnEnd(
            AgentContext.builder().sessionId("ghost").userId("u").currentQuery("x").build(),
            response(10, 0, 10, "m")));
    }

    // ===== UsageRecordSink integration =====

    @Test
    void writesUsageRecordWhenSinkAvailable() {
        InMemorySessionManager mgr = new InMemorySessionManager();
        Session s = mgr.getOrCreate("s3", "u1", "coder", Map.of());
        RecordingUsageRecordSink sink = new RecordingUsageRecordSink();
        CostAccountingHook hook = new CostAccountingHook(mgr, Map::of, providerOf(sink));

        hook.onTurnEnd(
            AgentContext.builder().sessionId("s3").userId("u1").currentQuery("hi").build(),
            response(500, 100, 200, "gpt-4"));

        // The hook must still accumulate session metadata as before.
        Map<String, Object> u = usage(s);
        assertEquals(500L, u.get("inputTokens"));
        assertEquals(200L, u.get("outputTokens"));

        // And the async sink must have been called.
        assertEquals(1, sink.callCount);
        assertEquals("u1", sink.lastUserId);
        assertEquals("s3", sink.lastSessionId);
        assertEquals("gpt-4", sink.lastModel);
        assertEquals(500, sink.lastInputTokens);
        assertEquals(200, sink.lastOutputTokens);
        assertEquals(100, sink.lastCachedTokens);
    }

    @Test
    void noSinkDoesNotBreakMetadataAccumulation() {
        InMemorySessionManager mgr = new InMemorySessionManager();
        Session s = mgr.getOrCreate("s4", "u1", "coder", Map.of());
        CostAccountingHook hook = new CostAccountingHook(mgr, Map::of, null);

        hook.onTurnEnd(
            AgentContext.builder().sessionId("s4").userId("u1").currentQuery("hi").build(),
            response(100, 0, 50, "m1"));

        Map<String, Object> u = usage(s);
        assertEquals(100L, u.get("inputTokens"));
        assertEquals(50L, u.get("outputTokens"));
    }

    @Test
    void sinkExceptionDoesNotBreakHook() {
        InMemorySessionManager mgr = new InMemorySessionManager();
        Session s = mgr.getOrCreate("s5", "u1", "coder", Map.of());
        UsageRecordSink badSink = (orgId, userId, sessionId, agentId, provider, model, in, out, cached, calls) -> {
            throw new RuntimeException("db down");
        };
        CostAccountingHook hook = new CostAccountingHook(mgr, Map::of, providerOf(badSink));

        // Should not throw — the hook contract (never fail a turn) must hold.
        assertDoesNotThrow(() -> hook.onTurnEnd(
            AgentContext.builder().sessionId("s5").userId("u1").currentQuery("hi").build(),
            response(100, 0, 50, "m1")));

        Map<String, Object> u = usage(s);
        assertEquals(100L, u.get("inputTokens"));
    }

    // ===== helpers =====

    @Test
    void usageRecordCarriesOrgIdFromContextMetadata() {
        InMemorySessionManager mgr = new InMemorySessionManager();
        Session s = mgr.getOrCreate("s6", "u1", "coder", Map.of());
        RecordingUsageRecordSink sink = new RecordingUsageRecordSink();
        CostAccountingHook hook = new CostAccountingHook(mgr, Map::of, providerOf(sink));

        // P0-3: CallerGuard injects the caller's org into the OBO metadata; the
        // hook must attribute the usage_record row to that org.
        AgentContext ctx = AgentContext.builder()
            .sessionId("s6").userId("u1").currentQuery("hi")
            .metadata(Map.of(com.gantang.tianshu.api.auth.CallerIdentity.META_ORG_ID, "org_42",
                com.gantang.tianshu.api.auth.CallerIdentity.META_ORG_ROLE, "MEMBER"))
            .build();
        hook.onTurnEnd(ctx, response(300, 50, 100, "gpt-4"));

        assertEquals(1, sink.callCount);
        assertEquals("org_42", sink.lastOrgId,
            "usage_record.org_id must come from the caller metadata, not a hardcoded null");
        assertEquals("u1", sink.lastUserId);
    }

    @Test
    void usageRecordOrgIdIsNullWhenCallerHasNoOrg() {
        InMemorySessionManager mgr = new InMemorySessionManager();
        Session s = mgr.getOrCreate("s7", "u1", "coder", Map.of());
        RecordingUsageRecordSink sink = new RecordingUsageRecordSink();
        CostAccountingHook hook = new CostAccountingHook(mgr, Map::of, providerOf(sink));

        hook.onTurnEnd(
            AgentContext.builder().sessionId("s7").userId("u1").currentQuery("hi").build(),
            response(300, 50, 100, "gpt-4"));

        assertEquals(1, sink.callCount);
        assertNull(sink.lastOrgId, "a caller without an org keeps org_id null");
    }

    // ===== helpers =====

    @SuppressWarnings("unchecked")
    private static ObjectProvider<UsageRecordSink> providerOf(UsageRecordSink w) {
        ObjectProvider<UsageRecordSink> p = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(p.getIfAvailable()).thenReturn(w);
        return p;
    }

    private static class RecordingUsageRecordSink implements UsageRecordSink {
        int callCount;
        String lastOrgId, lastUserId, lastSessionId, lastModel;
        int lastInputTokens, lastOutputTokens, lastCachedTokens;

        @Override
        public void write(String orgId, String userId, String sessionId, String agentId,
                          String provider, String model, int in, int out, int cached, int calls) {
            callCount++;
            lastOrgId = orgId;
            lastUserId = userId;
            lastSessionId = sessionId;
            lastModel = model;
            lastInputTokens = in;
            lastOutputTokens = out;
            lastCachedTokens = cached;
        }
    }
}
