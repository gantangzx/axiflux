package com.gantang.tianshu.impl.agent;

import com.gantang.tianshu.api.tool.ToolResultCache;
import com.gantang.tianshu.api.tool.ToolResultStore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.agent.AgentEvent;
import com.gantang.tianshu.api.agent.ApprovalManager;
import com.gantang.tianshu.api.agent.AutoApprovalPolicy;
import com.gantang.tianshu.api.config.LiveSettings;
import com.gantang.tianshu.api.observability.MetricsReporter;
import com.gantang.tianshu.api.observability.ToolExecutionRecord;
import com.gantang.tianshu.api.session.Session;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolCall;
import com.gantang.tianshu.api.tool.ToolRegistry;
import com.gantang.tianshu.api.tool.ToolResult;
import com.gantang.tianshu.impl.tool.ToolResultPruner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Executes one model-requested tool call, owning the full gate-to-result path
 * extracted from {@link ReactiveAgent}:
 *
 * <ul>
 *   <li>registry lookup (unknown tool → failure result, not a turn error);</li>
 *   <li>the security policy gate — DENY fails the call, ASK suspends for
 *       human approval, ALLOW runs (with no chain wired, the legacy rule
 *       applies: {@code requiresApproval()} gates, everything else runs);</li>
 *   <li>auto-approval shortcuts (deployment-trusted tools, session grants,
 *       budget policy);</li>
 *   <li>the approval handshake ({@link ApprovalManager}) when a human decision
 *       is required — rejection and "no manager configured" both fail the call
 *       gracefully, approval re-enters the same execution path;</li>
 *   <li>execution with per-tool timeout, metrics, output pruning and session
 *       persistence. Tool success/failure always becomes a TOOL_RESULT event —
 *       tool errors never abort the turn.</li>
 * </ul>
 */
