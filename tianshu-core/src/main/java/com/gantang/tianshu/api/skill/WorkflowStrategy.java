package com.gantang.tianshu.api.skill;

import com.gantang.tianshu.api.agent.AgentContext;
import reactor.core.publisher.Mono;

/**
 * Strategy for a specific {@link SkillMetadata#executionMode()}.  Strategy
 * pattern — a {@code SkillExecutor} looks up the right strategy for the
 * skill's declared mode.
 */
public interface WorkflowStrategy {

    /** Which execution mode this strategy handles (sequential/parallel/llm_guided). */
    String mode();

    /**
     * Execute the workflow. Implementations must publish appropriate
     * {@link SkillEvent}s via the executor's event bus (injected).
     */
    Mono<SkillResult> execute(Skill skill, String input, AgentContext context);
}
