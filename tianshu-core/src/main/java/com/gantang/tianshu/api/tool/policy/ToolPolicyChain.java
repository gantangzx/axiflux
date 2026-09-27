package com.gantang.tianshu.api.tool.policy;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.tool.Tool;

import java.util.List;
import java.util.Map;

/**
 * Ordered collection of {@link ToolPolicy} rules. Evaluation is
 * fail-closed for errors (a crashing policy denies the call) and the
 * most restrictive decision wins: any DENY → DENY, otherwise any ASK → ASK.
 */
public final class ToolPolicyChain {

    private final List<ToolPolicy> policies;

    public ToolPolicyChain(List<ToolPolicy> policies) {
        this.policies = policies == null ? List.of() : List.copyOf(policies);
    }

    public static ToolPolicyChain open() {
        return new ToolPolicyChain(List.of());
    }

    public PolicyDecision evaluate(Tool tool, Map<String, Object> params, AgentContext ctx) {
        PolicyDecision decision = PolicyDecision.allow();
        for (ToolPolicy policy : policies) {
            PolicyDecision next;
            try {
                next = policy.evaluate(tool, params, ctx);
            } catch (Exception e) {
                // Fail closed: a broken guard must never silently permit a call.
                return PolicyDecision.deny("policy '" + policy.getClass().getSimpleName()
                        + "' failed: " + e.getMessage());
            }
            if (next == null) continue;
            decision = decision.mostRestrictive(next);
            if (decision.isDeny()) return decision;
        }
        return decision;
    }

    public List<ToolPolicy> policies() {
        return policies;
    }
}
