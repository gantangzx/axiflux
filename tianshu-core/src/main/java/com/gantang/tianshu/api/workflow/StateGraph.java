package com.gantang.tianshu.api.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A declarative, directed state graph: a set of {@link NodeSpec nodes} connected by
 * {@link EdgeSpec edges}, executed by a {@code GraphRunner}.
 *
 * <p>Graphs are built via {@link #builder()} (programmatically) or parsed from YAML by
 * a loader. Two reserved node ids act as terminals: {@link #START} and {@link #END}.
 * Routing after a node is resolved from its outgoing edges; a node with no matching /
 * no outgoing edge terminates the run (equivalent to routing to {@link #END}).
 */
public final class StateGraph {

    /** Reserved entry node. */
    public static final String START = "__start__";
    /** Reserved terminal node. */
    public static final String END = "__end__";

    private final String name;
    private final Map<String, NodeSpec> nodes;
    private final List<EdgeSpec> edges;
    private final int maxSteps;

    private StateGraph(Builder b) {
        this.name = b.name;
        Map<String, Object> ignore;
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(b.nodes));
        this.edges = Collections.unmodifiableList(new ArrayList<>(b.edges));
        this.maxSteps = b.maxSteps;
    }

    public String name() {
        return name;
    }

    public Map<String, NodeSpec> nodes() {
        return nodes;
    }

    public List<EdgeSpec> edges() {
        return edges;
    }

    /** Max node executions before the run is failed with a step-budget error. */
    public int maxSteps() {
        return maxSteps;
    }

    public Optional<NodeSpec> node(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    /** All edges leaving {@code nodeId} in declaration order. */
    public List<EdgeSpec> outgoing(String nodeId) {
        List<EdgeSpec> out = new ArrayList<>();
        for (EdgeSpec e : edges) {
            if (e.source().equals(nodeId)) {
                out.add(e);
            }
        }
        return out;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name = "graph";
        private final Map<String, NodeSpec> nodes = new LinkedHashMap<>();
        private final List<EdgeSpec> edges = new ArrayList<>();
        private int maxSteps = 50;

        private Builder() {}

        public Builder name(String v) { this.name = v; return this; }

        /** Cap on total node executions for a single run. */
        public Builder maxSteps(int v) { this.maxSteps = v; return this; }

        public Builder addNode(NodeSpec node) {
            if (nodes.putIfAbsent(node.id(), node) != null) {
                throw new IllegalArgumentException("duplicate node id: " + node.id());
            }
            return this;
        }

        public Builder edge(EdgeSpec edge) {
            edges.add(edge);
            return this;
        }

        /** Add an unconditional edge {@code source -> target}. */
        public Builder edge(String source, String target) {
            return edge(EdgeSpec.of(source, target));
        }

        /** Add a conditional edge. */
        public Builder edgeWhen(String source, String target, String condition) {
            return edge(EdgeSpec.when(source, target, condition));
        }

        public StateGraph build() {
            StateGraph g = new StateGraph(this);
            new GraphValidator().validate(g);
            return g;
        }
    }
}
