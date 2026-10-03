package com.gantang.reaxon.api.agent;

/**
 * An {@link Agent} whose iteration cap can be re-tuned at runtime
 * (hot-reload / admin console) without recreating the agent bean.
 *
 * <p>P1-4 interface contract: the Spring layer previously reached into
 * {@code ReactiveAgent} with {@code instanceof} checks to apply runtime
 * overrides. Declaring the tuning surface here lets consumers depend on
 * the API contract only; agents that do not support runtime tuning simply
 * do not implement this interface, so the capability check stays explicit
 * and fail-loud ({@code instanceof RuntimeTunableAgent}).
 */
public interface RuntimeTunableAgent extends Agent {

    /**
     * Re-tune the maximum tool-loop iterations per turn.
     *
     * <p>Implementations clamp to their own sane bounds (the reference
     * implementation clamps to {@code [1, 100]}).
     *
     * @return {@code this} for fluent chaining (implementations with a
     *         concrete self-type return it covariantly)
     */
    Agent withMaxIterations(int maxIterations);

    /**
     * Effective iteration cap right now — reflecting both the configured
     * value and any live-settings override.
     */
    int getMaxIterations();
}
