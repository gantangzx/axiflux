package com.gantang.reaxon.api.workflow;

/**
 * Observability event emitted while a {@link StateGraph} runs.
 *
 * @param type   event type
 * @param nodeId the node this event relates to (null for graph-level events)
 * @param message human-readable detail
 */
public record GraphEvent(Type type, String nodeId, String message) {

    public enum Type {
        STARTED,
        NODE_START,
        NODE_END,
        PAUSED,
        RESUMED,
        APPROVAL_REQUESTED,
        APPROVAL_RESOLVED,
        COMPLETED,
        ERROR
    }

    public static GraphEvent started() {
        return new GraphEvent(Type.STARTED, null, "graph started");
    }

    public static GraphEvent nodeStart(String nodeId) {
        return new GraphEvent(Type.NODE_START, nodeId, null);
    }

    public static GraphEvent nodeEnd(String nodeId, String output) {
        return new GraphEvent(Type.NODE_END, nodeId, output);
    }

    public static GraphEvent paused(String nodeId, String waitFor) {
        return new GraphEvent(Type.PAUSED, nodeId, waitFor);
    }

    public static GraphEvent completed() {
        return new GraphEvent(Type.COMPLETED, null, "graph completed");
    }

    public static GraphEvent error(String message) {
        return new GraphEvent(Type.ERROR, null, message);
    }
}
