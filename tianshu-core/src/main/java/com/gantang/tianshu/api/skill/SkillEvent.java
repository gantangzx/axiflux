package com.gantang.tianshu.api.skill;

import java.time.Instant;

/**
 * Skill lifecycle event, published by a {@link SkillExecutor} to registered
 * {@link SkillEventListener}s.  Observer pattern.
 */
public record SkillEvent(
    Type type,
    String skillName,
    String sessionId,
    Instant at,
    Object payload
) {
    public enum Type {
        SKILL_MATCHED,
        SKILL_STARTED,
        STEP_STARTED,
        STEP_COMPLETED,
        STEP_FAILED,
        SKILL_COMPLETED,
        SKILL_FAILED,
        SKILL_CANCELLED
    }

    public static SkillEvent of(Type t, String skill, String sessionId, Object payload) {
        return new SkillEvent(t, skill, sessionId, Instant.now(), payload);
    }
}
