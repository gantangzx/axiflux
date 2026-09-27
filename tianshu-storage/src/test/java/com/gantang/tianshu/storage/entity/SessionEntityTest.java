package com.gantang.tianshu.storage.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-5: SessionEntity's {@code @PreUpdate} must NOT unconditionally bump
 * {@code lastActiveAt}. Only explicit touch paths (message append, explicit
 * save) should promote a session in the "recently active" list. State
 * transitions and metadata changes must not alter the semantic meaning of
 * "last active".
 */
class SessionEntityTest {

    @Test
    void preUpdateDoesNotBumpLastActiveAt() throws Exception {
        SessionEntity entity = new SessionEntity();
        entity.setId("s1");
        entity.setUserId("u1");
        entity.setAgentId("a1");

        // Simulate @PrePersist
        entity.onCreate();
        Instant lastActiveAfterCreate = entity.getLastActiveAt();
        assertNotNull(lastActiveAfterCreate);
        assertNotNull(entity.getCreatedAt());
        assertNotNull(entity.getUpdatedAt());

        // Wait a tiny bit so we can detect a change
        Thread.sleep(2);

        // Simulate @PreUpdate (state transition, metadata change, etc.)
        entity.onUpdate();

        assertTrue(entity.getUpdatedAt().isAfter(lastActiveAfterCreate)
                || entity.getUpdatedAt().equals(lastActiveAfterCreate),
            "updatedAt should be bumped by @PreUpdate");
        assertEquals(lastActiveAfterCreate, entity.getLastActiveAt(),
            "lastActiveAt must NOT be bumped by @PreUpdate (P2-5)");
    }

    @Test
    void explicitTouchStillUpdatesLastActiveAt() {
        SessionEntity entity = new SessionEntity();
        entity.onCreate();
        Instant before = entity.getLastActiveAt();

        Instant touchTime = before.plusSeconds(60);
        entity.setLastActiveAt(touchTime);
        assertEquals(touchTime, entity.getLastActiveAt(),
            "explicit setLastActiveAt (touch path) must work");
    }
}
