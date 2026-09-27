package com.gantang.tianshu.spring.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal OIDC client for the organization SSO flow (P3-7): authorization/token
 * endpoint discovery, the authorization-code token exchange and RS256 ID-token
 * verification.
 *
 * <p>Uses the JDK {@link HttpClient} for discovery/token calls and only
 * {@code nimbus-jose-jwt} (already on the classpath via Spring Security) for
 * signature validation; OIDC claim checks (issuer/audience/expiry/nonce) are
 * applied explicitly here.
 */
public class OidcClient {

    /** Verified identity claims read from the IdP ID token. */
    public record Identity(String subject, String email, String username, String displayName) {}

    /** Result of the code exchange. */
    public record TokenResult(Identity identity, String accessToken) {}

    /** Provider endpoints read from the discovery document. */
    public record Discovery(String issuer, String authorizationEndpoint,
                            String tokenEndpoint, String jwksUri) {}

    private final ObjectMapper om;
    private final HttpClient http;
    private final Clock clock;

    public OidcClient(ObjectMapper om) {
        this(om, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
            Clock.systemUTC());
    }

    OidcClient(ObjectMapper om, HttpClient http, Clock clock) {
        this.om = om;
        this.http = http;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Read OIDC discovery metadata from {@code <issuer>/.well-known/openid-configuration}. */
    public Discovery discover(String issuer) {
        try {
            String trimmed = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
            HttpRequest req = HttpRequest.newBuilder(URI.create(trimmed
                + "/.well-known/openid-configuration"))
                .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new SsoException(502, "OIDC discovery failed for " + issuer
                    + ": HTTP " + res.statusCode());
            }
            JsonNode node = om.readTree(res.body());
            String authz = text(node, "authorization_endpoint");
            String token = text(node, "token_endpoint");
            String jwks = text(node, "jwks_uri");
            if (authz == null || token == null || jwks == null) {
                throw new SsoException(502,
                    "OIDC discovery missing authorization_endpoint/token_endpoint/jwks_uri");
            }
            return new Discovery(textOr(node, "issuer", trimmed), authz, token, jwks);
        } catch (SsoException e) {
            throw e;
        } catch (Exception e) {
            throw new SsoException(502, "OIDC discovery error: " + e.getMessage());
        }
    }

    /** Build the full browser authorization URL for the code + PKCE flow. */
    public String authorizeUrl(Discovery discovery, String clientId, String redirectUri,
                               String state, String nonce, String codeChallenge) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("response_type", "code");
        q.put("client_id", clientId);
        q.put("redirect_uri", redirectUri);
        q.put("scope", "openid email profile");
        q.put("state", state);
        q.put("nonce", nonce);
        q.put("code_challenge", codeChallenge);
        q.put("code_challenge_method", "S256");
        return discovery.authorizationEndpoint() + "?" + formEncode(q);
    }

    /**
     * Exchange the authorization code for tokens and verify the ID token.
     *
     * @param clientSecret null for a public client (PKCE only)
     */
    public TokenResult exchange(Discovery discovery, String clientId, String clientSecret,
                                String redirectUri, String code, String codeVerifier,
                                String expectedNonce) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("client_id", clientId);
        if (codeVerifier != null) form.put("code_verifier", codeVerifier);
        if (clientSecret != null && !clientSecret.isBlank()) {
            form.put("client_secret", clientSecret);
        }

        JsonNode node = postForm(URI.create(discovery.tokenEndpoint()), form, clientId, clientSecret);
        String idToken = node.path("id_token").asText(null);
        if (idToken == null || idToken.isBlank()) {
            throw new SsoException(502, "OIDC token response did not include an id_token");
        }
        JWTClaimsSet claims = verifyIdToken(discovery, clientId, expectedNonce, idToken);
        return new TokenResult(toIdentity(claims), node.path("access_token").asText(null));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private JWTClaimsSet verifyIdToken(Discovery discovery, String clientId,
                                       String expectedNonce, String idToken) {
        try {
            JWKSource<SecurityContext> keySource = new RemoteJWKSet<>(URI.create(discovery.jwksUri()).toURL());
            DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            processor.setJWSKeySelector(new JWSVerificationKeySelector(JWSAlgorithm.RS256, keySource));

            SignedJWT jwt = SignedJWT.parse(idToken);
            JWTClaimsSet claims = processor.process(jwt, null);

            long now = clock.instant().getEpochSecond();
            long skew = 60;
            if (!discovery.issuer().equals(claims.getIssuer())) {
                throw new SsoException(401, "ID token issuer mismatch");
            }
            List<String> aud = claims.getAudience();
            if (aud == null || !aud.contains(clientId)) {
                throw new SsoException(401, "ID token audience mismatch");
            }
            if (claims.getExpirationTime() == null
                    || claims.getExpirationTime().toInstant().getEpochSecond() + skew < now) {
                throw new SsoException(401, "ID token expired");
            }
            if (claims.getNotBeforeTime() != null
                    && claims.getNotBeforeTime().toInstant().getEpochSecond() - skew > now) {
                throw new SsoException(401, "ID token not yet valid");
            }
            Object nonceClaim = claims.getClaim("nonce");
            if (expectedNonce != null && !expectedNonce.equals(nonceClaim == null ? null : nonceClaim.toString())) {
                throw new SsoException(401, "ID token nonce mismatch");
            }
            return claims;
        } catch (SsoException e) {
            throw e;
        } catch (Exception e) {
            throw new SsoException(401, "ID token verification failed: " + e.getMessage());
        }
    }

    private static Identity toIdentity(JWTClaimsSet c) {
        try {
            String sub = c.getSubject();
            String email = c.getStringClaim("email");
            String username = c.getStringClaim("preferred_username");
            String name = c.getStringClaim("name");
            if (sub == null || sub.isBlank()) {
                throw new SsoException(401, "ID token is missing subject");
            }
            if (username == null || username.isBlank()) {
                username = email != null ? email : sub;
            }
            return new Identity(sub, email, username, name != null ? name : username);
        } catch (SsoException e) {
            throw e;
        } catch (Exception e) {
            throw new SsoException(401, "failed to read ID token claims: " + e.getMessage());
        }
    }

    private JsonNode postForm(URI endpoint, Map<String, String> form,
                              String clientId, String clientSecret) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(form)));
            if (clientSecret != null && !clientSecret.isBlank()) {
                String pair = clientId + ":" + clientSecret;
                b.header("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
            }
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new SsoException(502, "OIDC token endpoint returned HTTP "
                    + res.statusCode() + ": " + brief(res.body()));
            }
            return om.readTree(res.body());
        } catch (SsoException e) {
            throw e;
        } catch (Exception e) {
            throw new SsoException(502, "OIDC token exchange error: " + e.getMessage());
        }
    }

    private static String formEncode(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (e.getValue() == null) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String v = text(node, field);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String brief(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
