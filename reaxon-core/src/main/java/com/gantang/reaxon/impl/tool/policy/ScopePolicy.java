package com.gantang.reaxon.impl.tool.policy;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.auth.CallerIdentity;
import com.gantang.reaxon.api.tool.Tool;
import com.gantang.reaxon.api.tool.policy.PolicyDecision;
import com.gantang.reaxon.api.tool.policy.ToolPolicy;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Authorization policy (on-behalf-of): a tool call is allowed only if the
 * <em>caller's</em> granted scopes cover the scope the tool requires.
 *
 * <p>The caller's scopes are read from {@link AgentContext#metadata()} under
 * {@link #META_SCOPES} (a {@code Collection<String>}), injected by the transport
 * layer after verifying the caller's token. Two modes:
 * <ul>
 *   <li><b>lenient</b> (default, dev/single-user): when the metadata contains
 *       no scope key at all (or metadata is {@code null}), no scopes are granted
 *       (same as strict). Only when a scope key is explicitly injected <i>with an
 *       empty/unparseable value</i> is full access ({@code "*"}) granted.</li>
 *   <li><b>strict</b> ({@code axiflux.auth.require-explicit-scopes=true}):
 *       absent/empty scopes mean <em>no</em> privileges — every scope-gated
 *       tool is denied. A wildcard {@code "*"} scope still grants everything,
 *       but only when explicitly present in the verified token.</li>
 * </ul>
 *
 * <p>Tool → required-scope mapping defaults to the sensitive tools and can be
 * overridden/extended by deployment.
 */
public final class ScopePolicy implements ToolPolicy {

    /**
     * Metadata key carrying the caller's granted scopes (a {@code Collection<String>}).
     * Alias of the API-layer contract constant {@link CallerIdentity#META_SCOPES};
     * kept here so existing policy call sites read naturally.
     */
    public static final String META_SCOPES = CallerIdentity.META_SCOPES;

    public static final String SCOPE_EXEC = "tool:exec";       // code execution
    public static final String SCOPE_DB = "tool:db";           // database query
    public static final String SCOPE_FILE_WRITE = "tool:file:write";
    public static final String SCOPE_EMAIL = "tool:email";     // outbound email
    public static final String SCOPE_SPAWN = "agent:spawn";    // sub-agent delegation
    public static final String SCOPE_NET = "tool:net";         // outbound http/fetch
    public static final String SCOPE_MCP = "tool:mcp";         // external MCP call

    private final Map<String, String> requiredScopes;
    private final boolean strictScopes;

    /** Lenient mode: absent scopes are treated as full access (dev/single-user). */
    public ScopePolicy() {
        this(defaultScopes(), false);
    }

    public ScopePolicy(Map<String, String> toolScopes) {
        this(toolScopes, false);
    }

    /**
     * @param strictScopes when {@code true}, absent/empty scopes deny all
     *     scope-gated tools instead of granting wildcard (production mode).
     */
    public ScopePolicy(boolean strictScopes) {
        this(defaultScopes(), strictScopes);
    }

    public ScopePolicy(Map<String, String> toolScopes, boolean strictScopes) {
        this.requiredScopes = (toolScopes == null) ? defaultScopes() : Map.copyOf(toolScopes);
        this.strictScopes = strictScopes;
    }

    public static Map<String, String> defaultScopes() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("code_executor", SCOPE_EXEC);
        m.put("database_query", SCOPE_DB);
        m.put("file_write", SCOPE_FILE_WRITE);
        m.put("email_send", SCOPE_EMAIL);
        m.put("spawn_task", SCOPE_SPAWN);
        m.put("http_client", SCOPE_NET);
        m.put("web_fetch", SCOPE_NET);
        m.put("mcp_client", SCOPE_MCP);
        return Map.copyOf(m);
    }

    @Override
    public PolicyDecision evaluate(Tool tool, Map<String, Object> params, AgentContext ctx) {
        String required = requiredScopes.get(tool.name());
        if (required == null) {
            return PolicyDecision.allow(); // tool does not need a privileged scope
        }
        Set<String> scopes = callerScopes(ctx);
        if (scopes.contains("*") || scopes.contains(required)) {
            return PolicyDecision.allow();
        }
        // Paid tiers bundle the baseline capabilities: a self-serve org on pro/team
        // gets the network read/write scopes even though its members carry no
        // admin-granted tool roles (plan upgrades never mutate account roles).
        // Free / no-org / unresolved-tier callers stay on the explicit-scope path.
        if (planCoversScope(required, planTier(ctx))) {
            return PolicyDecision.allow();
        }
        return PolicyDecision.deny("caller lacks scope '" + required
            + "' required by tool '" + tool.name() + "'");
    }

    /**
     * Scopes bundled into paid plans. {@code tool:net} (http_client/web_fetch)
     * and {@code tool:db} are baseline capabilities available from pro; the
     * file-write scope is likewise included. Execution / spawn / email / MCP
     * scopes are not granted here — they stay gated by explicit role or by the
     * dedicated {@link PlanTierToolPolicy} matrix.
     */
    private static boolean planCoversScope(String required, String tier) {
        if (tier == null) return false;
        int rank = switch (tier.trim().toLowerCase(Locale.ROOT)) {
            case "team" -> 2;
            case "pro" -> 1;
            default -> 0;
        };
        if (rank < 1) return false;
        return SCOPE_NET.equals(required) || SCOPE_DB.equals(required)
            || SCOPE_FILE_WRITE.equals(required);
    }

    /** Read the injected plan tier; null when absent (no-org / billing off). */
    private static String planTier(AgentContext ctx) {
        if (ctx == null || ctx.metadata() == null) return null;
        Object raw = ctx.metadata().get(CallerIdentity.META_PLAN_TIER);
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? null : s;
    }

    private Set<String> callerScopes(AgentContext ctx) {
        if (ctx == null || ctx.metadata() == null) {
            // No metadata at all → nothing injected → fail-closed (no scopes).
            return Set.of();
        }
        Object raw = ctx.metadata().get(META_SCOPES);
        if (raw == null) {
            // No scope key present at all: fail-closed even in lenient mode.
            // Only tools that require no scope will be allowed.
            return Set.of();
        }
        if (raw instanceof Collection<?> c && !c.isEmpty()) {
            Set<String> out = new HashSet<>(c.size());
            for (Object o : c) {
                if (o != null) {
                    String s = String.valueOf(o).trim();
                    if (!s.isEmpty()) out.add(s);
                }
            }
            if (!out.isEmpty()) return Set.copyOf(out);
        }
        // Scope key present but empty/unparseable: lenient → treat as dev
        // mode full access ("*"); strict → no privileges (fail-closed).
        return strictScopes ? Set.of() : Set.of("*");
    }
}
