package com.gantang.tianshu.impl.workflow;

import com.gantang.tianshu.api.workflow.StateGraph;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local registry of {@link StateGraph} definitions by name.
 *
 * <p>Used to resolve a graph when resuming a paused run by run id alone. This covers
 * the in-core single-process experience (including the open-source zero-dependency
 * deployment); a clustered/durable resume is provided by an enterprise implementation
 * that persists definitions rather than relying on this map.
 */
public final class GraphDefinitions {

    private static final ConcurrentHashMap<String, StateGraph> BY_NAME = new ConcurrentHashMap<>();

    private GraphDefinitions() {}

    /** Register a definition so runs of it can be resumed by name in this process. */
    public static void register(StateGraph graph) {
        BY_NAME.put(graph.name(), graph);
    }

    /** Remove a registered definition. */
    public static void unregister(String name) {
        BY_NAME.remove(name);
    }

    static Optional<StateGraph> from(String name) {
        return Optional.ofNullable(BY_NAME.get(name));
    }
}
