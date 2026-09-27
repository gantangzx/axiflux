package com.gantang.tianshu.spring.auth;

import com.gantang.tianshu.spring.config.props.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JwtIdentityResolverTest {

    private final AuthProperties props = new AuthProperties();
    private final JwtIdentityResolver resolver = new JwtIdentityResolver(props);

    @Test
    void resolvesConfigurableClaims() {
        props.setUserIdClaim("tenant_user_id");
        props.setUsernameClaim("login");
        props.setEmailClaim("mail");
        props.setDisplayNameClaim("cn");

        ResolvedJwtIdentity identity = resolver.resolve(jwt(Map.of(
            "tenant_user_id", "u_1",
            "login", "alice",
            "mail", "alice@example.com",
            "cn", "Alice Wang",
            AuthTokenService.SCOPES_CLAIM, List.of("chat:read", "agent:run"),
            "iss", "https://idp.example.com")));

        assertEquals("u_1", identity.userId());
        assertEquals("alice", identity.username());
        assertEquals("alice@example.com", identity.email());
        assertEquals("Alice Wang", identity.displayName());
        assertEquals("https://idp.example.com", identity.issuer());
        assertEquals(List.of("chat:read", "agent:run"), identity.scopes());
    }

    @Test
    void fallsBackToStandardOidcAndSpaceScopes() {
        ResolvedJwtIdentity identity = resolver.resolve(jwt(Map.of(
            "sub", "u_2",
            "preferred_username", "bob",
            "email", "bob@example.com",
            "name", "Bob",
            "scope", "chat:read tools:read ")));

        assertEquals("u_2", identity.userId());
        assertEquals("bob", identity.username());
        assertEquals(List.of("chat:read", "tools:read"), identity.scopes());
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(60),
            Map.of("alg", "none"), claims);
    }
}
