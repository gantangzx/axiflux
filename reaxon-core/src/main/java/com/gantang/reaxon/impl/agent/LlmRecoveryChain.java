package com.gantang.reaxon.impl.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.llm.CompletionRequest;
import com.gantang.reaxon.api.llm.CompletionResponse;
import com.gantang.reaxon.api.llm.LlmClient;
import com.gantang.reaxon.api.llm.ModelRouter;
import com.gantang.reaxon.api.llm.RoutingContext;
import com.gantang.reaxon.api.llm.StreamChunk;
import com.gantang.reaxon.api.memory.ContextAssembler;
import com.gantang.reaxon.api.memory.TokenCounter;
import com.gantang.reaxon.api.session.Message;
import com.gantang.reaxon.api.session.Session;
import com.gantang.reaxon.impl.memory.ContextBudget;
import com.gantang.reaxon.impl.memory.HeuristicTokenCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Obtains one successful {@link CompletionResponse} for a tool-loop iteration,
 * owning everything between "the agent needs the model's next move" and the
 * response arriving:
 *
 * <ul>
 *   <li><b>Routing</b> — {@link ModelRouter#routeChain} picks the primary
 *       provider plus ordered fallbacks;</li>
 *   <li><b>Proactive compaction</b> — aged history is summarized before it can
 *       force a context-overflow error;</li>
 *   <li><b>Context assembly</b> — budgeted message list for the routed model's
 *       window, with a history-only fallback when retrieval/assembly fails;</li>
 *   <li><b>Recovery</b> — context overflow compacts and retries the same
 *       provider once; auth/unavailable/timeout errors fall through to the
 *       next provider. A wedged call is abandoned after the watchdog timeout
 *       and classified UNAVAILABLE so the fallback chain takes over.</li>
 * </ul>
 *
 * <p>Extracted from {@link ReactiveAgent}; the agent keeps the tool loop and
 * delegates the model-call half here. Bounded by chain length plus one
 * compaction pass.
 */
final class LlmRecoveryChain {

    private static final Logger log = LoggerFactory.getLogger(LlmRecoveryChain.class);

    private static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 128_000;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 4_096;
    private static final int DEFAULT_RESERVE_TOKENS = 2_048;

    /** Hook for the agent's lifecycle hooks; observational only (never fails the turn). */
    @FunctionalInterface
    interface ModelCallHook {
        void beforeModelCall(AgentContext context, List<Message> messages, List<JsonNode> toolDefs);
    }

    private final ModelRouter modelRouter;
    private final ContextAssembler contextAssembler;
    private final CompactionService compactionService;
    private final ObjectMapper om;
    private final String agentId;
    private final ModelCallHook hook;

    private volatile TokenCounter tokenCounter = HeuristicTokenCounter.INSTANCE;
    private volatile int contextWindowTokens = DEFAULT_CONTEXT_WINDOW_TOKENS;
    private volatile int maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS;
    private volatile int reserveTokens = DEFAULT_RESERVE_TOKENS;

    /**
     * Sampling temperature for main-conversation model calls (chat, tool loop,
     * best-of-n sub-agent answerers, blocking fallback). Defaults to 0.7 (creative);
     * callers wanting deterministic / strict output (extraction, summarisation,
     * judging) build their own {@code CompletionRequest} with an explicit
     * temperature and never go through this field.
     *
     * <p>P2-2: previously a hard-coded {@code 0.7}; now an operator-tunable knob
     * bound to {@code axiflux.agent.temperature}.
     */
    private volatile double temperature = 0.7;

    /**
     * Wall-clock cap on one model call. The blocking completion runs on
     * boundedElastic and Reactor cannot interrupt the in-flight HTTP request,
     * but timing out abandons the stale call and lets the fallback chain try
     * another provider.
     *
     * <p><b>Zombie-call caveat (P2-11):</b> the timeout only stops <em>waiting</em>;
     * the underlying blocking HTTP call keeps occupying its boundedElastic
     * thread until it returns naturally. The LLM client layer is therefore
     * expected to enforce its own hard connect/read timeouts (via its HTTP
     * client configuration) so a wedged call is truly bounded and threads do
     * not accumulate under sustained provider failure.
     */
    private volatile Duration llmCallTimeout = Duration.ofSeconds(120);

    LlmRecoveryChain(ModelRouter modelRouter, ContextAssembler contextAssembler,
                     ObjectMapper om, String agentId, ModelCallHook hook) {
        this.modelRouter = java.util.Objects.requireNonNull(modelRouter, "modelRouter");
        this.contextAssembler = java.util.Objects.requireNonNull(contextAssembler, "contextAssembler");
        this.compactionService = new CompactionService();
        this.om = om != null ? om : new ObjectMapper();
        this.agentId = agentId;
        this.hook = hook != null ? hook : (c, m, t) -> {};
    }

    void setTokenCounter(TokenCounter counter) {
        this.tokenCounter = counter != null ? counter : HeuristicTokenCounter.INSTANCE;
    }

    /** Drop compaction watermarks for a session (call when the session is deleted). */
    void resetSession(String sessionId) {
        compactionService.reset(sessionId);
    }

    void setTokenBudget(int contextWindowTokens, int maxOutputTokens, int reserveTokens) {
        if (contextWindowTokens > 0) this.contextWindowTokens = contextWindowTokens;
        if (maxOutputTokens > 0) this.maxOutputTokens = maxOutputTokens;
        if (reserveTokens >= 0) this.reserveTokens = reserveTokens;
    }

    void setLlmCallTimeout(Duration timeout) {
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            this.llmCallTimeout = timeout;
        }
    }

    /**
     * Override the sampling temperature for the main-conversation path
     * ({@link #tryStreamProvider} streaming + blocking replay). Bounded above to
     * the API's 2.0 ceiling; the caller is expected to validate externally.
     */
    void setTemperature(double temperature) {
        if (temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("temperature out of [0,2]: " + temperature);
        }
        this.temperature = temperature;
    }

    /** Test seam for {@link LlmRecoveryChainDirectTest}. */
    double getTemperature() {
        return temperature;
    }

    /**
     * Everything the two call paths (blocking / streaming) need once routing,
     * proactive compaction and context assembly have finished.
     */
    private record PreparedCall(Session session, AgentContext context, List<Message> msgs,
                                List<LlmClient> chain, ContextAssembler.Budget budget,
                                int window, List<JsonNode> toolDefs) {}

    /**
     * Route, (proactively) compact and assemble the context for one tool-loop
     * iteration. Shared by the blocking ({@link #nextResponse}) and streaming
     * ({@link #nextResponseStream}) call paths so recovery semantics stay aligned.
     */
    private Mono<PreparedCall> prepare(Session session, AgentContext context, List<JsonNode> toolDefs) {
        List<Message> viewHistory = compactionService.view(session);
        // callerId := the turn's user id (the caller dimension available at this
        // layer); orgId stays null until the context model grows a tenant field.
        // The BYOK key passes through in-memory only — never logged or persisted.
        String planTier = context.metadata() != null
            ? (String) context.metadata().get(com.gantang.reaxon.api.auth.CallerIdentity.META_PLAN_TIER)
            : null;
        RoutingContext routingCtx = new RoutingContext(
            context.currentQuery(), viewHistory, context.forcedModel(),
            context.userId(), null, context.byokApiKey(), planTier);
        List<LlmClient> chain = modelRouter.routeChain(routingCtx);
        if (chain.isEmpty()) {
            return Mono.error(new IllegalStateException("No LLM client available for routing"));
        }
        LlmClient routed = chain.get(0);

        int window = (routed != null && routed.contextWindowTokens() > 0)
            ? routed.contextWindowTokens() : contextWindowTokens;
        ContextAssembler.Budget budget = new ContextAssembler.Budget(
            window, maxOutputTokens,
            tokenCounter.countText(om.valueToTree(toolDefs).toString()),
            reserveTokens);
        int systemPromptTokens = context.systemPrompt() != null
            ? tokenCounter.countText(context.systemPrompt()) : 0;

        // Proactively summarize aged history before it can force an overflow.
        return compactionService
            .maybeCompact(session, routed, budget, systemPromptTokens, tokenCounter)
            .flatMap(proactive -> {
                List<Message> effHistory = compactionService.view(session);
                log.info("[agent:{}] routed to provider={} model={} (forced={}, chain={}, proactiveCompaction={})",
                    agentId, routed.provider(), routed.primaryModel(),
                    context.forcedModel() == null ? "auto" : context.forcedModel(),
                    chain.stream().map(LlmClient::provider).toList(), proactive);
                return contextAssembler.assembleBudgeted(
                        context,
                        effHistory,
                        null,  // current user msg already persisted in session; pass null to avoid duplication
                        budget,
                        tokenCounter
                    )
                    .timeout(Duration.ofSeconds(10))
                    .onErrorReturn(assembleFromHistory(context, effHistory, budget))
                    .map(msgs -> new PreparedCall(session, context, msgs, chain, budget, window, toolDefs));
            });
    }

    /**
     * Route, (proactively) compact, assemble and call the model, recovering
     * from overflow/failure until one provider succeeds or the chain is
     * exhausted (terminal error). Blocking path.
     */
    Mono<CompletionResponse> nextResponse(Session session, AgentContext context,
                                          List<JsonNode> toolDefs) {
        return prepare(session, context, toolDefs)
            .flatMap(p -> obtainResponse(p.session(), p.context(), p.msgs(), p.chain(),
                0, false, p.budget(), p.window(), p.toolDefs()));
    }

    /**
     * Streaming counterpart of {@link #nextResponse}: emits REASONING/TEXT deltas
     * live while the model thinks/answers, then a terminal DONE carrying the
     * assembled response (text and/or tool calls).
     *
     * <p>Recovery: providers without native streaming are skipped in router
     * order; if a stream fails BEFORE the first chunk (unsupported by the
     * provider, auth error, 4xx, network, watchdog), the next stream-capable
     * provider is tried. Only when ALL stream-capable providers fail pre-flight
     * does the fully-tested blocking recovery chain ({@link #obtainResponse},
     * compact-on-overflow + provider fallback) take over, replaying its single
     * result as chunks. A failure AFTER chunks have started propagates — the UI
     * already received partial output, retrying would duplicate it.
     */
    Flux<StreamChunk> nextResponseStream(Session session, AgentContext context,
                                         List<JsonNode> toolDefs) {
        return prepare(session, context, toolDefs)
            .flatMapMany(this::streamWithFallback);
    }

    private Flux<StreamChunk> streamWithFallback(PreparedCall p) {
        // Prefer providers with native streaming (they carry reasoning deltas),
        // preserving the router's cost order AMONG THEM. Non-streaming providers
        // are skipped here rather than silently downgrading the whole turn to
        // the blocking recovery path (which loses the thinking stream).
        List<LlmClient> streamChain = p.chain().stream()
            .filter(LlmClient::supportsStreaming)
            .toList();
        if (streamChain.isEmpty()) {
            log.warn("[agent:{}] no stream-capable provider in chain {}; using blocking recovery path",
                agentId, p.chain().stream().map(LlmClient::provider).toList());
            return blockingReplay(p, 0);
        }
        return tryStreamProvider(p, streamChain, 0);
    }

    /**
     * Attempt one stream-capable provider. A failure BEFORE the first chunk
     * (unsupported/4xx/auth/network/watchdog) advances to the next streaming
     * provider; once chunks have started flowing, a failure propagates — the
     * UI already has partial output and retrying would duplicate it. Only when
     * every streaming provider fails pre-flight do we fall back to the fully
     * tested blocking recovery chain (compact-on-overflow + all providers).
     */
    private Flux<StreamChunk> tryStreamProvider(PreparedCall p, List<LlmClient> streamChain, int idx) {
        LlmClient provider = streamChain.get(idx);
        CompletionRequest request = CompletionRequest.builder()
            .messages(p.msgs())
            .maxTokens(maxOutputTokens)
            .temperature(temperature)
            .forcedModel(forcedModelFor(p.context(), p.chain(), provider))
            .build();
        JsonNode toolsNode = om.valueToTree(p.toolDefs());
        hook.beforeModelCall(p.context(), List.copyOf(p.msgs()), p.toolDefs());

        java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean(false);
        return provider.completeWithToolsStream(request, toolsNode)
            // The blocking fallback adapter runs the sync call inside Flux.defer;
            // keep it off the turn worker and apply the same idle watchdog the
            // blocking path uses. For native SSE streams each chunk resets the
            // timer, so this fires only on a truly wedged/no-progress connection.
            .subscribeOn(Schedulers.boundedElastic())
            .timeout(llmCallTimeout)
            .doOnNext(c -> started.set(true))
            .onErrorResume(e -> {
                if (started.get()) {
                    log.warn("[agent:{}] stream broken mid-flight on {}: {}",
                        agentId, provider.provider(), e.toString());
                    return Flux.error(e);
                }
                if (idx + 1 < streamChain.size()) {
                    log.warn("[agent:{}] stream unavailable on {} ({}); trying next stream provider {}",
                        agentId, provider.provider(), e.toString(), streamChain.get(idx + 1).provider());
                    return tryStreamProvider(p, streamChain, idx + 1);
                }
                log.warn("[agent:{}] all {} stream provider(s) failed pre-flight; using blocking recovery path: {}",
                    agentId, streamChain.size(), e.toString());
                return blockingReplay(p, 0);
            });
    }

    /** Last-resort path: blocking recovery chain, replayed as one-shot chunks. */
    private Flux<StreamChunk> blockingReplay(PreparedCall p, int chainIdx) {
        return obtainResponse(p.session(), p.context(), p.msgs(), p.chain(),
                chainIdx, false, p.budget(), p.window(), p.toolDefs())
            .flatMapMany(resp -> {
                List<StreamChunk> chunks = new ArrayList<>();
                if (resp.reasoning() != null && !resp.reasoning().isBlank()) {
                    chunks.add(StreamChunk.reasoning(resp.reasoning()));
                }
                if (resp.isText() && resp.content() != null && !resp.content().isBlank()) {
                    chunks.add(StreamChunk.text(resp.content()));
                }
                chunks.add(StreamChunk.done(resp));
                return Flux.fromIterable(chunks);
            });
    }

    /**
     * Call the routed model with automatic recovery, then hand the single
     * successful response back to the tool loop. Recovery wraps ONLY the
     * model call:
     * <ul>
     *   <li><b>Context overflow</b> — aggressively compact history and retry the
     *       same provider once.</li>
     *   <li><b>Auth / unavailable / timeout / other</b> — fall through to the
     *       next provider in the routing chain (different endpoint/credentials).
     *       A provider may still be down after a successful compaction, so a
     *       second overflow also falls through rather than looping.</li>
     * </ul>
     */
    private Mono<CompletionResponse> obtainResponse(
            Session session,
            AgentContext context,
            List<Message> msgs,
            List<LlmClient> chain,
            int clientIdx,
            boolean compacted,
            ContextAssembler.Budget budget,
            int window,
            List<JsonNode> toolDefs) {
        if (clientIdx >= chain.size()) {
            return Mono.error(new IllegalStateException("LLM fallback chain exhausted (no provider succeeded)"));
        }
        LlmClient llm = chain.get(clientIdx);
        CompletionRequest request = CompletionRequest.builder()
            .messages(msgs)
            .maxTokens(maxOutputTokens)
            .temperature(temperature)
            .forcedModel(forcedModelFor(context, chain, llm))
            .build();
        JsonNode toolsNode = om.valueToTree(toolDefs);
        hook.beforeModelCall(context, List.copyOf(msgs), toolDefs);

        // Idle/watchdog cap: a wedged model call (no response at all) is abandoned
        // after llmCallTimeout and classified UNAVAILABLE, so the fallback chain
        // tries another provider instead of hanging the turn forever.
        return Mono.fromCallable(() -> llm.completeWithTools(request, toolsNode))
            .subscribeOn(Schedulers.boundedElastic())
            .timeout(llmCallTimeout)
            .onErrorResume(e -> recoverResponse(e, session, context, msgs, chain,
                clientIdx, compacted, budget, window, toolDefs, llm));
    }

    /**
     * A forced model name is only meaningful on the provider it was forced for
     * (the routed primary at {@code chain.get(0)} — routing matched it by
     * provider name or primary model). Passing the same foreign model name to a
     * fallback provider makes the fallback fail with \"model not found\" or
     * silently map to the wrong model, so fallback calls drop the override and
     * use the provider's own default model (P1-7).
     */
    private static String forcedModelFor(AgentContext context, List<LlmClient> chain, LlmClient client) {
        String forced = context != null ? context.forcedModel() : null;
        if (forced == null || chain == null || chain.isEmpty()) {
            return forced;
        }
        return chain.get(0) == client ? forced : null;
    }

    /** Decide how to recover from a failed model call: compact-on-overflow, else next provider. */
    private Mono<CompletionResponse> recoverResponse(
            Throwable e,
            Session session,
            AgentContext context,
            List<Message> msgs,
            List<LlmClient> chain,
            int clientIdx,
            boolean compacted,
            ContextAssembler.Budget budget,
            int window,
            List<JsonNode> toolDefs,
            LlmClient llm) {
        com.gantang.reaxon.impl.llm.LlmErrorClassifier.Kind kind =
            com.gantang.reaxon.impl.llm.LlmErrorClassifier.classify(e);

        if (kind == com.gantang.reaxon.impl.llm.LlmErrorClassifier.Kind.OVERFLOW && !compacted) {
            log.warn("[agent:{}] context overflow on {}; compacting and retrying: {}",
                agentId, llm.provider(), e.toString());
            return compactionService.compactForOverflow(session, llm, window, tokenCounter)
                .flatMap(didCompact -> {
                    if (!didCompact) {
                        return nextClientResponse(e, kind, session, context, msgs, chain,
                            clientIdx, compacted, budget, window, toolDefs);
                    }
                    List<Message> eff = compactionService.view(session);
                    return contextAssembler.assembleBudgeted(context, eff, null, budget, tokenCounter)
                        .timeout(Duration.ofSeconds(10))
                        .onErrorReturn(assembleFromHistory(context, eff, budget))
                        .flatMap(rebuilt -> obtainResponse(session, context, rebuilt, chain,
                            clientIdx, true, budget, window, toolDefs));
                });
        }
        return nextClientResponse(e, kind, session, context, msgs, chain,
            clientIdx, compacted, budget, window, toolDefs);
    }

    /** Advance to the next provider in the chain, or propagate the error if exhausted. */
    private Mono<CompletionResponse> nextClientResponse(
            Throwable e,
            com.gantang.reaxon.impl.llm.LlmErrorClassifier.Kind kind,
            Session session,
            AgentContext context,
            List<Message> msgs,
            List<LlmClient> chain,
            int clientIdx,
            boolean compacted,
            ContextAssembler.Budget budget,
            int window,
            List<JsonNode> toolDefs) {
        if (clientIdx + 1 < chain.size()) {
            log.warn("[agent:{}] LLM call failed on {} (kind={}); falling back to provider {}",
                agentId, chain.get(clientIdx).provider(), kind, chain.get(clientIdx + 1).provider());
            return obtainResponse(session, context, msgs, chain,
                clientIdx + 1, compacted, budget, window, toolDefs);
        }
        log.warn("[agent:{}] LLM chain exhausted (kind={}); propagating error", agentId, kind);
        return Mono.error(e);
    }

    /** Fallback when retrieval/assembly fails: system prompt + budget-trimmed history, no memory. */
    private List<Message> assembleFromHistory(AgentContext context, List<Message> history,
                                              ContextAssembler.Budget budget) {
        List<Message> prefix = new ArrayList<>();
        if (context.systemPrompt() != null && !context.systemPrompt().isBlank()) {
            prefix.add(Message.system(context.systemPrompt()));
        }
        return ContextBudget.fit(prefix, history, null, budget, tokenCounter);
    }
}