final class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);
    private static final Duration DEFAULT_TOOL_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Fallback ceiling on the human-approval handshake when the wired
     * {@link ApprovalManager} never resolves its Mono: without it a stalled
     * approval parks the turn's TurnSerializer slot forever and starves every
     * later turn of the session (P1-9). Configurable via
     * {@link #setApprovalTimeout}; on expiry the call fails closed (denied).
     */
    private static final Duration DEFAULT_APPROVAL_TIMEOUT = Duration.ofMinutes(5);

    /** Observational hook for tool-result lifecycle (audit/metrics); never fails the turn. */
    @FunctionalInterface
    interface ToolResultHook {
        void onToolResult(AgentContext context, Tool tool, ToolCall call, ToolResult result);
    }

    private final ToolRegistry toolRegistry;
    private final ObjectMapper om;
    private final String agentId;
    private final ToolResultHook hook;
    private final ToolResultPruner toolResultPruner = new ToolResultPruner();
    private volatile com.gantang.tianshu.api.tool.ToolResultStore toolResultStore;
    private volatile com.gantang.tianshu.api.tool.ToolResultCache toolResultCache;
    /** Tools whose (successful) results may be replayed from the session cache. */
    private volatile Set<String> cacheableTools = Set.of();
    /**
     * Per-session in-flight dedupe for cacheable calls: the first identical
     * read-only call executes; concurrent twins share its outcome via
     * {@link reactor.core.publisher.Mono#cache()} instead of double-executing
     * (P1-8). Entries are removed as soon as the shared Mono terminates, so the
     * map only holds currently-executing calls. Process-local: this does NOT
     * provide single-flight across nodes of a multi-instance deployment.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, reactor.core.publisher.Mono<ToolResult>> cacheInflight =
        new java.util.concurrent.ConcurrentHashMap<>();

    private volatile MetricsReporter metricsReporter = MetricsReporter.NOOP;
    private volatile ApprovalManager approvalManager;  // optional, may be null
    private volatile com.gantang.tianshu.api.tool.policy.ToolPolicyChain toolPolicyChain;  // optional security gate
    private volatile LiveSettings liveSettings;  // optional live source
    private volatile Set<String> autoApproveTools = Set.of();  // deployment-trusted tools
    private volatile AutoApprovalPolicy autoApprovalPolicy = AutoApprovalPolicy.NEVER;
    private volatile Duration approvalTimeout = DEFAULT_APPROVAL_TIMEOUT;

    ToolExecutor(ToolRegistry toolRegistry, ObjectMapper om, String agentId, ToolResultHook hook) {
        this.toolRegistry = java.util.Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.om = om != null ? om : new ObjectMapper();
        this.agentId = agentId;
        this.hook = hook != null ? hook : (c, t, call, r) -> {};
    }

    void setMetricsReporter(MetricsReporter metricsReporter) {
        this.metricsReporter = metricsReporter != null ? metricsReporter : MetricsReporter.NOOP;
    }

    void setApprovalManager(ApprovalManager approvalManager) {
        this.approvalManager = approvalManager;
    }

    void setPolicyChain(com.gantang.tianshu.api.tool.policy.ToolPolicyChain chain) {
        this.toolPolicyChain = chain;
    }

    void setLiveSettings(LiveSettings liveSettings) {
        this.liveSettings = liveSettings;
    }

    void setAutoApproveTools(Set<String> tools) {
        this.autoApproveTools = tools == null ? Set.of() : Set.copyOf(tools);
    }

    void setAutoApprovalPolicy(AutoApprovalPolicy policy) {
        this.autoApprovalPolicy = policy == null ? AutoApprovalPolicy.NEVER : policy;
    }

    /** Ceiling for the approval handshake; values <= 0 are ignored (default 5 min). */
    void setApprovalTimeout(Duration timeout) {
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            this.approvalTimeout = timeout;
        }
    }

    void setToolResultMaxChars(int maxChars) {
        toolResultPruner.setMaxChars(maxChars);
    }

    /** Wire the session-scoped side store for oversized tool results (P1-3). */
    void setToolResultStore(com.gantang.tianshu.api.tool.ToolResultStore store) {
        this.toolResultStore = store;
    }

    /**
     * Wire the session-scoped idempotent-result cache (P2-2). Only tools in
     * {@code cacheableTools} participate; write/exec tools must never be listed.
     *
     * <p>Concurrency note (P1-8): identical concurrent calls within one session
     * are single-flighted via a process-local in-flight map (& cached replays
     * go through the same persistence exit), but the cache itself does NOT
     * guarantee single-flight across nodes — a multi-instance deployment may
     * still execute the same key once per node. Cached tools must stay
     * idempotent reads where a duplicate execution is safe, merely wasteful.
     */
    void setToolResultCache(com.gantang.tianshu.api.tool.ToolResultCache cache,
                            Set<String> cacheableTools) {
        this.toolResultCache = cache;
        this.cacheableTools = cacheableTools == null ? Set.of() : Set.copyOf(cacheableTools);
    }

    /** Turn-level lifecycle metric (the agent owns turn state, the reporter lives here). */
    void recordAgentRequest(String agentId, String status, Duration elapsed) {
        metricsReporter.recordAgentRequest(agentId, status, elapsed, 0);
    }

    /**
     * Run one tool call end to end and emit its events (APPROVAL_REQUIRED when
     * gated, then exactly one TOOL_RESULT).
     */
    Flux<AgentEvent> execute(Session session, ToolCall call, AgentContext context) {
        Optional<Tool> toolOpt = toolRegistry.get(call.toolName());

        if (toolOpt.isEmpty()) {
            ToolResult result = ToolResult.failure(call.callId(),
                "Tool not found: " + call.toolName());
            ToolResult saved = persistToolResult(session, call, result, null, context);
            return Flux.just(AgentEvent.toolResult(call.callId(), formatFailure(saved)));
        }

        Tool tool = toolOpt.get();

        // ── Security policy gate ─────────────────────────────────────────
        // DENY  → fail the call without executing
        // ASK   → suspend for human approval (approval flow)
        // ALLOW → execute directly
        com.gantang.tianshu.api.tool.policy.PolicyDecision decision = evaluatePolicy(tool, call.arguments(), context, session);
        if (decision.isDeny()) {
            log.warn("[agent:{}] tool {} DENIED by policy: {}", agentId, tool.name(), decision.reason());
            ToolResult denied = ToolResult.failure(call.callId(),
                "Security policy denied this tool call: " + decision.reason());
            metricsReporter.recordToolCall(tool.name(), "denied", Duration.ZERO);
            metricsReporter.recordToolExecution(ToolExecutionRecord.of(
                context.sessionId(), context.userId(), tool.name(), call.callId(),
                call.arguments(), false, Duration.ZERO, "denied: " + decision.reason()));
            ToolResult savedDenied = persistToolResult(session, call, denied, tool, context);
            return Flux.just(AgentEvent.toolResult(call.callId(), formatFailure(savedDenied)));
        }
        if (decision.isAsk() && (decision.escalated() || !isAutoApproved(tool, context))) {
            // Non-interactive turn (scheduler/background): there is no human to
            // approve the gate, so fail fast instead of hanging on the handshake.
            if (isHeadless(context)) {
                log.info("[agent:{}] tool {} requires approval but turn is headless - denying",
                    agentId, tool.name());
                ToolResult blocked = ToolResult.failure(call.callId(),
                    "tool '" + tool.name() + "' requires human approval but this is a "
                    + "non-interactive scheduled/background run");
                metricsReporter.recordToolCall(tool.name(), "denied", Duration.ZERO);
                metricsReporter.recordToolExecution(ToolExecutionRecord.of(
                    context.sessionId(), context.userId(), tool.name(), call.callId(),
                    call.arguments(), false, Duration.ZERO, "denied: headless turn"));
                ToolResult savedBlocked = persistToolResult(session, call, blocked, tool, context);
                return Flux.just(AgentEvent.toolResult(call.callId(), formatFailure(savedBlocked)));
            }
            log.info("[agent:{}] tool {} requires approval: {}", agentId, tool.name(), decision.reason());
            return Flux.just(
                AgentEvent.approvalRequired(call.callId(), tool.name(), tool.description()))
                .concatWith(waitForApproval(session, call, tool, context));
        }

        // ALLOW (or auto-approved): serve from the session cache for read-only tools,
        // otherwise execute. The reactive SPI bridges blocking tools onto
        // boundedElastic inside the default executeReactive implementation.
        if (isCacheable(tool)) {
            com.gantang.tianshu.api.tool.ToolResultCache cache = this.toolResultCache;
            String cacheKey = canonicalCacheKey(tool.name(), call.arguments());
            var hit = cache.get(context.sessionId(), cacheKey);
            if (hit.isPresent()) {
                var c = hit.get();
                log.debug("[agent:{}] tool {} cache hit session={}", agentId, tool.name(), context.sessionId());
                metricsReporter.incrementCounter(com.gantang.tianshu.api.observability.MetricNames.TOOL_CACHE,
                    Map.of(com.gantang.tianshu.api.observability.MetricNames.TAG_TOOL, tool.name(),
                           com.gantang.tianshu.api.observability.MetricNames.TAG_STATUS, "hit"));
                Map<String, Object> meta = new java.util.HashMap<>();
                meta.put("cached", true);
                meta.put("cachedAt", c.cachedAtEpochMilli());
                ToolResult replayed = c.success()
                    ? new ToolResult(call.callId(), true, c.content(), null, meta)
                    : new ToolResult(call.callId(), false, null, c.errorMessage(), meta);
                ToolResult saved = persistToolResult(session, call, replayed, tool, context);
                return Flux.just(AgentEvent.toolResult(call.callId(),
                    saved.success() ? saved.displayContent() : formatFailure(saved)));
            }
            metricsReporter.incrementCounter(com.gantang.tianshu.api.observability.MetricNames.TOOL_CACHE,
                Map.of(com.gantang.tianshu.api.observability.MetricNames.TAG_TOOL, tool.name(),
                       com.gantang.tianshu.api.observability.MetricNames.TAG_STATUS, "miss"));
            return invokeToolSingleFlight(session, call, tool, context, cacheKey, cache);
        }
        return invokeTool(session, call, tool, context, null, null);
    }

    /**
     * In-flight dedupe for cacheable calls (P1-8): within one session, concurrent
     * identical read-only calls (e.g. the model emitting the same call twice in a
     * parallel flatMap) share ONE execution via {@code Mono.cache()} instead of
     * both missing the cache and double-executing. Only the raw execution is
     * shared — each caller then persists/emits its own result (its own callId),
     * so the transcript keeps one tool result per issued tool call. The entry is
     * evicted as soon as the shared execution terminates, so later calls still
     * miss/fresh-execute or hit the TTL cache normally. Process-local only — see
     * the javadoc on {@link #setToolResultCache} for the cross-node caveat.
     */
    private Flux<AgentEvent> invokeToolSingleFlight(Session session, ToolCall call, Tool tool,
                                                    AgentContext context, String cacheKey,
                                                    com.gantang.tianshu.api.tool.ToolResultCache cache) {
        final String flightKey = context.sessionId() + '|' + cacheKey;
        reactor.core.publisher.Mono<ToolResult> shared = cacheInflight.computeIfAbsent(flightKey,
            k -> executeTool(call, tool, context)
                .cache()
                .doFinally(sig -> cacheInflight.remove(k)));
        // Re-key the shared outcome to THIS call so persistence and the emitted
        // event carry each caller's own callId.
        reactor.core.publisher.Mono<ToolResult> perCall = shared.map(r ->
            call.callId().equals(r.callId()) ? r
                : new ToolResult(call.callId(), r.success(), r.content(), r.errorMessage(), r.metadata()));
        return finishExecution(perCall, session, call, tool, context, cacheKey, cache);
    }

    private boolean isCacheable(Tool tool) {
        return toolResultCache != null && cacheableTools.contains(tool.name());
    }

    /**
     * Canonical cache key: arguments are serialized with recursively sorted map
     * keys so semantically identical JSON objects ({@code {a:1,b:2}} vs
     * {@code {b:2,a:1}}) collide. Falls back to raw string on serialization error.
     */
    @SuppressWarnings("unchecked")
    private String canonicalCacheKey(String toolName, Map<String, Object> args) {
        if (args == null || args.isEmpty()) return com.gantang.tianshu.api.tool.ToolResultCache.key(toolName, "{}");
        try {
            var sorted = sortDeep(args);
            return com.gantang.tianshu.api.tool.ToolResultCache.key(toolName, om.writeValueAsString(sorted));
        } catch (Exception e) {
            return com.gantang.tianshu.api.tool.ToolResultCache.key(toolName, String.valueOf(args));
        }
    }

    @SuppressWarnings("unchecked")
    private static Object sortDeep(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new java.util.TreeMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), sortDeep(v)));
            return out;
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(ToolExecutor::sortDeep).toList();
        }
        return value;
    }

    /** Resolve a tool's execution budget: its declared override or the global cap. */
    private Duration toolTimeout(Tool tool) {
        Duration t = tool.timeout();
        if (t != null && !t.isZero() && !t.isNegative()) return t;
        LiveSettings ls = this.liveSettings;
        long secs = ls != null ? ls.snapshot().toolTimeoutSeconds() : 30L;
        return Duration.ofSeconds(secs > 0 ? secs : 30L);
    }

    /**
     * Run the security policy chain for a tool call. When no chain is wired the
     * legacy rule applies: approval-gated tools ASK, everything else ALLOWs.
     */
    private com.gantang.tianshu.api.tool.policy.PolicyDecision evaluatePolicy(
            Tool tool, Map<String, Object> args, AgentContext context, Session session) {
        com.gantang.tianshu.api.tool.policy.ToolPolicyChain chain = this.toolPolicyChain;
        if (chain != null) {
            // Prompt-injection layer 1/2 signal: surface recent untrusted tool output
            // (boundary-wrapped at persistence) to the policy chain via turn metadata.
            AgentContext policyCtx = context;
            com.gantang.tianshu.impl.tool.UntrustedContent.Scan scan =
                com.gantang.tianshu.impl.tool.UntrustedContent.scanRecent(session, 12, 6000);
            if (scan.present()) {
                Map<String, Object> meta = new java.util.HashMap<>(
                    context.metadata() != null ? context.metadata() : Map.of());
                meta.put(com.gantang.tianshu.impl.tool.UntrustedContent.META_UNTRUSTED_PRESENT, true);
                meta.put(com.gantang.tianshu.impl.tool.UntrustedContent.META_UNTRUSTED_TEXT, scan.text());
                policyCtx = context.toBuilder().metadata(meta).build();
            }
            return chain.evaluate(tool, args, policyCtx);
        }
        return tool.requiresApproval()
            ? com.gantang.tianshu.api.tool.policy.PolicyDecision.ask("tool requires approval")
            : com.gantang.tianshu.api.tool.policy.PolicyDecision.allow();
    }

    /**
     * Tools the deployment operator explicitly trusts to run WITHOUT human
     * approval ("budget threshold / auto-approve"). These still pass the
     * DENY checks (whitelist, SSRF, agent scope) — only the ASK/approval gate
     * is bypassed. Session-scoped grants ("approve for this session") are
     * honoured separately via {@link ApprovalManager#isSessionGranted}.
     */
    /** True for non-interactive turns (scheduler/background) that cannot await human approval. */
    private boolean isHeadless(AgentContext context) {
        return context != null && context.metadata() != null
            && Boolean.TRUE.equals(context.metadata().get(com.gantang.tianshu.api.auth.CallerIdentity.META_HEADLESS));
    }

    private boolean isAutoApproved(Tool tool, AgentContext context) {
        Set<String> trusted = liveSettings != null
            ? liveSettings.snapshot().autoApproveTools() : autoApproveTools;
        if (trusted.contains(tool.name())) {
            log.info("[agent:{}] tool {} auto-approved by deployment policy", agentId, tool.name());
            return true;
        }
        ApprovalManager mgr = this.approvalManager;
        if (mgr != null && mgr.isSessionGranted(context.sessionId(), tool.name())) {
            log.info("[agent:{}] tool {} auto-approved by session grant", agentId, tool.name());
            return true;
        }
        if (autoApprovalPolicy.isAutoApproved(context, tool)) {
            log.info("[agent:{}] tool {} auto-approved by budget policy", agentId, tool.name());
            return true;
        }
        return false;
    }

    private Flux<AgentEvent> invokeTool(Session session, ToolCall call, Tool tool, AgentContext context) {
        return invokeTool(session, call, tool, context, null, null);
    }

    /**
     * Execute a tool that has already cleared the policy/approval gate. Shared by
     * the direct ALLOW path and the post-approval path so timeout handling,
     * metrics, result persistence and error translation live in exactly one place.
     *
     * @param cacheKey when non-null and the call succeeds, the RAW result is stored
     *                 in the session tool-result cache for future replays
     */
    private Flux<AgentEvent> invokeTool(Session session, ToolCall call, Tool tool, AgentContext context,
                                        String cacheKey, com.gantang.tianshu.api.tool.ToolResultCache cache) {
        return finishExecution(executeTool(call, tool, context), session, call, tool, context, cacheKey, cache);
    }

    /**
     * Run the tool and normalize the outcome to a {@link ToolResult}: success and
     * tool errors (including the per-tool timeout) both become a result — this
     * Mono never signals an error, so it is safe to share via {@code Mono.cache()}
     * for the P1-8 in-flight dedupe. Metrics are recorded here, once per execution.
     */
    private reactor.core.publisher.Mono<ToolResult> executeTool(ToolCall call, Tool tool, AgentContext context) {
        final Duration execTimeout = toolTimeout(tool);
        final long start = System.nanoTime();
        return tool.executeReactive(call.callId(), call.arguments(), context)
            .timeout(execTimeout)
            .map(result -> {
                Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
                metricsReporter.recordToolCall(tool.name(),
                    result.success() ? "success" : "error", elapsed);
                metricsReporter.recordToolExecution(ToolExecutionRecord.of(
                    context.sessionId(), context.userId(), tool.name(), call.callId(),
                    call.arguments(), result.success(), elapsed, result.errorMessage()));
                return result;
            })
            .onErrorResume(e -> {
                Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
                boolean timedOut = isTimeout(e);
                metricsReporter.recordToolCall(tool.name(), timedOut ? "timeout" : "error",
                    timedOut ? execTimeout : elapsed);
                metricsReporter.recordToolExecution(ToolExecutionRecord.of(
                    context.sessionId(), context.userId(), tool.name(), call.callId(),
                    call.arguments(), false, timedOut ? execTimeout : elapsed,
                    timedOut ? "timed out after " + execTimeout.toSeconds() + "s" : e.getMessage()));
                String msg = timedOut
                    ? tool.name() + " timed out after " + execTimeout.toSeconds()
                        + "s" + (tool.timeout() != null ? " (extended budget)" : "")
                    : e.getMessage();
                return reactor.core.publisher.Mono.just(ToolResult.failure(call.callId(), msg));
            });
    }

    /**
     * Post-execution tail shared by the direct path, the post-approval path and
     * the P1-8 single-flight replay: populate the read-only cache, persist the
     * result under the caller's own callId and emit exactly one TOOL_RESULT.
     */
    private Flux<AgentEvent> finishExecution(reactor.core.publisher.Mono<ToolResult> execution,
                                             Session session, ToolCall call, Tool tool, AgentContext context,
                                             String cacheKey, com.gantang.tianshu.api.tool.ToolResultCache cache) {
        return execution.flatMapMany(result -> {
            // Populate the read-only result cache with the RAW result (before
            // pruning/trust wrapping); replays go through persistToolResult again.
            if (cache != null && cacheKey != null && result.success()) {
                try {
                    cache.put(context.sessionId(), cacheKey,
                        new com.gantang.tianshu.api.tool.ToolResultCache.CachedResult(
                            true, result.content(), null, System.currentTimeMillis()));
                } catch (Throwable cacheErr) {
                    // A cache implementation throwing an Error must not abort
                    // the turn either — caching is best-effort (P1-8).
                    log.debug("[agent:{}] tool {} cache put failed: {}",
                        agentId, tool.name(), cacheErr.toString());
                }
            }
            ToolResult saved = persistToolResult(session, call, result, tool, context);
            return Flux.just(AgentEvent.toolResult(call.callId(), saved.displayContent()));
        });
    }

    /** True when the error chain is a Reactor/Hazleton timeout rather than a tool exception. */
    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.util.concurrent.TimeoutException) return true;
            String n = t.getClass().getName();
            if (n.contains("TimeoutException") || n.contains("$TimeoutException")) return true;
        }
        // Reactor's .timeout() raises a TimeoutException whose message mentions the duration.
        return e != null && e.getMessage() != null && e.getMessage().contains("timed out");
    }

    private Flux<AgentEvent> waitForApproval(
        Session session,
        ToolCall call,
        Tool tool,
        AgentContext context
    ) {
        ApprovalManager mgr = this.approvalManager;
        if (mgr == null) {
            // No approval manager configured — reject
            ToolResult result = ToolResult.failure(call.callId(),
                "Approval required but no approval manager is configured");
            ToolResult saved = persistToolResult(session, call, result, tool, context);
            return Flux.just(AgentEvent.toolResult(call.callId(), formatFailure(saved)));
        }

        String argsSummary = summarizeArgs(call.arguments());
        ApprovalManager.ApprovalRequest req = new ApprovalManager.ApprovalRequest(
            call.callId(),
            context.sessionId(),
            context.userId(),
            tool.name(),
            tool.description(),
            argsSummary,
            java.time.Instant.now(),
            null  // use default timeout
        );

        return mgr.submit(req)
            // Failsafe ceiling: an ApprovalManager without its own timeout must
            // not park the turn (and its TurnSerializer slot) forever (P1-9).
            .timeout(approvalTimeout)
            .onErrorResume(e -> isTimeout(e), e -> {
                log.warn("[agent:{}] approval for tool {} timed out after {}s - denying (fail-closed)",
                    agentId, tool.name(), approvalTimeout.toSeconds());
                return reactor.core.publisher.Mono.just(ToolResult.failure(call.callId(),
                    "approval timed out after " + approvalTimeout.toSeconds()
                        + "s with no human decision - denied"));
            })
            .flatMapMany(decision -> {
                metricsReporter.recordToolApproval(tool.name(), decision.success());
                if (!decision.success()) {
                    ToolResult saved = persistToolResult(session, call, decision, tool, context);
                    return Flux.just(AgentEvent.toolResult(call.callId(), formatFailure(saved)));
                }
                // Approved — run through the same invoke path as the ALLOW branch
                // (timeout, metrics, persistence, error translation).
                return invokeTool(session, call, tool, context);
            });
    }

    private String summarizeArgs(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "(no arguments)";
        try {
            String json = om.writeValueAsString(args);
            return json.length() > 200 ? json.substring(0, 200) + "..." : json;
        } catch (Exception e) {
            return args.toString();
        }
    }

    /**
     * Prune a tool result to the history size cap, persist it, and fire the
     * tool-result lifecycle hook. Returns the persisted (possibly pruned)
     * result so emitted events match what the model will later read back.
     */
    private ToolResult persistToolResult(
            Session session, ToolCall call, ToolResult result, Tool tool, AgentContext context) {
        // P1-3: park the full oversized output in the session-scoped side store
        // BEFORE pruning, so the omitted middle is retrievable via result_read
        // instead of being permanently lost.
        String handle = null;
        com.gantang.tianshu.api.tool.ToolResultStore store = this.toolResultStore;
        if (store != null && tool != null && result.success()
                && result.content() != null
                && toolResultPruner.wouldTruncate(result.content(), tool.name())) {
            try {
                handle = store.store(session.sessionId(), result.content(), tool.name());
            } catch (RuntimeException e) {
                log.warn("[agent:{}] tool result side-store failed, pruning only: {}",
                    agentId, e.toString());
            }
        }
        ToolResult pruned = toolResultPruner.prune(result, tool != null ? tool.name() : null, handle);
        // Prompt-injection layer 1: wrap output from outside the trust boundary in
        // explicit markers at the single persistence exit (after pruning so the
        // closing marker is never cut off). result_read re-surfaces content whose
        // ORIGINAL tool was outside the trust boundary, and inherits that marking.
        ToolResult saved = pruned;
        if (tool != null && pruned.success()
                && pruned.content() != null && !pruned.content().isBlank()
                && !com.gantang.tianshu.impl.tool.UntrustedContent.isWrapped(pruned.content())
                && isUntrustedOutput(tool.name(), pruned.metadata())) {
            String wrapped = com.gantang.tianshu.impl.tool.UntrustedContent.wrap(
                tool.name(), call.arguments(), pruned.content());
            saved = new ToolResult(pruned.callId(), true, wrapped, null, pruned.metadata());
        }
        session.addToolResult(call.callId(), saved);
        hook.onToolResult(context, tool, call, saved);
        return saved;
    }

    /**
     * Whether a tool's output originates outside the trust boundary. Built-in
     * untrusted sources are marked directly; {@code result_read} inherits the
     * origin of the parked record it reads (metadata {@code originTool}).
     */
    private static boolean isUntrustedOutput(String toolName, Map<String, Object> metadata) {
        if (com.gantang.tianshu.impl.tool.UntrustedContent.isUntrustedSource(toolName)) return true;
        if (com.gantang.tianshu.impl.tool.builtin.ResultReadTool.NAME.equals(toolName)
                && metadata != null
                && metadata.get("originTool") instanceof String origin) {
            return com.gantang.tianshu.impl.tool.UntrustedContent.isUntrustedSource(origin);
        }
        return false;
    }

    private String formatFailure(ToolResult result) {
        return result.success() ? result.content() : "Error: " + result.errorMessage();
    }
}
