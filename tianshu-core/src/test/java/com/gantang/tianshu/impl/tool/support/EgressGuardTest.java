package com.gantang.tianshu.impl.tool.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EgressGuardTest {

    @Test
    void allowsPublicHttps() {
        assertNull(EgressGuard.denyReason("https://api.example.com/v1/users", false));
    }

    @Test
    void blocksLoopbackIp() {
        assertNotNull(EgressGuard.denyReason("http://127.0.0.1:8080/health", false));
    }

    @Test
    void blocksLocalhostName() {
        assertNotNull(EgressGuard.denyReason("http://localhost:8080", false));
        assertNotNull(EgressGuard.denyReason("http://foo.localhost/", false));
    }

    @Test
    void blocksPrivateRanges() {
        assertNotNull(EgressGuard.denyReason("http://10.0.0.5/", false));
        assertNotNull(EgressGuard.denyReason("http://192.168.1.1/", false));
        assertNotNull(EgressGuard.denyReason("http://172.16.0.1/", false));
    }

    @Test
    void cloudMetadataBlockedEvenWhenPrivateNetworkAllowed() {
        // 169.254.169.254 hands out temp cloud credentials — never reachable, even in trusted private deployments.
        assertNotNull(EgressGuard.denyReason("http://169.254.169.254/latest/meta-data/", true));
        assertNotNull(EgressGuard.denyReason("http://169.254.169.254/latest/meta-data/", false));
    }

    @Test
    void privateNetworkFlagOpensPrivateRanges() {
        assertNull(EgressGuard.denyReason("http://192.168.1.1/", true));
        assertNull(EgressGuard.denyReason("http://10.0.0.5:9090/", true));
    }

    @Test
    void blocksNonHttpSchemes() {
        assertNotNull(EgressGuard.denyReason("file:///etc/passwd", false));
        assertNotNull(EgressGuard.denyReason("gopher://example.com/", false));
        assertNotNull(EgressGuard.denyReason("ftp://example.com/file", false));
    }

    @Test
    void blocksMalformedAndHostless() {
        assertNotNull(EgressGuard.denyReason("not a url", false));
        assertNotNull(EgressGuard.denyReason("", false));
    }

    @Test
    void blocksIpv6Loopback() {
        assertNotNull(EgressGuard.denyReason("http://[::1]:8080/", false));
    }

    // ===== resolveAndPin (connection pinning hook) =====

    @Test
    void resolveAndPinAllowsPublicHost() {
        var pinned = EgressGuard.resolveAndPin("example.com", false);
        assertFalse(pinned.isEmpty(), "public host should return pinned IPs");
    }

    @Test
    void resolveAndPinBlocksLocalhost() {
        assertTrue(EgressGuard.resolveAndPin("localhost", false).isEmpty());
    }

    @Test
    void resolveAndPinBlocksCloudMetadata() {
        assertTrue(EgressGuard.resolveAndPin("169.254.169.254", false).isEmpty());
        assertTrue(EgressGuard.resolveAndPin("169.254.169.254", true).isEmpty());
    }

    @Test
    void resolveAndPinBlocksPrivateLiteral() {
        assertTrue(EgressGuard.resolveAndPin("10.0.0.5", false).isEmpty());
        assertTrue(EgressGuard.resolveAndPin("192.168.1.1", false).isEmpty());
    }

    @Test
    void resolveAndPinAllowsPrivateWhenFlagged() {
        var pinned = EgressGuard.resolveAndPin("192.168.1.1", true);
        assertFalse(pinned.isEmpty());
    }

    @Test
    void resolveAndPinBlocksEmptyAndNull() {
        assertTrue(EgressGuard.resolveAndPin(null, false).isEmpty());
        assertTrue(EgressGuard.resolveAndPin("", false).isEmpty());
    }
}
