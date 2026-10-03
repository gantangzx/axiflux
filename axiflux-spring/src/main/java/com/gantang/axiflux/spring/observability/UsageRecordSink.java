package com.gantang.axiflux.spring.observability;

/**
 * Open-core SPI for asynchronous per-turn usage persistence.
 *
 * <p>Contract shipped in the open-source framework; the implementation that
 * writes {@code usage_record} rows lives in the closed-source
 * {@code axiflux-commercial} module. {@link CostAccountingHook} obtains it
 * through an {@code ObjectProvider}; absent the commercial jar no usage rows are
 * persisted (observability never blocks a turn).
 */
public interface UsageRecordSink {

    /**
     * Enqueue one usage record for best-effort async persistence.
     *
     * @param orgId         organization id (nullable)
     * @param userId        calling user
     * @param sessionId     session the turn belongs to
     * @param agentId       agent that ran the turn (nullable)
     * @param provider      LLM provider name (nullable)
     * @param model         model identifier (nullable)
     * @param inputTokens   real input tokens
     * @param outputTokens  real output tokens
     * @param cachedTokens  cached input tokens
     * @param modelCalls    number of LLM calls in this turn
     */
    void write(String orgId, String userId, String sessionId, String agentId,
               String provider, String model,
               int inputTokens, int outputTokens, int cachedTokens, int modelCalls);
}
