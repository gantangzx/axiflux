package com.gantang.reaxon.impl.tool.policy;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.auth.CallerIdentity;
import com.gantang.reaxon.api.tool.Tool;
import com.gantang.reaxon.api.tool.policy.PolicyDecision;
import com.gantang.reaxon.api.tool.policy.ToolPolicy;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Commercial plan-tier gate applied <em>at tool execution</em>.
 *
 * <p>Each gated tool names a minimum plan rank; the caller's plan tier is read
 * from {@link AgentContext#metadata()} under {@link CallerIdentity#META_PLAN_TIER}
 * (a {@code String}), injected once by the transport layer after resolving the
 * organization's subscription. Keeping the policy pure (no Spring, no DB call
 * per tool invocation) is what lets it live in the core policy chain alongside
 * the security rules.
 *
 * <p>Zero-impact for deployments that predate billing — the policy allows when:
 * <ul>
 *   <li>the metadata carries no plan tier at all (no-org / billing disabled /
 *       plan unresolved): the transport simply does not inject the key;</li>
 *   <li>the tool is not in the gating matrix.</li>
 * </ul>
 * Only a turn that explicitly carries a tier lower than the tool requires is
 * denied. A higher tier inherits every lower tier's tool.
 *
 * <p>Default matrix (Pro-gated value tools): {@code code_executor},
 * {@code spawn_task}, {@code email_send}, {@code tts}. {@code long_term_memory}
 * and premium-model routing are gated at their own (non-tool) integration
 * points, not here.
 */
public final class PlanTierToolPolicy implements ToolPolicy {

    /** Rank of the free / base tier. */
    public static final int RANK_FREE = 0;
    public static final int RANK_PRO = 1;
    public static final int RANK_TEAM = 2;

    private final Map<String, Integer> requiredRank;

    public PlanTierToolPolicy() {
        this(defaultMatrix());
    }

    /** @param toolMinRank tool name → minimum plan rank; {@code null} → defaults */
    public PlanTierToolPolicy(Map<String, Integer> toolMinRank) {
        this.requiredRank = (toolMinRank == null)
            ? defaultMatrix() : Map.copyOf(toolMinRank);
    }

    /** Built-in tool → minimum-plan matrix. Overridable via configuration. */
    public static Map<String, Integer> defaultMatrix() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("code_executor", RANK_PRO);
        m.put("spawn_task", RANK_PRO);
        m.put("email_send", RANK_PRO);
        m.put("tts", RANK_PRO);
        return Map.copyOf(m);
    }

    @Override
    public PolicyDecision evaluate(Tool tool, Map<String, Object> params, AgentContext ctx) {
        Integer need = requiredRank.get(tool.name());
        if (need == null) return PolicyDecision.allow();      // ungated tool

        String tier = planTier(ctx);
        if (tier == null) return PolicyDecision.allow();     // no billing context → invisible gate

        int have = rank(tier);
        if (have >= need) return PolicyDecision.allow();
        return PolicyDecision.deny("tool '" + tool.name() + "' requires the '"
            + nameOf(need) + "' plan (current plan: '" + nameOf(have) + "')");
    }

    /** Read the injected tier; returns null when absent (not the literal string "null"). */
    private static String planTier(AgentContext ctx) {
        if (ctx == null || ctx.metadata() == null) return null;
        Object raw = ctx.metadata().get(CallerIdentity.META_PLAN_TIER);
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? null : s;
    }

    /** Tier ordering; anything unrecognized ranks as free. */
    static int rank(String tier) {
        return switch (tier.trim().toLowerCase(Locale.ROOT)) {
            case "team" -> RANK_TEAM;
            case "pro" -> RANK_PRO;
            default -> RANK_FREE;
        };
    }

    static String nameOf(int rank) {
        return switch (rank) {
            case RANK_TEAM -> "team";
            case RANK_PRO -> "pro";
            default -> "free";
        };
    }
}
