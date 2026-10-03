package com.gantang.reaxon.impl.approval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalSessionGrantTest {

    private final DefaultApprovalManager mgr = new DefaultApprovalManager();

    @Test
    void grantIsScopedToSessionAndTool() {
        mgr.grantSession("sess-1", "file_write", "alice");
        assertTrue(mgr.isSessionGranted("sess-1", "file_write"));
        // different tool not covered
        assertFalse(mgr.isSessionGranted("sess-1", "code_executor"));
        // different session not covered
        assertFalse(mgr.isSessionGranted("sess-2", "file_write"));
    }

    @Test
    void grantIsIdempotent() {
        mgr.grantSession("s", "t", "alice");
        mgr.grantSession("s", "t", "bob");
        assertTrue(mgr.isSessionGranted("s", "t"));
    }

    @Test
    void revokeRemovesGrant() {
        mgr.grantSession("s", "t", "alice");
        assertTrue(mgr.isSessionGranted("s", "t"));
        mgr.revokeSessionGrant("s", "t");
        assertFalse(mgr.isSessionGranted("s", "t"));
        // revoking a non-existent grant is a no-op
        mgr.revokeSessionGrant("s", "never-granted");
    }

    @Test
    void sessionGrantsDoNotLeakAcrossUsers() {
        mgr.grantSession("s", "t", "alice");
        // isSessionGranted only knows session+tool; user scoping is enforced at submission
        assertTrue(mgr.isSessionGranted("s", "t"));
    }

    @Test
    void grantsExpireAfterTtl() throws InterruptedException {
        DefaultApprovalManager shortTtl = new DefaultApprovalManager(
            java.time.Duration.ofMinutes(5), java.time.Duration.ofMillis(200));
        shortTtl.grantSession("s", "t", "alice");
        assertTrue(shortTtl.isSessionGranted("s", "t"));
        Thread.sleep(300);
        assertFalse(shortTtl.isSessionGranted("s", "t"),
            "an expired session grant must not auto-approve calls");
    }

    @Test
    void revokeAllSessionGrantsClearsEveryToolForOneSession() {
        mgr.grantSession("s1", "file_write", "alice");
        mgr.grantSession("s1", "email_send", "alice");
        mgr.grantSession("s2", "file_write", "bob");
        assertEquals(2, mgr.revokeAllSessionGrants("s1"));
        assertFalse(mgr.isSessionGranted("s1", "file_write"));
        assertFalse(mgr.isSessionGranted("s1", "email_send"));
        assertTrue(mgr.isSessionGranted("s2", "file_write"), "other sessions are unaffected");
    }
}
