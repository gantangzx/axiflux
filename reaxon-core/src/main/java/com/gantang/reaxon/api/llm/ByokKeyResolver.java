package com.gantang.reaxon.api.llm;

import java.util.Optional;

/**
 * Resolves the API key a single LLM call should use, per routed provider and turn.
 *
 * <p>BYOK (bring-your-own-key) entry point: a turn may carry a caller-supplied
 * provider key ({@link RoutingContext#byokApiKey()}), letting different
 * users/tenants pay with their own credentials. When the resolver returns
 * {@link Optional#empty()} the statically configured key is used — the exact
 * pre-BYOK behaviour.
 *
 * <p>The resolved key is a turn-scoped secret: implementations must never
 * persist it, log it, or copy it into session metadata.
 *
 * <p>Implementations must be thread-safe; the router calls this on the turn
 * hot path.
 */
@FunctionalInterface
public interface ByokKeyResolver {

    /**
     * @param provider the routed provider name (e.g. "openai", "anthropic")
     * @param ctx      the turn's routing context (never {@code null})
     * @return the key to use for this call, or empty to fall back to the
     *         provider's statically configured key
     */
    Optional<String> resolveApiKey(String provider, RoutingContext ctx);

    /**
     * The default resolver: only an explicitly turn-carried key
     * ({@link RoutingContext#byokApiKey()}) wins; everything else falls back to
     * static configuration. This is the extension point for deployments that
     * store per-user/per-org keys — wrap or replace this resolver to consult a
     * secrets store when the turn carries no explicit key, e.g.
     * {@code (provider, ctx) -> explicit(provider, ctx).or(() -> vault.lookup(provider, ctx.callerId()))}.
     *
     * <p>Note: the explicit key is returned for every provider it is resolved
     * against — a caller who pins one provider's key while routing falls back
     * to another provider will fail authentication there, which the recovery
     * chain already treats as a per-provider failure. Multi-provider
     * deployments should install a provider-aware resolver.
     */
    static ByokKeyResolver explicit() {
        return (provider, ctx) -> {
            if (ctx != null && ctx.hasByokKey()) {
                return Optional.of(ctx.byokApiKey());
            }
            return Optional.empty();
        };
    }
}
