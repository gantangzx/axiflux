package com.gantang.tianshu.spring.service;

import com.gantang.tianshu.api.workflow.StateGraph;
import com.gantang.tianshu.impl.workflow.GraphDefinitions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Spring-managed catalog of discovered {@link StateGraph} definitions.
 *
 * <p>Backed by an ordered map keyed by graph name. Every graph registered here is
 * also published to the core static {@link GraphDefinitions} registry so a paused
 * run can be resolved by run id within the process.
 */
public final class GraphCatalog {

    private final Map<String, StateGraph> graphs = new TreeMap<>();

    /** Add or replace a graph definition. */
    public void register(StateGraph graph) {
        graphs.put(graph.name(), graph);
        GraphDefinitions.register(graph);
    }

    public Optional<StateGraph> find(String name) {
        return Optional.ofNullable(graphs.get(name));
    }

    /** All registered graphs in name order. */
    public List<StateGraph> all() {
        return new ArrayList<>(graphs.values());
    }

    public int size() {
        return graphs.size();
    }
}
