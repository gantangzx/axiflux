package com.gantang.tianshu.spring.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SecurityConfigTest {

    @Test
    void scopesClaimMapsToScopeAuthorities() {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(3600),
            Map.of("alg", "HS256"),
            Map.of("sub", "user-9",
                AuthTokenService.SCOPES_CLAIM, List.of("tool:net", "tool:db", "agent:spawn")));

        var auth = SecurityConfig.jwtAuthConverter().convert(jwt).block();
        assertNotNull(auth);
        assertTrue(auth.getAuthorities().contains(new SimpleGrantedAuthority("SCOPE_tool:net")));
        assertTrue(auth.getAuthorities().contains(new SimpleGrantedAuthority("SCOPE_tool:db")));
        assertTrue(auth.getAuthorities().contains(new SimpleGrantedAuthority("SCOPE_agent:spawn")));
        assertEquals(3, auth.getAuthorities().size());
        assertEquals("user-9", auth.getName());
    }

    @Test
    void missingScopesClaimYieldsNoAuthorities() {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(3600),
            Map.of("alg", "HS256"), Map.of("sub", "user-9"));
        var auth = SecurityConfig.jwtAuthConverter().convert(jwt).block();
        assertNotNull(auth);
        assertTrue(auth.getAuthorities().isEmpty());
    }
}
