package com.gantang.tianshu.impl.skill;

import com.gantang.tianshu.api.skill.Skill;
import com.gantang.tianshu.api.skill.SkillMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Immutable default {@link Skill} implementation used by loaders.
 */
public final class DefaultSkill implements Skill {

    private final SkillMetadata metadata;
    private final Path skillPath;
    private final Path skillMdPath;

    public DefaultSkill(SkillMetadata metadata, Path skillPath, Path skillMdPath) {
        this.metadata = metadata;
        this.skillPath = skillPath;
        this.skillMdPath = skillMdPath;
    }

    @Override public String name()                { return metadata.name(); }
    @Override public String description()          { return metadata.description(); }
    @Override public Path skillPath()              { return skillPath; }
    @Override public List<String> triggers()       { return metadata.triggers(); }
    @Override public SkillMetadata metadata()      { return metadata; }

    @Override
    public String readContent() {
        try {
            return Files.readString(skillMdPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read SKILL.md at " + skillMdPath, e);
        }
    }
}
