package com.gantang.tianshu.spring.auth;

import com.gantang.tianshu.api.auth.CallerIdentity;
import com.gantang.tianshu.spring.config.props.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AuthTokenServiceTest {

    private AuthTokenService service(boolean enabled, String secret) {
        AuthProperties props = new AuthProperties();
        props.setEnabled(enabled);
        props.setSecret(secret);
        return new AuthTokenService(props);
    }

    @Test
    void issueAndVerifyRoundTrip() {
        AuthTokenService svc = service(true, "unit-test-secret-0123456789");
        String token = svc.issue("user-42", Set.of("tool:net", "tool:db"), 600);
        Optional<CallerIdentity> id = svc.verify(token);
        assertTrue(id.isPresent());
        assertEquals("user-42", id.get().userId());
        assertTrue(id.get().scopes().contains("tool:net"));
        assertTrue(id.get().scopes().contains("tool:db"));
        assertEquals(2, id.get().scopes().size());
    }

    @Test
    void issuedTokenIsStandardJwtAndAcceptedByResourceServerDecoder() {
        // The Spring Security resource-server decoder must verify exactly what the
        // issuer signs — otherwise authenticated requests would 401 at the edge.
        AuthTokenService svc = service(true, "unit-test-secret-0123456789");
        String token = svc.issue("user-7", Set.of("tool:net", "agent:spawn"), 600);
        assertEquals(3, token.split("\\.").length, "JWT must have header.payload.signature");

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(svc.secretKey()).build();
        Jwt jwt = decoder.decode(token);
        assertEquals("user-7", jwt.getSubject());
        assertEquals(new java.util.HashSet<>(java.util.List.of("tool:net", "agent:spawn")),
            new java.util.HashSet<>(jwt.getClaimAsStringList(AuthTokenService.SCOPES_CLAIM)));
        assertNotNull(jwt.getExpiresAt());
    }

    @Test
    void tamperedSignatureRejected() {
        AuthTokenService svc = service(true, "unit-test-secret-0123456789");
        String token = svc.issue("u", Set.of("*"), 600);
        // flip a character in the signature segment
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertTrue(svc.verify(tampered).isEmpty());
        // completely garbage
        assertTrue(svc.verify("not-a-token").isEmpty());
        assertTrue(svc.verify("").isEmpty());
        assertTrue(svc.verify(null).isEmpty());
    }

    @Test
    void tokenSignedByDifferentSecretRejected() {
        String token = service(true, "secret-AAA").issue("u", Set.of("tool:net"), 600);
        // a different service with a different secret must not accept it
        assertTrue(service(true, "secret-BBB").verify(token).isEmpty());
    }

    @Test
    void expiredTokenRejected() {
        // Deterministic: fixed clock at issue time, offset clock at verify time.
        AuthProperties props = new AuthProperties();
        props.setEnabled(true);
        props.setSecret("unit-test-secret-0123456789");
        Clock t0 = Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);
        AuthTokenService issuer = new AuthTokenService(props, t0);
        String token = issuer.issue("u", Set.of("tool:net"), 60);

        // still valid 10s later
        AuthTokenService alive = new AuthTokenService(props, Clock.offset(t0, Duration.ofSeconds(10)));
        assertTrue(alive.verify(token).isPresent());

        // rejected once past exp
        AuthTokenService later = new AuthTokenService(props, Clock.offset(t0, Duration.ofMinutes(5)));
        assertTrue(later.verify(token).isEmpty());
    }

    @Test
    void expiredTokenRejectedByResourceServerDecoder() {
        // The edge decoder (system clock) must also reject an expired token.
        AuthProperties props = new AuthProperties();
        props.setEnabled(true);
        props.setSecret("unit-test-secret-0123456789");
        AuthTokenService issuer = new AuthTokenService(props,
            Clock.fixed(Instant.now().minusSeconds(3600), ZoneOffset.UTC));
        String expired = issuer.issue("u", Set.of("tool:net"), 60);

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(issuer.secretKey()).build();
        assertThrows(Exception.class, () -> decoder.decode(expired));
    }

    @Test
    void disabledFlagReflectsConfig() {
        assertFalse(service(false, "x").isEnabled());
        assertTrue(service(true, "x").isEnabled());
    }

    @Test
    void authEnabledWithDefaultSecretAndSelfServeEdgeFailsFast() {
        // Foot-gun: self-serve token edge on (HS256 deployment) with a public
        // default secret would let anyone forge full-privilege tokens.
        AuthProperties props = new AuthProperties();
        props.setEnabled(true);
        props.setSecret(AuthTokenService.DEFAULT_DEV_SECRET);
        props.setTokenEndpointEnabled(Boolean.TRUE);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> new AuthTokenService(props));
        assertTrue(ex.getMessage().contains("secret"), ex.getMessage());
    }

    @Test
    void blankSecretAlsoFailsFastWhenEdgeOn() {
        AuthProperties props = new AuthProperties();
        props.setEnabled(true);
        props.setSecret("   ");
        props.setTokenEndpointEnabled(Boolean.TRUE);
        assertThrows(IllegalStateException.class, () -> new AuthTokenService(props));
    }

    @Test
    void weakSecretAllowedWhenEdgeOffExternalIdp() {
        // Production with an external IdP (JWKS): the local HS256 secret is unused,
        // the self-serve edge is off by default, so startup must not fail.
        AuthProperties props = new AuthProperties();
        props.setEnabled(true);
        props.setSecret(AuthTokenService.DEFAULT_DEV_SECRET);
        props.setTokenEndpointEnabled(Boolean.FALSE);
        assertDoesNotThrow(() -> new AuthTokenService(props));
    }

    @Test
    void authDisabledWithDefaultSecretIsAllowedInDev() {
        assertDoesNotThrow(() -> service(false, AuthTokenService.DEFAULT_DEV_SECRET));
    }

    @Test
    void tokenEndpointDefaultsToEnabledInDevAndDisabledWhenAuthOn() {
        assertTrue(service(false, "x").isTokenEndpointEnabled());
        assertFalse(service(true, "unit-test-secret-0123456789").isTokenEndpointEnabled());
    }

    @Test
    void tokenEndpointOverrideIsHonored() {
        AuthProperties props = new AuthProperties();
        props.setEnabled(true);
        props.setSecret("unit-test-secret-0123456789");
        props.setTokenEndpointEnabled(Boolean.TRUE);
        assertTrue(new AuthTokenService(props).isTokenEndpointEnabled());

        props.setEnabled(false);
        props.setTokenEndpointEnabled(Boolean.FALSE);
        assertFalse(new AuthTokenService(props).isTokenEndpointEnabled());
    }

    @Test
    void requireExplicitScopesFlagReflectsConfig() {
        AuthProperties props = new AuthProperties();
        props.setEnabled(true);
        props.setSecret("unit-test-secret-0123456789");
        assertFalse(new AuthTokenService(props).isRequireExplicitScopes());
        props.setRequireExplicitScopes(true);
        assertTrue(new AuthTokenService(props).isRequireExplicitScopes());
    }
}
