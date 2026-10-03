package com.gantang.axiflux.spring.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.reaxon.api.auth.CallerIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * WebFlux filter that bridges Spring Security's authenticated principal into the
 * internal identity headers consumed by controllers.
 *
 * <p>Authentication itself is performed by the OAuth2 resource-server filter
 * chain (see {@link SecurityConfig}); this filter runs <em>after</em> it and:
 * <ol>
 *   <li>Strips any client-supplied identity headers (so they cannot be forged).</li>
 *   <li>Auth enabled: reads the verified {@link JwtAuthenticationToken} from the
 *       reactive security context and injects {@code X-axiflux-User} /
 *       {@code X-axiflux-Scopes} from its {@code sub}/{@code scopes} claims;
 *       missing authentication on {@code /api/**} yields 401.</li>
 *   <li>Auth disabled (single-user/dev): injects a wildcard full-access identity
 *       for the default user.</li>
 * </ol>
 *
 * <p>Controllers read only these injected headers (never the raw Authorization
 * header) to build the {@link CallerIdentity} threaded into agent/tool calls, so
 * tool scope checks run against the authenticated caller rather than the
 * service's own privileges.
 */
public class AuthWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthWebFilter.class);

    public static final String H_USER = "X-axiflux-User";
    public static final String H_SCOPES = "X-axiflux-Scopes";
    private static final String DEFAULT_USER = "console-user";
    /**
     * Identity bridging is keyed on the whole {@code /api/} tree, not just
     * {@code /api/v1/}: a controller mounted anywhere else under {@code /api}
     * (the app module lives at {@code /api/app}) would otherwise see neither
     * an injected identity nor its forged headers stripped.
     */
    private static final String API_ROOT = "/api/";

    private final AuthTokenService tokenService;
    private final ObjectMapper om;
    private final JwtIdentityResolver identityResolver;
    private final AccountDirectorySpi userAccounts;
    /**
     * Whether {@code axiflux.auth.enabled} is true. Audit authz P2-3: when
     * auth is on but no usable token service exists, the filter must fail
     * closed (401), not silently inject a wildcard dev identity. Only an
     * explicit {@code enabled=false} gets the dev identity.
     */
    private final boolean authEnabled;

    /**
     * @param tokenService may be null when no auth backend is configured; the filter
     *                     then behaves exactly as it does when auth is disabled
     *                     (forged identity headers stripped, default identity injected)
     */
    public AuthWebFilter(AuthTokenService tokenService, ObjectMapper om) {
        this(tokenService, om, tokenService != null && tokenService.isEnabled());
    }

    public AuthWebFilter(AuthTokenService tokenService, ObjectMapper om, boolean authEnabled) {
        this(tokenService, om, authEnabled, null, null);
    }

    public AuthWebFilter(AuthTokenService tokenService, ObjectMapper om, boolean authEnabled,
                         JwtIdentityResolver identityResolver, AccountDirectorySpi userAccounts) {
        this.tokenService = tokenService;
        this.om = om;
        this.authEnabled = authEnabled;
        this.identityResolver = identityResolver;
        this.userAccounts = userAccounts;
    }

    /** After the Spring Security chain (ordered ~-100) so the principal exists. */
    @Override
    public int getOrder() {
        return 1000;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        // Identity bridging covers every authenticated surface: the REST API
        // (all of /api/**), the MCP endpoint and the WebSocket upgrade. Static
        // assets and probes (permitted at the security layer) pass through untouched.
        boolean identitySurface = path != null && (path.startsWith(API_ROOT)
            || path.startsWith("/Axiflux/ws") || path.equals("/mcp"));
        if (!identitySurface) {
            return chain.filter(exchange);
        }
        // Public auth edges carry no caller and must reach the controllers
        // without an injected identity: self-serve token issuance, local
        // registration and local login, plus the organization SSO start/callback
        // (no session exists before the IdP round-trip).
        if (path.equals("/api/v1/auth/token")
                || path.equals("/api/v1/auth/register")
                || path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/sso/start")
                || path.equals("/api/v1/sso/callback")
                || path.equals("/api/v1/billing/stripe/webhook")
                || path.equals("/api/v1/billing/confirm")) {
            return chain.filter(stripForgedHeaders(exchange));
        }

        if (tokenService == null || !tokenService.isEnabled()) {
            // Audit authz P2-3: with auth explicitly enabled, a missing/disabled
            // token service is a wiring failure — fail closed instead of
            // upgrading unauthenticated requests to a wildcard identity. Only an
            // explicit axiflux.auth.enabled=false selects dev mode.
            if (authEnabled) {
                return unauthorized(exchange,
                    "auth token service unavailable (axiflux.auth.enabled=true but no token service)");
            }
            // Single-user / dev mode: full-access default identity.
            ServerWebExchange mutated = stripForgedHeaders(exchange);
            mutated = mutated.mutate().request(r -> r
                .header(H_USER, DEFAULT_USER)
                .header(H_SCOPES, CallerIdentity.WILDCARD)).build();
            return chain.filter(mutated);
        }

        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> ctx.getAuthentication())
            .filter(Authentication::isAuthenticated)
            // NOTE: chain.filter(...) is Mono<Void> and completes empty; decide on
            // the <em>authentication</em> presence, never with switchIfEmpty on the
            // chain output (that would 401 every successful response post-commit).
            .flatMap(auth -> {
                ResolvedJwtIdentity identity = resolveIdentity(auth);
                // JIT provisioning also yields the locally stored account so the
                // filter can enforce status and merge administrator-granted roles.
                reactor.core.publisher.Mono<AccountView> provision;
                if (userAccounts == null) {
                    provision = reactor.core.publisher.Mono.empty();
                } else {
                    provision = reactor.core.publisher.Mono
                        .fromCallable(() -> userAccounts.provision(identity.userId(),
                            identity.username(), identity.email(),
                            identity.displayName(), identity.issuer()))
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                        .onErrorResume(e -> {
                            log.warn("JIT user provisioning failed for {}: {}", identity.userId(), e.toString());
                            return reactor.core.publisher.Mono.empty();
                        });
                }
                // An empty signal means "no account available" (store absent or
                // provision failed) -> fall back to token-only scopes. A present
                // account is enforced for status and role merge; note a committed
                // 403 body also completes empty, so the two cases must never be
                // conflated via switchIfEmpty.
                reactor.core.publisher.Mono<java.util.Optional<AccountView>> maybeAccount =
                    provision.map(java.util.Optional::of)
                        .defaultIfEmpty(java.util.Optional.empty());
                return maybeAccount.flatMap(maybe -> {
                    if (maybe.isEmpty()) {
                        ServerWebExchange mutated = withIdentity(stripForgedHeaders(exchange), identity);
                        return chain.filter(mutated);
                    }
                    var account = maybe.get();
                    if (AccountDirectorySpi.STATUS_SUSPENDED.equals(account.status())) {
                        return forbidden(exchange, account.id(), "account suspended");
                    }
                    // The injected identity is the internal surrogate id (ULID),
                    // never the external subject; administrator roles are merged.
                    ResolvedJwtIdentity effective = withInternalUser(identity, account.id());
                    effective = withAccountRoles(effective, account.roles());
                    ServerWebExchange mutated = withIdentity(stripForgedHeaders(exchange), effective);
                    return chain.filter(mutated);
                }).thenReturn(Boolean.TRUE);
            })
            .defaultIfEmpty(Boolean.FALSE)
            .flatMap(allowed -> Boolean.TRUE.equals(allowed)
                ? Mono.empty()
                : unauthorized(exchange, "missing or invalid bearer token"));
    }

    private ResolvedJwtIdentity resolveIdentity(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            if (identityResolver != null) {
                return identityResolver.resolve(jwtAuth.getToken());
            }
            var jwt = jwtAuth.getToken();
            return new ResolvedJwtIdentity(
                jwt.getSubject() != null ? jwt.getSubject() : auth.getName(),
                jwt.getSubject(), null, null,
                jwt.getClaimAsString("iss"),
                jwt.getClaimAsStringList(AuthTokenService.SCOPES_CLAIM));
        }
        List<String> scopes = new ArrayList<>();
        auth.getAuthorities().forEach(a -> {
            String role = a.getAuthority();
            if (role != null && role.startsWith("SCOPE_")) scopes.add(role.substring(6));
        });
        return new ResolvedJwtIdentity(auth.getName(), auth.getName(), null, null, null, scopes);
    }

    private static ServerWebExchange withIdentity(ServerWebExchange exchange, ResolvedJwtIdentity identity) {
        return exchange.mutate().request(r -> {
            r.header(H_USER, identity.userId());
            r.header(H_SCOPES, String.join(",", identity.scopes()));
        }).build();
    }

    /** Replace the external subject with the mapped internal surrogate id. */
    private static ResolvedJwtIdentity withInternalUser(ResolvedJwtIdentity identity, String internalId) {
        return new ResolvedJwtIdentity(internalId, identity.username(), identity.email(),
            identity.displayName(), identity.issuer(), identity.scopes());
    }

    /** Merge the account's administrator-granted roles into the token scopes. */
    private static ResolvedJwtIdentity withAccountRoles(ResolvedJwtIdentity identity, List<String> accountRoles) {
        java.util.Set<String> merged = new java.util.LinkedHashSet<>(
            identity.scopes() != null ? identity.scopes() : List.of());
        if (accountRoles != null) merged.addAll(accountRoles);
        return new ResolvedJwtIdentity(identity.userId(), identity.username(), identity.email(),
            identity.displayName(), identity.issuer(), new ArrayList<>(merged));
    }

    /** Return a mutated exchange whose client-supplied identity headers are removed. */
    private ServerWebExchange stripForgedHeaders(ServerWebExchange exchange) {
        return exchange.mutate().request(
            exchange.getRequest().mutate()
                .headers(h -> { h.remove(H_USER); h.remove(H_SCOPES); })
                .build()).build();
    }

    private Mono<Void> forbidden(ServerWebExchange exchange, String user, String reason) {
        log.debug("Auth forbidden user={} path={}: {}", user, exchange.getRequest().getPath(), reason);
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] body = om.writeValueAsBytes(Map.of("error", "forbidden", "reason", reason));
            return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
        } catch (Exception e) {
            return exchange.getResponse().setComplete();
        }
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String reason) {
        log.debug("Auth rejected {}: {}", exchange.getRequest().getPath(), reason);
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        try {
            byte[] body = om.writeValueAsBytes(Map.of("error", "unauthorized", "reason", reason));
            return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
        } catch (Exception e) {
            return exchange.getResponse().setComplete();
        }
    }

    /**
     * Read the caller identity injected by this filter from request headers.
     * Note: an empty scope set is preserved as-is — whether it means "full
     * access" (auth off / lenient) or "no privileges" (strict mode) is decided
     * by {@code ScopePolicy}, never here.
     */
    public static CallerIdentity identityFrom(String user, List<String> scopeHeader) {
        String uid = (user == null || user.isBlank()) ? DEFAULT_USER : user;
        java.util.Set<String> scopes = new java.util.HashSet<>();
        if (scopeHeader != null) {
            for (String csv : scopeHeader) {
                for (String s : csv.split(",")) {
                    String t = s.trim();
                    if (!t.isEmpty()) scopes.add(t);
                }
            }
        }
        return new CallerIdentity(uid, scopes);
    }
}
