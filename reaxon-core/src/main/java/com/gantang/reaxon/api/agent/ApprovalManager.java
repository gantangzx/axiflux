package com.gantang.reaxon.api.agent;

import com.gantang.reaxon.api.tool.ToolResult;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Manages human-in-the-loop approval for tools marked {@code requiresApproval()}.
 *
 * <p>When an agent encounters such a tool, it calls {@link #submit(ApprovalRequest)}
 * which returns a {@link Mono} that completes when a human approves or rejects.
 * External systems (REST API, WebSocket UI) call {@link #approve} or
 * {@link #reject} to resolve pending requests.
 *
 * <p>Design pattern: <b>Strategy</b> — the storage and notification mechanism
 * is pluggable (in-memory default, Redis-backed in production).
 */
public interface ApprovalManager {

    /**
     * Submit an approval request and await resolution.
     * The returned Mono completes when a human responds or the request times out.
     */
    Mono<ToolResult> submit(ApprovalRequest request);

    /** Approve a pending request. Returns true if the request existed. */
    boolean approve(String callId, String approverId);

    /** Reject a pending request with a reason. Returns true if the request existed. */
    boolean reject(String callId, String approverId, String reason);

    /**
     * True when the given tool has been pre-authorized for the rest of the
     * session ("approve for this session"), so no interactive prompt is needed.
     * Default implementation has no session memory.
     */
    default boolean isSessionGranted(String sessionId, String toolName) {
        return false;
    }

    /**
     * Remember a session-scoped grant: future calls to {@code toolName} within
     * {@code sessionId} skip the interactive approval. Default is a no-op.
     */
    default void grantSession(String sessionId, String toolName, String approverId) {}

    /** Revoke a session-scoped grant. Returns true if a grant existed. */
    default boolean revokeSessionGrant(String sessionId, String toolName) {
        return false;
    }

    /** Get a pending request by call ID. */
    Optional<ApprovalRequest> get(String callId);

    /** List all pending requests (optionally filtered by userId). */
    List<ApprovalRequest> listPending(String userId);

    /**
     * An approval request awaiting human decision.
     */
    record ApprovalRequest(
        String callId,
        String sessionId,
        String userId,
        String toolName,
        String toolDescription,
        String argumentsSummary,   // human-readable summary of arguments
        Instant createdAt,
        Instant expiresAt          // auto-reject after this time
    ) {}
}
