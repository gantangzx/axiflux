package com.gantang.reaxon.api.skill;

import com.gantang.reaxon.api.agent.AgentContext;
import reactor.core.publisher.Mono;

/**
 * Interceptor around a skill execution.  Chain of Responsibility pattern.
 *
 * <p>Interceptors form a chain that wraps the actual execution; each one may
 * short-circuit (e.g. deny), mutate the input, or observe timing/errors.
 */
public interface SkillInterceptor {

    /**
     * Called around a skill execution. Implementations must call
     * {@link Chain#proceed(Skill, String, AgentContext)} to continue the chain
     * (or return a {@link SkillResult} directly to short-circuit).
     */
    Mono<SkillResult> intercept(Skill skill, String input, AgentContext context, Chain chain);

    /** Order — lower runs first. Default 0. */
    default int order() { return 0; }

    /** The remaining chain to continue with. */
    interface Chain {
        Mono<SkillResult> proceed(Skill skill, String input, AgentContext context);
    }
}
