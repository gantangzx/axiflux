package com.gantang.tianshu.api.auth;

import java.util.Set;

/**
 * The authenticated caller on whose behalf an agent turn / tool call runs
 * (on-behalf-of / delegated identity).
 *
 * <p>Replaces the previous bare {@code userId} string for authorization
 * decisions: a tool never runs with the service's own privileges, it runs with
 * the <em>caller's</em> granted scopes. This bounds prompt-injection blast
 * radius — a hijacked agent can only reach what that user's token allows.
 *
 * <p>P0-3 (org/subscription model): the identity optionally carries the
 * organization the caller is acting on behalf of — {@code orgId} plus the
 * caller's {@code orgRole} within it ({@code OWNER}/{@code ADMIN}/
 * {@code MEMBER}/{@code VIEWER}). Both are {@code null} for callers that
 * belong to no organization (single-user/dev deployments, pre-P0-3 rows).
 * The org dimension is additive: the legacy {@link #CallerIdentity(String, Set)}
 * constructor, {@link #fullAccess(String)} and {@link #has(String)} behave
 * exactly as before, so all existing call sites compile and run unchanged.
 *
 * @param userId stable user id (e.g. "console-user", "u_123")
 * @param orgId  organization the caller belongs to / acts for; {@code null}
 *               when the caller has no org
 * @param orgRole the caller's role within {@code orgId}
 *               ({@value #ORG_ROLE_OWNER}/{@value #ORG_ROLE_ADMIN}/
 *               {@value #ORG_ROLE_MEMBER}/{@value #ORG_ROLE_VIEWER});
 *               {@code null} when the caller has no org
 * @param scopes granted permission scopes; contains {@code "*"} for full access
 *               (unauthenticated single-user / dev mode)
 */
public record CallerIdentity(String userId, String orgId, String orgRole, Set<String> scopes) {

    public static final String WILDCARD = "*";

    /** Org role: full control of the org, its members and its subscription. */
    public static final String ORG_ROLE_OWNER = "OWNER";
    /** Org role: manage members and day-to-day org settings (not billing ownership). */
    public static final String ORG_ROLE_ADMIN = "ADMIN";
    /** Org role: regular member — uses the org's resources, no management rights. */
    public static final String ORG_ROLE_MEMBER = "MEMBER";
    /** Org role: read-only member (usage dashboards, no resource mutation). */
    public static final String ORG_ROLE_VIEWER = "VIEWER";

    /**
     * {@code AgentContext.metadata()} key under which the transport layer injects
     * this caller's granted scopes (a {@code Collection<String>}). Declared here,
     * in the API layer, because it is the contract between every transport entry
     * point (HTTP / WebSocket / MCP / sub-agent spawn) and the tool authorization
     * policy that reads it.
     */
    public static final String META_SCOPES = "__tianshu_scopes";

    /**
     * {@code AgentContext.metadata()} flag ({@code Boolean}) marking a turn that
     * runs with no human present to approve a gated tool (scheduler fires,
     * isolated background runs). When {@code true}, tools that would ASK for
     * approval fail fast instead of hanging on the approval handshake.
     */
    public static final String META_HEADLESS = "__tianshu_headless";

    /**
     * {@code AgentContext.metadata()} key ({@code String}) carrying the caller's
     * organization id for this turn, injected by the transport layer alongside
     * {@link #META_SCOPES}. Usage accounting reads it to attribute token rows to
     * the org ({@code usage_record.org_id}) instead of only to the bare user.
     * Absent when the caller belongs to no org.
     */
    public static final String META_ORG_ID = "__tianshu_org_id";

    /**
     * {@code AgentContext.metadata()} key ({@code String}) carrying the caller's
     * role within {@link #META_ORG_ID}'s organization. Absent when the caller
     * belongs to no org.
     */
    public static final String META_ORG_ROLE = "__tianshu_org_role";

    /**
     * {@code AgentContext.metadata()} key ({@code String}) carrying the resolved
     * plan tier of the caller's organization for this turn
     * ({@code free|pro|team}), injected by the transport layer after a single
     * plan lookup. Read by the plan-tier tool policy that gates paid tools.
     * Absent when the caller has no org or billing is off, in which case the
     * policy allows the call (zero-impact for pre-billing deployments).
     */
    public static final String META_PLAN_TIER = "__tianshu_plan_tier";

    /**
     * {@code AgentContext.metadata()} key ({@code String}, comma-separated)
     * carrying the organization's tool allow-list for this turn, injected by
     * the transport layer after resolving the org. Read by the org tool
     * whitelist policy. Absent/null/empty means no org-level restriction.
     */
    public static final String META_ORG_ALLOWED_TOOLS = "__tianshu_org_allowed_tools";

    public CallerIdentity {
        scopes = (scopes == null) ? Set.of() : Set.copyOf(scopes);
    }

    /**
     * Backward-compatible constructor for pre-P0-3 call sites: no organization.
     * Delegates to the canonical constructor with {@code null} orgId/orgRole.
     */
    public CallerIdentity(String userId, Set<String> scopes) {
        this(userId, null, null, scopes);
    }

    /** Full-access identity used when auth is disabled (single-user/dev). */
    public static CallerIdentity fullAccess(String userId) {
        return new CallerIdentity(userId, Set.of(WILDCARD));
    }

    /** True if this caller holds the given scope (wildcard grants everything). */
    public boolean has(String scope) {
        return scopes.contains(WILDCARD) || scopes.contains(scope);
    }

    /** True when this caller acts on behalf of an organization. */
    public boolean hasOrg() {
        return orgId != null && !orgId.isBlank();
    }

    /**
     * True when this caller holds an administrative org role
     * ({@value #ORG_ROLE_OWNER} or {@value #ORG_ROLE_ADMIN}).
     */
    public boolean isOrgAdmin() {
        return ORG_ROLE_OWNER.equals(orgRole) || ORG_ROLE_ADMIN.equals(orgRole);
    }

    /** True when this caller owns the organization (full control incl. billing/roles). */
    public boolean isOrgOwner() {
        return ORG_ROLE_OWNER.equals(orgRole);
    }
}
