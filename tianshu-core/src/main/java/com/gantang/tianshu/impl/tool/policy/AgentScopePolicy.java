package com.gantang.tianshu.impl.tool.policy;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.policy.PolicyDecision;
import com.gantang.tianshu.api.tool.policy.RiskLevel;
import com.gantang.tianshu.api.tool.policy.ToolPolicy;

import java.util.List;
import java.util.Map;

/**
 * Per-agent tool-scope policy.
 *
 * <p>The active agent persona can only <em>narrow</em> the deployment policy:
 * <ul>
 *   <li>If the agent declares an {@link #META_ALLOWED_TOOLS} whitelist, a tool
 *       not on it is denied.</li>
 *   <li>If the agent declares a {@link #META_RISK_CEILING}, any tool whose
 *       {@link RiskLevel} is above that ceiling is denied (hard boundary, not
 *       an approval prompt — the persona simply has no permission).</li>
 * </ul>
 *
 * <p>The permissions are carried on {@link AgentContext#metadata()} by the
 * engine when it resolves the session's persona, so this policy stays pure and
 * has no dependency on the agent directory.
 */
public final class AgentScopePolicy implements ToolPolicy {

    /** Metadata key: {@code List<String>} of tool names the agent may call. */
    public static final String META_ALLOWED_TOOLS = "__tianshu_allowedTools";
    /** Metadata key: {@code String} risk-ceiling name (e.g. "READ"). */
    public static final String META_RISK_CEILING = "__tianshu_riskCeiling";

    @Override
    public PolicyDecision evaluate(Tool tool, Map<String, Object> params, AgentContext ctx) {
        Map<String, Object> meta = ctx != null ? ctx.metadata() : null;
        if (meta == null) return PolicyDecision.allow();

        Object allowed = meta.get(META_ALLOWED_TOOLS);
        if (allowed instanceof List<?> list && !list.isEmpty()) {
            boolean listed = list.stream()
                .filter(o -> o instanceof String)
                .map(Object::toString)
                .anyMatch(n -> n.equalsIgnoreCase(tool.name()));
            if (!listed) {
                return PolicyDecision.deny("tool '" + tool.name()
                        + "' is not permitted for this agent persona");
            }
        }

        Object ceilingRaw = meta.get(META_RISK_CEILING);
        if (ceilingRaw instanceof String s && !s.isBlank()) {
            RiskLevel ceiling;
            try {
                ceiling = RiskLevel.valueOf(s.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return PolicyDecision.allow(); // unknown ceiling -> don't block on misconfig
            }
            RiskLevel level = tool.riskLevel();
            RiskLevel beyond = next(ceiling);
            if (level != null && beyond != null && level.atLeast(beyond)) {
                return PolicyDecision.deny("tool '" + tool.name() + "' risk level " + level
                        + " exceeds this agent's risk ceiling " + ceiling);
            }
        }
        return PolicyDecision.allow();
    }

    /** The first level strictly above the ceiling — a tool at/above it is out of scope. */
    private static RiskLevel next(RiskLevel level) {
        RiskLevel[] all = RiskLevel.values();
        int idx = level.ordinal() + 1;
        return idx < all.length ? all[idx] : null; // null ceiling top -> nothing exceeds
    }
}
