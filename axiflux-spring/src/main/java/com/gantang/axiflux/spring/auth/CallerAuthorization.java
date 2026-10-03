package com.gantang.axiflux.spring.auth;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Centralizes owner and administrator checks for externally addressed resources. */
public final class CallerAuthorization {

    private static final String ADMIN_SCOPE = "scheduler:admin";

    /**
     * Scope required to read or mutate deployment-wide runtime configuration.
     * Config changes (tool policy mode, file roots, SSRF allowance, auto-approve)
     * affect every user and every instance, so they are gated separately from
     * per-resource ownership.
     */
    public static final String ADMIN_SCOPE_CONFIG = "config:admin";

    /** Scope required to read the deployment-wide audit trail (all users' rows). */
    public static final String ADMIN_SCOPE_AUDIT = "audit:read";

    /** Scope required to inspect or reload the enterprise license. */
    public static final String ADMIN_SCOPE_LICENSE = "license:admin";

    /**
     * Scope required to install/reload/update/delete skills. Skill install pulls a
     * remote source (SSRF surface) and drops executable code onto the host, so it
     * must never be reachable by a low-privilege authenticated caller.
     */
    public static final String ADMIN_SCOPE_SKILL = "skill:admin";

    /**
     * Scope required to create/update/delete shared agent personas. An agent
     * definition is a global resource (systemPrompt, allowedTools, riskCeiling)
     * with no per-user owner, so mutating it is an indirect privilege escalation
     * affecting every tenant.
     */
    public static final String ADMIN_SCOPE_AGENT = "agent:admin";

    /**
     * Scope required to list accounts, suspend/re-enable users and grant or
     * revoke platform roles. These actions affect authentication and privilege
     * across the whole deployment, so they are gated as a separate admin scope.
     */
    public static final String ADMIN_SCOPE_USERS = "users:admin";

    private CallerAuthorization() {}

    /**
     * Marker placed in the audit/observability attributes of actions taken by a
     * wildcard (dev/console) identity, so the audit trail can distinguish
     * "granted by a real scoped token" from "allowed because the deployment is
     * in single-user dev mode" (audit authz P2-5).
     */
    public static final String DEV_IDENTITY_MARKER = "devIdentity";

    /**
     * Snapshot the caller's granted scopes for the audit trail (P2-5): the raw
     * scope set exactly as verified, or the literal {@code "(none)"}. Pair with
     * {@link #isWildcard(String)} — a snapshot of {@code "*"} marks the action
     * as taken under the dev identity rather than a real grant.
     */
    public static String scopeSnapshot(String scopes) {
        return (scopes == null || scopes.isBlank()) ? "(none)" : scopes.trim();
    }

    public static String effectiveUser(String authenticatedUser, String requestedUser) {
        if (authenticatedUser != null && !authenticatedUser.isBlank()) {
            return authenticatedUser;
        }
        return requestedUser != null && !requestedUser.isBlank() ? requestedUser : "anonymous";
    }

    public static String effectiveUser(String authenticatedUser, String requestedUser, String scopes) {
        if (requestedUser != null && !requestedUser.isBlank() && isWildcard(scopes)) return requestedUser;
        return effectiveUser(authenticatedUser, requestedUser);
    }

    public static boolean isWildcard(String scopes) {
        return parseScopes(scopes).contains("*");
    }

    /**
     * Ownership check for an externally addressed resource.
     *
     * <p>Fail-closed: a blank caller means no identity was injected by
     * {@link AuthWebFilter}, which is never a legitimate state on a guarded
     * surface — it indicates a controller that forgot to read the identity
     * headers, or a token without a usable subject. Both must deny, not allow.
     * Dev/single-user mode still works because the filter injects an explicit
     * {@code console-user} + wildcard identity rather than leaving it blank.
     */
    public static boolean canAccess(String authenticatedUser, String owner, String scopes) {
        if (authenticatedUser == null || authenticatedUser.isBlank()) return false;
        return isWildcard(scopes) || authenticatedUser.equals(owner);
    }

    public static boolean isAdmin(String scopes) {
        return hasScope(scopes, ADMIN_SCOPE);
    }

    /** True when the caller may read/mutate deployment-wide runtime configuration. */
    public static boolean isConfigAdmin(String scopes) {
        return hasScope(scopes, ADMIN_SCOPE_CONFIG);
    }

    /** True when the caller may read audit rows belonging to other users. */
    public static boolean isAuditReader(String scopes) {
        return hasScope(scopes, ADMIN_SCOPE_AUDIT);
    }

    /** True when the caller may install/reload/update/delete skills. */
    public static boolean isSkillAdmin(String scopes) {
        return hasScope(scopes, ADMIN_SCOPE_SKILL);
    }

    /** True when the caller may mutate shared agent personas. */
    public static boolean isAgentAdmin(String scopes) {
        return hasScope(scopes, ADMIN_SCOPE_AGENT);
    }

    /** True when the caller may administer user accounts and platform roles. */
    public static boolean isUsersAdmin(String scopes) {
        return hasScope(scopes, ADMIN_SCOPE_USERS);
    }

    /** True when the granted scope set contains {@code required} or the wildcard. */
    public static boolean hasScope(String scopes, String required) {
        Set<String> granted = parseScopes(scopes);
        return granted.contains("*") || granted.contains(required);
    }

    private static Set<String> parseScopes(String scopes) {
        return scopeSet(scopes);
    }

    /** Parse the comma-separated {@code X-axiflux-Scopes} header into a scope set. */
    public static Set<String> scopeSet(String scopes) {
        if (scopes == null || scopes.isBlank()) return Set.of();
        return Arrays.stream(scopes.split(","))
            .map(String::trim)
            .filter(scope -> !scope.isBlank())
            .collect(Collectors.toUnmodifiableSet());
    }
}
