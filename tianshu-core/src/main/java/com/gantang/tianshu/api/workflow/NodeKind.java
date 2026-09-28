package com.gantang.tianshu.api.workflow;

/**
 * The built-in node types a {@link StateGraph} can declare.
 *
 * <p>The {@link #CUSTOM} kind marks a node supplied programmatically as a
 * {@link GraphNode} (it cannot be expressed in a YAML definition).
 */
public enum NodeKind {
    /** Run one full (possibly tool-calling) agent turn. */
    AGENT,
    /** Invoke a single registered tool by name. */
    TOOL,
    /** Execute a registered skill. */
    SKILL,
    /** Ask an LLM to choose the next node from a fixed set of routes. */
    DECISION,
    /** Run several nodes concurrently and merge their outputs. */
    PARALLEL,
    /** Suspend the run until an external caller resumes it. */
    PAUSE,
    /** Submit a human-approval request and continue on approve / branch on reject. */
    APPROVAL,
    /** No-op that passes state unchanged (useful as a junction). */
    PASS,
    /** A programmatically supplied {@link GraphNode}. */
    CUSTOM
}
