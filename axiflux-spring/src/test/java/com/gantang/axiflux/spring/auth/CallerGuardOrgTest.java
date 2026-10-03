package com.gantang.axiflux.spring.auth;

import com.gantang.reaxon.api.auth.CallerIdentity;
import com.gantang.axiflux.spring.service.Membership;
import com.gantang.axiflux.spring.service.OrgDirectorySpi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * P0-3 additions to {@link CallerGuard}: the resolved caller carries its
 * primary organization (id + role), threaded into the OBO metadata so usage
 * accounting can attribute token rows to the org. Org resolution is
 * best-effort and must never break a request.
 */
class CallerGuardOrgTest {

    private OrgDirectorySpi orgDirectory;
    private CallerGuard guard;

    @BeforeEach
    void setUp() {
        orgDirectory = mock(OrgDirectorySpi.class);
        guard = new CallerGuard((com.gantang.reaxon.api.session.SessionManager) null, orgDirectory);
    }

    @Test
    void callerCarriesPrimaryOrgWhenTheUserHasOne() {
        when(orgDirectory.primaryMembership("alice"))
            .thenReturn(Optional.of(new Membership("org_1", "ADMIN")));

        CallerGuard.Caller caller = guard.context("alice", "chat", null, null);

        assertEquals("org_1", caller.orgId());
        assertEquals("ADMIN", caller.orgRole());
        assertTrue(caller.hasOrg());
        assertTrue(caller.isOrgAdmin());
    }

    @Test
    void orgIsThreadedIntoOboMetadata() {
        when(orgDirectory.primaryMembership("alice"))
            .thenReturn(Optional.of(new Membership("org_1", "OWNER")));

        Map<String, Object> meta = guard.context("alice", "chat", null, null).metadata();

        assertEquals("org_1", meta.get(CallerIdentity.META_ORG_ID));
        assertEquals("OWNER", meta.get(CallerIdentity.META_ORG_ROLE));
    }

    @Test
    void userWithoutOrgGetsNullOrgAndNoOrgMetadata() {
        when(orgDirectory.primaryMembership("solo")).thenReturn(Optional.empty());

        CallerGuard.Caller caller = guard.context("solo", "chat", null, null);

        assertNull(caller.orgId());
        assertNull(caller.orgRole());
        assertFalse(caller.hasOrg());
        assertFalse(caller.isOrgAdmin());
        assertFalse(caller.metadata().containsKey(CallerIdentity.META_ORG_ID));
        assertFalse(caller.metadata().containsKey(CallerIdentity.META_ORG_ROLE));
    }

    @Test
    void orgResolutionFailureNeverBreaksTheRequest() {
        when(orgDirectory.primaryMembership(anyString()))
            .thenThrow(new RuntimeException("db down"));

        CallerGuard.Caller caller = guard.context("alice", "chat", null, null);

        assertEquals("alice", caller.userId());
        assertNull(caller.orgId());
        assertFalse(caller.metadata().containsKey(CallerIdentity.META_ORG_ID));
    }

    @Test
    void noOrgBackendLeavesTheCallerOrgless() {
        CallerGuard noOrgs = new CallerGuard((com.gantang.reaxon.api.session.SessionManager) null);

        CallerGuard.Caller caller = noOrgs.context("alice", "chat", null, null);
        assertNull(caller.orgId());
        assertNull(caller.orgRole());
    }

    @Test
    void legacyThreeArgCallerConstructorStillWorks() {
        CallerGuard.Caller caller = new CallerGuard.Caller("alice", "s1", "chat");

        assertEquals("alice", caller.userId());
        assertEquals("s1", caller.sessionId());
        assertEquals("chat", caller.scopes());
        assertNull(caller.orgId());
        assertNull(caller.orgRole());
        assertFalse(caller.hasOrg());
    }

    @Test
    void oboMetadataThreeArgVariantAddsOrgKeys() {
        Map<String, Object> meta = CallerGuard.oboMetadata("chat", "org_1", "MEMBER");
        assertEquals("org_1", meta.get(CallerIdentity.META_ORG_ID));
        assertEquals("MEMBER", meta.get(CallerIdentity.META_ORG_ROLE));
        assertTrue(meta.containsKey(com.gantang.reaxon.impl.tool.policy.ScopePolicy.META_SCOPES));

        // Blank org values contribute no keys.
        Map<String, Object> blank = CallerGuard.oboMetadata("chat", " ", null);
        assertFalse(blank.containsKey(CallerIdentity.META_ORG_ID));
        assertFalse(blank.containsKey(CallerIdentity.META_ORG_ROLE));

        // Org id without a role still contributes the id only.
        Map<String, Object> noRole = CallerGuard.oboMetadata("chat", "org_1", null);
        assertEquals("org_1", noRole.get(CallerIdentity.META_ORG_ID));
        assertFalse(noRole.containsKey(CallerIdentity.META_ORG_ROLE));
    }
}
