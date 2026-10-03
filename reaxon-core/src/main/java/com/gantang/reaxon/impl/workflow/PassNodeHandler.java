package com.gantang.reaxon.impl.workflow;

import com.gantang.reaxon.api.workflow.GraphState;
import com.gantang.reaxon.api.workflow.NodeSpec;
import reactor.core.publisher.Mono;

/**
 * {@link com.gantang.reaxon.api.workflow.NodeKind#PASS}: no-op junction; state is
 * unchanged and routing proceeds through the node's outgoing edges.
 */
final class PassNodeHandler implements NodeHandler {

    @Override
    public Mono<NodeOutcome> execute(NodeSpec spec, GraphState state, NodeRuntime runtime) {
        return Mono.just(NodeOutcome.next(state, null));
    }
}
