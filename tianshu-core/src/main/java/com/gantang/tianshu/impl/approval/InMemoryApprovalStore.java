package com.gantang.tianshu.impl.approval;

import com.gantang.tianshu.api.agent.ApprovalDecision;
import com.gantang.tianshu.api.agent.ApprovalManager.ApprovalRequest;
import com.gantang.tianshu.api.agent.ApprovalStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Single-node {@link ApprovalStore}: pending requests and grants live in
 * {@link ConcurrentHashMap}s; decisions are fanned out through a Reactor
 * {@link Sinks} multicast so the local {@code ApprovalManager} wakes the
 * waiting agent turn.
 *
 * <p>Expired entries are evicted lazily on read and opportunistically when a
 * new pending request is saved (a full sweep is cheap at in-memory scale).
 */
public class InMemoryApprovalStore implements ApprovalStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryApprovalStore.class);

    private record Pending(ApprovalRequest request, Instant expiresAt) {}
    private record Grant(Instant expiresAt) {}

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final Sinks.Many<ApprovalDecision> decisionSink =
        Sinks.many().multicast().onBackpressureBuffer();

    /** Background sweep that evicts expired pending/granted entries (mirrors Redis TTL). */
    private final ScheduledExecutorService cleanup =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tianshu-approval-store-cleanup");
            t.setDaemon(true);
            return t;
        });

    public InMemoryApprovalStore() {
        cleanup.scheduleAtFixedRate(this::sweepExpired, 30, 30, TimeUnit.SECONDS);
    }

    /** Stop the background sweep. Safe to call multiple times. */
    public void shutdown() {
        cleanup.shutdownNow();
    }

    @Override
    public Mono<Void> savePending(ApprovalRequest request, Duration ttl) {
        return Mono.fromRunnable(() -> {
            sweepExpired();
            Instant expiresAt = request.expiresAt() != null
                ? request.expiresAt() : Instant.now().plus(ttl);
            pending.put(request.callId(), new Pending(request, expiresAt));
        });
    }

    @Override
    public Mono<ApprovalRequest> removePending(String callId) {
        return Mono.fromCallable(() -> {
            Pending p = pending.remove(callId);
            if (p == null || p.expiresAt().isBefore(Instant.now())) return null;
            return p.request();
        });
    }

    @Override
    public Mono<ApprovalRequest> getPending(String callId) {
        return Mono.fromCallable(() -> {
            Pending p = pending.get(callId);
            if (p == null || p.expiresAt().isBefore(Instant.now())) return null;
            return p.request();
        });
    }

    @Override
    public Flux<ApprovalRequest> listPending(String userId) {
        Instant now = Instant.now();
        return Flux.defer(() -> Flux.fromIterable(pending.values()))
            .filter(p -> p.expiresAt().isAfter(now))
            .map(Pending::request)
            .filter(r -> userId == null || userId.equals(r.userId()));
    }

    @Override
    public Mono<Void> publishDecision(ApprovalDecision decision) {
        return Mono.fromRunnable(() -> {
            Sinks.EmitResult r = decisionSink.tryEmitNext(decision);
            if (r.isFailure()) {
                log.warn("Failed to emit approval decision {}: {}", decision.callId(), r);
            }
        });
    }

    @Override
    public Flux<ApprovalDecision> decisions() {
        return decisionSink.asFlux();
    }

    @Override
    public void saveGrant(String sessionId, String toolName, Duration ttl) {
        if (sessionId == null || toolName == null) return;
        grants.put(grantKey(sessionId, toolName), new Grant(Instant.now().plus(ttl)));
    }

    @Override
    public boolean hasGrant(String sessionId, String toolName) {
        if (sessionId == null || toolName == null) return false;
        Grant g = grants.get(grantKey(sessionId, toolName));
        if (g == null) return false;
        if (g.expiresAt().isBefore(Instant.now())) {
            grants.remove(grantKey(sessionId, toolName), g);
            return false;
        }
        return true;
    }

    @Override
    public boolean removeGrant(String sessionId, String toolName) {
        if (sessionId == null || toolName == null) return false;
        return grants.remove(grantKey(sessionId, toolName)) != null;
    }

    @Override
    public int removeAllGrants(String sessionId) {
        String prefix = sessionId + "\u0000";
        int[] removed = {0};
        grants.keySet().removeIf(k -> {
            if (k.startsWith(prefix)) { removed[0]++; return true; }
            return false;
        });
        return removed[0];
    }

    private static String grantKey(String sessionId, String toolName) {
        return sessionId + "\u0000" + toolName;
    }

    private void sweepExpired() {
        Instant now = Instant.now();
        pending.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
        grants.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }

    @Override
    protected void finalize() throws Throwable {
        try { cleanup.shutdownNow(); } finally { super.finalize(); }
    }
}
