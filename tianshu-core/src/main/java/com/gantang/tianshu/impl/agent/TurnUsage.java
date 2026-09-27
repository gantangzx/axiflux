package com.gantang.tianshu.impl.agent;

import com.gantang.tianshu.api.llm.CompletionResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-turn accumulator for LLM token usage across the (possibly multi-iteration)
 * tool loop. Each model call may report input/output tokens on its terminal
 * chunk; the final DONE {@link com.gantang.tianshu.api.agent.AgentResponse} carries the
 * totals so callers (e.g. the sub-agent service, cost accounting) can attribute
 * spend.
 *
 * <p>Package-private: produced and consumed inside {@code impl.agent}.
 */
final class TurnUsage {

    public static final String META_INPUT_TOKENS = "inputTokens";
    public static final String META_OUTPUT_TOKENS = "outputTokens";
    public static final String META_CACHED_INPUT_TOKENS = "cachedInputTokens";
    public static final String META_TOTAL_TOKENS = "totalTokens";
    public static final String META_MODEL_CALLS = "modelCalls";
    public static final String META_MODEL = "model";
    /** Registered provider/client name of the last call (cost-table key). */
    public static final String META_PROVIDER = "provider";

    private int inputTokens;
    private int outputTokens;
    private int cachedInputTokens;
    private int modelCalls;
    private String model = "";
    private String provider = "";

    /** Add one model call's reported usage. Zero-usage calls (adapters without stats) don't count. */
    void add(CompletionResponse response) {
        if (response == null) return;
        if (response.inputTokens() > 0) inputTokens += response.inputTokens();
        if (response.outputTokens() > 0) outputTokens += response.outputTokens();
        if (response.cachedInputTokens() > 0) cachedInputTokens += response.cachedInputTokens();
        if (response.inputTokens() > 0 || response.outputTokens() > 0) modelCalls++;
        if (response.model() != null && !response.model().isBlank()) model = response.model();
        if (response.provider() != null && !response.provider().isBlank()) provider = response.provider();
    }

    int inputTokens() { return inputTokens; }
    int outputTokens() { return outputTokens; }
    int cachedInputTokens() { return cachedInputTokens; }
    int modelCallsTotal() { return modelCalls; }
    String model() { return model; }
    String provider() { return provider; }

    /** Snapshot for the DONE event metadata; always present (zeros when adapters report nothing). */
    Map<String, Object> metadata() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(META_INPUT_TOKENS, inputTokens);
        m.put(META_OUTPUT_TOKENS, outputTokens);
        m.put(META_CACHED_INPUT_TOKENS, cachedInputTokens);
        m.put(META_TOTAL_TOKENS, inputTokens + outputTokens);
        m.put(META_MODEL_CALLS, modelCalls);
        if (!model.isEmpty()) m.put(META_MODEL, model);
        if (!provider.isEmpty()) m.put(META_PROVIDER, provider);
        return m;
    }

    /** Read usage carried on a response's metadata map (empty when absent). */
    static TurnUsage fromMetadata(Map<String, Object> meta) {
        TurnUsage u = new TurnUsage();
        if (meta == null) return u;
        Object in = meta.get(META_INPUT_TOKENS);
        Object out = meta.get(META_OUTPUT_TOKENS);
        Object cached = meta.get(META_CACHED_INPUT_TOKENS);
        Object calls = meta.get(META_MODEL_CALLS);
        Object model = meta.get(META_MODEL);
        Object provider = meta.get(META_PROVIDER);
        if (in instanceof Number n) u.inputTokens = n.intValue();
        if (out instanceof Number n) u.outputTokens = n.intValue();
        if (cached instanceof Number n) u.cachedInputTokens = n.intValue();
        if (calls instanceof Number n) u.modelCalls = n.intValue();
        if (model instanceof String s) u.model = s;
        if (provider instanceof String s) u.provider = s;
        return u;
    }
}
