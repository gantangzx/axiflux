package com.gantang.axiflux.spring.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CallerAuthorizationTest {

    @Test
    void authenticatedCallerCannotAccessAnotherOwner() {
        assertFalse(CallerAuthorization.canAccess("alice", "bob", "memory:read"));
    }

    @Test
    void wildcardDevelopmentCallerCanAccessRequestedOwner() {
        assertTrue(CallerAuthorization.canAccess("console-user", "alice", "*"));
    }

    @Test
    void schedulerAdminDoesNotAccessAnotherResourceOwner() {
        assertFalse(CallerAuthorization.canAccess("alice", "bob", "scheduler:admin,tool:net"));
        assertTrue(CallerAuthorization.isAdmin("scheduler:admin,tool:net"));
    }

    @Test
    void effectiveUserIgnoresClientOwnerWhenAuthenticated() {
        assertEquals("alice", CallerAuthorization.effectiveUser("alice", "bob"));
        assertEquals("bob", CallerAuthorization.effectiveUser(null, "bob"));
    }

    @Test
    void blankCallerIsDeniedEvenWithWildcardScopes() {
        // A missing caller means the controller never read the identity headers, or the
        // token had no usable subject. Neither is a legitimate state on a guarded
        // surface, so it must deny rather than fall through to "unauthenticated = allow".
        assertFalse(CallerAuthorization.canAccess(null, "alice", "*"));
        assertFalse(CallerAuthorization.canAccess("", "alice", "*"));
        assertFalse(CallerAuthorization.canAccess("   ", "alice", "memory:read"));
        // ...including when the owner is itself blank, which used to match trivially.
        assertFalse(CallerAuthorization.canAccess(null, null, null));
    }

    @Test
    void configAdminAndAuditReaderAreSeparateScopes() {
        assertTrue(CallerAuthorization.isConfigAdmin("config:admin"));
        assertTrue(CallerAuthorization.isConfigAdmin("*"));
        // scheduler:admin is not a blanket operator scope.
        assertFalse(CallerAuthorization.isConfigAdmin("scheduler:admin"));
        assertFalse(CallerAuthorization.isAuditReader("config:admin"));
        assertTrue(CallerAuthorization.isAuditReader("audit:read,memory:read"));
    }

    @Test
    void scopeSnapshotPreservesTheVerifiedGrantForAudit() {
        // Audit authz P2-5: the snapshot is what distinguishes a real scoped
        // grant from the dev wildcard in the approval audit trail.
        assertEquals("*", CallerAuthorization.scopeSnapshot("*"));
        assertEquals("tool:net,tool:db", CallerAuthorization.scopeSnapshot("tool:net,tool:db"));
        assertEquals("(none)", CallerAuthorization.scopeSnapshot(null));
        assertEquals("(none)", CallerAuthorization.scopeSnapshot("   "));
    }
}
