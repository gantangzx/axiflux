package com.gantang.reaxon.impl.workflow;

import com.gantang.reaxon.api.skill.Skill;
import com.gantang.reaxon.api.skill.SkillExecutor;
import com.gantang.reaxon.api.skill.SkillRegistry;
import com.gantang.reaxon.api.skill.SkillResult;
import com.gantang.reaxon.api.workflow.GraphState;
import com.gantang.reaxon.api.workflow.NodeSpec;
import reactor.core.publisher.Mono;

/**
 * {@link com.gantang.reaxon.api.workflow.NodeKind#SKILL}: resolves a registered skill
 * and executes it through the {@link SkillExecutor}, capturing its output.
 */
final class SkillNodeHandler implements NodeHandler {

    @Override
    public Mono<NodeOutcome> execute(NodeSpec spec, GraphState state, NodeRuntime runtime) {
        SkillRegistry registry = runtime.services().skillRegistry();
        SkillExecutor executor = runtime.services().skillExecutor();
        if (registry == null || executor == null) {
            return Mono.just(NodeOutcome.fail(state,
                "skill registry/executor not configured for node " + spec.id()));
        }
        Skill skill = registry.get(spec.skill()).orElse(null);
        if (skill == null) {
            return Mono.just(NodeOutcome.fail(state,
                "skill '" + spec.skill() + "' not found (node " + spec.id() + ")"));
        }

        String input = firstNonBlank(
            GraphSupport.render(spec.query(), state),
            state.varString(GraphState.LAST_OUTPUT),
            state.varString(GraphState.INPUT),
            "");

        return executor.execute(skill, input, runtime.baseContext())
            .map(result -> applyResult(spec, state, result))
            .onErrorResume(e -> Mono.just(
                NodeOutcome.fail(state, "skill node " + spec.id() + " failed: " + e.getMessage())));
    }

    private NodeOutcome applyResult(NodeSpec spec, GraphState state, SkillResult result) {
        if (!result.success()) {
            return NodeOutcome.fail(state,
                "skill '" + spec.skill() + "' failed: " + result.error());
        }
        String text = result.output();
        GraphState next = state.withNodeOutput(spec.id(), text);
        if (spec.outputVar() != null && !spec.outputVar().isBlank()) {
            next = next.withVariable(spec.outputVar(), text);
        }
        return NodeOutcome.next(next, null);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
