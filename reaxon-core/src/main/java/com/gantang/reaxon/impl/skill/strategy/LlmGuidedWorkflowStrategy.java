package com.gantang.reaxon.impl.skill.strategy;

import com.gantang.reaxon.api.agent.Agent;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.agent.AgentResponse;
import com.gantang.reaxon.api.skill.Skill;
import com.gantang.reaxon.api.skill.SkillEvent;
import com.gantang.reaxon.api.skill.SkillMetadata;
import com.gantang.reaxon.api.skill.SkillResult;
import com.gantang.reaxon.api.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * LLM-Guided execution strategy.
 *
 * <p>Instead of running pre-defined steps, this strategy re-enters the
 * {@link Agent} tool-loop with the skill's SKILL.md content as a system
 * prompt.  The LLM reads the skill definition, plans its own approach, and
 * drives tool calls autonomously — identical to how the Node.js Axiflux
 * executes {@code executionMode: llm_guided} skills.
 *
 * <p>System prompt constructed from SKILL.md:
 * <pre>
 * ## Role
 * You are executing the "{{ skill.name }}" skill.
 *
 * ## Description
 * {{ skill.description }}
 *
 * ## Available Tools
 * - tool_name_1
 * - tool_name_2
 *
 * ## Your Task
 * {{ input }}
 *
 * ## Skill Definition
 * [SKILL.md body]
 * </pre>
 *
 * <p>Because the delegate {@link Agent} is injected via
 * {@link #setAgent(Agent)} (called by {@code WorkflowSkillExecutor}), this
 * strategy works in both a pure-core context (agent == null → fail fast with
 * clear error) and in the Spring Boot context where the full Agent bean is wired.
 */
public final class LlmGuidedWorkflowStrategy extends AbstractWorkflowStrategy {

    private static final Logger log = LoggerFactory.getLogger(LlmGuidedWorkflowStrategy.class);
    private static final Duration AGENT_TIMEOUT = Duration.ofMinutes(5);

    public LlmGuidedWorkflowStrategy(ToolRegistry toolRegistry) {
        super(toolRegistry);
    }

    @Override
    public String mode() { return SkillMetadata.MODE_LLM_GUIDED; }

    @Override
    protected void runSteps(Skill skill, String input, AgentContext context,
                            WorkflowContext wf, Consumer<SkillEvent> emit) {
        Agent ag = agent();
        if (ag == null) {
            throw new IllegalStateException(
                "LlmGuidedWorkflowStrategy requires an Agent to be wired in. " +
                "Ensure AxifluxAutoConfiguration wires SkillExecutor → Agent, " +
                "or provide a custom Agent bean.");
        }

        String systemPrompt = buildSkillSystemPrompt(skill, input);

        emit.accept(SkillEvent.of(SkillEvent.Type.STEP_STARTED, skill.name(),
            context.sessionId(), "llm_guided"));

        try {
            // Build a sub-context that overrides the system prompt.
            // Session ID is preserved so memory context is carried over.
            AgentContext sub = AgentContext.builder()
                .sessionId(context.sessionId())
                .userId(context.userId())
                .currentQuery(input)
                .systemPrompt(systemPrompt)
                .metadata(Map.ofEntries(
                    Map.entry("skill_name", skill.name()),
                    Map.entry("skill_mode", "llm_guided"),
                    Map.entry("parent_session_id", context.sessionId())
                ))
                .build();

            AgentResponse response = ag.process(sub)
                .timeout(AGENT_TIMEOUT)
                .onErrorResume(e -> {
                    log.warn("LlmGuided skill '{}' agent error: {}", skill.name(), e.getMessage());
                    return Mono.just(AgentResponse.builder()
                        .status(AgentResponse.Status.ERROR)
                        .content("Agent execution error: " + e.getMessage())
                        .build());
                })
                .block(AGENT_TIMEOUT);

            String output = response != null ? response.content() : "(no response)";
            wf.setLastOutput(output);
            wf.setVariable("agent_response", output);
            wf.setVariable("agent_status",
                response != null ? response.status().name() : "ERROR");

            SkillResult.StepResult stepResult = new SkillResult.StepResult(
                wf.nextStepIndex(), "llm_guided", "llm",
                response == null || response.status() != AgentResponse.Status.ERROR,
                output, null, 0L);
            wf.addResult(stepResult);

            emit.accept(SkillEvent.of(SkillEvent.Type.STEP_COMPLETED, skill.name(),
                context.sessionId(), stepResult));

        } catch (Exception e) {
            log.error("LlmGuided skill '{}' failed: {}", skill.name(), e.getMessage(), e);
            SkillResult.StepResult stepResult = new SkillResult.StepResult(
                wf.nextStepIndex(), "llm_guided", "llm",
                false, null, e.getMessage(), 0L);
            wf.addResult(stepResult);
            wf.setLastOutput("Error: " + e.getMessage());
            emit.accept(SkillEvent.of(SkillEvent.Type.STEP_COMPLETED, skill.name(),
                context.sessionId(), stepResult));
        }
    }

    /**
     * Builds the system prompt injected into the Agent when running this skill.
     * The prompt is constructed from SKILL.md metadata + body so the LLM knows
     * the skill's purpose, available tools, and execution guidelines.
     */
    private static String buildSkillSystemPrompt(Skill skill, String input) {
        StringBuilder sb = new StringBuilder();

        sb.append("## Role\n");
        sb.append("You are executing the skill \"").append(skill.name()).append("\".\n\n");

        if (skill.description() != null && !skill.description().isBlank()) {
            sb.append("## Description\n");
            sb.append(skill.description()).append("\n\n");
        }

        if (!skill.metadata().requiredTools().isEmpty()) {
            sb.append("## Available Tools\n");
            sb.append("You may call the following tools to complete this task:\n");
            for (String t : skill.metadata().requiredTools()) {
                sb.append("- `").append(t).append("`\n");
            }
            sb.append("\n");
        }

        sb.append("## Your Task\n");
        sb.append(input).append("\n\n");

        String body = skill.readContent();
        if (body != null && !body.isBlank()) {
            sb.append("## Skill Definition\n");
            sb.append("Follow the guidelines below to complete the task:\n\n");
            sb.append(body).append("\n");
        }

        return sb.toString();
    }
}
