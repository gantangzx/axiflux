package com.gantang.reaxon.impl.skill.strategy;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.skill.Skill;
import com.gantang.reaxon.api.skill.SkillEvent;
import com.gantang.reaxon.api.skill.SkillMetadata;
import com.gantang.reaxon.api.skill.SkillResult;
import com.gantang.reaxon.api.tool.ToolRegistry;

import java.util.function.Consumer;

/**
 * Executes skill steps one after another in declared order.
 * Strategy pattern.
 */
public final class SequentialWorkflowStrategy extends AbstractWorkflowStrategy {

    public SequentialWorkflowStrategy(ToolRegistry toolRegistry) {
        super(toolRegistry);
    }

    @Override public String mode() { return SkillMetadata.MODE_SEQUENTIAL; }

    @Override
    protected void runSteps(Skill skill, String input, AgentContext context,
                            WorkflowContext wf, Consumer<SkillEvent> emit) {
        for (SkillMetadata.StepDefinition step : skill.metadata().steps()) {
            emit.accept(SkillEvent.of(SkillEvent.Type.STEP_STARTED, skill.name(),
                context.sessionId(), step.name()));
            SkillResult.StepResult r = runStep(step, wf);
            wf.addResult(r);
            emit.accept(SkillEvent.of(
                r.success() ? SkillEvent.Type.STEP_COMPLETED : SkillEvent.Type.STEP_FAILED,
                skill.name(), context.sessionId(), r));
            if (!r.success()) break;      // stop on first failure
        }
    }
}
