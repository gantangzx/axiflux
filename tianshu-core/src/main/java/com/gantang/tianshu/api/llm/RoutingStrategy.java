package com.gantang.tianshu.api.llm;

import java.util.Map;

/**
 * Pluggable strategy deciding <em>which</em> registered {@link LlmClient} serves a turn.
 *
 * <p>A {@link ModelRouter} owns the provider registry and delegates the choice to a
 * strategy, so deployments can swap routing policy (cost, latency, capability,
 * round-robin, …) without touching the registry or the agent. Implementations must be
 * thread-safe: one instance serves all concurrent turns.
 *
 * <p>Returning {@code null} is allowed and means "no opinion" — the router then falls
 * back to its configured default provider.
 */
@FunctionalInterface
public interface RoutingStrategy {

    /**
     * Pick a client for this turn.
     *
     * @param ctx     routing inputs for the turn
     * @param clients registered providers, keyed by {@link LlmClient#provider()};
     *                never {@code null}, but may be empty
     * @return the chosen client, or {@code null} to defer to the router's default
     */
    LlmClient select(RoutingContext ctx, Map<String, LlmClient> clients);

    /** Strategy name, used for configuration lookup and logging. */
    default String name() {
        return getClass().getSimpleName();
    }
}
