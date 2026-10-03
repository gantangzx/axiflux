package com.gantang.reaxon.impl.llm.router;

import com.gantang.reaxon.api.llm.LlmClient;
import com.gantang.reaxon.api.llm.RoutingContext;
import com.gantang.reaxon.api.llm.RoutingStrategy;
import com.gantang.reaxon.api.session.Message;

import java.util.Map;

/**
 * Shared helpers for the built-in {@link RoutingStrategy} implementations.
 */
abstract class AbstractRoutingStrategy implements RoutingStrategy {

    /**
     * Rough characters-per-token ratio. Deliberately coarse: routing only needs the
     * <em>relative</em> size of a request to compare provider prices, not an exact
     * token count, and an exact count would require a per-vendor tokenizer.
     */
    private static final int CHARS_PER_TOKEN = 4;

    /**
     * Honour an explicit provider/model override from the caller (UI model selector
     * or API {@code forcedModel}). Matches a provider name first, then a model name.
     *
     * @return the pinned client, or {@code null} when nothing was pinned or it is unknown
     */
    protected LlmClient resolveForced(RoutingContext ctx, Map<String, LlmClient> clients) {
        if (!ctx.hasForcedModel()) {
            return null;
        }
        String key = ctx.forcedModel().trim();
        LlmClient exact = clients.get(key);
        if (exact != null) {
            return exact;
        }
        for (LlmClient c : clients.values()) {
            if (key.equalsIgnoreCase(c.primaryModel())) {
                return c;
            }
        }
        return null;
    }

    /** Estimated prompt tokens for this turn, from assembled messages plus the query. */
    protected long estimatePromptTokens(RoutingContext ctx) {
        long chars = 0;
        for (Message m : ctx.messages()) {
            if (m != null && m.content() != null) {
                chars += m.content().length();
            }
        }
        if (ctx.messages().isEmpty() && ctx.query() != null) {
            chars += ctx.query().length();
        }
        return Math.max(1, chars / CHARS_PER_TOKEN);
    }

    /** First registered client, or {@code null} when none are registered. */
    protected LlmClient anyClient(Map<String, LlmClient> clients) {
        return clients.values().stream().findFirst().orElse(null);
    }
}
