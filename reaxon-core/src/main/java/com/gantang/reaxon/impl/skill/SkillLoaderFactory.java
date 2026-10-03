package com.gantang.reaxon.impl.skill;

import com.gantang.reaxon.api.skill.Skill;
import com.gantang.reaxon.api.skill.SkillLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Factory for picking the right {@link SkillLoader} for a given path.
 * Factory pattern.
 *
 * <p>Loaders are consulted in order; the first that {@link SkillLoader#supports}
 * the path wins.  {@link MarkdownSkillLoader} is registered by default.
 */
public final class SkillLoaderFactory {

    private final List<SkillLoader> loaders;

    public SkillLoaderFactory() {
        this(List.of(new MarkdownSkillLoader()));
    }

    public SkillLoaderFactory(List<SkillLoader> loaders) {
        this.loaders = new ArrayList<>(Objects.requireNonNull(loaders));
    }

    public void addLoader(SkillLoader loader) {
        loaders.add(Objects.requireNonNull(loader));
    }

    /** Return the loader that supports {@code skillDir}, if any. */
    public Optional<SkillLoader> loaderFor(Path skillDir) {
        for (SkillLoader l : loaders) {
            if (l.supports(skillDir)) return Optional.of(l);
        }
        return Optional.empty();
    }

    /** Load a single skill by asking a supporting loader. */
    public Optional<Skill> load(Path skillDir) throws IOException {
        var l = loaderFor(skillDir);
        return l.isPresent() ? Optional.of(l.get().load(skillDir)) : Optional.empty();
    }

    /** Load all skills below {@code rootDir} using every registered loader. */
    public List<Skill> loadAll(Path rootDir) throws IOException {
        if (rootDir == null || !Files.isDirectory(rootDir)) return List.of();
        Map<String, Skill> merged = new LinkedHashMap<>();
        for (SkillLoader l : loaders) {
            for (Skill s : l.loadAll(rootDir)) {
                merged.putIfAbsent(s.name(), s);
            }
        }
        return new ArrayList<>(merged.values());
    }

    public List<SkillLoader> loaders() { return Collections.unmodifiableList(loaders); }
}
