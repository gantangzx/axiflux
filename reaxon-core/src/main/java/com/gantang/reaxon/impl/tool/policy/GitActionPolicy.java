package com.gantang.reaxon.impl.tool.policy;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.tool.Tool;
import com.gantang.reaxon.api.tool.policy.PolicyDecision;
import com.gantang.reaxon.api.tool.policy.ToolPolicy;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Argument-aware gate for the consolidated {@code git} tool.
 *
 * <p>The git tool is registered as READ level so the read-only actions
 * ({@code status}, {@code diff}, {@code log}) run without friction during a
 * coding session. This policy raises an ASK only for the state-mutating actions
 * ({@code add}, {@code commit}, and any future history-changing verbs), so a
 * human approves before the repository is modified. It never pushes.
 *
 * <p>Returns {@code null} for non-git tools so the rest of the chain decides.
 */
public final class GitActionPolicy implements ToolPolicy {

    private static final Set<String> MUTATING = Set.of(
        "add", "commit", "push", "reset", "checkout", "merge", "rebase", "stash");

    @Override
    public PolicyDecision evaluate(Tool tool, Map<String, Object> params, AgentContext ctx) {
        if (!"git".equals(tool.name())) return null;
        String action = String.valueOf(params.getOrDefault("action", "status"))
            .trim().toLowerCase(Locale.ROOT);
        if (MUTATING.contains(action)) {
            return PolicyDecision.ask("git '" + action
                + "' modifies the repository and requires approval");
        }
        return PolicyDecision.allow();
    }
}
