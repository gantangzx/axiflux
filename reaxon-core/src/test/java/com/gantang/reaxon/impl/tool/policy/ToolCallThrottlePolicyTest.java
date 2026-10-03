package com.gantang.reaxon.impl.tool.policy;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.tool.policy.PolicyDecision;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallThrottlePolicyTest {

    private final AgentContext ctx = AgentContext.builder()
        .sessionId("s-1").userId("u").currentQuery("").build();

    /** A trivial tool-like stub: reuse CalculatorTool for a real name. */
    private com.gantang.reaxon.api.tool.Tool tool() {
        return new com.gantang.reaxon.impl.tool.builtin.CalculatorTool();
    }

    @Test
    void identicalCallLoopIsDenied() {
        // repeatThreshold default 3 within 60s -> 4th identical call denied
        ToolCallThrottlePolicy p = new ToolCallThrottlePolicy(60_000, 1000, 60_000, 3);
        var t = tool();
        Map<String, Object> args = Map.of("expression", "1+1");
        for (int i = 0; i < 3; i++) {
            assertTrue(p.evaluate(t, args, ctx).isAllow(), "call " + i + " allowed");
        }
        PolicyDecision d = p.evaluate(t, args, ctx);
        assertTrue(d.isDeny(), "4th identical call must be denied");
        assertTrue(d.reason().contains("identical"), d.reason());
    }

    @Test
    void differentArgumentsAreNotCountedAsRepeat() {
        ToolCallThrottlePolicy p = new ToolCallThrottlePolicy(60_000, 1000, 60_000, 3);
        var t = tool();
        for (int i = 0; i < 8; i++) {
            PolicyDecision d = p.evaluate(t, Map.<String,Object>of("expression", i + "+" + i), ctx);
            assertTrue(d.isAllow(), "distinct call " + i + " should pass: " + d.reason());
        }
    }

    @Test
    void burstCapTriggersDeny() {
        // burst 5 in 30s -> 6th denied
        ToolCallThrottlePolicy p = new ToolCallThrottlePolicy(30_000, 5, 60_000, 1000);
        var t = tool();
        for (int i = 0; i < 5; i++) {
            assertTrue(p.evaluate(t, Map.<String,Object>of("expression", i), ctx).isAllow());
        }
        PolicyDecision d = p.evaluate(t, Map.<String,Object>of("expression", 99), ctx);
        assertTrue(d.isDeny(), "burst should be denied, got: " + d);
        assertTrue(d.reason().contains("burst"), d.reason());
    }

    @Test
    void sessionsAreIsolated() {
        ToolCallThrottlePolicy p = new ToolCallThrottlePolicy(60_000, 1000, 60_000, 2);
        var t = tool();
        Map<String, Object> args = Map.<String,Object>of("expression", "1+1");
        AgentContext a = AgentContext.builder().sessionId("a").userId("u").currentQuery("").build();
        AgentContext b = AgentContext.builder().sessionId("b").userId("u").currentQuery("").build();
        p.evaluate(t, args, a);
        p.evaluate(t, args, a); // a at threshold-1
        assertTrue(p.evaluate(t, args, b).isAllow(), "session b must have its own budget");
        assertTrue(p.evaluate(t, args, a).isDeny(), "session a still denied");
    }

    @Test
    void canonicalIgnoresKeyOrder() {
        assertEquals(
            ToolCallThrottlePolicy.canonical(Map.of("b", 2, "a", 1)),
            ToolCallThrottlePolicy.canonical(Map.of("a", 1, "b", 2)));
    }
}
