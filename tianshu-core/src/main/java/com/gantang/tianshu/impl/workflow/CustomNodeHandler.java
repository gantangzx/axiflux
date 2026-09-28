package com.gantang.tianshu.impl.workflow;

import com.gantang.tianshu.api.workflow.GraphNode;
import com.gantang.tianshu.api.workflow.GraphNodeContext;
import com.gantang.tianshu.api.workflow.GraphState;
import com.gantang.tianshu.api.workflow.NodeSpec;
import reactor.core.publisher.Mono;

/**
 * {@link com.gantang.tianshu.api.workflow.NodeKind#CUSTOM}: delegates to a
 * programmatically supplied {@link GraphNode}, carried in the node's metadata under
 * {@link #IMPL_KEY}. The node returns whatever state the implementation produces.
 */
final class CustomNodeHandler implements NodeHandler {

    /** Metadata key under which the {@link GraphNode} implementation is supplied. */
    static final String IMPL_KEY = "__graphNode__";

    @Override
    public Mono<NodeOutcome> execute(NodeSpec spec, GraphState state, NodeRuntime runtime) {
        Object impl = spec.metadata().get(IMPL_KEY);
        if (!(impl instanceof GraphNode node)) {
            return Mono.just(NodeOutcome.fail(state,
                "custom node " + spec.id() + " has no " + IMPL_KEY + " implementation"));
        }
        GraphNodeContext ctx = new SpecNodeContext(spec, runtime.baseContext());
        return node.apply(state, ctx)
            .map(next -> NodeOutcome.next(next, null))
            .onErrorResume(e -> Mono.just(
                NodeOutcome.fail(state, "custom node " + spec.id() + " failed: " + e.getMessage())));
    }

    private record SpecNodeContext(NodeSpec spec,
                                   com.gantang.tianshu.api.agent.AgentContext baseContext)
        implements GraphNodeContext {
    }
}
