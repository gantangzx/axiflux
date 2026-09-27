package com.gantang.tianshu.impl.skill.interceptor;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.skill.Skill;
import com.gantang.tianshu.api.skill.SkillInterceptor;
import com.gantang.tianshu.api.skill.SkillResult;
import com.gantang.tianshu.api.tool.ToolRegistry;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Short-circuits a skill that declares tools which are not present in the
 * {@link ToolRegistry}.  Prevents partial-failure workflows.  Chain of
 * Responsibility.
 */
public final class RequiredToolsInterceptor implements SkillInterceptor {

    private final ToolRegistry registry;

    public RequiredToolsInterceptor(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override public int order() { return -500; }

    @Override
    public Mono<SkillResult> intercept(Skill skill, String input, AgentContext context, Chain chain) {
        List<String> missing = new ArrayList<>();
        for (String t : skill.metadata().requiredTools()) {
            if (registry.get(t).isEmpty()) missing.add(t);
        }
        if (!missing.isEmpty()) {
            return Mono.just(SkillResult.fail(skill.name(),
                "missing required tools: " + String.join(", ", missing)));
        }
        return chain.proceed(skill, input, context);
    }
}
