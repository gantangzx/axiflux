package com.gantang.tianshu.impl.tool.policy;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.policy.PolicyDecision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScopePolicyTest {

    private final ScopePolicy policy = new ScopePolicy();

    private Tool tool(String name) {
        Tool t = mock(Tool.class);
        when(t.name()).thenReturn(name);
        return t;
    }

    private AgentContext ctxWithScopes(List<String> scopes) {
        return AgentContext.builder().sessionId("s").userId("u").currentQuery("")
            .metadata(Map.of(ScopePolicy.META_SCOPES, scopes))
            .build();
    }

    @Test
    void noScopesNoMetadataLenientDeniesScopeGatedTool() {
        // No metadata injected at all: fail-closed even in lenient mode.
        AgentContext ctx = AgentContext.builder().sessionId("s").userId("u").currentQuery("")
            .metadata(null)
            .build();
        assertTrue(policy.evaluate(tool("code_executor"), Map.of(), ctx).isDeny());
    }

    @Test
    void noScopeKeyInMetadataLenientDeniesScopeGatedTool() {
        // Metadata present but no scope key at all: fail-closed.
        AgentContext ctx = AgentContext.builder().sessionId("s").userId("u").currentQuery("")
            .metadata(Map.of("some.other.key", "value"))
            .build();
        assertTrue(policy.evaluate(tool("code_executor"), Map.of(), ctx).isDeny());
    }

    @Test
    void emptyScopeListLenientFullAccess() {
        // Scope key present but empty: lenient dev mode grants wildcard.
        AgentContext ctx = AgentContext.builder().sessionId("s").userId("u").currentQuery("")
            .metadata(Map.of(ScopePolicy.META_SCOPES, List.of()))
            .build();
        assertTrue(policy.evaluate(tool("code_executor"), Map.of(), ctx).isAllow());
    }

    @Test
    void noMetadataUnprivilegedToolStillAllowed() {
        // calculator has no required scope: allowed even with no metadata at all.
        AgentContext ctx = AgentContext.builder().sessionId("s").userId("u").currentQuery("")
            .metadata(null)
            .build();
        assertTrue(policy.evaluate(tool("calculator"), Map.of(), ctx).isAllow());
    }

    @Test
    void wildcardScopeGrantsEverything() {
        assertTrue(policy.evaluate(tool("code_executor"), Map.of(), ctxWithScopes(List.of("*"))).isAllow());
    }

    @Test
    void unprivilegedToolNeedsNoScope() {
        // calculator has no required scope: allowed even with an empty scope set
        assertTrue(policy.evaluate(tool("calculator"), Map.of(), ctxWithScopes(List.of("tool:net"))).isAllow());
    }

    @Test
    void missingScopeIsDenied() {
        PolicyDecision d = policy.evaluate(tool("code_executor"), Map.of(),
            ctxWithScopes(List.of("tool:net", "tool:db")));
        assertTrue(d.isDeny());
        assertTrue(d.reason().contains("tool:exec"), d.reason());
    }

    @Test
    void matchingScopeAllowed() {
        assertTrue(policy.evaluate(tool("code_executor"), Map.of(),
            ctxWithScopes(List.of("tool:exec"))).isAllow());
        assertTrue(policy.evaluate(tool("spawn_task"), Map.of(),
            ctxWithScopes(List.of("agent:spawn"))).isAllow());
    }

    // ===== strict mode (production: fail-closed when no scopes are injected) =====

    private final ScopePolicy strictPolicy = new ScopePolicy(true);

    @Test
    void strictAbsentScopesDenyScopeGatedTool() {
        AgentContext ctx = AgentContext.builder().sessionId("s").userId("u").currentQuery("").build();
        PolicyDecision d = strictPolicy.evaluate(tool("code_executor"), Map.of(), ctx);
        assertTrue(d.isDeny());
        assertTrue(d.reason().contains("tool:exec"), d.reason());
    }

    @Test
    void strictEmptyScopeListAlsoDenies() {
        PolicyDecision d = strictPolicy.evaluate(tool("database_query"), Map.of(),
            ctxWithScopes(List.of()));
        assertTrue(d.isDeny());
    }

    @Test
    void strictExplicitWildcardStillGrantsAll() {
        assertTrue(strictPolicy.evaluate(tool("code_executor"), Map.of(),
            ctxWithScopes(List.of("*"))).isAllow());
    }

@Test
    void strictUnprivilegedToolStillAllowed() {
        assertTrue(strictPolicy.evaluate(tool("calculator"), Map.of(),
            ctxWithScopes(List.of())).isAllow());
    }

    // ===== paid-plan tier unlocks baseline tool scopes =====

    private AgentContext ctxWithTier(String tier) {
        return AgentContext.builder().sessionId("s").userId("u").currentQuery("")
            .metadata(Map.of(com.gantang.tianshu.api.auth.CallerIdentity.META_PLAN_TIER, tier))
            .build();
    }

    @Test
    void proTierUnlocksWebFetchWithoutScope() {
        assertTrue(policy.evaluate(tool("web_fetch"), Map.of(), ctxWithTier("pro")).isAllow());
        assertTrue(policy.evaluate(tool("http_client"), Map.of(), ctxWithTier("pro")).isAllow());
    }

    @Test
    void teamTierInheritsNetworkAndDbScopes() {
        assertTrue(policy.evaluate(tool("web_fetch"), Map.of(), ctxWithTier("team")).isAllow());
        assertTrue(policy.evaluate(tool("database_query"), Map.of(), ctxWithTier("team")).isAllow());
        assertTrue(policy.evaluate(tool("file_write"), Map.of(), ctxWithTier("team")).isAllow());
    }

    @Test
    void freeTierDoesNotUnlockNetworkScope() {
        PolicyDecision d = policy.evaluate(tool("web_fetch"), Map.of(), ctxWithTier("free"));
        assertTrue(d.isDeny());
        assertTrue(d.reason().contains("tool:net"), d.reason());
    }

    @Test
    void planTierDoesNotGrantExecScope() {
        // code_executor stays gated by its own PlanTierToolPolicy matrix, not by
        // this baseline-scope relaxation.
        assertTrue(policy.evaluate(tool("code_executor"), Map.of(), ctxWithTier("pro")).isDeny());
    }
}
