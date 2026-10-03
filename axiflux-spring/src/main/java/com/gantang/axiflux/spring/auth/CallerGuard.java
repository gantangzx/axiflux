package com.gantang.axiflux.spring.auth;

import com.gantang.reaxon.api.auth.CallerIdentity;
import com.gantang.reaxon.api.session.Session;
import com.gantang.reaxon.api.session.SessionManager;
import com.gantang.axiflux.spring.service.Membership;
import com.gantang.axiflux.spring.service.OrgDirectorySpi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Single entry point for the per-request authorization decisions every
 * user-facing surface has to make.
 *
 * <p>Before this existed each controller hand-rolled the same three steps —
 * resolve the effective user, verify session ownership, thread the caller's
 * scopes into the agent context — and the ones that forgot a step (audit,
 * skills, the example app) became cross-user read/write holes. Routing all of
 * them through one component means a new controller inherits the checks by
 * calling {@link #context} instead of having to remember them.
 *
 * <p>The guard is deliberately strict:
 * <ul>
 *   <li>a client-supplied {@code userId} is honoured only for a wildcard
 *       (dev/console) caller — otherwise the verified header identity wins;</li>
 *   <li>an explicitly addressed session that belongs to somebody else yields
 *       404, not 403, so session ids stay unenumerable;</li>
 *   <li>an unnamed session is namespaced per user, so two tenants can never
 *       land in one shared "default" history.</li>
 * </ul>
 *
 * @see CallerAuthorization for the stateless owner/scope predicates this builds on
 */
public class CallerGuard {

    private final Supplier<SessionManager> sessions;
    private final Supplier<OrgDirectorySpi> orgs;

    public CallerGuard(ObjectProvider<SessionManager> sessions) {
        this(sessions, (ObjectProvider<OrgDirectorySpi>) null);
    }

    /**
     * P0-3: with an optional {@link OrgDirectorySpi} so resolved callers carry
     * their primary organization (orgId/orgRole) into the agent context.
     */
    public CallerGuard(ObjectProvider<SessionManager> sessions,
                       ObjectProvider<OrgDirectorySpi> orgs) {
        this.sessions = sessions::getIfAvailable;
        this.orgs = orgs != null ? orgs::getIfAvailable : () -> null;
    }

    /**
     * Bind a concrete {@link SessionManager} — for manual wiring and tests, where
     * there is no {@code ObjectProvider} to hand in. A null manager means "no
     * session backend", which makes the ownership check a no-op.
     */
    public CallerGuard(SessionManager sessions) {
        this(sessions, (OrgDirectorySpi) null);
    }

    /** Test/manual wiring variant with a concrete org directory (may be null). */
    public CallerGuard(SessionManager sessions, OrgDirectorySpi orgs) {
        this.sessions = () -> sessions;
        this.orgs = () -> orgs;
    }

    /**
     * Resolve and authorize a request in one call.
     *
     * @param authUser       verified caller from {@link AuthWebFilter#H_USER}
     * @param authScopes     granted scopes from {@link AuthWebFilter#H_SCOPES}
     * @param requestedUser  {@code userId} taken from the request body/query, may be null
     * @param requestedSession {@code sessionId} taken from the request, may be null
     * @return the resolved caller, never null
     * @throws ResponseStatusException 404 when the addressed session is not the caller's
     */
    public Caller context(String authUser, String authScopes,
                          String requestedUser, String requestedSession) {
        String user = CallerAuthorization.effectiveUser(authUser, requestedUser, authScopes);
        requireSessionOwner(requestedSession, authUser, authScopes);
        String sessionId = requestedSession != null && !requestedSession.isBlank()
            ? requestedSession
            : defaultSessionId(user);
        Membership primary = primaryMembership(user);
        return new Caller(user, sessionId, authScopes,
            primary != null ? primary.orgId() : null,
            primary != null ? primary.role() : null);
    }

    /**
     * Primary org membership of the user (first joined), or null when the user
     * has no org or no org backend is wired. Org resolution is best-effort —
     * it must never fail a request.
     */
    private Membership primaryMembership(String user) {
        OrgDirectorySpi svc = orgs.get();
        if (svc == null) return null;
        if (user == null || CallerIdentity.WILDCARD.equals(user)) return null;
        try {
            return svc.primaryMembership(user).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reject a request that addresses an existing session owned by somebody else.
     * A session that does not exist yet is allowed through: creating it will bind
     * it to the caller.
     *
     * <p>TOCTOU hardening (audit authz P2-2): when the session does not exist
     * yet, the caller races to create it via {@code getOrCreate}. If another
     * user won that race in the meantime, the loser must not land in the
     * winner's history — so we re-check the owner <em>after</em> the create
     * path and answer 409 (conflict) rather than silently proceeding. 409 (not
     * 404) because the session now genuinely exists and belongs to somebody
     * else; the id was client-chosen, so no enumeration is enabled by the
     * distinction.
     */
    public void requireSessionOwner(String sessionId, String authUser, String authScopes) {
        if (!ownsSession(sessionId, authUser, authScopes)) {
            // 404 rather than 403: a 403 would confirm the id exists.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
        }
        recheckOwnerAfterCreate(sessionId, authUser, authScopes);
    }

    /**
     * Post-{@code getOrCreate} owner re-check (audit authz P2-2). A no-op when
     * the session still does not exist (creation happens downstream) or when
     * the caller legitimately owns it. Throws 409 when a concurrent first-write
     * bound the session to somebody else between the ownership check and now.
     */
    private void recheckOwnerAfterCreate(String sessionId, String authUser, String authScopes) {
        if (sessionId == null || sessionId.isBlank()) return;
        SessionManager sm = sessions.get();
        if (sm == null) return;
        Optional<Session> current = sm.get(sessionId);
        if (current.isEmpty()) return;
        if (!CallerAuthorization.canAccess(authUser, current.get().userId(), authScopes)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "session was created concurrently by another caller; retry with a new sessionId");
        }
    }

    /**
     * Boolean form of {@link #requireSessionOwner} for transports that report errors
     * in-band instead of by status code (the WebSocket handler sends an error frame).
     *
     * <p>An unknown session id passes: it is not yet owned by anyone, and the caller
     * becomes its owner on creation. Only an existing session owned by someone else
     * is refused.
     */
    public boolean ownsSession(String sessionId, String authUser, String authScopes) {
        if (sessionId == null || sessionId.isBlank()) return true;
        SessionManager sm = sessions.get();
        if (sm == null) return true;
        Optional<Session> existing = sm.get(sessionId);
        if (existing.isEmpty()) return true;
        return CallerAuthorization.canAccess(authUser, existing.get().userId(), authScopes);
    }

    /** Reject a caller lacking {@code required}, with a message naming the scope. */
    public void requireScope(String authScopes, String required) {
        if (CallerAuthorization.hasScope(authScopes, required)) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
            "caller lacks scope '" + required + "'");
    }

    /** Per-user default session so tenants never share one implicit history. */
    public static String defaultSessionId(String user) {
        return "default-" + user;
    }

    /**
     * On-behalf-of metadata for {@code AgentContext}: the caller's scopes in the
     * form {@code ToolPolicy} (via the shared {@link CallerIdentity#META_SCOPES}
     * key) reads them, so tool scope checks run against the caller rather than
     * the service identity.
     */
    public static Map<String, Object> oboMetadata(String authScopes) {
        return oboMetadata(authScopes, null, null);
    }

    /**
     * P0-3 variant: also carries the caller's org (id + role) so usage accounting
     * attributes the turn's token rows to the organization.
     */
    public static Map<String, Object> oboMetadata(String authScopes, String orgId, String orgRole) {
        Map<String, Object> meta = new HashMap<>();
        List<String> scopes = List.copyOf(CallerAuthorization.scopeSet(authScopes));
        if (!scopes.isEmpty()) {
            meta.put(CallerIdentity.META_SCOPES, scopes);
        }
        if (orgId != null && !orgId.isBlank()) {
            meta.put(CallerIdentity.META_ORG_ID, orgId);
            if (orgRole != null && !orgRole.isBlank()) {
                meta.put(CallerIdentity.META_ORG_ROLE, orgRole);
            }
        }
        return meta;
    }

    /** Split a raw scope header into a set of non-blank entries. */
    public static java.util.Set<String> scopeList(String authScopes) {
        if (authScopes == null || authScopes.isBlank()) return java.util.Set.of();
        return Arrays.stream(authScopes.split(","))
            .map(String::trim).filter(s -> !s.isEmpty())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * The authorized caller behind a request.
     *
     * @param userId    the identity all persisted rows must be attributed to
     * @param sessionId the session the caller is authorized to use
     * @param scopes    the raw granted-scope header, for downstream OBO checks
     * @param orgId     the caller's primary organization (P0-3), null when none
     * @param orgRole   the caller's role in {@code orgId}, null when none
     */
    public record Caller(String userId, String sessionId, String scopes,
                         String orgId, String orgRole) {

        /** Backward-compatible constructor: no organization dimension. */
        public Caller(String userId, String sessionId, String scopes) {
            this(userId, sessionId, scopes, null, null);
        }

        /** OBO metadata for an {@code AgentContext} built from this caller. */
        public Map<String, Object> metadata() {
            return oboMetadata(scopes, orgId, orgRole);
        }

        public boolean hasScope(String required) {
            return CallerAuthorization.hasScope(scopes, required);
        }

        /** True when the caller belongs to an organization. */
        public boolean hasOrg() {
            return orgId != null && !orgId.isBlank();
        }

        /** True when the caller is OWNER/ADMIN of their organization. */
        public boolean isOrgAdmin() {
            return CallerIdentity.ORG_ROLE_OWNER.equals(orgRole)
                || CallerIdentity.ORG_ROLE_ADMIN.equals(orgRole);
        }
    }
}
