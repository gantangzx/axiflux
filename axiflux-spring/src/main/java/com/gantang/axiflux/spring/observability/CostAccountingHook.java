package com.gantang.axiflux.spring.observability;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.agent.AgentHook;
import com.gantang.reaxon.api.agent.AgentResponse;
import com.gantang.reaxon.api.llm.ModelCost;
import com.gantang.reaxon.api.session.Session;
import com.gantang.reaxon.api.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Accumulates per-session token usage and estimated cost into session metadata
 * ({@code usage} key) at every terminal turn. Fires for chat, scheduled and
 * sub-agent turns alike because it sits on the agent's hook chain.
 *
 * <p>Cost is computed from the configured {@code axiflux.llm.costs.*} price
 * table, keyed by the model name returned in response metadata. Turns served by
 * unpriced models still accumulate tokens, just no cost. Nothing here may fail
 * a turn — all errors are swallowed (AgentHook contract).
 */
public class CostAccountingHook implements AgentHook {

    private static final Logger log = LoggerFactory.getLogger(CostAccountingHook.class);

    /** Metadata key holding the session-usage accumulator. */
    public static final String META_USAGE = "usage";

    private final SessionManager sessionManager;
    private final Supplier<Map<String, ModelCost>> priceTable;
    private final ObjectProvider<UsageRecordSink> usageRecordWriter;

    public CostAccountingHook(SessionManager sessionManager,
                              Supplier<Map<String, ModelCost>> priceTable) {
        this(sessionManager, priceTable, null);
    }

    public CostAccountingHook(SessionManager sessionManager,
                              Supplier<Map<String, ModelCost>> priceTable,
                              ObjectProvider<UsageRecordSink> usageRecordWriter) {
        this.sessionManager = sessionManager;
        this.priceTable = priceTable;
        this.usageRecordWriter = usageRecordWriter;
    }

    @Override
    public int order() {
        // After audit/metrics/memory hooks; accounting is non-critical.
        return 120;
    }

    @Override
    public void onTurnEnd(AgentContext context, AgentResponse response) {
        try {
            if (response == null || response.metadata() == null) return;
            Map<String, Object> meta = response.metadata();
            int input = asInt(meta.get("inputTokens"));
            int output = asInt(meta.get("outputTokens"));
            if (input <= 0 && output <= 0) return;
            int cached = asInt(meta.get("cachedInputTokens"));
            String model = meta.get("model") instanceof String s ? s : null;
            String provider = meta.get("provider") instanceof String p ? p : null;

            // Cost table is keyed by registered provider name; fall back to model
            // name for setups that price concrete model ids directly.
            Map<String, ModelCost> prices = priceTable.get();
            ModelCost mc = null;
            if (prices != null) {
                if (provider != null) mc = prices.get(provider);
                if (mc == null && model != null) mc = prices.get(model);
            }

            double turnCost = 0.0;
            boolean priced = false;
            if (mc != null) {
                turnCost = mc.estimate(input, cached, output);
                priced = true;
            }

            Optional<Session> found = sessionManager.get(context.sessionId());
            if (found.isEmpty()) return;
            Session session = found.get();

            Map<String, Object> usage =
                usageSnapshot(session.metadata().get(META_USAGE));
            long turns = asLong(usage.get("turns")) + 1;
            long in = asLong(usage.get("inputTokens")) + input;
            long out = asLong(usage.get("outputTokens")) + output;
            long cache = asLong(usage.get("cachedInputTokens")) + cached;
            double cost = round4(asDouble(usage.get("estimatedCost")) + (priced ? turnCost : 0.0));
            double cacheRatio = in > 0 ? round4((double) cache / in) : 0.0;

            usage.put("turns", turns);
            usage.put("inputTokens", in);
            usage.put("outputTokens", out);
            usage.put("cachedInputTokens", cache);
            usage.put("totalTokens", in + out);
            usage.put("cacheHitRatio", cacheRatio);
            if (priced) usage.put("costCurrency", "configured");
            usage.put("estimatedCost", cost);
            session.updateMetadata(META_USAGE, usage);

            // Also persist a structured usage_record row for the usage dashboard.
            // Never let a writer failure break the turn or the metadata accumulator.
            writeUsageRecord(context, meta, input, output, cached, model, provider);
        } catch (Exception e) {
            log.debug("cost accounting skipped for session {}: {}",
                context.sessionId(), e.toString());
        }
    }

    /**
     * Best-effort async write of one usage_record row. All errors are swallowed
     * so the hook contract (never fail a turn) is preserved.
     */
    private void writeUsageRecord(AgentContext context, Map<String, Object> meta,
                                  int input, int output, int cached,
                                  String model, String provider) {
        try {
            if (usageRecordWriter == null) return;
            UsageRecordSink writer = usageRecordWriter.getIfAvailable();
            if (writer == null) return;

            String userId = context.userId();
            int modelCalls = asInt(meta.get("modelCalls"));

            // P0-3: org attribution comes from the caller metadata injected by
            // CallerGuard (null when the caller belongs to no organization).
            String orgId = null;
            if (context.metadata() != null) {
                Object raw = context.metadata().get(com.gantang.reaxon.api.auth.CallerIdentity.META_ORG_ID);
                if (raw instanceof String s && !s.isBlank()) orgId = s;
            }
            writer.write(orgId, userId, context.sessionId(), null,
                provider, model, input, output, cached, modelCalls);
        } catch (Exception ex) {
            log.debug("usage_record write skipped for session {}: {}",
                context.sessionId(), ex.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> usageSnapshot(Object raw) {
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, v) -> { if (k != null) copy.put(k.toString(), v); });
            return copy;
        }
        return new LinkedHashMap<>();
    }

    private static int asInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static double asDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
