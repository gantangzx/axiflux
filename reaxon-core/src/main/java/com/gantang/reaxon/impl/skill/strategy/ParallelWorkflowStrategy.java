package com.gantang.reaxon.impl.skill.strategy;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.skill.Skill;
import com.gantang.reaxon.api.skill.SkillEvent;
import com.gantang.reaxon.api.skill.SkillMetadata;
import com.gantang.reaxon.api.skill.SkillResult;
import com.gantang.reaxon.api.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Executes all steps concurrently and aggregates results.
 * Strategy pattern.
 *
 * <p>Steps in parallel mode should be independent — they cannot read each
 * other's {@code output_var}s. Use sequential mode when there are
 * dependencies.
 */
public final class ParallelWorkflowStrategy extends AbstractWorkflowStrategy {

    private final ExecutorService executor;
    private final int maxConcurrency;

    public ParallelWorkflowStrategy(ToolRegistry toolRegistry) {
        this(toolRegistry, Runtime.getRuntime().availableProcessors() * 2);
    }

    public ParallelWorkflowStrategy(ToolRegistry toolRegistry, int maxConcurrency) {
        super(toolRegistry);
        this.maxConcurrency = Math.max(1, maxConcurrency);
        this.executor = Executors.newFixedThreadPool(this.maxConcurrency, r -> {
            Thread t = new Thread(r, "axiflux-skill-parallel");
            t.setDaemon(true);
            return t;
        });
    }

    @Override public String mode() { return SkillMetadata.MODE_PARALLEL; }

    @Override
    protected void runSteps(Skill skill, String input, AgentContext context,
                            WorkflowContext wf, Consumer<SkillEvent> emit) {
        List<SkillMetadata.StepDefinition> steps = skill.metadata().steps();
        List<CompletableFuture<SkillResult.StepResult>> futures = new ArrayList<>(steps.size());
        for (SkillMetadata.StepDefinition step : steps) {
            emit.accept(SkillEvent.of(SkillEvent.Type.STEP_STARTED, skill.name(),
                context.sessionId(), step.name()));
            futures.add(CompletableFuture.supplyAsync(() -> runStep(step, wf), executor));
        }
        for (int i = 0; i < futures.size(); i++) {
            try {
                SkillResult.StepResult r = futures.get(i).get(2, TimeUnit.MINUTES);
                wf.addResult(r);
                emit.accept(SkillEvent.of(
                    r.success() ? SkillEvent.Type.STEP_COMPLETED : SkillEvent.Type.STEP_FAILED,
                    skill.name(), context.sessionId(), r));
            } catch (Exception e) {
                SkillMetadata.StepDefinition step = steps.get(i);
                SkillResult.StepResult r = new SkillResult.StepResult(
                    i, step.name(), step.type(), false, null, e.getMessage(), 0L);
                wf.addResult(r);
                emit.accept(SkillEvent.of(SkillEvent.Type.STEP_FAILED,
                    skill.name(), context.sessionId(), r));
            }
        }
    }

    public void shutdown() { executor.shutdown(); }
}
