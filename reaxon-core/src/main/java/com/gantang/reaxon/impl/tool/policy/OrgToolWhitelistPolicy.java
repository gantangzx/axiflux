package com.gantang.reaxon.impl.tool.policy;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.auth.CallerIdentity;
import com.gantang.reaxon.api.tool.Tool;
import com.gantang.reaxon.api.tool.policy.PolicyDecision;
import com.gantang.reaxon.api.tool.policy.ToolPolicy;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Organization-level tool allow-list governance (P3-7).
 *
 * <p>When an organization configures an allow-list, every member is narrowed to
 * those tools regardless of individual scopes; deployment-level and persona
 * policies still apply, so this forms the middle of the deployment → org →
 * persona narrowing. Like {@link PlanTierToolPolicy}, the resolved list is
 * injected once by the transport layer into {@link AgentContext#metadata()}
 * under {@link CallerIdentity#META_ORG_ALLOWED_TOOLS} (comma-separated), which
 * keeps this policy pure (no Spring/DB) and lets it live in the core chain.
 *
 * <p>Zero-impact otherwise: no metadata key, or an empty list, means the org
 * has set no boundary and the call is allowed (later policies may still deny).
 */
public final class OrgToolWhitelistPolicy implements ToolPolicy {

    @Override
    public PolicyDecision evaluate(Tool tool, Map<String, Object> params, AgentContext ctx) {
        Set<String> allowed = readAllowed(ctx);
        if (allowed.isEmpty()) {
            return PolicyDecision.allow();                 // no org boundary configured
        }
        if (allowed.contains(tool.name().trim().toLowerCase(Locale.ROOT))) {
            return PolicyDecision.allow();
        }
        return PolicyDecision.deny("tool '" + tool.name()
            + "' is not allowed by this organization's tool allow-list");
    }

    /** Parse the injected list; empty when the key is absent or blank. */
    private static Set<String> readAllowed(AgentContext ctx) {
        if (ctx == null || ctx.metadata() == null) return Set.of();
        Object raw = ctx.metadata().get(CallerIdentity.META_ORG_ALLOWED_TOOLS);
        if (raw == null) return Set.of();
        String csv = String.valueOf(raw).trim();
        if (csv.isEmpty()) return Set.of();
        return Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(s -> s.toLowerCase(Locale.ROOT))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
