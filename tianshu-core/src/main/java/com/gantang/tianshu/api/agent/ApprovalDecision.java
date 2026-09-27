package com.gantang.tianshu.api.agent;

/**
 * The outcome of a human's decision on a pending tool approval.
 *
 * <p>Published through the {@code ApprovalStore} decision stream so that the
 * agent instance waiting on the request (which may be a different instance
 * in a multi-node deployment) is woken up regardless of which node received
 * the approve/reject call.
 *
 * @param callId     the tool-call ID the decision resolves
 * @param approved   true for approve, false for reject
 * @param approverId who made the decision (for audit)
 * @param message    human-readable result message; rejection reason on reject
 */
public record ApprovalDecision(
    String callId,
    boolean approved,
    String approverId,
    String message
) {
    public static ApprovalDecision approve(String callId, String approverId) {
        return new ApprovalDecision(callId, true, approverId,
            "Approved by " + (approverId != null ? approverId : "unknown"));
    }

    public static ApprovalDecision reject(String callId, String approverId, String message) {
        return new ApprovalDecision(callId, false, approverId, message);
    }
}
