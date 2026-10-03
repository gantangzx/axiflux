package com.gantang.axiflux.spring.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter;
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import com.gantang.axiflux.spring.config.props.CorsProperties;
import com.gantang.axiflux.spring.config.props.HeadersProperties;
import com.gantang.axiflux.spring.config.props.WebProperties;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Reactive Spring Security configuration for OBO bearer-token auth.
 *
 * <p>Two mutually exclusive filter chains:
 * <ul>
 *   <li><b>auth enabled</b> ({@code axiflux.auth.enabled=true}): <em>every</em>
 *       non-public path requires a valid JWT bearer token (verified by an
 *       OAuth2 resource-server decoder against {@link AuthTokenService#secretKey()},
 *       or an external IdP JWKS when {@code spring.security.oauth2.resourceserver
 *       .jwt.jwk-set-uri}/{@code issuer-uri} is configured). Public paths are
 *       limited to the token-issuance edge, the console static assets and
 *       liveness/readiness probes — this means {@code /mcp}, {@code /axiflux/ws}
 *       and {@code /actuator/**} (beyond health/info) are authenticated too.
 *       The {@code scopes} claim is mapped to {@code SCOPE_*} authorities.</li>
 *   <li><b>auth disabled</b> (default, dev/single-user): everything is permitted;
 *       {@link AuthWebFilter} injects a full-access identity.</li>
 * </ul>
 *
 * <p>WebSocket handshakes cannot set headers in the browser API; a token passed
 * as {@code ?token=} is hoisted to the Authorization header by
 * {@link BearerTokenHoistFilter} before this chain runs.
 *
 * <p>Authorization of individual <em>tool calls</em> stays in the framework-free
 * {@code ScopePolicy} inside reaxon-core: this layer only establishes who the
 * caller is, never what an LLM-driven agent turn may do.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /** JWT claim carrying the caller's granted scopes. */
    public static final String SCOPES_CLAIM = AuthTokenService.SCOPES_CLAIM;
    private static final String API_PREFIX = "/api/v1/";

    /** Public path matchers under auth-enabled mode (keep this list minimal). */
    private static final String[] PUBLIC_PATHS = {
        // the token-issuance ("login") edge must be reachable without a token;
        // the controller itself 404s when the edge is disabled.
        API_PREFIX + "auth/token",
        // self-serve local registration / login are public by definition.
        API_PREFIX + "auth/register",
        API_PREFIX + "auth/login",
        // organization OIDC SSO start/callback (no session before the IdP round-trip)
        API_PREFIX + "sso/start",
        API_PREFIX + "sso/callback",
        // Stripe server-to-server webhook carries no JWT; authenticity is enforced
        // inside the controller via HMAC-SHA256 signature verification.
        API_PREFIX + "billing/stripe/webhook",
        // post-Checkout confirmation activates the plan server-to-server while
        // the browser still has no JWT; Stripe verifies the paid session.
        API_PREFIX + "billing/confirm",
        // console static assets
        "/", "/index.html", "/favicon.ico",
        "/sso-complete.html",
        // Stripe Checkout browser redirects (no JWT at that point); these bridge
        // pages just flag the outcome and bounce back into the console.
        "/billing-return.html", "/billing-cancel.html",
        "/assets/**", "/*.js", "/*.css",
        // static brand/icon/font assets referenced by index.html (favicon) and the
        // sidebar mark; no sensitive content, safe to serve without a token.
        "/*.svg", "/*.ico", "/*.png", "/*.webp", "/*.jpg",
        "/*.woff", "/*.woff2", "/*.ttf", "/*.webmanifest",
        // liveness/readiness probes (no internals exposed)
        "/actuator/health", "/actuator/health/**", "/actuator/info",
        // Enterprise Edition SAML SP endpoints (no session before IdP round-trip;
        // SCIM uses its own bearer-token auth via ScimAuthWebFilter)
        "/saml/**",
        "/scim/**"
    };

    @Bean
    @Order(1)
    @ConditionalOnProperty(name = "axiflux.auth.enabled", havingValue = "true")
    public SecurityWebFilterChain secureChain(ServerHttpSecurity http, AuthTokenService tokens,
                                              ObjectProvider<ReactiveJwtDecoder> decoderProvider,
                                              WebProperties webProps) {
        // Prefer a Boot auto-configured decoder (issuer-uri/jwk-set-uri -> external
        // IdP with JWKS, asymmetric keys); fall back to the local HS256 secret so
        // the dev self-serve issuer works out of the box.
        ReactiveJwtDecoder decoder = decoderProvider.getIfAvailable(
            () -> NimbusReactiveJwtDecoder.withSecretKey(tokens.secretKey()).build());
        http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)      // token-auth API, no cookies
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .logout(ServerHttpSecurity.LogoutSpec::disable)
            .authorizeExchange(ex -> ex
                .pathMatchers(PUBLIC_PATHS).permitAll()
                // everything else authenticated: /api/v1/**, /mcp, /axiflux/ws,
                // /actuator (beyond health/info), any future admin path.
                .anyExchange().authenticated())
            .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt
                .jwtDecoder(decoder)
                .jwtAuthenticationConverter(jwtAuthConverter())));
        applyCors(http);
        applySecurityHeaders(http, webProps);
        return http.build();
    }

    /** Dev/single-user chain: no authentication; AuthWebFilter fakes a full-access caller. */
    @Bean
    @Order(2)
    @ConditionalOnProperty(name = "axiflux.auth.enabled", havingValue = "false", matchIfMissing = true)
    public SecurityWebFilterChain openChain(ServerHttpSecurity http, WebProperties webProps) {
        http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .logout(ServerHttpSecurity.LogoutSpec::disable)
            .authorizeExchange(ex -> ex.anyExchange().permitAll());
        applyCors(http);
        // Headers are a defence-in-depth measure independent of authentication:
        // a dev console on localhost is still a browser origin worth protecting.
        applySecurityHeaders(http, webProps);
        return http.build();
    }

    /**
     * Apply the configured security response headers to a chain.
     *
     * <p>Blank string / non-positive numeric values disable an individual header
     * rather than emitting something malformed, so operators can drop one header
     * without hand-writing a whole filter.
     */
    static void applySecurityHeaders(ServerHttpSecurity http, WebProperties webProps) {
        HeadersProperties h = webProps == null || webProps.getHeaders() == null
            ? new HeadersProperties() : webProps.getHeaders();
        if (h == null || !h.isEnabled()) {
            return;   // leave Spring Security's own defaults in place
        }
        http.headers(headers -> {
            headers.contentTypeOptions(Customizer.withDefaults());   // nosniff

            if (!h.getContentSecurityPolicy().isBlank()) {
                // Note: both methods return HeaderSpec (not the CSP spec), so they are
                // NOT chainable — they must be invoked as separate statements.
                headers.contentSecurityPolicy(csp -> {
                    csp.policyDirectives(h.getContentSecurityPolicy());
                    csp.reportOnly(h.isCspReportOnly());
                });
            }

            // HSTS is only written on HTTPS exchanges by Spring's writer, so leaving
            // it enabled on a plaintext dev port is harmless.
            //
            // Audit infra P2-2 (deployment note): behind a TLS-terminating
            // reverse proxy the exchange only looks "secure" to Spring when the
            // proxy sets X-Forwarded-Proto correctly AND the app honours it —
            // configure `server.forward-headers-strategy=framework` (or native)
            // / a ForwardedHeaderTransformer, otherwise HSTS is silently never
            // sent to browsers. We deliberately do NOT add `preload`: the
            // console may be served from internal hostnames where preloading
            // would be wrong.
            if (h.getHstsMaxAgeSeconds() > 0) {
                headers.hsts(hsts -> hsts
                    .maxAge(Duration.ofSeconds(h.getHstsMaxAgeSeconds()))
                    .includeSubdomains(h.isHstsIncludeSubdomains()));
            } else {
                headers.hsts(ServerHttpSecurity.HeaderSpec.HstsSpec::disable);
            }

            if (!h.getReferrerPolicy().isBlank()) {
                headers.referrerPolicy(rp -> rp.policy(referrerPolicy(h.getReferrerPolicy())));
            }

            if (h.getFrameOptions().isBlank()) {
                headers.frameOptions(ServerHttpSecurity.HeaderSpec.FrameOptionsSpec::disable);
            } else {
                headers.frameOptions(fo -> fo.mode(frameOptionsMode(h.getFrameOptions())));
            }

            if (!h.getPermissionsPolicy().isBlank()) {
                headers.permissionsPolicy(pp -> pp.policy(h.getPermissionsPolicy()));
            }
        });
    }

    /** Map a header-style value ({@code no-referrer}) to Spring's enum constant. */
    static ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy referrerPolicy(String value) {
        String name = value.trim().replace('-', '_').toUpperCase(java.util.Locale.ROOT);
        try {
            return ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unsupported axiflux.web.headers.referrer-policy: '"
                + value + "'. Valid values: no-referrer, no-referrer-when-downgrade, same-origin, "
                + "origin, strict-origin, origin-when-cross-origin, strict-origin-when-cross-origin, "
                + "unsafe-url", e);
        }
    }

    /** Map {@code DENY} / {@code SAMEORIGIN} to Spring's X-Frame-Options mode. */
    static XFrameOptionsServerHttpHeadersWriter.Mode frameOptionsMode(String value) {
        String name = value.trim().replace("-", "").replace("_", "").toUpperCase(java.util.Locale.ROOT);
        try {
            return XFrameOptionsServerHttpHeadersWriter.Mode.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unsupported axiflux.web.headers.frame-options: '"
                + value + "'. Valid values: DENY, SAMEORIGIN (or blank to disable)", e);
        }
    }

    /**
     * CORS is handled by a dedicated {@link org.springframework.web.cors.reactive.CorsWebFilter}
     * ordered ahead of the security chain (see {@link #AxifluxCorsWebFilter}), so the
     * security chain always disables its own CORS handling to avoid emitting
     * duplicate {@code Access-Control-*} headers.
     */
    static void applyCors(ServerHttpSecurity http) {
        http.cors(ServerHttpSecurity.CorsSpec::disable);
    }

    /**
     * Preflight-capable CORS filter, ordered before the security chain so an
     * {@code OPTIONS} preflight is answered without needing a bearer token
     * (browsers never attach credentials to preflights).
     *
     * <p>Only created when {@code axiflux.web.cors.enabled=true}; otherwise no
     * CORS headers are emitted anywhere, which is the correct posture for the
     * same-origin console.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnProperty(name = "axiflux.web.cors.enabled", havingValue = "true")
    public org.springframework.web.cors.reactive.CorsWebFilter AxifluxCorsWebFilter(
            CorsConfigurationSource source) {
        return new org.springframework.web.cors.reactive.CorsWebFilter(source);
    }

    /**
     * Explicit CORS allowlist for the API, only created when
     * {@code axiflux.web.cors.enabled=true}. The console is served same-origin,
     * so the default is no CORS at all.
     *
     * <p>Fails fast on {@code allowed-origins: "*"} + {@code allow-credentials:
     * true}: browsers reject that combination, and booting with a config that
     * silently never works is worse than refusing to boot.
     */
    @Bean
    @ConditionalOnProperty(name = "axiflux.web.cors.enabled", havingValue = "true")
    public CorsConfigurationSource AxifluxCorsConfigurationSource(WebProperties webProps) {
        CorsProperties c = webProps.getCors();
        boolean wildcard = c.getAllowedOrigins().contains("*");
        if (wildcard && c.isAllowCredentials()) {
            throw new IllegalStateException(
                "axiflux.web.cors: allowed-origins '*' cannot be combined with allow-credentials=true "
                + "(browsers reject it). Use allowed-origin-patterns for wildcard + credentials, "
                + "or set allow-credentials=false.");
        }
        if (c.getAllowedOrigins().isEmpty() && c.getAllowedOriginPatterns().isEmpty()) {
            throw new IllegalStateException(
                "axiflux.web.cors.enabled=true requires at least one allowed-origins "
                + "or allowed-origin-patterns entry");
        }
        CorsConfiguration cfg = new CorsConfiguration();
        if (!c.getAllowedOrigins().isEmpty()) cfg.setAllowedOrigins(c.getAllowedOrigins());
        if (!c.getAllowedOriginPatterns().isEmpty()) cfg.setAllowedOriginPatterns(c.getAllowedOriginPatterns());
        cfg.setAllowedMethods(c.getAllowedMethods());
        cfg.setAllowedHeaders(c.getAllowedHeaders());
        cfg.setExposedHeaders(c.getExposedHeaders());
        cfg.setAllowCredentials(c.isAllowCredentials());
        cfg.setMaxAge(c.getMaxAgeSeconds());
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }

    /** Map the {@code scopes} claim (string list) to {@code SCOPE_*} authorities. */
    static ReactiveJwtAuthenticationConverter jwtAuthConverter() {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<GrantedAuthority> authorities = new ArrayList<>();
            List<String> scopes = jwt.getClaimAsStringList(SCOPES_CLAIM);
            if (scopes == null) {
                // Standard OIDC/OAuth2 tokens carry a space-delimited "scope" string.
                String scope = jwt.getClaimAsString("scope");
                scopes = scope != null ? java.util.Arrays.asList(scope.split("\\s+")) : List.of();
            }
            for (String s : scopes) {
                if (s != null && !s.isBlank()) authorities.add(new SimpleGrantedAuthority("SCOPE_" + s.trim()));
            }
            return Flux.fromIterable(authorities);
        });
        return converter;
    }
}
