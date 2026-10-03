package com.gantang.reaxon.api.skill;

/**
 * Listener for {@link SkillEvent}s. Observer pattern.
 */
@FunctionalInterface
public interface SkillEventListener {

    /** Invoked whenever a skill lifecycle event is published. */
    void onEvent(SkillEvent event);
}
