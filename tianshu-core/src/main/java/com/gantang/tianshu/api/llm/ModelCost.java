package com.gantang.tianshu.api.llm;

/**
 * Per-model token pricing used by cost-aware routing and per-session cost
 * accounting.
 *
 * <p>Prices are <em>configuration data</em>, never hardcoded defaults: vendors change
 * them, and a stale built-in table silently routes to the wrong model. Deployments
 * declare them (see {@code tianshu.llm.costs.*}) and a model without a declared
 * price is treated as "cost unknown" rather than "free".
 *
 * @param inputPer1k       price per 1,000 prompt tokens, in the deployment's own currency unit
 * @param outputPer1k      price per 1,000 completion tokens, same unit
 * @param cachedInputPer1k optional discounted price per 1,000 prompt tokens served
 *                         from the provider's prompt/prefix cache; {@code null} means
 *                         cached tokens are billed at {@link #inputPer1k} (conservative)
 */
public record ModelCost(double inputPer1k, double outputPer1k, Double cachedInputPer1k) {

    /** Back-compact constructor for deployments without cache pricing. */
    public ModelCost(double inputPer1k, double outputPer1k) {
        this(inputPer1k, outputPer1k, null);
    }

    public ModelCost {
        if (inputPer1k < 0 || outputPer1k < 0
            || (cachedInputPer1k != null && cachedInputPer1k < 0)) {
            throw new IllegalArgumentException(
                "Token prices must be non-negative, got input=" + inputPer1k
                    + " output=" + outputPer1k + " cached=" + cachedInputPer1k);
        }
    }

    /**
     * Estimated cost of one turn (no cache discount).
     *
     * @param promptTokens     estimated prompt size
     * @param completionTokens estimated completion size
     */
    public double estimate(long promptTokens, long completionTokens) {
        return (promptTokens / 1000.0) * inputPer1k + (completionTokens / 1000.0) * outputPer1k;
    }

    /**
     * Estimated cost with prompt-cache accounting: {@code cachedPromptTokens}
     * are priced at {@link #cachedInputPer1k} when declared, otherwise at the
     * full input price; the remaining {@code promptTokens - cached} are full price.
     */
    public double estimate(long promptTokens, long cachedPromptTokens, long completionTokens) {
        long cached = Math.max(0, Math.min(cachedPromptTokens, promptTokens));
        long uncached = promptTokens - cached;
        double cachedRate = cachedInputPer1k != null ? cachedInputPer1k : inputPer1k;
        return (uncached / 1000.0) * inputPer1k
            + (cached / 1000.0) * cachedRate
            + (completionTokens / 1000.0) * outputPer1k;
    }
}
