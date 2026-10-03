package com.gantang.axiflux.spring.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.gantang.reaxon.api.auth.CallerIdentity;
import com.gantang.axiflux.spring.config.props.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Issues and verifies short-lived, scoped access tokens for on-behalf-of (OBO)
 * delegation.
 *
 * <p>Tokens are standard signed JWTs (HS256) with claims
 * {@code sub} (user id), {@code scopes} (list of granted scopes),
 * {@code iat}, {@code exp}. Verification in the HTTP layer is performed by
 * Spring Security's OAuth2 resource server ({@code NimbusJwtDecoder}); this
 * service additionally issues tokens (the dev/self-serve edge) and exposes the
 * signing {@link #secretKey() key} so the resource-server decoder verifies
 * exactly what this issuer signs.
 *
 * <p>The symmetric key is derived as {@code SHA-256(secret)} so any configured
 * passphrase yields the 256-bit key HS256 requires. For production, replace the
 * self-serve issuer with an external IdP/OIDC and point the resource server at
 * its JWK set ({@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri})
 * with an asymmetric algorithm — the controllers/policy chain do not change.
 *
 * <p>When auth is disabled ({@code axiflux.auth.enabled=false}) tokens remain
 * issuable for testing but the security chain permits all requests and the
 * filter injects a full-access identity.
 */
public class AuthTokenService {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenService.class);
    public static final String SCOPES_CLAIM = "scopes";
    /** Default dev secret; auth enabled together with this value is a fatal misconfig. */
    public static final String DEFAULT_DEV_SECRET = "dev-insecure-secret-change-me";
    /** Issuer claim stamped on tokens this service signs (self-serve/local edge). */
    public static final String LOCAL_ISSUER = "urn:axiflux:local";

    private final String secret;
    private final SecretKey secretKey;
    private final int defaultTtlSeconds;
    private final boolean enabled;
    private final boolean requireExplicitScopes;
    private final boolean tokenEndpointEnabled;
    private final String tokenAdminSecret;
    private final Clock clock;

    public AuthTokenService(AuthProperties auth) {
        this(auth, Clock.systemUTC());
    }

    /** Test/determinism hook: supply a fixed or offset {@link Clock}. */
    public AuthTokenService(AuthProperties auth, Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        AuthProperties a = auth;
        String raw = (a != null && a.getSecret() != null) ? a.getSecret() : DEFAULT_DEV_SECRET;
        this.secret = raw;
        this.secretKey = new SecretKeySpec(sha256(raw), "HmacSHA256");
        this.defaultTtlSeconds = (a != null && a.getDefaultTtlSeconds() > 0) ? a.getDefaultTtlSeconds() : 3600;
        this.enabled = a != null && a.isEnabled();
        this.requireExplicitScopes = a != null && a.isRequireExplicitScopes();
        Boolean endpointOverride = a != null ? a.getTokenEndpointEnabled() : null;
        this.tokenEndpointEnabled = endpointOverride != null ? endpointOverride : !this.enabled;
        this.tokenAdminSecret = a != null && a.getTokenAdminSecret() != null
            ? a.getTokenAdminSecret().trim() : "";
        // Fail fast: if this service mints tokens itself (self-serve edge on)
        // while auth is enabled, a public default/empty secret means anyone can
        // forge full-privilege tokens. With an external IdP (edge off, JWKS
        // decoder wired) the local secret is unused and this does not fire.
        boolean weakSecret = raw == null || raw.isBlank() || DEFAULT_DEV_SECRET.equals(raw);
        if (this.enabled && this.tokenEndpointEnabled && weakSecret) {
            throw new IllegalStateException(
                "axiflux.auth.enabled=true with the token endpoint enabled, but no strong "
                + "axiflux.auth.secret is set (missing/default/blank). Either set a unique "
                + "secret (env AUTH_SECRET, e.g. openssl rand -hex 32) or point the resource "
                + "server at an external IdP via spring.security.oauth2.resourceserver.jwt "
                + "and leave token-endpoint-enabled off.");
        }
        if (this.tokenEndpointEnabled) {
            // Audit authz P2-1: the self-serve edge is a development convenience,
            // not a production token edge. Every issuance requires the
            // X-Admin-Token credential (see AuthController) and wildcard scopes
            // are clamped; say so loudly at startup.
            if (this.tokenAdminSecret.isEmpty()) {
                log.warn("axiflux.auth.token-endpoint-enabled=true but "
                    + "axiflux.auth.token-admin-secret is blank: POST /api/v1/auth/token "
                    + "will refuse issuance with 503 until a credential is configured "
                    + "(DEV-ONLY edge — never expose it in production).");
            } else {
                log.warn("POST /api/v1/auth/token is ENABLED (DEV-ONLY edge — never "
                    + "expose it in production). Issuance requires the X-Admin-Token "
                    + "header; wildcard '*' scopes are clamped per audit authz P2-1.");
            }
        }
    }

    public int getDefaultTtlSeconds() { return defaultTtlSeconds; }

    public boolean isEnabled() { return enabled; }

    /** When true, callers without explicit scopes get no privileges (ScopePolicy fail-closed). */
    public boolean isRequireExplicitScopes() { return requireExplicitScopes; }

    /** Whether the self-serve token issuance endpoint is exposed. */
    public boolean isTokenEndpointEnabled() { return tokenEndpointEnabled; }

    /**
     * The static admin credential required on {@code POST /api/v1/auth/token}
     * (audit authz P2-1). Empty when unconfigured — issuance then fails closed
     * with 503. Callers must compare against the {@code X-Admin-Token} header.
     */
    public String tokenAdminSecret() { return tokenAdminSecret; }

    /** Symmetric signing key, shared with the resource-server JWT decoder. */
    public SecretKey secretKey() { return secretKey; }

    /** Mint a signed JWT for a user with the given scopes. */
    public String issue(String userId, Set<String> scopes, Integer ttlSeconds) {
        try {
            long now = Instant.now(clock).getEpochSecond();
            long ttl = (ttlSeconds != null && ttlSeconds > 0) ? ttlSeconds : defaultTtlSeconds;
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(LOCAL_ISSUER)
                .subject(userId)
                .claim(SCOPES_CLAIM, List.copyOf(scopes == null ? Set.of() : scopes))
                .issueTime(new Date(now * 1000L))
                .expirationTime(new Date((now + ttl) * 1000L))
                .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secretKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to issue token: " + e.getMessage(), e);
        }
    }

    /**
     * Verify a JWT (signature + expiry) and resolve the caller identity.
     * @return the identity, or empty if malformed/bad-signature/expired.
     */
    public Optional<CallerIdentity> verify(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new MACVerifier(secretKey))) {
                log.debug("Token signature mismatch");
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            if (exp != null && exp.toInstant().isBefore(Instant.now(clock))) {
                log.debug("Token expired for sub={}", claims.getSubject());
                return Optional.empty();
            }
            String sub = claims.getSubject() != null ? claims.getSubject() : "anonymous";
            Set<String> scopes = new HashSet<>();
            Object raw = claims.getClaim(SCOPES_CLAIM);
            if (raw instanceof List<?> list) {
                for (Object o : list) if (o != null) scopes.add(String.valueOf(o));
            }
            return Optional.of(new CallerIdentity(sub, scopes));
        } catch (Exception e) {
            log.debug("Token verify failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
