package com.gantang.reaxon.impl.tool.policy;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.auth.CallerIdentity;
import com.gantang.reaxon.api.tool.Tool;
import com.gantang.reaxon.api.tool.policy.PolicyDecision;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PlanTierToolPolicy}: paid tools are denied below their
 * minimum tier and allowed at/above it, ungated tools always pass, and an
 * absent tier keeps the gate invisible (pre-billing / no-org deployments).
 */
class PlanTierToolPolicyTest {

    private final PlanTierToolPolicy policy = new PlanTierToolPolicy();

    private static Tool tool(String name) {
        Tool t = mock(Tool.class);
        when(t.name()).thenReturn(name);
        return t;
    }

    private static AgentContext ctx(String tier) {
        Map<String, Object> meta = tier == null
            ? Map.of()
            : Map.of(CallerIdentity.META_PLAN_TIER, tier);
        return AgentContext.builder()
            .sessionId("s1").userId("u1").metadata(meta).build();
    }

    @Test
    void freeTierDeniedPaidTool() {
        PolicyDecision d = policy.evaluate(tool("code_executor"), Map.of(), ctx("free"));
        assertTrue(d.isDeny());
        assertTrue(d.reason().contains("pro"));
    }

    @Test
    void proTierAllowedPaidTool() {
        assertFalse(policy.evaluate(tool("code_executor"), Map.of(), ctx("pro")).isDeny());
    }

    @Test
    void teamTierInheritsProTools() {
        assertFalse(policy.evaluate(tool("tts"), Map.of(), ctx("team")).isDeny());
    }

    @Test
    void absentTierKeepsGateInvisible() {
        assertFalse(policy.evaluate(tool("email_send"), Map.of(), ctx(null)).isDeny());
    }

    @Test
    void ungatedToolAlwaysAllowed() {
        assertFalse(policy.evaluate(tool("calculator"), Map.of(), ctx("free")).isDeny());
    }

    @Test
    void allProToolsAreProGated() {
        for (String name : new String[]{"code_executor", "spawn_task", "email_send", "tts"}) {
            assertTrue(policy.evaluate(tool(name), Map.of(), ctx("free")).isDeny(),
                name + " should be pro-gated");
        }
    }

    @Test
    void unknownTierRanksAsFree() {
        assertTrue(policy.evaluate(tool("spawn_task"), Map.of(), ctx("enterprise")).isDeny());
    }
}
