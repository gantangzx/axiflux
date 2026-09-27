package com.gantang.tianshu.spring.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.spring.config.props.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthWebFilterTest {

    private final AuthWebFilter filter = new AuthWebFilter(service(true), new ObjectMapper());
    private final AuthWebFilter disabledFilter = new AuthWebFilter(service(false), new ObjectMapper());
    // P2-3: no token service at all — dev identity only when auth is explicitly off.
    private final AuthWebFilter nullFilter = new AuthWebFilter(null, new ObjectMapper(), false);
    private final AuthWebFilter nullFilterAuthOn = new AuthWebFilter(null, new ObjectMapper(), true);

    private AuthTokenService service(boolean enabled) {
        AuthProperties props = new AuthProperties();
        props.setEnabled(enabled);
        props.setSecret("webfilter-test-secret-0123456789");
        return new AuthTokenService(props);
    }

    /** Chain that captures the exchange that actually reaches downstream handlers. */
    private static class CapturingChain implements WebFilterChain {
        final List<ServerWebExchange> seen = new ArrayList<>();
        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            seen.add(exchange);
            return Mono.empty();
        }
    }

    private JwtAuthenticationToken jwtAuth() {
        Instant now = Instant.now();
        Jwt jwt = new Jwt("jwt-token-value", now, now.plusSeconds(3600),
            Map.of("alg", "HS256"),
            Map.of("sub", "user-1", AuthTokenService.SCOPES_CLAIM, List.of("tool:net", "tool:db")));
        return new JwtAuthenticationToken(jwt,
            List.of(new SimpleGrantedAuthority("SCOPE_tool:net"), new SimpleGrantedAuthority("SCOPE_tool:db")),
            "user-1");
    }

    @Test
    void authenticatedJwtInjectsIdentityHeaders() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/chat").build());
        CapturingChain chain = new CapturingChain();

        StepVerifier.create(
                filter.filter(exchange, chain)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(jwtAuth())))
            .verifyComplete();

        assertEquals(1, chain.seen.size());
        var headers = chain.seen.get(0).getRequest().getHeaders();
        assertEquals("user-1", headers.getFirst(AuthWebFilter.H_USER));
        assertEquals("tool:net,tool:db", headers.getFirst(AuthWebFilter.H_SCOPES));
    }

    @Test
    void resolvesConfiguredClaimsAndProvisionsAccount() {
        AuthProperties props = new AuthProperties();
        props.setUserIdClaim("tenant_user_id");
        props.setUsernameClaim("login");
        Instant now = Instant.now();
        Jwt jwt = new Jwt("jwt-custom", now, now.plusSeconds(3600), Map.of("alg", "RS256"), Map.of(
            "tenant_user_id", "external-1",
            "login", "gateway-alice",
            AuthTokenService.SCOPES_CLAIM, List.of("chat:read")));
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt,
            List.of(new SimpleGrantedAuthority("SCOPE_chat:read")), "external-1");
        AccountDirectorySpi accounts = mock(AccountDirectorySpi.class);
        AccountView mapped = new AccountView("01HX9K4EZWVYYZ5WR0H1Q1T4N6", "active", List.of());
        when(accounts.provision(any(), any(), any(), any(), any())).thenReturn(mapped);
        AuthWebFilter customFilter = new AuthWebFilter(service(true), new ObjectMapper(), true,
            new JwtIdentityResolver(props), accounts);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/chat").build());
        CapturingChain chain = new CapturingChain();

        StepVerifier.create(customFilter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
            .verifyComplete();

        var headers = chain.seen.get(0).getRequest().getHeaders();
        // Injected identity is the internal surrogate id, not the external subject.
        assertEquals("01HX9K4EZWVYYZ5WR0H1Q1T4N6", headers.getFirst(AuthWebFilter.H_USER));
        assertEquals("chat:read", headers.getFirst(AuthWebFilter.H_SCOPES));
        verify(accounts).provision(eq("external-1"), eq("gateway-alice"),
            any(), any(), any());
    }

    @Test
    void suspendedAccountIsRefusedWith403() {
        AccountDirectorySpi accounts = mock(AccountDirectorySpi.class);
        AccountView suspended = new AccountView("user-1", AccountDirectorySpi.STATUS_SUSPENDED, List.of());
        when(accounts.provision(any(), any(), any(), any(), any())).thenReturn(suspended);
        AuthWebFilter guarded = new AuthWebFilter(service(true), new ObjectMapper(), true,
            new JwtIdentityResolver(new AuthProperties()), accounts);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/chat").build());
        CapturingChain chain = new CapturingChain();

        StepVerifier.create(guarded.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(jwtAuth())))
            .verifyComplete();

        assertTrue(chain.seen.isEmpty(), "suspended request must not reach the chain");
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void accountRolesAreMergedIntoScopes() {
        AccountDirectorySpi accounts = mock(AccountDirectorySpi.class);
        AccountView active = new AccountView("user-1", "active",
            List.of("users:admin", "config:admin"));
        when(accounts.provision(any(), any(), any(), any(), any())).thenReturn(active);
        AuthWebFilter guarded = new AuthWebFilter(service(true), new ObjectMapper(), true,
            new JwtIdentityResolver(new AuthProperties()), accounts);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/chat").build());
        CapturingChain chain = new CapturingChain();

        StepVerifier.create(guarded.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(jwtAuth())))
            .verifyComplete();

        var headers = chain.seen.get(0).getRequest().getHeaders();
        List<String> scopes = headers.getValuesAsList(AuthWebFilter.H_SCOPES).stream()
            .flatMap(v -> java.util.Arrays.stream(v.split(",")))
            .map(String::trim).toList();
        assertEquals(List.of("tool:net", "tool:db", "users:admin", "config:admin"), scopes);
    }

    @Test
    void forgedClientIdentityHeadersAreReplaced() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/chat")
                .header(AuthWebFilter.H_USER, "attacker")
                .header(AuthWebFilter.H_SCOPES, "*")
                .build());
        CapturingChain chain = new CapturingChain();

        StepVerifier.create(
                filter.filter(exchange, chain)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(jwtAuth())))
            .verifyComplete();

        var headers = chain.seen.get(0).getRequest().getHeaders();
        assertEquals("user-1", headers.getFirst(AuthWebFilter.H_USER),
            "client-supplied identity header must not survive");
        assertEquals("tool:net,tool:db", headers.getFirst(AuthWebFilter.H_SCOPES));
    }

    @Test
    void missingAuthenticationReturns401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/chat").build());
        CapturingChain chain = new CapturingChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertTrue(chain.seen.isEmpty(), "request must not reach the chain");
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void disabledModeInjectsFullAccessDefaultIdentity() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/chat")
                .header(AuthWebFilter.H_USER, "attacker") // still stripped
                .build());
        CapturingChain chain = new CapturingChain();

        StepVerifier.create(disabledFilter.filter(exchange, chain)).verifyComplete();

        var headers = chain.seen.get(0).getRequest().getHeaders();
        assertEquals("console-user", headers.getFirst(AuthWebFilter.H_USER));
        assertEquals("*", headers.getFirst(AuthWebFilter.H_SCOPES));
    }

    @Test
    void nullTokenServiceStripsForgedHeadersAndInjectsDefaultIdentity() {
        // Deployments with no AuthTokenService bean (no auth backend configured) must
        // still strip client-supplied X-Tianshu-* headers and substitute the default
        // identity. A pass-through would let any client forge X-Tianshu-Scopes: *
        // and walk past every ownership and admin-scope gate downstream.
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/chat")
                .header(AuthWebFilter.H_USER, "attacker")
                .header(AuthWebFilter.H_SCOPES, "*")
                .build());
        CapturingChain chain = new CapturingChain();

        StepVerifier.create(nullFilter.filter(exchange, chain)).verifyComplete();

        var headers = chain.seen.get(0).getRequest().getHeaders();
        assertEquals("console-user", headers.getFirst(AuthWebFilter.H_USER),
            "forged H_USER must be replaced with default");
        assertEquals("*", headers.getFirst(AuthWebFilter.H_SCOPES),
            "forged H_SCOPES must be replaced with wildcard");
    }

    @Test
    void nullTokenServiceWithAuthEnabledFailsClosedWith401() {
        // Audit authz P2-3: tianshu.auth.enabled=true but no usable token
        // service is a wiring failure; upgrading the request to a wildcard
        // identity would be a silent auth bypass.
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/chat").build());
        CapturingChain chain = new CapturingChain();

        StepVerifier.create(nullFilterAuthOn.filter(exchange, chain)).verifyComplete();

        assertTrue(chain.seen.isEmpty(), "request must not reach the chain");
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void disabledTokenServiceWithAuthEnabledFailsClosedWith401() {
        // Same fail-closed path when the service bean exists but auth was never
        // enabled on it while the deployment configured enabled=true (P2-3).
        AuthWebFilter failClosed = new AuthWebFilter(service(false), new ObjectMapper(), true);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/chat").build());
        CapturingChain chain = new CapturingChain();

        StepVerifier.create(failClosed.filter(exchange, chain)).verifyComplete();

        assertTrue(chain.seen.isEmpty(), "request must not reach the chain");
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void twoArgConstructorDerivesAuthEnabledFromService() {
        // Legacy wiring (no explicit flag) keeps the old behaviour: a null or
        // disabled service means auth is off → dev identity; an enabled
        // service means JWT bridging.
        assertEquals("console-user", devIdentityUser(new AuthWebFilter(null, new ObjectMapper())));
        assertEquals("console-user", devIdentityUser(new AuthWebFilter(service(false), new ObjectMapper())));
    }

    private static String devIdentityUser(AuthWebFilter f) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/chat").build());
        CapturingChain chain = new CapturingChain();
        StepVerifier.create(f.filter(exchange, chain)).verifyComplete();
        if (chain.seen.isEmpty()) return null;
        return chain.seen.get(0).getRequest().getHeaders().getFirst(AuthWebFilter.H_USER);
    }

    @Test
    void tokenIssueEdgeIsPermittedWithoutAuthentication() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/auth/token")
                .header(AuthWebFilter.H_USER, "forged")
                .build());
        CapturingChain chain = new CapturingChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(1, chain.seen.size());
        var headers = chain.seen.get(0).getRequest().getHeaders();
        assertNull(headers.getFirst(AuthWebFilter.H_USER), "forged header stripped, no identity injected");
    }

    @Test
    void staticAssetsPassThroughUntouched() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/assets/index-abc.js").build());
        CapturingChain chain = new CapturingChain();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(1, chain.seen.size());
        assertSame(exchange, chain.seen.get(0));
    }

    @Test
    void websocketAndMcpPathsGetIdentityBridgingInDevMode() {
        // /tianshu/ws and /mcp are now identity surfaces: in dev mode (auth off)
        // the full-access default identity is injected just like for /api/v1/**.
        for (String path : new String[]{"/tianshu/ws", "/mcp"}) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(path).build());
            CapturingChain chain = new CapturingChain();

            StepVerifier.create(disabledFilter.filter(exchange, chain)).verifyComplete();

            assertEquals(1, chain.seen.size(), path);
            var headers = chain.seen.get(0).getRequest().getHeaders();
            assertEquals("console-user", headers.getFirst(AuthWebFilter.H_USER), path);
            assertEquals("*", headers.getFirst(AuthWebFilter.H_SCOPES), path);
        }
    }

    @Test
    void runsAfterSpringSecurityChain() {
        // Security's WebFilterChainProxy orders itself ~-100; this filter must see
        // the authenticated principal, so its order must be higher (later).
        assertTrue(filter.getOrder() > -100);
    }
}
