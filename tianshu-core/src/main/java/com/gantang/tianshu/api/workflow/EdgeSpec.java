package com.gantang.tianshu.api.workflow;

/**
 * A directed edge between two nodes.
 *
 * <p>An edge is either <b>unconditional</b> ({@link #condition()} is blank, always
 * routes to {@link #target()}) or <b>conditional</b> ({@link #condition()} is an
 * Aviator expression evaluated against the current state's variables; the edge is
 * taken only when it evaluates to true). Multiple conditional edges may share a
 * source node and are evaluated in declaration order — the first match wins.
 *
 * @param source    source node id
 * @param target    destination node id
 * @param condition Aviator boolean expression, or blank for an unconditional edge
 */
public record EdgeSpec(String source, String target, String condition) {

    /** An unconditional edge. */
    public static EdgeSpec of(String source, String target) {
        return new EdgeSpec(source, target, null);
    }

    /** A conditional edge taken when {@code condition} evaluates to true. */
    public static EdgeSpec when(String source, String target, String condition) {
        return new EdgeSpec(source, target, condition);
    }

    public boolean isConditional() {
        return condition != null && !condition.isBlank();
    }
}
