package com.gantang.tianshu.api.workflow;

import com.gantang.tianshu.api.agent.AgentContext;

/**
 * Read-only context handed to a {@link GraphNode}: the node's own spec plus the
 * caller-supplied base {@link AgentContext} (session/user identity, metadata, BYOK).
 */
public interface GraphNodeContext {

    /** The spec of the node currently executing. */
    NodeSpec spec();

    /** The base agent context for this run (never {@code null}). */
    AgentContext baseContext();
}
