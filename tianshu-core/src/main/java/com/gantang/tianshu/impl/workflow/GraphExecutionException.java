package com.gantang.tianshu.impl.workflow;

/**
 * Raised when a graph run cannot continue: step-budget exceeded, a node failed, no
 * handler for a node kind, or an invalid graph structure encountered at runtime.
 */
public class GraphExecutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GraphExecutionException(String message) {
        super(message);
    }

    public GraphExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
