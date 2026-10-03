package com.gantang.reaxon.impl.tool.policy;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.tool.Tool;
import com.gantang.reaxon.api.tool.policy.PolicyDecision;
import com.gantang.reaxon.api.tool.policy.RiskLevel;
import com.gantang.reaxon.api.tool.policy.ToolPolicy;

import java.util.Map;

/**
 * Maps a tool's declared {@link RiskLevel} to a decision:
 *
 * <ul>
 *   <li>SAFE / READ / NETWORK → ALLOW (parameter-level policies handle the rest)</li>
 *   <li>WRITE / DESTRUCTIVE   → ASK (human approval)</li>
 * </ul>
 *
 * <p>A tool that statically declares {@link Tool#requiresApproval()} always ASKs,
 * even if its risk level is misconfigured.
 */
public final class RiskLevelPolicy implements ToolPolicy {

    @Override
    public PolicyDecision evaluate(Tool tool, Map<String, Object> params, AgentContext ctx) {
        if (tool.requiresApproval()) {
            return PolicyDecision.ask("tool '" + tool.name() + "' requires human approval");
        }
        RiskLevel level = tool.riskLevel();
        if (level == null) return PolicyDecision.allow();
        return switch (level) {
            case SAFE, READ, NETWORK -> PolicyDecision.allow();
            case WRITE -> PolicyDecision.ask("write-level tool '" + tool.name()
                    + "' requires approval before it can modify state or send data");
            case DESTRUCTIVE -> PolicyDecision.ask("destructive tool '" + tool.name()
                    + "' (code execution / delegation) requires explicit approval");
        };
    }
}
