package com.gantang.tianshu.impl.workflow;

import com.gantang.tianshu.api.workflow.GraphState;

/**
 * Internal result of executing one node: the updated state plus how the engine should
 * proceed. Produced by the node handlers and consumed by {@code DefaultGraphRunner}.
 */
record NodeOutcome(GraphState state, Action action, String target, String reason) {

    enum Action {
        /** Continue to {@code target} (or, when target is null, resolve edges/END). */
        CONTINUE,
        /** Run suspended at {@code state}; a checkpoint was persisted. */
        PAUSE,
        /** The run failed; {@code reason} describes the error. */
        FAIL
    }

    /** Normal completion: proceed to {@code target} (null → resolve outgoing edges). */
    static NodeOutcome next(GraphState state, String target) {
        return new NodeOutcome(state, Action.CONTINUE, target, null);
    }

    /** Suspend the run (checkpoint already persisted). */
    static NodeOutcome pause(GraphState state, String reason) {
        return new NodeOutcome(state, Action.PAUSE, null, reason);
    }

    /** Fail the run. */
    static NodeOutcome fail(GraphState state, String reason) {
        return new NodeOutcome(state, Action.FAIL, null, reason);
    }
}
