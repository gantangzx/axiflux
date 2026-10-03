package com.gantang.axiflux.spring.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.axiflux.spring.config.props.SsoProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

/**
 * Mints and verifies the stateless OIDC authorization state (P3-7).
 *
 * <p>The state is {@code base64url(json).base64url(hmac)} where the HMAC is
 * keyed on the deployment auth secret, so it needs no server-side store and is
 * valid on any instance behind a load balancer. The payload carries the org,
 * the PKCE code verifier, the OIDC nonce and an expiry; verification is
 * signature + expiry with constant-time comparison.
 */
public final class SsoStateService {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectMapper om;
    private final SecretKeySpec key;
    private final Clock clock;

    public SsoStateService(ObjectMapper om, String secret, SsoProperties props) {
        this(om, secret, Clock.systemUTC());
    }

    SsoStateService(ObjectMapper om, String secret, Clock clock) {
        this.om = om;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        byte[] raw = (secret == null || secret.isBlank())
            ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.key = new SecretKeySpec(raw, "HmacSHA256");
    }

    /** State payload exchanged between {@link #sign} and {@link #verify}. */
    public record State(String orgId, String codeVerifier, String nonce) {}

    /** Build and sign a state string for an OIDC start. */
    public String sign(State state, int ttlSeconds) {
        try {
            long exp = clock.instant().getEpochSecond() + Math.max(30, ttlSeconds);
            var node = om.createObjectNode();
            node.put("o", state.orgId());
            node.put("v", state.codeVerifier());
            node.put("n", state.nonce());
            node.put("e", exp);
            String body = B64.encodeToString(om.writeValueAsBytes(node));
            return body + "." + B64.encodeToString(hmac(body));
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign SSO state: " + e.getMessage(), e);
        }
    }

    /**
     * Verify a state string and return its payload.
     *
     * @throws IllegalArgumentException when the signature is bad or it has expired
     */
    public State verify(String value) {
        if (value == null) throw new IllegalArgumentException("missing state");
        int dot = value.lastIndexOf('.');
        if (dot <= 0 || dot >= value.length() - 1) {
            throw new IllegalArgumentException("malformed state");
        }
        String body = value.substring(0, dot);
        String sig = value.substring(dot + 1);
        if (!MessageDigest.isEqual(
                B64.encodeToString(hmac(body)).getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("state signature mismatch");
        }
        try {
            JsonNode node = om.readTree(B64D.decode(body));
            long exp = node.path("e").asLong(0);
            if (clock.instant().getEpochSecond() > exp) {
                throw new IllegalArgumentException("state expired");
            }
            return new State(node.path("o").asText(null),
                node.path("v").asText(null), node.path("n").asText(null));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("malformed state payload: " + e.getMessage());
        }
    }

    /** Generate an OIDC PKCE / nonce random token (URL-safe). */
    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }

    /** PKCE S256 challenge for a code verifier. */
    public static String pkceChallenge(String verifier) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return B64.encodeToString(sha.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private byte[] hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }
}
