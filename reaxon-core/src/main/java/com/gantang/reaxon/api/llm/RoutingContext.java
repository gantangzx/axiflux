package com.gantang.reaxon.api.llm;

import com.gantang.reaxon.api.session.Message;

import java.util.List;

/**
 * Inputs a {@link RoutingStrategy} may use to pick an {@link LlmClient} for one turn.
 *
 * <p>This record lives in the API layer on purpose: it used to be nested inside
 * {@code impl.agent.ReactiveAgent}, which forced {@link ModelRouter} — an API-layer
 * SPI — to import an implementation class. Third parties could therefore not
 * implement routing without depending on the default agent. Routing inputs are
 * now a first-class API type with no dependency on any agent implementation.
 *
 * <p>BYOK (bring-your-own-key): when {@code byokApiKey} is non-null the router
 * resolves a per-call credential for the routed provider instead of the
 * statically configured key (see {@link ByokKeyResolver}). The key is a
 * turn-scoped in-memory value: it must never be persisted, logged, or copied
 * into session metadata. {@link #toString()} therefore masks it.
 *
 * @param query        the user's current query text (may be {@code null})
 * @param messages     the assembled prompt messages for this turn (never {@code null})
 * @param forcedModel  explicit provider or model override requested by the caller
 *                     (UI model selector / API {@code forcedModel}); {@code null} for auto
 * @param callerId     stable identity of the caller for this turn (authenticated
 *                     user id); {@code null} for anonymous/system turns
 * @param orgId        tenant/org the caller belongs to; {@code null} when the
 *                     deployment has no org dimension
 * @param byokApiKey   caller-supplied provider API key for this turn (plaintext,
 *                     in-memory only); {@code null} means "no BYOK — use the
 *                     statically configured key" (the pre-BYOK behaviour)
 * @param planTier     commercial plan tier of the caller ("free"/"pro"/"team");
 *                     {@code null} when the deployment has no billing dimension.
 *                     Lets a routing strategy refuse premium models on a plan
 *                     that does not cover {@code advanced_models}.
 */
public record RoutingContext(
    String query,
    List<Message> messages,
    String forcedModel,
    String callerId,
    String orgId,
    String byokApiKey,
    String planTier
) {

    public RoutingContext {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /** Back-compatible 6-arg form: routing inputs without a plan tier. */
    public RoutingContext(
        String query,
        List<Message> messages,
        String forcedModel,
        String callerId,
        String orgId,
        String byokApiKey
    ) {
        this(query, messages, forcedModel, callerId, orgId, byokApiKey, null);
    }

    /** Back-compatible 3-arg form: routing inputs without caller identity or BYOK key. */
    public RoutingContext(String query, List<Message> messages, String forcedModel) {
        this(query, messages, forcedModel, null, null, null, null);
    }

    /** Convenience: routing inputs for a bare query with no history and no override. */
    public static RoutingContext of(String query) {
        return new RoutingContext(query, List.of(), null);
    }

    /** True when the caller pinned a specific provider/model for this turn. */
    public boolean hasForcedModel() {
        return forcedModel != null && !forcedModel.isBlank();
    }

    /** True when this turn carries an explicit BYOK key. */
    public boolean hasByokKey() {
        return byokApiKey != null && !byokApiKey.isBlank();
    }

    /** Return this context with a plan tier attached, preserving every other field. */
    public RoutingContext withPlanTier(String tier) {
        return new RoutingContext(query, messages, forcedModel, callerId, orgId, byokApiKey, tier);
    }

    /**
     * Masked rendering: the BYOK key is a secret and must never appear in logs.
     * Records print every component by default, so this override is load-bearing.
     */
    @Override
    public String toString() {
        return "RoutingContext[query=" + query
            + ", messages=" + (messages != null ? messages.size() : 0) + " item(s)"
            + ", forcedModel=" + forcedModel
            + ", callerId=" + callerId
            + ", orgId=" + orgId
            + ", byokApiKey=" + (hasByokKey() ? "***" : null)
            + "]";
    }
}
