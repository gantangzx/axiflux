package com.gantang.tianshu.impl.workflow;

import com.gantang.tianshu.api.workflow.GraphState;
import com.gantang.tianshu.api.workflow.NodeSpec;
import reactor.core.publisher.Mono;

/**
 * Executes one {@link com.gantang.tianshu.api.workflow.NodeKind} of node.
 * Implementations are stateless and non-blocking; per-run state travels in
 * {@link NodeRuntime}.
 */
@FunctionalInterface
interface NodeHandler {

    Mono<NodeOutcome> execute(NodeSpec spec, GraphState state, NodeRuntime runtime);
}
