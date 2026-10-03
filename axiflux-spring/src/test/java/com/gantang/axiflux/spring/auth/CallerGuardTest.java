package com.gantang.axiflux.spring.auth;

import com.gantang.reaxon.api.session.Session;
import com.gantang.reaxon.api.session.SessionManager;
import com.gantang.reaxon.impl.tool.policy.ScopePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Contract tests for {@link CallerGuard}.
 *
 * <p>Every user-facing surface routes its authorization through this class, so a
 * regression here is a cross-user hole everywhere at once. These tests pin the
 * three properties the controllers rely on: the verified header identity wins
 * over anything in the request body, an addressed session owned by someone else
 * yields 404 (never 403, which would confirm the id exists), and an unnamed
 * session is namespaced per user rather than shared.
 */
class CallerGuardTest {

    private static final String OWNER = "alice";
    private static final String ATTACKER = "bob";
    private static final String OWNED_SESSION = "s-alice";

    private SessionManager sessions;
    private CallerGuard guard;

    @BeforeEach
    void setUp() {
        sessions = mock(SessionManager.class);
        when(sessions.get(anyString())).thenReturn(Optional.empty());
        Session owned = mock(Session.class);
        when(owned.sessionId()).thenReturn(OWNED_SESSION);
        when(owned.userId()).thenReturn(OWNER);
        when(sessions.get(OWNED_SESSION)).thenReturn(Optional.of(owned));
        guard = new CallerGuard(sessions);
    }

    @Test
    void verifiedIdentityOverridesBodySuppliedUserId() {
        CallerGuard.Caller caller = guard.context(OWNER, "chat", ATTACKER, null);

        assertEquals(OWNER, caller.userId(),
            "a body userId must never be able to re-attribute a request");
    }

    @Test
    void wildcardCallerMayActOnBehalfOfAnotherUser() {
        CallerGuard.Caller caller = guard.context("console", "*", ATTACKER, null);

        assertEquals(ATTACKER, caller.userId());
    }

    @Test
    void unnamedSessionIsNamespacedPerUser() {
        assertEquals("default-" + OWNER, guard.context(OWNER, "chat", null, null).sessionId());
        assertEquals("default-" + ATTACKER, guard.context(ATTACKER, "chat", null, null).sessionId());
        assertNotEquals(
            guard.context(OWNER, "chat", null, null).sessionId(),
            guard.context(ATTACKER, "chat", null, null).sessionId(),
            "two tenants must not land in one shared default history");
    }

