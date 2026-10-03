package com.gantang.reaxon.api.agent;

import reactor.core.publisher.Mono;

/**
 * Capability to spawn an isolated sub-agent run (delegated / background work).
 *
 * <p>Implementations run a task in a fresh, independent session with its own
 * short-term memory, so a sub-task cannot pollute the parent conversation.
 * The parent receives only the sub-agent's final text answer.
 *
 * <p>This abstraction lives in core so the {@code spawn_task} built-in tool can
 * invoke it without depending on Spring wiring. The runtime (Spring) provides
 * the implementation; the tool resolves it lazily to break the
 * tool ↔ agent dependency cycle.
 */
@FunctionalInterface
public interface SubAgentRunner {

    /** Result of a sub-agent run: the child session id plus its final answer. */
    record SpawnResult(String sessionId, String answer) {}

    /**
     * Run a task in an isolated sub-agent session.
     *
     * <p>The child runs strictly with the {@link DelegationRequest#caller()}'s
     * granted scopes — implementations must not widen them.
     *
     * @param request the delegation, including the caller identity the child inherits
     * @return the child session id and the sub-agent's final text answer
     */
    Mono<SpawnResult> spawn(DelegationRequest request);
}
