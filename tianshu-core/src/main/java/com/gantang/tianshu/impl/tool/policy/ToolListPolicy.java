package com.gantang.tianshu.impl.tool.policy;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.config.LiveSettings;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.policy.PolicyDecision;
import com.gantang.tianshu.api.tool.policy.ToolPolicy;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Hard deployment-level switch for the tool set:
 *
 * <ul>
 *   <li>{@code all}       — every registered tool is eligible (other policies still apply)</li>
 *   <li>{@code whitelist} — only tools in {@code allowed} are eligible</li>
 *   <li>{@code blacklist} — tools in {@code blocked} are never eligible</li>
 * </ul>
 *
 * <p>When constructed with a {@link LiveSettings} holder the mode/lists are read
 * live on every evaluation, so console changes take effect immediately; the
 * fixed-value constructor remains for tests and embedders.
 */
public final class ToolListPolicy implements ToolPolicy {

    public enum Mode { ALL, WHITELIST, BLACKLIST }

    private final LiveSettings live;
    private final Mode fixedMode;
    private final Set<String> fixedAllowed;
    private final Set<String> fixedBlocked;

    public ToolListPolicy(Mode mode, Set<String> allowed, Set<String> blocked) {
        this.live = null;
        this.fixedMode = mode == null ? Mode.ALL : mode;
        this.fixedAllowed = allowed == null ? Set.of() : Set.copyOf(allowed);
        this.fixedBlocked = blocked == null ? Set.of() : Set.copyOf(blocked);
    }

    /** Live mode: mode/allowed/blocked are resolved from {@link LiveSettings} per call. */
    public ToolListPolicy(LiveSettings live) {
        this.live = live;
        this.fixedMode = Mode.ALL;
        this.fixedAllowed = Set.of();
        this.fixedBlocked = Set.of();
    }

    private Mode mode() {
        if (live == null) return fixedMode;
        try {
            return Mode.valueOf(live.snapshot().toolPolicyMode().trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return Mode.ALL;
        }
    }

    private Set<String> allowed() {
        return live == null ? fixedAllowed : live.snapshot().allowedTools();
    }

    private Set<String> blocked() {
        return live == null ? fixedBlocked : live.snapshot().blockedTools();
    }

    @Override
    public PolicyDecision evaluate(Tool tool, Map<String, Object> params, AgentContext ctx) {
        String name = tool.name();
        return switch (mode()) {
            case WHITELIST -> allowed().contains(name)
                    ? PolicyDecision.allow()
                    : PolicyDecision.deny("tool '" + name + "' is not in the deployment whitelist");
            case BLACKLIST -> blocked().contains(name)
                    ? PolicyDecision.deny("tool '" + name + "' is blocked by the deployment blacklist")
                    : PolicyDecision.allow();
            case ALL -> PolicyDecision.allow();
        };
    }
}
