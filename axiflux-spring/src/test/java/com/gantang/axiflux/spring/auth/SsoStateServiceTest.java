package com.gantang.axiflux.spring.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the stateless signed OIDC state + PKCE helpers. */
class SsoStateServiceTest {

    private final ObjectMapper om = new ObjectMapper();

    private SsoStateService serviceAt(String instant) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        return new SsoStateService(om, "test-auth-secret", clock);
    }

    @Test
    void signThenVerifyRoundTripsPayload() {
        SsoStateService svc = serviceAt("2026-09-22T10:00:00Z");
        var state = new SsoStateService.State("org_123", "verifier-abc", "nonce-xyz");
        String value = svc.sign(state, 600);

        SsoStateService.State parsed = svc.verify(value);
        assertEquals("org_123", parsed.orgId());
        assertEquals("verifier-abc", parsed.codeVerifier());
        assertEquals("nonce-xyz", parsed.nonce());
    }

    @Test
    void verifyRejectsTamperedSignature() {
        SsoStateService svc = serviceAt("2026-09-22T10:00:00Z");
        String value = svc.sign(new SsoStateService.State("o", "v", "n"), 600);
        // Flip a character in the signed body while keeping a dot + suffix.
        String tampered = value.substring(0, 4) + "X" + value.substring(5);
        assertThrows(IllegalArgumentException.class, () -> svc.verify(tampered));
    }

    @Test
    void verifyRejectsExpiredState() {
        SsoStateService issuer = serviceAt("2026-09-22T10:00:00Z");
        String value = issuer.sign(new SsoStateService.State("o", "v", "n"), 60);

        SsoStateService later = serviceAt("2026-09-22T10:02:00Z");
        assertThrows(IllegalArgumentException.class, () -> later.verify(value));
    }

    @Test
    void verifyAcceptsStateJustBeforeExpiry() {
        SsoStateService issuer = serviceAt("2026-09-22T10:00:00Z");
        String value = issuer.sign(new SsoStateService.State("o", "v", "n"), 60);

        SsoStateService later = serviceAt("2026-09-22T10:00:59Z");
        assertNotNull(later.verify(value));
    }

    @Test
    void differentSecretsProduceIncompatibleState() {
        var a = new SsoStateService(om, "secret-a",
            Clock.fixed(Instant.parse("2026-09-22T10:00:00Z"), ZoneOffset.UTC));
        var b = new SsoStateService(om, "secret-b",
            Clock.fixed(Instant.parse("2026-09-22T10:00:00Z"), ZoneOffset.UTC));
        String value = a.sign(new SsoStateService.State("o", "v", "n"), 600);
        assertThrows(IllegalArgumentException.class, () -> b.verify(value));
    }

    @Test
    void pkceChallengeIsDeterministicS256() {
        String c1 = SsoStateService.pkceChallenge("verifier");
        String c2 = SsoStateService.pkceChallenge("verifier");
        assertEquals(c1, c2);
        assertNotEquals(c1, SsoStateService.pkceChallenge("verifier2"));
        // URL-safe base64, no padding.
        assertTrue(c1.matches("[A-Za-z0-9_-]+"));
    }

    @Test
    void randomTokensAreUnique() {
        assertNotEquals(SsoStateService.randomToken(), SsoStateService.randomToken());
        assertEquals(43, SsoStateService.randomToken().length());
    }

    @Test
    void verifyRejectsGarbage() {
        SsoStateService svc = serviceAt("2026-09-22T10:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> svc.verify(null));
        assertThrows(IllegalArgumentException.class, () -> svc.verify("nodot"));
    }
}
