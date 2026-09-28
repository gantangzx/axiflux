package com.gantang.tianshu.impl.workflow;

import com.gantang.tianshu.api.workflow.GraphState;
import com.gantang.tianshu.api.workflow.NodeSpec;
import reactor.core.publisher.Mono;

/**
 * {@link com.gantang.tianshu.api.workflow.NodeKind#PASS}: no-op junction; state is
 * unchanged and routing proceeds through the node's outgoing edges.
 */
final class PassNodeHandler implements NodeHandler {

    @Override
    public Mono<NodeOutcome> execute(NodeSpec spec, GraphState state, NodeRuntime runtime) {
        return Mono.just(NodeOutcome.next(state, null));
    }
}
