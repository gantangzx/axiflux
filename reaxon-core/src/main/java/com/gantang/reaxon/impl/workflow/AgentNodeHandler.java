package com.gantang.reaxon.impl.workflow;

import com.gantang.reaxon.api.agent.Agent;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.agent.AgentResponse;
import com.gantang.reaxon.api.workflow.GraphState;
import com.gantang.reaxon.api.workflow.NodeSpec;
import reactor.core.publisher.Mono;

/**
 * {@link com.gantang.reaxon.api.workflow.NodeKind#AGENT}: runs one full agent turn
 * (which itself may perform its internal tool-call loop) and captures the final text.
 */
final class AgentNodeHandler implements NodeHandler {

    @Override
    public Mono<NodeOutcome> execute(NodeSpec spec, GraphState state, NodeRuntime runtime) {
        Agent agent = runtime.services().agent();
        if (agent == null) {
            return Mono.just(NodeOutcome.fail(state, "no agent configured for node " + spec.id()));
        }

        AgentContext base = runtime.baseContext();
        String query = firstNonBlank(
            GraphSupport.render(spec.query(), state),
            state.varString(GraphState.LAST_OUTPUT),
            state.varString(GraphState.INPUT));

        AgentContext context = base.toBuilder()
            .currentQuery(query == null ? "" : query)
            .systemPrompt(firstNonBlank(GraphSupport.render(spec.systemPrompt(), state),
                base.systemPrompt()))
            .build();

        return agent.process(context)
            .map(response -> applyResponse(spec, state, response))
            .onErrorResume(e -> Mono.just(
                NodeOutcome.fail(state, "agent node " + spec.id() + " failed: " + e.getMessage())));
    }

    private NodeOutcome applyResponse(NodeSpec spec, GraphState state, AgentResponse response) {
        String content = response.content();
        GraphState next = state.withNodeOutput(spec.id(), content);
        if (spec.outputVar() != null && !spec.outputVar().isBlank()) {
            next = next.withVariable(spec.outputVar(), content);
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
