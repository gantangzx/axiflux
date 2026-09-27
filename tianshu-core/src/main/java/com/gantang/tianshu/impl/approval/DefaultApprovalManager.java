package com.gantang.tianshu.impl.approval;

import com.gantang.tianshu.api.agent.ApprovalDecision;
import com.gantang.tianshu.api.agent.ApprovalManager;
import com.gantang.tianshu.api.agent.ApprovalStore;
import com.gantang.tianshu.api.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.Disposable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ApprovalManager} that delegates persistence and cross-instance
 * notification to an {@link ApprovalStore}.
 *
 * <p>Each submitted request registers a local {@link Sinks.One} the agent
 * turn awaits. Decisions arrive on the store's hot {@link ApprovalStore#decisions()}
 * stream — regardless of whether the approve/reject call hit <i>this</i>
 * instance or another node — and complete the matching waiter. With the
 * in-memory store this is a local Sink; with the Redis store the decision is
 * published over pub/sub, so approvals survive load-balanced multi-instance
 * deployments.
 *
 * <p>The synchronous {@link ApprovalManager} contract (approve/reject/get/
 * list/grants) bridges to the reactive store via blocking calls; these are
 * low-frequency human/console operations on the request thread, consistent
 * with the framework's other blocking stores.
 */
public class DefaultApprovalManager implements ApprovalManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultApprovalManager.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
    /** Session grants don't live forever: expire 8h after issue by default. */
    private static final Duration DEFAULT_GRANT_TTL = Duration.ofHours(8);

    private final ApprovalStore store;
    private final Duration defaultTimeout;
    private final Duration grantTtl;

    /** Waiters held by THIS instance (only the node that submitted waits here). */
    private final Map<String, Sinks.One<ToolResult>> localWaiters = new ConcurrentHashMap<>();
    private final Disposable decisionSubscription;

    public DefaultApprovalManager() {
        this(new InMemoryApprovalStore());
    }

    public DefaultApprovalManager(ApprovalStore store) {
        this(store, DEFAULT_TIMEOUT, DEFAULT_GRANT_TTL);
    }

    public DefaultApprovalManager(Duration defaultTimeout) {
        this(new InMemoryApprovalStore(), defaultTimeout, DEFAULT_GRANT_TTL);
    }

    /** Test/operator hook: override the session-grant time-to-live. */
    public DefaultApprovalManager(Duration defaultTimeout, Duration grantTtl) {
        this(new InMemoryApprovalStore(), defaultTimeout, grantTtl);
    }

    public DefaultApprovalManager(ApprovalStore store, Duration defaultTimeout, Duration grantTtl) {
        this.store = store != null ? store : new InMemoryApprovalStore();
        this.defaultTimeout = defaultTimeout != null ? defaultTimeout : DEFAULT_TIMEOUT;
        this.grantTtl = (grantTtl == null || grantTtl.isZero() || grantTtl.isNegative())
            ? DEFAULT_GRANT_TTL : grantTtl;

        // Single hot subscription for this instance: any decision (local or from
        // another node) completes the waiter registered here.
        this.decisionSubscription = this.store.decisions().subscribe(
            this::onDecision,
            err -> log.error("Approval decision stream error", err));
    }

    private void onDecision(ApprovalDecision decision) {
        Sinks.One<ToolResult> sink = localWaiters.remove(decision.callId());
        if (sink == null) {
            // Either this node isn't waiting for it (another node is) or the
            // waiter already timed out. Nothing to do here.
            log.debug("Approval decision for {} with no local waiter (resolved elsewhere or timed out)",
                decision.callId());
            return;
        }
        ToolResult result = decision.approved()
            ? ToolResult.success(decision.callId(), decision.message())
            : ToolResult.failure(decision.callId(), decision.message());
        sink.tryEmitValue(result);
    }

    @Override
    public Mono<ToolResult> submit(ApprovalRequest request) {
        Instant expiresAt = request.expiresAt() != null
            ? request.expiresAt()
            : Instant.now().plus(defaultTimeout);

        ApprovalRequest effective = request.expiresAt() != null
            ? request
            : new ApprovalRequest(
                request.callId(), request.sessionId(), request.userId(),
                request.toolName(), request.toolDescription(),
                request.argumentsSummary(), request.createdAt(), expiresAt);

        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return Mono.just(ToolResult.failure(request.callId(), "Approval already expired"));
        }

        Sinks.One<ToolResult> sink = Sinks.one();
        localWaiters.put(request.callId(), sink);

        log.info("Approval requested: tool={} callId={} user={} expires={}",
            request.toolName(), request.callId(), request.userId(), expiresAt);

        return store.savePending(effective, ttl)
            .then(sink.asMono())
            .timeout(ttl)
            .onErrorResume(e -> {
                localWaiters.remove(request.callId());
                // Best-effort cleanup so a timed-out request doesn't linger in the store.
                store.removePending(request.callId()).subscribe();
                String msg = e instanceof java.util.concurrent.TimeoutException
                    ? "Approval timed out after " + defaultTimeout.toMinutes() + " minutes"
                    : "Approval failed: " + e.getMessage();
                log.warn("Approval {} failed: {}", request.callId(), msg);
                return Mono.just(ToolResult.failure(request.callId(), msg));
            })
            .doFinally(s -> localWaiters.remove(request.callId()));
    }

    @Override
    public boolean approve(String callId, String approverId) {
        ApprovalRequest req = store.removePending(callId).block();
        if (req == null) return false;
        log.info("Approval granted: callId={} approver={}", callId, approverId);
        store.publishDecision(ApprovalDecision.approve(callId, approverId)).block();
        return true;
    }

    @Override
    public boolean reject(String callId, String approverId, String reason) {
        ApprovalRequest req = store.removePending(callId).block();
        if (req == null) return false;
        String msg = "Rejected by " + (approverId != null ? approverId : "unknown")
            + (reason != null && !reason.isBlank() ? ": " + reason : "");
        log.info("Approval rejected: callId={} reason={}", callId, msg);
        store.publishDecision(ApprovalDecision.reject(callId, approverId, msg)).block();
        return true;
    }

    @Override
    public Optional<ApprovalRequest> get(String callId) {
        return Optional.ofNullable(store.getPending(callId).block());
    }

    @Override
    public List<ApprovalRequest> listPending(String userId) {
        List<ApprovalRequest> list = store.listPending(userId).collectList().block();
        return list != null ? list : List.of();
    }

    @Override
    public boolean isSessionGranted(String sessionId, String toolName) {
        return store.hasGrant(sessionId, toolName);
    }

    @Override
    public void grantSession(String sessionId, String toolName, String approverId) {
        if (sessionId == null || toolName == null) return;
        store.saveGrant(sessionId, toolName, grantTtl);
        log.info("Session grant: tool={} session={} approver={} ttl={}s (future calls auto-approved)",
            toolName, sessionId, approverId, grantTtl.getSeconds());
    }

    @Override
    public boolean revokeSessionGrant(String sessionId, String toolName) {
        return store.removeGrant(sessionId, toolName);
    }

    /** Revoke every session-scoped grant held by a session (e.g. when it ends). */
    public int revokeAllSessionGrants(String sessionId) {
        return store.removeAllGrants(sessionId);
    }

    /**
     * Release background resources. Called automatically on Spring context close
     * (the @Bean registers it as the inferred destroy method) and safe to call directly.
     */
    public void shutdown() {
        decisionSubscription.dispose();
        // Fail any waiters still held by this instance.
        localWaiters.forEach((id, sink) ->
            sink.tryEmitValue(ToolResult.failure(id, "Approval manager shutting down")));
        localWaiters.clear();
        // Release the underlying store's background resources (no-op for Redis).
        if (store instanceof InMemoryApprovalStore mem) mem.shutdown();
    }
}
