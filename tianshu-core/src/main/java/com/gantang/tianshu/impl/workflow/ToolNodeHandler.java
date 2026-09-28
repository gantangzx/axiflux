package com.gantang.tianshu.impl.workflow;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolRegistry;
import com.gantang.tianshu.api.tool.ToolResult;
import com.gantang.tianshu.api.workflow.GraphState;
import com.gantang.tianshu.api.workflow.NodeSpec;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * {@link com.gantang.tianshu.api.workflow.NodeKind#TOOL}: invokes one registered tool
 * directly (bypassing the model-driven loop) with parameters resolved from the graph
 * state, and captures its {@link ToolResult#displayContent()}.
 */
final class ToolNodeHandler implements NodeHandler {

    @Override
    public Mono<NodeOutcome> execute(NodeSpec spec, GraphState state, NodeRuntime runtime) {
        ToolRegistry registry = runtime.services().toolRegistry();
        if (registry == null) {
            return Mono.just(NodeOutcome.fail(state,
                "no tool registry configured for node " + spec.id()));
        }
        Tool tool = registry.get(spec.tool()).orElse(null);
        if (tool == null) {
            return Mono.just(NodeOutcome.fail(state,
                "tool '" + spec.tool() + "' not found (node " + spec.id() + ")"));
        }

        Map<String, Object> params = GraphSupport.resolveParams(spec.params(), state);
        AgentContext context = runtime.baseContext();
        String callId = "call_" + UUID.randomUUID();

        return tool.executeReactive(callId, params, context)
            .map(result -> applyResult(spec, state, result))
            .onErrorResume(e -> Mono.just(
                NodeOutcome.fail(state, "tool node " + spec.id() + " failed: " + e.getMessage())));
    }

    private NodeOutcome applyResult(NodeSpec spec, GraphState state, ToolResult result) {
        String text = result.displayContent();
        if (!result.success()) {
            return NodeOutcome.fail(state,
                "tool '" + spec.tool() + "' failed: " + result.errorMessage());
        }
        GraphState next = state.withNodeOutput(spec.id(), text);
        if (spec.outputVar() != null && !spec.outputVar().isBlank()) {
            next = next.withVariable(spec.outputVar(), text);
        }
        return NodeOutcome.next(next, null);
    }
}
