package com.gantang.tianshu.api.skill;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * In-memory registry of loaded skills.  Registry pattern.
 */
public interface SkillRegistry {

    void register(Skill skill);

    void unregister(String skillName);

    Optional<Skill> get(String name);

    /** Find skills whose triggers match the given query. */
    List<Skill> findByTrigger(String query);

    Collection<Skill> all();

    int size();

    void clear();
}
