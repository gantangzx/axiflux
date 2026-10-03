package com.gantang.reaxon.impl.skill.interceptor;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.skill.Skill;
import com.gantang.reaxon.api.skill.SkillInterceptor;
import com.gantang.reaxon.api.skill.SkillResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

/**
 * Logs entry/exit of every skill execution.  First stage of the interceptor
 * chain (order = Integer.MIN_VALUE + 100).
 */
public final class LoggingInterceptor implements SkillInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);

    @Override public int order() { return Integer.MIN_VALUE + 100; }

    @Override
    public Mono<SkillResult> intercept(Skill skill, String input, AgentContext context, Chain chain) {
        Instant t0 = Instant.now();
        log.info("skill.start name={} session={} inputLen={}",
            skill.name(), context.sessionId(), input == null ? 0 : input.length());
        return chain.proceed(skill, input, context)
            .doOnSuccess(r -> log.info("skill.end name={} success={} steps={} took={}ms",
                skill.name(), r.success(), r.stepsExecuted(),
                Duration.between(t0, Instant.now()).toMillis()))
            .doOnError(e -> log.warn("skill.error name={} err={}", skill.name(), e.toString()));
    }
}
