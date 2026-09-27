package com.gantang.tianshu.impl.skill;

import com.gantang.tianshu.api.skill.Skill;
import com.gantang.tianshu.api.skill.SkillRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory {@link SkillRegistry}.  Registry pattern.
 */
public final class DefaultSkillRegistry implements SkillRegistry {

    private final Map<String, Skill> skills = new ConcurrentHashMap<>();

    @Override
    public void register(Skill skill) {
        Objects.requireNonNull(skill, "skill");
        Objects.requireNonNull(skill.name(), "skill.name");
        skills.put(skill.name(), skill);
    }

    @Override
    public void unregister(String skillName) {
        if (skillName != null) skills.remove(skillName);
    }

    @Override
    public Optional<Skill> get(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(skills.get(name));
    }

    @Override
    public List<Skill> findByTrigger(String query) {
        if (query == null || query.isBlank()) return List.of();
        String q = query.toLowerCase(Locale.ROOT);
        List<Skill> out = new ArrayList<>();
        for (Skill s : skills.values()) {
            for (String t : s.triggers()) {
                if (t != null && !t.isBlank() && q.contains(t.toLowerCase(Locale.ROOT))) {
                    out.add(s);
                    break;
                }
            }
        }
        return out;
    }

    @Override
    public Collection<Skill> all() {
        return Collections.unmodifiableCollection(new ArrayList<>(skills.values()));
    }

    @Override
    public int size() { return skills.size(); }

    @Override
    public void clear() { skills.clear(); }
}
