package com.gantang.reaxon.api.auth;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for {@link CallerIdentity} — above all its P0-3 backward
 * compatibility: the legacy (userId, scopes) shape must behave exactly as
 * before for the 17 pre-existing call sites, with the org dimension additive.
 */
class CallerIdentityTest {

    // ===== backward compatibility: 2-arg constructor =====

    @Test
    void twoArgConstructorLeavesOrgDimensionNull() {
        CallerIdentity id = new CallerIdentity("alice", Set.of("chat"));

        assertEquals("alice", id.userId());
        assertEquals(Set.of("chat"), id.scopes());
        assertNull(id.orgId(), "legacy constructor must not invent an org");
        assertNull(id.orgRole());
        assertFalse(id.hasOrg());
        assertFalse(id.isOrgAdmin());
        assertFalse(id.isOrgOwner());
    }

    @Test
    void twoArgConstructorStillDefensivelyCopiesAndNullsScopes() {
        CallerIdentity nullScopes = new CallerIdentity("alice", null);
        assertEquals(Set.of(), nullScopes.scopes());

        java.util.Set<String> mutable = new java.util.HashSet<>();
        mutable.add("chat");
        CallerIdentity id = new CallerIdentity("alice", mutable);
        mutable.add("tool:exec");
        assertEquals(Set.of("chat"), id.scopes(), "scopes must be copied at construction");
    }

    @Test
    void fullAccessFactoryStillGrantsWildcard() {
        CallerIdentity id = CallerIdentity.fullAccess("console-user");
        assertEquals("console-user", id.userId());
        assertTrue(id.has("anything"));
        assertTrue(id.has("tool:exec"));
        assertNull(id.orgId());
        assertFalse(id.hasOrg());
    }

    @Test
    void hasScopeIsUnaffectedByTheOrgDimension() {
        CallerIdentity noOrg = new CallerIdentity("alice", Set.of("chat"));
        CallerIdentity withOrg = new CallerIdentity("alice", "org_1",
            CallerIdentity.ORG_ROLE_OWNER, Set.of("chat"));

        assertEquals(noOrg.has("chat"), withOrg.has("chat"));
        assertEquals(noOrg.has("tool:db"), withOrg.has("tool:db"));
        assertTrue(withOrg.has("chat"));
        assertFalse(withOrg.has("tool:db"));

        CallerIdentity wildcard = new CallerIdentity("a", "o", "MEMBER", Set.of("*"));
        assertTrue(wildcard.has("tool:db"), "wildcard must still grant everything");
    }

    // ===== full (org-aware) constructor =====

    @Test
    void fullConstructorCarriesTheOrgDimension() {
        CallerIdentity id = new CallerIdentity("bob", "org_9",
            CallerIdentity.ORG_ROLE_MEMBER, Set.of("chat"));

        assertEquals("bob", id.userId());
        assertEquals("org_9", id.orgId());
        assertEquals("MEMBER", id.orgRole());
        assertTrue(id.hasOrg());
        assertFalse(id.isOrgAdmin(), "MEMBER is not an org admin");
        assertFalse(id.isOrgOwner());
    }

    @Test
    void orgAdminCoversOwnerAndAdminOnly() {
        assertTrue(new CallerIdentity("u", "o", CallerIdentity.ORG_ROLE_OWNER, Set.of()).isOrgAdmin());
        assertTrue(new CallerIdentity("u", "o", CallerIdentity.ORG_ROLE_ADMIN, Set.of()).isOrgAdmin());
        assertFalse(new CallerIdentity("u", "o", CallerIdentity.ORG_ROLE_MEMBER, Set.of()).isOrgAdmin());
        assertFalse(new CallerIdentity("u", "o", CallerIdentity.ORG_ROLE_VIEWER, Set.of()).isOrgAdmin());
        assertFalse(new CallerIdentity("u", "o", null, Set.of()).isOrgAdmin());
    }

    @Test
    void orgOwnerIsOnlyTheOwner() {
        assertTrue(new CallerIdentity("u", "o", CallerIdentity.ORG_ROLE_OWNER, Set.of()).isOrgOwner());
        assertFalse(new CallerIdentity("u", "o", CallerIdentity.ORG_ROLE_ADMIN, Set.of()).isOrgOwner());
    }

    @Test
    void hasOrgRejectsBlankOrgIds() {
        assertFalse(new CallerIdentity("u", "", "MEMBER", Set.of()).hasOrg());
        assertFalse(new CallerIdentity("u", "  ", "MEMBER", Set.of()).hasOrg());
        assertFalse(new CallerIdentity("u", null, "MEMBER", Set.of()).hasOrg());
    }

    @Test
    void orgRoleConstantsMatchTheRbacVocabulary() {
        assertEquals("OWNER", CallerIdentity.ORG_ROLE_OWNER);
        assertEquals("ADMIN", CallerIdentity.ORG_ROLE_ADMIN);
        assertEquals("MEMBER", CallerIdentity.ORG_ROLE_MEMBER);
        assertEquals("VIEWER", CallerIdentity.ORG_ROLE_VIEWER);
    }

    @Test
    void recordEqualityIncludesTheOrgDimension() {
        assertEquals(
            new CallerIdentity("a", "o", "MEMBER", Set.of("chat")),
            new CallerIdentity("a", "o", "MEMBER", Set.of("chat")));
        assertNotEquals(
            new CallerIdentity("a", Set.of("chat")),
            new CallerIdentity("a", "o", "MEMBER", Set.of("chat")));
    }
}
