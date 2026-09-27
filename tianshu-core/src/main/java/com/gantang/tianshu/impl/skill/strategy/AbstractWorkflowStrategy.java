package com.gantang.tianshu.impl.skill.strategy;

import com.googlecode.aviator.AviatorEvaluator;
import com.gantang.tianshu.api.agent.Agent;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.skill.*;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolRegistry;
import com.gantang.tianshu.api.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template Method base class for {@link WorkflowStrategy}s.
 *
 * <p>Concrete subclasses implement {@link #runSteps(Skill, String, AgentContext, WorkflowContext, Consumer)}
 * to define how the steps are ordered / parallelised / planned.  This base
 * handles common concerns: variable substitution, condition evaluation, tool
 * dispatch, step timing, and event emission.
 */
public abstract class AbstractWorkflowStrategy implements WorkflowStrategy {

    private static final Logger log = LoggerFactory.getLogger(AbstractWorkflowStrategy.class);
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    protected final ToolRegistry toolRegistry;
    private volatile Agent agent;  // set by WorkflowSkillExecutor via setAgent()

    protected AbstractWorkflowStrategy(ToolRegistry toolRegistry) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
    }

    /** Inject the Agent (used by LlmGuidedWorkflowStrategy to re-enter the tool loop). */
    public void setAgent(Agent agent) { this.agent = agent; }
    /** @return the injected Agent, or null if not available. */
    protected Agent agent() { return agent; }

    /**
     * Template method — subclasses implement the step execution order.
     * The default {@link #execute} calls this once and aggregates.
     */
    protected abstract void runSteps(Skill skill,
                                     String input,
                                     AgentContext context,
                                     WorkflowContext workflow,
                                     Consumer<SkillEvent> emit);

    @Override
    public reactor.core.publisher.Mono<SkillResult> execute(Skill skill, String input, AgentContext context) {
        return reactor.core.publisher.Mono.fromCallable(() -> {
            Instant started = Instant.now();
            SkillResult.Builder rb = SkillResult.builder(skill.name()).startedAt(started);
            WorkflowContext wf = new WorkflowContext(skill, input, context);
            Consumer<SkillEvent> emit = e -> {}; // wired externally; no-op if none

            try {
                runSteps(skill, input, context, wf, emit);
                for (SkillResult.StepResult s : wf.stepResults()) {
                    rb.addStep(s);
                }
                boolean ok = wf.stepResults().stream().allMatch(SkillResult.StepResult::success);
                rb.success(ok);
                if (!ok) {
                    rb.error(wf.stepResults().stream()
                        .filter(s -> !s.success())
                        .map(s -> s.stepName() + ": " + s.error())
                        .findFirst().orElse("workflow failed"));
                }
                rb.output(wf.finalOutput());
            } catch (Exception e) {
                log.error("Workflow strategy {} failed for skill {}", mode(), skill.name(), e);
                rb.success(false).error(e.getMessage());
            }
            return rb.completedAt(Instant.now()).build();
        });
    }

    // === Shared helpers used by subclasses ===

    /** Run a single step, respecting condition and timeout. */
    protected final SkillResult.StepResult runStep(SkillMetadata.StepDefinition step,
                                                    WorkflowContext wf) {
        Instant t0 = Instant.now();
        try {
            if (step.condition() != null && !step.condition().isBlank()
                && !evalCondition(step.condition(), wf.variables())) {
                return new SkillResult.StepResult(
                    wf.nextStepIndex(), step.name(), step.type(),
                    true, "(skipped)", null,
                    java.time.Duration.between(t0, Instant.now()).toMillis());
            }
            String output;
            String type = step.type() == null ? "tool" : step.type().toLowerCase(Locale.ROOT);
            output = switch (type) {
                case "tool"      -> runToolStep(step, wf);
                case "llm"       -> runLlmStep(step, wf);
                case "sub_skill" -> runSubSkillStep(step, wf);
                case "condition" -> Boolean.toString(evalCondition(step.target(), wf.variables()));
                default          -> throw new IllegalArgumentException("Unknown step type: " + step.type());
            };
            if (step.outputVar() != null && !step.outputVar().isBlank()) {
                wf.setVariable(step.outputVar(), output);
            }
            wf.setLastOutput(output);
            return new SkillResult.StepResult(
                wf.nextStepIndex(), step.name(), step.type(), true, output, null,
                java.time.Duration.between(t0, Instant.now()).toMillis());
        } catch (Exception e) {
            log.warn("Step '{}' failed in skill '{}': {}", step.name(), wf.skill().name(), e.getMessage());
            return new SkillResult.StepResult(
                wf.nextStepIndex(), step.name(), step.type(), false, null, e.getMessage(),
                java.time.Duration.between(t0, Instant.now()).toMillis());
        }
    }

    private String runToolStep(SkillMetadata.StepDefinition step, WorkflowContext wf) {
        Tool tool = toolRegistry.get(step.target())
            .orElseThrow(() -> new IllegalStateException("Tool not found: " + step.target()));
        Map<String, Object> params = substituteVars(step.params(), wf.variables());
        String callId = "skill:" + wf.skill().name() + ":" + UUID.randomUUID();
        ToolResult r = tool.execute(callId, params, wf.agentContext());
        if (!r.success()) throw new RuntimeException(r.errorMessage());
        return r.displayContent();
    }

    /**
     * LLM step — default impl treats the target as a static prompt and returns
     * it unchanged.  Override this if you have an {@code LlmClient} to call.
     */
    protected String runLlmStep(SkillMetadata.StepDefinition step, WorkflowContext wf) {
        return substituteVars(step.target(), wf.variables());
    }

    /**
     * Sub-skill step — default impl throws; wire via a {@code SkillExecutor}
     * override if you support nested skills.
     */
    protected String runSubSkillStep(SkillMetadata.StepDefinition step, WorkflowContext wf) {
        throw new UnsupportedOperationException(
            "sub_skill step type not supported by this strategy: " + step.target());
    }

    /** Evaluate an Aviator boolean expression with the workflow variables. */
    protected boolean evalCondition(String expr, Map<String, Object> vars) {
        if (expr == null || expr.isBlank()) return true;
        try {
            Object v = AviatorEvaluator.execute(expr, new LinkedHashMap<>(vars));
            return Boolean.TRUE.equals(v);
        } catch (Exception e) {
            log.debug("Condition '{}' failed: {}", expr, e.getMessage());
            return false;
        }
    }

    /** Substitute ${var} placeholders in a string. */
    protected static String substituteVars(String s, Map<String, Object> vars) {
        if (s == null || s.indexOf('$') < 0) return s;
        Matcher m = VAR_PATTERN.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object v = vars.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(v == null ? "" : v.toString()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Substitute variables inside every string value in a params map. */
    protected static Map<String, Object> substituteVars(Map<String, Object> params, Map<String, Object> vars) {
        if (params == null || params.isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s) out.put(e.getKey(), substituteVars(s, vars));
            else out.put(e.getKey(), v);
        }
        return out;
    }
}
