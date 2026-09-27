package com.gantang.tianshu.impl.llm.router;

import com.gantang.tianshu.api.llm.LlmClient;
import com.gantang.tianshu.api.llm.RoutingContext;
import com.gantang.tianshu.api.session.Message;

import java.util.Map;

/**
 * Capability-oriented routing: infer from the query what kind of model the turn needs
 * and prefer providers known to be good at it.
 *
 * <p>This is the historical default behaviour and needs no pricing configuration, which
 * makes it the safe default: it never silently depends on a price table a deployment has
 * not filled in. Preference lists are provider <em>names</em>, so a deployment that does
 * not register e.g. {@code deepseek} simply falls through to the next candidate.
 */
public final class CapabilityStrategy extends AbstractRoutingStrategy {

    /** Query markers suggesting the turn benefits from a stronger reasoning model. */
    private static final String[] REASONING_MARKERS = {"分析", "reasoning", "think", "思考", "推理"};

    /** Query markers suggesting a code-specialised model. */
    private static final String[] CODE_MARKERS = {"code", "代码", "implement", "函数", "class ", "def "};

    /** Beyond this many messages a turn is treated as a substantive conversation. */
    private static final int SHORT_CONVERSATION_MESSAGES = 2;

    /** Fallback used when no preferred provider is registered (P2-9). */
    private volatile String defaultProviderName = "";

    /** Set the deterministic fallback provider name (P2-9). */
    public void setDefaultProviderName(String name) {
        this.defaultProviderName = name != null ? name : "";
    }

    @Override
    public String name() {
        return "capability";
    }

    @Override
    public LlmClient select(RoutingContext ctx, Map<String, LlmClient> clients) {
        LlmClient forced = resolveForced(ctx, clients);
        if (forced != null) {
            return forced;
        }

        String query = ctx.query() != null ? ctx.query().toLowerCase() : "";

        if (containsAny(query, REASONING_MARKERS)) {
            return prefer(clients, "anthropic", "claude");
        }
        if (containsAny(query, CODE_MARKERS)) {
            return prefer(clients, "deepseek", "anthropic");
        }
        if (ctx.messages().size() <= SHORT_CONVERSATION_MESSAGES) {
            return prefer(clients, "deepseek", "openai-mini", "openai", "anthropic");
        }
        return prefer(clients, "openai");
    }

    private LlmClient prefer(Map<String, LlmClient> clients, String... preferred) {
        for (String p : preferred) {
            LlmClient c = clients.get(p);
            if (c != null) {
                return c;
            }
        }
        // P2-9: fall back to the router's default provider for determinism
        // (prefix-cache warmth, stable style) rather than an arbitrary map value.
        if (!defaultProviderName.isBlank()) {
            LlmClient d = clients.get(defaultProviderName);
            if (d != null) return d;
        }
        return anyClient(clients);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /** Exposed for {@link CostOptimizedStrategy}, which escalates complex turns by capability. */
    boolean needsStrongModel(RoutingContext ctx) {
        String query = ctx.query() != null ? ctx.query().toLowerCase() : "";
        if (containsAny(query, REASONING_MARKERS) || containsAny(query, CODE_MARKERS)) {
            return true;
        }
        // A long tool-using conversation has outgrown the cheapest tier.
        long substantive = ctx.messages().stream()
            .filter(m -> m != null && m.role() != Message.Role.SYSTEM)
            .count();
        return substantive > 12;
    }
}
