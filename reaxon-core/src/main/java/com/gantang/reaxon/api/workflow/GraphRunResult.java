package com.gantang.reaxon.api.workflow;

/**
 * Terminal outcome of a {@link StateGraph} run.
 *
 * @param runId  the run instance id
 * @param status how the run ended
 * @param state  the final state (or the state at a pause)
 */
public record GraphRunResult(String runId, Status status, GraphState state) {

    public enum Status {
        COMPLETED,
        PAUSED,
        FAILED
    }

    public String output() {
        Object v = state.var(GraphState.LAST_OUTPUT);
        return v == null ? null : v.toString();
    }
}
