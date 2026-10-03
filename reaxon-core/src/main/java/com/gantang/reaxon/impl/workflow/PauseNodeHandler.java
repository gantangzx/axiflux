package com.gantang.reaxon.impl.workflow;

import com.gantang.reaxon.api.workflow.Checkpoint;
import com.gantang.reaxon.api.workflow.GraphState;
import com.gantang.reaxon.api.workflow.NodeSpec;
import reactor.core.publisher.Mono;

/**
 * {@link com.gantang.reaxon.api.workflow.NodeKind#PAUSE}: persists a checkpoint and
 * suspends the run until an external caller resumes it with a payload. The awaited
 * value is merged into the variable named by {@link NodeSpec#waitFor()} (default
 * {@code resume}).
 */
final class PauseNodeHandler implements NodeHandler {

    @Override
    public Mono<NodeOutcome> execute(NodeSpec spec, GraphState state, NodeRuntime runtime) {
        String reason = firstNonBlank(GraphSupport.render(spec.waitFor(), state), "resume");
        Checkpoint checkpoint = Checkpoint.of(
            runtime.runId(), runtime.graphName(), spec.id(), state, reason,
            runtime.baseContext());
        return runtime.checkpointStore().save(checkpoint)
            .thenReturn(NodeOutcome.pause(state, reason))
            .onErrorResume(e -> Mono.just(NodeOutcome.fail(state,
                "pause node " + spec.id() + " could not persist checkpoint: " + e.getMessage())));
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
