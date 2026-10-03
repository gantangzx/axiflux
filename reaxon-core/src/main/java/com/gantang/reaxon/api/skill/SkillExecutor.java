package com.gantang.reaxon.api.skill;

import com.gantang.reaxon.api.agent.AgentContext;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Coordinates the execution of a skill:
 *  - trigger matching
 *  - interceptor chain
 *  - dispatch to the strategy for the skill's execution mode
 *  - publish events to listeners
 *
 * <p>Registry access is provided by {@link SkillRegistry}; interceptors and
 * listeners are wired via the concrete implementation.
 */
public interface SkillExecutor {

    /** Execute a specific skill. */
    Mono<SkillResult> execute(Skill skill, String input, AgentContext context);

    /** Execute the best matching skill for {@code query}, if any. */
    Mono<SkillResult> executeMatching(String query, AgentContext context);

    /** True if any of the skill's triggers appear in the query. */
    boolean matchesTrigger(Skill skill, String query);

    /**
     * Load all skills from a directory into the executor's {@link SkillRegistry}.
     * Delegates to the configured {@link SkillLoader}s.
     */
    void loadFromDirectory(String rootDir);

    /**
     * Hot-reload: re-scan the previously loaded skill root directory and
     * reconcile the registry — new skills are registered, changed skills are
     * re-registered, skills removed from disk are unregistered. No-op by
     * default for executors without directory backing.
     */
    default SkillReloadResult reloadSkills() {
        return SkillReloadResult.empty(0);
    }

    /** Add a lifecycle event listener. */
    void addListener(SkillEventListener listener);

    /** Register an interceptor into the chain. */
    void addInterceptor(SkillInterceptor interceptor);

    /**
     * Wire a "disabled skill names" gate. The supplier is re-evaluated on every
     * {@link #reloadSkills()} so enable / disable flips take effect on the next
     * reload without needing a process restart. Pass {@code null} to disable
     * the gate (every loaded skill is registered).
     *
     * <p>P1-4 contract: implementations that honour a disabled-supplier override
     * this; default is a no-op for executors without per-skill gating.
     */
    default void setDisabledNamesSupplier(Supplier<Set<String>> supplier) { /* no-op */ }
}
