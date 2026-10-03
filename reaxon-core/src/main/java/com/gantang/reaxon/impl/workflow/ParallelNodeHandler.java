package com.gantang.reaxon.impl.workflow;

import com.gantang.reaxon.api.workflow.GraphState;
import com.gantang.reaxon.api.workflow.NodeSpec;
import com.gantang.reaxon.api.workflow.StateGraph;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link com.gantang.reaxon.api.workflow.NodeKind#PARALLEL}: runs its branch nodes
 * concurrently (Reactor {@code flatMap}), then merges each branch output into the
 * state under a {@code <parallelNode>.<branchId>} namespaced variable and a namespaced
 * map stored as the node output. Any branch failure fails the whole node; a branch
 * that suspends is illegal inside a parallel node.
 */
final class ParallelNodeHandler implements NodeHandler {

    @Override
    public Mono<NodeOutcome> execute(NodeSpec spec, GraphState state, NodeRuntime runtime) {
        StateGraph graph = runtime.currentGraph();
        return runBranches(spec, state, runtime, graph)
            .collectMap(BranchResult::branchId, BranchResult::output, LinkedHashMap::new)
            .map(merged -> finish(spec, state, merged))
            .onErrorResume(e -> Mono.just(
                NodeOutcome.fail(state, "parallel node " + spec.id() + " failed: " + e.getMessage())));
    }

    private Flux<BranchResult> runBranches(NodeSpec parallelSpec, GraphState state,
                                           NodeRuntime runtime, StateGraph graph) {
        return Flux.fromIterable(parallelSpec.branches())
            .flatMap(branchId -> {
                NodeSpec branchSpec = graph.nodes().get(branchId);
                if (branchSpec == null) {
                    return Mono.<BranchResult>error(
                        new IllegalStateException("parallel branch '" + branchId + "' not found"));
                }
                return runtime.branchExecutor()
                    .runSpec(branchSpec, state, runtime)
                    .flatMap(outcome -> normalize(branchId, outcome));
            });
    }

    private Mono<BranchResult> normalize(String branchId, NodeOutcome outcome) {
        return switch (outcome.action()) {
            case CONTINUE -> {
                Object value = outcome.state().outputs().get(branchId);
                yield Mono.just(new BranchResult(branchId, value));
            }
            case PAUSE -> Mono.error(new IllegalStateException(
                "parallel branch '" + branchId + "' tried to pause; not allowed inside parallel"));
            case FAIL -> Mono.error(new IllegalStateException(
                "parallel branch '" + branchId + "' failed: " + outcome.reason()));
        };
    }

    private NodeOutcome finish(NodeSpec spec, GraphState state, Map<String, Object> merged) {
        GraphState next = state.withNodeOutput(spec.id(), merged);
        next = next.withVariables(prefixKeys(spec.id(), merged));
        return NodeOutcome.next(next, null);
    }

    private Map<String, Object> prefixKeys(String nodeId, Map<String, Object> merged) {
        Map<String, Object> vars = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : merged.entrySet()) {
            vars.put(nodeId + "." + e.getKey(), e.getValue());
        }
        return vars;
    }
}
