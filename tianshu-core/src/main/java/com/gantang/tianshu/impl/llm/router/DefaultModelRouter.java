package com.gantang.tianshu.impl.llm.router;

import com.gantang.tianshu.api.llm.ByokKeyResolver;
import com.gantang.tianshu.api.llm.LlmClient;
import com.gantang.tianshu.api.llm.ModelRouter;
import com.gantang.tianshu.api.llm.MutableModelRouter;
import com.gantang.tianshu.api.llm.RoutingContext;
import com.gantang.tianshu.api.llm.RoutingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link ModelRouter}: owns the provider registry and delegates the per-turn
 * choice to a pluggable {@link RoutingStrategy}.
 *
 * <p>The strategy is an API-level SPI, so deployments can supply their own policy
 * (latency-aware, tenant-pinned, round-robin, …) without subclassing this router.
 * The default is {@link CapabilityStrategy} because it needs no pricing configuration;
 * see {@link CostOptimizedRouter} for the price-driven variant.
 */
public class DefaultModelRouter implements MutableModelRouter {

    private static final Logger log = LoggerFactory.getLogger(DefaultModelRouter.class);

    private final Map<String, LlmClient> clients = new ConcurrentHashMap<>();
    /** Provider names in registration order (P2-8: drives stable routeChain order). */
    private final java.util.List<String> registrationOrder =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    /**
     * The default {@link RoutingStrategy} receives this map; when a deployment
     * replaces the strategy with one that consults {@link RoutingContext} only
     * (e.g. CostOptimizedStrategy), the map is still the single source of truth.
     * {@link #prefer(Map, String...)} below consults the router's
     * {@link #defaultProvider} as the final fallback instead of an arbitrary
     * map value (P2-9).
     */
    private volatile RoutingStrategy strategy = new CapabilityStrategy();
    private volatile String defaultProvider = "openai";
    /**
     * BYOK key source for the per-turn credential override. The default only
     * honours an explicitly turn-carried key ({@link RoutingContext#byokApiKey()});
     * deployments with a per-user/per-org secrets store install their own.
     */
    private volatile ByokKeyResolver byokKeyResolver = ByokKeyResolver.explicit();

