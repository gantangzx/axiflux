package com.gantang.tianshu.impl.workflow;

import com.gantang.tianshu.api.workflow.GraphState;
import com.gantang.tianshu.api.workflow.NodeSpec;
import reactor.core.publisher.Mono;

/**
 * Lets the parallel node execute other node specs within the same run. Implemented by
 * the runner so node handlers never own the handler registry.
 */
@FunctionalInterface
interface BranchExecutor {

    /** Execute an already-resolved branch node spec starting from {@code state}. */
    Mono<NodeOutcome> runSpec(NodeSpec spec, GraphState state, NodeRuntime runtime);
}
