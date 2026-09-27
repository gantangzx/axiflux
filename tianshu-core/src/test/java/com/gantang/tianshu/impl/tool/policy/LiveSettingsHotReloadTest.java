package com.gantang.tianshu.impl.tool.policy;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.config.LiveSettings;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.policy.PolicyDecision;
import com.gantang.tianshu.api.tool.policy.RiskLevel;
import com.gantang.tianshu.impl.approval.BudgetAutoApprovalPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies policies read the shared {@link LiveSettings} hub live: mutating the
 * snapshot changes the very next evaluation without rebuilding the policy.
 */
class LiveSettingsHotReloadTest {

    private Tool tool(String name) {
        Tool t = mock(Tool.class);
        when(t.name()).thenReturn(name);
        when(t.riskLevel()).thenReturn(RiskLevel.WRITE);
        return t;
    }

    private AgentContext ctx(String session) {
        AgentContext c = mock(AgentContext.class);
        when(c.sessionId()).thenReturn(session);
        return c;
    }

    @Test
    void toolListPolicySwitchesModeLive() {
        LiveSettings live = new LiveSettings(LiveSettings.builder()
            .toolPolicyMode("all").build());
        ToolListPolicy policy = new ToolListPolicy(live);

        Tool secret = tool("database_query");
        // all mode: allowed
        assertTrue(policy.evaluate(secret, Map.of(), ctx("s1")).isAllow());

        // flip to whitelist that does NOT include database_query
        live.replace(LiveSettings.builder()
            .toolPolicyMode("WHITELIST").allowedTools(Set.of("calculator")).build());
        PolicyDecision denied = policy.evaluate(secret, Map.of(), ctx("s1"));
        assertTrue(denied.isDeny());

        // now whitelist includes it
        live.replace(LiveSettings.builder().toolPolicyMode("WHITELIST")
            .allowedTools(Set.of("database_query", "calculator")).build());
        assertTrue(policy.evaluate(secret, Map.of(), ctx("s1")).isAllow());
    }

    @Test
    void budgetPolicyEnableDisableLive() {
        LiveSettings live = new LiveSettings(LiveSettings.builder()
            .budgetEnabled(false).budgetDefault(2).budgetRiskCeiling("WRITE").build());
        BudgetAutoApprovalPolicy policy = new BudgetAutoApprovalPolicy(live);
        Tool fw = tool("file_write");

        // disabled -> never auto-approve
        assertFalse(policy.isAutoApproved(ctx("s1"), fw));

        // enable live
        live.replace(LiveSettings.builder()
            .budgetEnabled(true).budgetDefault(2).budgetRiskCeiling("WRITE").build());
        assertTrue(policy.isAutoApproved(ctx("s1"), fw));   // 1/2
        assertTrue(policy.isAutoApproved(ctx("s1"), fw));   // 2/2
        assertFalse(policy.isAutoApproved(ctx("s1"), fw));  // budget spent
    }

    @Test
    void networkEgressFlagLive() {
        LiveSettings live = new LiveSettings(LiveSettings.builder()
            .allowPrivateNetwork(false).build());
        NetworkEgressPolicy policy = new NetworkEgressPolicy(live);
        Tool http = tool("http_client");

        PolicyDecision blocked = policy.evaluate(http,
            Map.of("url", "http://127.0.0.1:8080/x"), ctx("s1"));
        assertTrue(blocked.isDeny());

        live.replace(LiveSettings.builder().allowPrivateNetwork(true).build());
        PolicyDecision allowed = policy.evaluate(http,
            Map.of("url", "http://127.0.0.1:8080/x"), ctx("s1"));
        assertTrue(allowed.isAllow());
    }

    @Test
    void snapshotIsImmutableCopy() {
        LiveSettings live = new LiveSettings(LiveSettings.builder()
            .allowedTools(Set.of("a")).fileAllowedRoots(List.of()).build());
        LiveSettings.Snapshot snap = live.snapshot();
        assertEquals(Set.of("a"), snap.allowedTools());
        assertThrows(UnsupportedOperationException.class, () -> snap.allowedTools().add("b"));
    }
}