    /**
     * Provider/model tokens that count as premium for the {@code advanced_models}
     * commercial gate. Matched against the caller's {@code forcedModel} token.
     * Empty by default (no premium classification): commercial deployments populate
     * it via configuration. Concurrent because it may be set after registration.
     */
    private final java.util.Set<String> premiumModels =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    public void setStrategy(RoutingStrategy strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("Routing strategy must not be null");
        }
        this.strategy = strategy;
        if (strategy instanceof CapabilityStrategy cs) {
            cs.setDefaultProviderName(defaultProvider);
        }
        log.info("Routing strategy set to '{}'", strategy.name());
    }

    /** Currently active routing strategy. */
    public RoutingStrategy getStrategy() {
        return strategy;
    }

    public void setDefaultProvider(String provider) {
        this.defaultProvider = provider;
        // Propagate to the active strategy so its final fallback is the
        // deterministic default provider rather than an arbitrary client (P2-9).
        RoutingStrategy s = this.strategy;
        if (s instanceof CapabilityStrategy cs && provider != null) {
            cs.setDefaultProviderName(provider);
        }
    }

    /** Currently active default provider name (the fallback used by routing). */
    public String getDefaultProvider() {
        return defaultProvider;
    }

    /**
     * Install the {@link ByokKeyResolver} consulted after strategy selection.
     * {@code null} resets to the default explicit resolver. When the resolver
     * yields no key the statically configured client is used untouched — the
     * pre-BYOK behaviour.
     */
    public void setByokKeyResolver(ByokKeyResolver resolver) {
        this.byokKeyResolver = resolver != null ? resolver : ByokKeyResolver.explicit();
    }

    /** Currently active BYOK key resolver. */
    public ByokKeyResolver getByokKeyResolver() {
        return byokKeyResolver;
    }

    /**
     * Replace the premium-model token set used by the {@code advanced_models} gate.
     * Tokens are matched case-insensitively against {@code forcedModel}; they may be
     * either exact model ids or provider/model fragments (substring match).
     */
    public void setPremiumModels(java.util.Collection<String> models) {
        premiumModels.clear();
        if (models != null) {
            models.stream()
                .filter(m -> m != null && !m.isBlank())
                .map(m -> m.trim().toLowerCase())
                .forEach(premiumModels::add);
        }
    }

    /** True when the requested forced-model token is classified as premium. */
    private boolean isPremium(String forcedModel) {
        if (forcedModel == null || forcedModel.isBlank() || premiumModels.isEmpty()) {
            return false;
        }
        String token = forcedModel.trim().toLowerCase();
        for (String p : premiumModels) {
            if (token.equals(p) || token.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Enforce the {@code advanced_models} commercial gate for an explicitly forced
     * premium model. No-op unless a plan tier is known and it is a non-premium tier.
     * Automatic routing never reaches this: it only applies to a deliberate choice.
     */
    private void enforceAdvancedModelGate(RoutingContext ctx) {
        if (ctx == null || !ctx.hasForcedModel() || ctx.planTier() == null) {
            return;
        }
        String tier = ctx.planTier().trim().toLowerCase();
        if ("free".equals(tier) && isPremium(ctx.forcedModel())) {
            throw new AdvancedModelGateException(ctx.forcedModel(), tier);
        }
    }

    /**
     * Apply the per-turn BYOK override to a selected client: when the resolver
     * yields a key for the client's provider, return a derived client bound to
     * that key; otherwise return the registered (static-key) client untouched.
     * The key itself is never logged here — it is a turn-scoped secret.
     */
    private LlmClient applyByok(LlmClient client, RoutingContext ctx) {
        if (client == null || ctx == null) return client;
        java.util.Optional<String> key;
        try {
            key = byokKeyResolver.resolveApiKey(client.provider(), ctx);
        } catch (Exception e) {
            // A broken resolver must not break routing: fall back to the static key.
            log.warn("ByokKeyResolver {} threw for provider {}; using static key: {}",
                byokKeyResolver.getClass().getName(), client.provider(), e.toString());
            return client;
        }
        if (key.isEmpty() || key.get().isBlank()) return client;
        LlmClient derived = client.withApiKey(key.get());
        if (derived != client) {
            log.debug("BYOK key applied for provider {} (caller={})",
                client.provider(), ctx.callerId());
        }
        return derived;
    }

    @Override
    public LlmClient route(RoutingContext ctx) {
        enforceAdvancedModelGate(ctx);
        LlmClient selected = strategy.select(ctx, clients);
        if (selected == null) {
            // A strategy returning null means "no opinion", not an error.
            log.debug("Strategy '{}' had no opinion; using default provider {}",
                strategy.name(), defaultProvider);
            selected = clients.get(defaultProvider);
        }
        if (selected == null) {
            throw new IllegalStateException("No LLM client available. Registered: " + clients.keySet());
        }
        return applyByok(selected, ctx);
    }

    @Override
    public void register(LlmClient client) {
        // Track first-registration order so routeChain's fallback sequence is
        // deterministic (P2-8); re-registering a provider keeps its original slot.
        if (!clients.containsKey(client.provider())) {
            registrationOrder.add(client.provider());
        }
        clients.put(client.provider(), client);
        log.info("Registered LLM provider: {} (model={})", client.provider(), client.primaryModel());
    }

    /** Clients in first-registration order (P2-8). */
    private java.util.List<LlmClient> snapshotClientsInRegistrationOrder() {
        java.util.List<LlmClient> out = new java.util.ArrayList<>();
        synchronized (registrationOrder) {
            for (String p : registrationOrder) {
                LlmClient c = clients.get(p);
                if (c != null) out.add(c);
            }
        }
        return out;
    }

    @Override
    public List<String> listProviders() {
        return List.copyOf(clients.keySet());
    }

    @Override
    public Optional<LlmClient> get(String provider) {
        return Optional.ofNullable(clients.get(provider));
    }

    /**
     * Fallback chain: the routed primary first, then every other registered
     * client (so a transient failure or context overflow on one provider retries
     * on the next). Order among the rest follows registration order (P2-8).
     */
    @Override
    public List<LlmClient> routeChain(RoutingContext ctx) {
        LlmClient primary = route(ctx);  // throws when no client is registered; BYOK already applied
        List<LlmClient> chain = new java.util.ArrayList<>();
        chain.add(primary);
        for (LlmClient c : snapshotClientsInRegistrationOrder()) {
            // Skip the primary's own registry entry: without BYOK the primary IS
            // the registry client (c != primary catches it); with BYOK the primary
            // is a derived wrapper, so dedupe by provider name instead.
            boolean isPrimary = (c == primary) || c.provider().equals(primary.provider());
            if (!isPrimary && !chain.contains(c)) chain.add(applyByok(c, ctx));
        }
        return List.copyOf(chain);
    }
}
