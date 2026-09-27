package com.gantang.tianshu.spring.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * infra P2-3: {@code IllegalArgumentException} messages may embed internal
 * absolute paths or source URLs; the 400/404 body must scrub them.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void sanitize_masksWindowsAbsolutePaths() {
        String out = GlobalExceptionHandler.sanitize(
            "Skill archive C:\\Users\\ops\\secrets\\skill.zip is not a valid zip");
        assertFalse(out.contains("C:\\Users\\ops\\secrets"), out);
        assertTrue(out.contains("C:\\…"), out);
        assertTrue(out.contains("is not a valid zip"), out);
    }

    @Test
    void sanitize_masksUnixAbsolutePaths() {
        String out = GlobalExceptionHandler.sanitize(
            "Skill not found at /home/deploy/tianshu/skills/bad-skill");
        assertFalse(out.contains("/home/deploy"), out);
        assertTrue(out.contains("/…"), out);
    }

    @Test
    void sanitize_masksUrlsButKeepsScheme() {
        String out = GlobalExceptionHandler.sanitize(
            "Cannot fetch https://internal-host.corp:8443/registry/skills/x?token=abc123");
        assertFalse(out.contains("internal-host"), out);
        assertFalse(out.contains("token=abc123"), out);
        assertTrue(out.contains("https://…"), out);
    }

    @Test
    void sanitize_keepsPlainMessagesIntact() {
        assertEquals("max-iterations must be between 1 and 100",
            GlobalExceptionHandler.sanitize("max-iterations must be between 1 and 100"));
        assertEquals("bad request", GlobalExceptionHandler.sanitize(null));
        assertEquals("bad request", GlobalExceptionHandler.sanitize("   "));
    }

    @Test
    void illegalArgument_withPath_returnsSanitized400() {
        var entity = handler.handleIllegalArgument(
            new IllegalArgumentException("bad skill at /opt/tianshu/skills/x")).block();
        assertNotNull(entity);
        assertEquals(400, entity.getStatusCode().value());
        assertNotNull(entity.getBody());
        assertFalse(entity.getBody().error().contains("/opt/tianshu"), entity.getBody().error());
        assertTrue(entity.getBody().error().contains("/…"), entity.getBody().error());
    }

    @Test
    void illegalArgument_notFoundMessage_becomes404() {
        var entity = handler.handleIllegalArgument(
            new IllegalArgumentException("tool not found: nope")).block();
        assertNotNull(entity);
        assertEquals(404, entity.getStatusCode().value());
    }
}
