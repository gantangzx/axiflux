package com.gantang.tianshu.impl.skill.interceptor;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.skill.Skill;
import com.gantang.tianshu.api.skill.SkillInterceptor;
import com.gantang.tianshu.api.skill.SkillResult;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Cancels a skill execution that exceeds the configured wall-clock timeout.
 * Chain of Responsibility.
 */
public final class TimeoutInterceptor implements SkillInterceptor {

    private final Duration timeout;

    public TimeoutInterceptor(Duration timeout) {
        this.timeout = timeout == null || timeout.isZero() ? Duration.ofMinutes(5) : timeout;
    }

    @Override public int order() { return -1000; }

    @Override
    public Mono<SkillResult> intercept(Skill skill, String input, AgentContext context, Chain chain) {
        return chain.proceed(skill, input, context)
            .timeout(timeout,
                Mono.just(SkillResult.fail(skill.name(),
                    "skill timed out after " + timeout.toSeconds() + "s")));
    }
}
