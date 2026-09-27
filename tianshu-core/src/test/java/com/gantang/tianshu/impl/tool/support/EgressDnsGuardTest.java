package com.gantang.tianshu.impl.tool.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EgressDnsGuardTest {

    @Test
    void cloudMetadataBlockedEvenWhenPrivateNetworkAllowed() {
        // link-local/cloud metadata is ALWAYS blocked regardless of the dev escape hatch
        assertNotNull(EgressGuard.denyReason("http://169.254.169.254/latest/meta-data/", true));
        assertNotNull(EgressGuard.denyReason("http://169.254.169.254/latest/meta-data/", false));
        // IPv6 link-local too
        assertNotNull(EgressGuard.denyReason("http://[fe80::1]/", true));
    }

    @Test
    void privateRangesRespectAllowFlag() {
        // explicitly allowed → permitted
        assertNull(EgressGuard.denyReason("http://192.168.1.10/x", true));
        assertNull(EgressGuard.denyReason("http://10.0.0.5/x", true));
        // default → blocked by SSRF guard
        assertNotNull(EgressGuard.denyReason("http://192.168.1.10/x", false));
        assertNotNull(EgressGuard.denyReason("http://10.0.0.5/x", false));
    }

    @Test
    void publicHostsAllowed() {
        assertNull(EgressGuard.denyReason("https://example.com/api", false));
        assertNull(EgressGuard.denyReason("https://httpbin.org/get", false));
    }
}
