package com.gantang.tianshu.impl.workflow;

import com.gantang.tianshu.api.agent.Agent;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.agent.AgentResponse;
import com.gantang.tianshu.api.workflow.GraphState;
import com.gantang.tianshu.api.workflow.NodeSpec;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Map;

/**
 * {@link com.gantang.tianshu.api.workflow.NodeKind#DECISION}: asks the agent to choose
 * exactly one route from the declared set, then forces the next node to that route.
 *
 * <p>The choice is matched case-insensitively against route ids: an exact id appearing
 * in the answer wins, otherwise the first id whose description is mentioned. If no
 * route can be matched, the run fails rather than taking an arbitrary branch.
 */
final class DecisionNodeHandler implements NodeHandler {

    @Override
    public Mono<NodeOutcome> execute(NodeSpec spec, GraphState state, NodeRuntime runtime) {
        Agent agent = runtime.services().agent();
        if (agent == null) {
            return Mono.just(NodeOutcome.fail(state,
                "no agent configured for decision node " + spec.id()));
        }

        AgentContext base = runtime.baseContext();
        String prompt = buildPrompt(spec, state);
        AgentContext context = base.toBuilder()
            .currentQuery(prompt)
            .systemPrompt("You are a deterministic router. Reply with exactly one route id "
                + "from the options and nothing else.")
            .build();

        return agent.process(context)
            .map(response -> route(spec, state, response))
            .onErrorResume(e -> Mono.just(NodeOutcome.fail(state,
                "decision node " + spec.id() + " failed: " + e.getMessage())));
    }

    private String buildPrompt(NodeSpec spec, GraphState state) {
        StringBuilder sb = new StringBuilder("Choose exactly one route.\n\nRoutes:\n");
        for (Map.Entry<String, String> r : spec.routes().entrySet()) {
            sb.append("- ").append(r.getKey())
                .append(": ").append(GraphSupport.render(r.getValue(), state)).append('\n');
        }
        Object last = state.var(GraphState.LAST_OUTPUT);
        sb.append("\nContext: ").append(last == null ? state.var(GraphState.INPUT) : last);
        return sb.toString();
    }

    private NodeOutcome route(NodeSpec spec, GraphState state, AgentResponse response) {
        String answer = response.content() == null ? ""
            : response.content().trim().toLowerCase(Locale.ROOT);
        GraphState next = state.withNodeOutput(spec.id(), response.content());

        for (String id : spec.routes().keySet()) {
            if (answer.equals(id.toLowerCase(Locale.ROOT))) {
                return NodeOutcome.next(next, id);
            }
        }
        for (String id : spec.routes().keySet()) {
            if (answer.contains(id.toLowerCase(Locale.ROOT))) {
                return NodeOutcome.next(next, id);
            }
        }
        for (Map.Entry<String, String> r : spec.routes().entrySet()) {
            String desc = GraphSupport.render(r.getValue(), state);
            if (desc != null && !desc.isBlank()
                && answer.contains(desc.trim().toLowerCase(Locale.ROOT))) {
                return NodeOutcome.next(next, r.getKey());
            }
        }
        return NodeOutcome.fail(next,
            "decision node " + spec.id() + " could not match a route from: " + answer);
    }
}