    @Test
    void addressingAnotherUsersSessionYields404NotForbidden() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> guard.context(ATTACKER, "chat", null, OWNED_SESSION));

        assertEquals(HttpStatus.NOT_FOUND.value(), e.getStatusCode().value(),
            "403 would confirm the session id exists");
        assertFalse(guard.ownsSession(OWNED_SESSION, ATTACKER, "chat"));
    }

    @Test
    void ownerAndWildcardCallerMayAddressTheSession() {
        assertEquals(OWNED_SESSION, guard.context(OWNER, "chat", null, OWNED_SESSION).sessionId());
        assertTrue(guard.ownsSession(OWNED_SESSION, OWNER, "chat"));
        assertTrue(guard.ownsSession(OWNED_SESSION, "console", "*"));
    }

    @Test
    void unknownSessionPassesBecauseCreationBindsItToTheCaller() {
        assertTrue(guard.ownsSession("does-not-exist", ATTACKER, "chat"));
        assertEquals("does-not-exist",
            guard.context(ATTACKER, "chat", null, "does-not-exist").sessionId());
    }

    @Test
    void anonymousCallerCannotReachAnOwnedSession() {
        // canAccess is fail-closed, so a request that arrived with no verified
        // identity cannot touch an existing session even by exact id.
        assertFalse(guard.ownsSession(OWNED_SESSION, null, null));
        assertFalse(guard.ownsSession(OWNED_SESSION, "", "chat"));
    }

    @Test
    void scopesAreThreadedIntoContextMetadataForOboChecks() {
        CallerGuard.Caller caller = guard.context(OWNER, "chat,tools:file", null, null);

        Object scopes = caller.metadata().get(ScopePolicy.META_SCOPES);
        assertInstanceOf(List.class, scopes);
        assertTrue(((List<?>) scopes).containsAll(List.of("chat", "tools:file")));
        assertTrue(caller.hasScope("tools:file"));
        assertFalse(caller.hasScope("config:admin"));
    }

    @Test
    void noScopesMeansNoMetadataEntryRatherThanAnEmptyGrant() {
        assertTrue(guard.context(OWNER, null, null, null).metadata().isEmpty());
        assertTrue(CallerGuard.oboMetadata("   ").isEmpty());
    }

    @Test
    void requireScopeRejectsWithForbiddenNamingTheScope() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> guard.requireScope("chat", CallerAuthorization.ADMIN_SCOPE_CONFIG));

        assertEquals(HttpStatus.FORBIDDEN.value(), e.getStatusCode().value());
        assertTrue(e.getReason() != null && e.getReason().contains(CallerAuthorization.ADMIN_SCOPE_CONFIG));
        assertDoesNotThrow(() -> guard.requireScope("config:admin", CallerAuthorization.ADMIN_SCOPE_CONFIG));
        assertDoesNotThrow(() -> guard.requireScope("*", CallerAuthorization.ADMIN_SCOPE_CONFIG));
    }

    @Test
    void withoutASessionBackendOwnershipCannotBeCheckedSoAccessIsAllowed() {
        // Deployments with no SessionManager have no owner to compare against; the
        // guard must not turn that into a blanket 404 for every request.
        CallerGuard noBackend = new CallerGuard((SessionManager) null);

        assertTrue(noBackend.ownsSession(OWNED_SESSION, ATTACKER, "chat"));
        assertEquals(OWNER, noBackend.context(OWNER, "chat", ATTACKER, null).userId());
    }

    // ===== audit authz P2-2: post-create owner re-check (TOCTOU) =====

    @Test
    void concurrentFirstWriteByAnotherUserYields409() {
        // The addressed session did not exist at ownership-check time, but a
        // racing first write by another user bound it in the meantime. The
        // post-create re-check must refuse rather than append to the winner's
        // history.
        SessionManager racing = mock(SessionManager.class);
        Session foreign = mock(Session.class);
        when(foreign.userId()).thenReturn(OWNER);
        when(foreign.sessionId()).thenReturn("race-s");
        // First get() (ownership check): absent; second get() (re-check): present, owned by alice.
        when(racing.get("race-s"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(foreign));
        CallerGuard g = new CallerGuard(racing);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> g.context(ATTACKER, "chat", null, "race-s"));
        assertEquals(HttpStatus.CONFLICT.value(), e.getStatusCode().value());
    }

    @Test
    void concurrentFirstWriteBySameUserPasses() {
        SessionManager racing = mock(SessionManager.class);
        Session own = mock(Session.class);
        when(own.userId()).thenReturn(ATTACKER);
        when(racing.get("race-s"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(own));
        CallerGuard g = new CallerGuard(racing);

        assertEquals("race-s", g.context(ATTACKER, "chat", null, "race-s").sessionId());
    }

    @Test
    void wildcardCallerSurvivesTheRecheck() {
        // The console (wildcard) may act on behalf of any owner; a session that
        // appeared between check and create is still accessible to it.
        SessionManager racing = mock(SessionManager.class);
        Session foreign = mock(Session.class);
        when(foreign.userId()).thenReturn(OWNER);
        when(racing.get("race-s"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(foreign));
        CallerGuard g = new CallerGuard(racing);

        assertEquals("race-s", g.context("console", "*", ATTACKER, "race-s").sessionId());
    }
}
