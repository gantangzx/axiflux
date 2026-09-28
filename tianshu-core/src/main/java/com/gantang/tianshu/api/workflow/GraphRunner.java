package com.gantang.tianshu.api.workflow;

import com.gantang.tianshu.api.agent.AgentContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Compiles and executes {@link StateGraph}s.
 *
 * <p>A run is started with {@link #startStream} (a fresh run) and a paused run is
 * continued with {@link #resumeStream}. The streaming forms surface
 * {@link GraphEvent}s for observability; callers that only care about the terminal
 * state use {@link #start} / {@link #resume}, which run on subscription.
 */
public interface GraphRunner {

    /**
     * Start a fresh run of {@code graph} using the identity/session in {@code context},
     * optionally seeded with {@code input} (placed in {@link GraphState#INPUT}).
     */
    Flux<GraphEvent> startStream(StateGraph graph, AgentContext context, Object input);

    /** Terminal-result form of {@link #startStream}. */
    Mono<GraphRunResult> start(StateGraph graph, AgentContext context, Object input);

    /**
     * Start a fresh run from an explicit initial {@link GraphState} (rather than one
     * seeded from a single input value). Use this when a graph's conditional edges
     * read variables that must be set before the first node runs.
     */
    default Mono<GraphRunResult> startFrom(StateGraph graph, AgentContext context,
                                           GraphState initialState) {
        return Mono.error(new UnsupportedOperationException(
            "state-based start is provided by the default runner"));
    }

    /** Streaming form of {@link #startFrom}. */
    default Flux<GraphEvent> startFromStream(StateGraph graph, AgentContext context,
                                             GraphState initialState) {
        return Flux.error(new UnsupportedOperationException(
            "state-based startStream is provided by the default runner"));
    }

    /**
     * Resume a previously paused run. {@code payload} (may be {@code null}) is merged
     * into the state variables under the key supplied by the pausing node.
     */
    Flux<GraphEvent> resumeStream(String runId, Object payload);

    /** Terminal-result form of {@link #resumeStream}. */
    Mono<GraphRunResult> resume(String runId, Object payload);
}
