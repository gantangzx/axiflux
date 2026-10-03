package com.gantang.reaxon.api.skill;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads a {@link Skill} from a directory containing SKILL.md.  Factory pattern
 * (a {@code SkillLoaderFactory} picks the loader that {@link #supports(Path)}
 * a given path).
 */
public interface SkillLoader {

    /** True if this loader can parse the given path. */
    boolean supports(Path skillDir);

    /** Parse and build a Skill from the given directory. */
    Skill load(Path skillDir) throws IOException;

    /** Scan a root directory and load all supported skills below it. */
    List<Skill> loadAll(Path rootDir) throws IOException;
}
