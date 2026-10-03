package com.gantang.reaxon.api.agent;

/**
 * Agent execution status for a given session.
 */
public record AgentStatus(
    String sessionId,
    State state,
    int iterationCount,
    String currentToolName,
    long startedAtMs
) {
    public enum State {
        IDLE,
        /** Turn accepted and waiting in the per-session queue for an earlier turn to finish. */
        QUEUED,
        RUNNING,
        WAITING_APPROVAL,
        INTERRUPTED
    }
}
