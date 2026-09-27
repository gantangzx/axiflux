package com.gantang.tianshu.registry.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-3: the publish/admin token must come ONLY from the
 * {@code Authorization: Bearer} header. The multipart form {@code token} field
 * bypass was removed (form bodies leak into proxy/access logs far more often
 * than the Authorization header).
 */
class RegistryControllerTokenTest {

    private static final String EXPECTED = "s3cret-publish-token";

    @Test
    void bearerHeaderIsAccepted() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + EXPECTED);
        assertDoesNotThrow(() -> RegistryController.requireToken(headers, EXPECTED));
    }

    @Test
    void bearerHeaderWithWhitespaceIsAccepted() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer   " + EXPECTED + "  ");
        assertDoesNotThrow(() -> RegistryController.requireToken(headers, EXPECTED));
    }

    @Test
    void wrongTokenIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer wrong-token");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> RegistryController.requireToken(headers, EXPECTED));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void missingAuthorizationHeaderIsRejected() {
        // No header at all → 401. A form field token can no longer substitute.
        HttpHeaders headers = new HttpHeaders();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> RegistryController.requireToken(headers, EXPECTED));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
    }

    @Test
    void nonBearerSchemeIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz");
        assertThrows(ResponseStatusException.class,
            () -> RegistryController.requireToken(headers, EXPECTED));
    }
}
