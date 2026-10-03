package com.gantang.reaxon.api.llm;

import java.util.List;
import java.util.Optional;

/**
 * Routes LLM requests to the appropriate LlmClient based on strategy.
 *
 * <p>Pure API: this interface depends only on other API types, so a deployment can
 * supply its own router (or its own {@link RoutingStrategy}) without pulling in the
 * default agent implementation.
 */
@FunctionalInterface
public interface ModelRouter {

    /** Select an LlmClient for the given routing context */
    LlmClient route(RoutingContext ctx);

    /**
     * Ordered fallback chain for a turn: the primary {@link #route(RoutingContext)}
     * choice first, then alternative providers to try when the primary call fails
     * with a transient/overflow error. The default returns just the primary (no
     * fallback); routers with multiple registered clients SHOULD override this to
     * list the rest. Authentication errors on one provider may still succeed on
     * another (different credentials), so fallbacks apply to every retryable kind.
     */
    default List<LlmClient> routeChain(RoutingContext ctx) {
        LlmClient primary = route(ctx);
        return primary == null ? List.of() : List.of(primary);
    }

    /** Register a client (for programmatic setup; default is no-op) */
    default void register(LlmClient client) {}

    /** List all registered providers (default returns empty) */
    default List<String> listProviders() { return List.of(); }

    /** Get a specific provider (default returns empty) */
    default Optional<LlmClient> get(String provider) { return Optional.empty(); }
}
