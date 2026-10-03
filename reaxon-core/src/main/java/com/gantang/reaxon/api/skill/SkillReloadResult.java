package com.gantang.reaxon.api.skill;

import java.util.List;

/**
 * Result of a skill-directory hot reload: what was added / updated / removed
 * when the registry was reconciled against the files on disk.
 *
 * @param added   skill names newly found on disk
 * @param updated skill names that already existed and were re-registered
 * @param removed skill names that disappeared from disk
 * @param total   total number of skills in the registry after reconciliation
 */
public record SkillReloadResult(List<String> added,
                                List<String> updated,
                                List<String> removed,
                                int total) {

    public static SkillReloadResult empty(int total) {
        return new SkillReloadResult(List.of(), List.of(), List.of(), total);
    }

    /** True when the reload produced no registry changes. */
    public boolean isEmpty() {
        return added.isEmpty() && updated.isEmpty() && removed.isEmpty();
    }
}
