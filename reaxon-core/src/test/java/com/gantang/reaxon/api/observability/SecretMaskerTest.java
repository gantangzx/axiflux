package com.gantang.reaxon.api.observability;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SecretMaskerTest {

    @Test
    @SuppressWarnings("unchecked")
    void masksSensitiveKeysRecursively() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("url", "https://example.com/webhook");
        in.put("apiKey", "sk-1234567890abcdef");
        in.put("headers", Map.of("Authorization", "Bearer abcdef123", "X-Custom", "keep-me"));
        in.put("nested", List.of(Map.of("password", "hunter2", "user", "bob")));

        Map<String, Object> out = (Map<String, Object>) SecretMasker.mask(in);

        String json = out.toString();
        assertTrue(json.contains("***"));
        assertFalse(json.contains("sk-1234567890abcdef"), "api key leaked: " + json);
        assertFalse(json.contains("Bearer abcdef123"), "bearer token leaked: " + json);
        assertFalse(json.contains("hunter2"), "password leaked: " + json);
        // non-sensitive values survive
        assertTrue(json.contains("https://example.com/webhook"));
        assertTrue(json.contains("keep-me"));
        assertTrue(json.contains("bob"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void masksCommonHeaderAndCredentialForms() {
        Map<String, Object> out = (Map<String, Object>) SecretMasker.mask(Map.of(
            "x-api-key", "abc",
            "access_token", "tok",
            "client_secret", "cs",
            "cookie", "sid=1",
            "dbPassword", "pw"));
        out.values().forEach(v -> assertEquals(SecretMasker.MASK, v));
    }

    @Test
    void passesThroughPrimitivesAndNull() {
        assertEquals("x", SecretMasker.mask("x"));
        assertEquals(42, SecretMasker.mask(42));
        assertNull(SecretMasker.mask(null));
    }

    // ===== maskString (free text) =====

    @Test
    void maskStringRedactsBearerAndKeyValue() {
        String s = "HTTP 401 from https://api.example.com/v1?api_key=abc123def "
            + "header Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig";
        String out = SecretMasker.maskString(s);
        assertFalse(out.contains("abc123def"), "api_key value leaked: " + out);
        assertFalse(out.contains("eyJhbGciOiJIUzI1NiJ9"), "bearer token leaked: " + out);
        assertTrue(out.contains("api_key="), "key label should survive for readability: " + out);
    }

    @Test
    void maskStringRedactsKnownTokenShapes() {
        assertFalse(SecretMasker.maskString("error: sk-ABCDEFGHIJK12345").contains("sk-ABCDEFGHIJK12345"));
        assertFalse(SecretMasker.maskString("key ark-7d95824c-9f0c-4c6e-8e6c failed")
            .contains("ark-7d95824c-9f0c-4c6e-8e6c"));
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV";
        assertFalse(SecretMasker.maskString("tok=" + jwt).contains(jwt));
    }

    @Test
    void maskStringLeavesCleanTextUntouched() {
        String clean = "Connection refused to https://example.com/path (timeout 15s)";
        assertEquals(clean, SecretMasker.maskString(clean));
        assertNull(SecretMasker.maskString(null));
        assertEquals("", SecretMasker.maskString(""));
    }
}
