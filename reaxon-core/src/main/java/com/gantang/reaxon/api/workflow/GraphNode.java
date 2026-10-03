package com.gantang.reaxon.api.workflow;

import reactor.core.publisher.Mono;

/**
 * A programmatically supplied workflow node (backing a {@link NodeKind#CUSTOM} node).
 *
 * <p>Implementations receive the current {@link GraphState} and return a mono of the
 * next state (typically built with {@link GraphState#withNodeOutput}). They must not
 * block the subscribing thread; bridge blocking work onto
 * {@code Schedulers.boundedElastic()}.
 */
@FunctionalInterface
public interface GraphNode {

    Mono<GraphState> apply(GraphState state, GraphNodeContext context);
}
