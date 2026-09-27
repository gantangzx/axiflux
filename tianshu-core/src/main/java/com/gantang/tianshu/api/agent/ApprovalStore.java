package com.gantang.tianshu.api.agent;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Persistence and cross-instance notification for approval requests.
 *
 * <p>Decouples {@link ApprovalManager} from where pending requests live and
 * how decisions reach the waiting agent turn. The default in-memory
 * implementation is sufficient for a single node; a Redis-backed
 * implementation (in the Spring starter) lets a multi-instance deployment
 * route an approve/reject call to any node — the decision is published on a
 * shared channel and the instance holding the waiting agent turn is woken.
 *
 * <p>Pending requests and grants use reactive signatures (the agent turn
 * already runs on Reactor); grant reads/writes are synchronous because the
 * existing {@link ApprovalManager} grant contract is synchronous and Spring
 * Data Redis templates are blocking anyway.
 */
public interface ApprovalStore {

    /** Persist a pending request with an expiry (auto-evicted after {@code ttl}). */
    Mono<Void> savePending(ApprovalManager.ApprovalRequest request, Duration ttl);

    /** Atomically fetch and remove a pending request (empty when already resolved/expired). */
    Mono<ApprovalManager.ApprovalRequest> removePending(String callId);

    /** Fetch a pending request without removing it. */
    Mono<ApprovalManager.ApprovalRequest> getPending(String callId);

    /** List non-expired pending requests, optionally filtered by user (null = all). */
    Flux<ApprovalManager.ApprovalRequest> listPending(String userId);

    /** Broadcast a decision to every instance. */
    Mono<Void> publishDecision(ApprovalDecision decision);

    /** Stream of decisions published by any instance (hot, multicast). */
    Flux<ApprovalDecision> decisions();

    /** Remember a session-scoped grant for {@code ttl}. */
    void saveGrant(String sessionId, String toolName, Duration ttl);

    /** True when a non-expired session grant exists. */
    boolean hasGrant(String sessionId, String toolName);

    /** Remove a session grant; returns true if one existed. */
    boolean removeGrant(String sessionId, String toolName);

    /** Remove every grant held by a session (e.g. when the session ends); returns count removed. */
    int removeAllGrants(String sessionId);
}
