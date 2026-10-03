package com.gantang.reaxon.api.skill;

import java.nio.file.Path;
import java.util.List;

/**
 * A skill — a reusable workflow defined in SKILL.md.
 * Compatible with Axiflux's SKILL.md format.
 */
public interface Skill {

    /** Unique skill name */
    String name();

    /** Human-readable description */
    String description();

    /** Directory containing SKILL.md and supporting files */
    Path skillPath();

    /** Trigger keywords that activate this skill */
    List<String> triggers();

    /** Full metadata parsed from SKILL.md front-matter */
    SkillMetadata metadata();

    /** Raw SKILL.md content */
    String readContent();
}
