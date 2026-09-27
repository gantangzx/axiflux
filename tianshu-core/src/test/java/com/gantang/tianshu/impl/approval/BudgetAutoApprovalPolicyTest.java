package com.gantang.tianshu.impl.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolResult;
import com.gantang.tianshu.api.tool.policy.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BudgetAutoApprovalPolicyTest {

    /** Minimal tool stub with configurable risk. */
    private static Tool stubTool(String name, RiskLevel risk) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " stub"; }
            @Override public JsonNode parameters() { return JsonNodeFactory.instance.objectNode(); }
            @Override public RiskLevel riskLevel() { return risk; }
            @Override public ToolResult execute(String callId, Map<String, Object> params, AgentContext context) {
                return ToolResult.success(callId, "ok");
            }
        };
    }

    private static AgentContext ctx(String session) {
        return AgentContext.builder().sessionId(session).userId("u1").currentQuery("q").build();
    }

    @Test
    void autoApprovesWithinBudgetThenEscalates() {
        BudgetAutoApprovalPolicy policy =
            new BudgetAutoApprovalPolicy(RiskLevel.WRITE, 2, Map.of());
        Tool fw = stubTool("file_write", RiskLevel.WRITE);

        assertTrue(policy.isAutoApproved(ctx("s1"), fw), "1st call within budget");
        assertTrue(policy.isAutoApproved(ctx("s1"), fw), "2nd call within budget");
        assertFalse(policy.isAutoApproved(ctx("s1"), fw), "3rd call over budget → human");
    }

    @Test
    void budgetIsIsolatedPerSessionAndTool() {
        BudgetAutoApprovalPolicy policy =
            new BudgetAutoApprovalPolicy(RiskLevel.WRITE, 1, Map.of());
        Tool fw = stubTool("file_write", RiskLevel.WRITE);
        Tool email = stubTool("email_send", RiskLevel.WRITE);

        assertTrue(policy.isAutoApproved(ctx("s1"), fw));
        assertFalse(policy.isAutoApproved(ctx("s1"), fw), "s1 file_write budget exhausted");
        assertTrue(policy.isAutoApproved(ctx("s2"), fw), "s2 has its own budget");
        assertTrue(policy.isAutoApproved(ctx("s1"), email), "s1 has separate budget per tool");
    }

    @Test
    void destructiveToolsAreNeverAutoApproved() {
        BudgetAutoApprovalPolicy policy =
            new BudgetAutoApprovalPolicy(RiskLevel.WRITE, 100, Map.of());
        Tool exec = stubTool("code_executor", RiskLevel.DESTRUCTIVE);
        for (int i = 0; i < 5; i++) {
            assertFalse(policy.isAutoApproved(ctx("s1"), exec));
        }
    }

    @Test
    void riskCeilingIsEnforced() {
        // Ceiling READ: NETWORK/WRITE tools are too risky for budget auto-approval.
        BudgetAutoApprovalPolicy policy =
            new BudgetAutoApprovalPolicy(RiskLevel.READ, 100, Map.of());
        Tool net = stubTool("http_client", RiskLevel.NETWORK);
        Tool read = stubTool("file_read", RiskLevel.READ);
        assertFalse(policy.isAutoApproved(ctx("s1"), net));
        assertTrue(policy.isAutoApproved(ctx("s1"), read));
    }

    @Test
    void perToolOverridesAndZeroBudget() {
        BudgetAutoApprovalPolicy policy = new BudgetAutoApprovalPolicy(
            RiskLevel.WRITE, 5, Map.of("file_write", 0, "email_send", 1));
        Tool fw = stubTool("file_write", RiskLevel.WRITE);
        Tool email = stubTool("email_send", RiskLevel.WRITE);

        assertFalse(policy.isAutoApproved(ctx("s1"), fw), "explicit 0 budget disables");
        assertTrue(policy.isAutoApproved(ctx("s1"), email));
        assertFalse(policy.isAutoApproved(ctx("s1"), email));
    }

    @Test
    void resetSessionClearsConsumedBudget() {
        BudgetAutoApprovalPolicy policy =
            new BudgetAutoApprovalPolicy(RiskLevel.WRITE, 1, Map.of());
        Tool fw = stubTool("file_write", RiskLevel.WRITE);
        assertTrue(policy.isAutoApproved(ctx("s1"), fw));
        assertFalse(policy.isAutoApproved(ctx("s1"), fw));
        policy.resetSession("s1");
        assertTrue(policy.isAutoApproved(ctx("s1"), fw), "fresh budget after reset");
    }

    @Test
    void nullContextOrToolIsSafe() {
        BudgetAutoApprovalPolicy policy =
            new BudgetAutoApprovalPolicy(RiskLevel.WRITE, 1, Map.of());
        assertFalse(policy.isAutoApproved(null, stubTool("t", RiskLevel.READ)));
        assertFalse(policy.isAutoApproved(ctx("s1"), null));
    }
}
