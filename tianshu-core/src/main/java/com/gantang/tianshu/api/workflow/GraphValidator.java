package com.gantang.tianshu.api.workflow;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Structural validation for a {@link StateGraph}. Runs at {@code build()} time so an
 * invalid graph fails fast before any execution. Package-private: only the graph
 * builder needs it.
 */
final class GraphValidator {

    void validate(StateGraph graph) {
        Map<String, NodeSpec> nodes = graph.nodes();
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("graph '" + graph.name() + "' has no nodes");
        }
        if (graph.maxSteps() <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }

        Set<String> ids = new HashSet<>(nodes.keySet());
        ids.add(StateGraph.START);
        ids.add(StateGraph.END);

        for (EdgeSpec e : graph.edges()) {
            if (!ids.contains(e.source())) {
                throw new IllegalArgumentException(
                    "edge source '" + e.source() + "' is not a node in graph '" + graph.name() + "'");
            }
            if (!ids.contains(e.target())) {
                throw new IllegalArgumentException(
                    "edge target '" + e.target() + "' is not a node in graph '" + graph.name() + "'");
            }
        }

        if (graph.outgoing(StateGraph.START).isEmpty()) {
            throw new IllegalArgumentException("graph '" + graph.name() + "' has no edge from start");
        }

        for (NodeSpec node : nodes.values()) {
            validateNode(graph, node);
        }
    }

    private void validateNode(StateGraph graph, NodeSpec node) {
        switch (node.kind()) {
            case TOOL -> require(node.tool() != null, node, "tool name is required");
            case SKILL -> require(node.skill() != null, node, "skill name is required");
            case DECISION -> {
                require(!node.routes().isEmpty(), node, "decision node needs at least one route");
                for (String target : node.routes().keySet()) {
                    if (!graph.nodes().containsKey(target)) {
                        throw new IllegalArgumentException("decision route target '" + target
                            + "' is not a node (node " + node.id() + ")");
                    }
                }
            }
            case PARALLEL -> {
                require(!node.branches().isEmpty(), node, "parallel node needs at least one branch");
                for (String b : node.branches()) {
                    if (!graph.nodes().containsKey(b)) {
                        throw new IllegalArgumentException("parallel branch '" + b
                            + "' is not a node (node " + node.id() + ")");
                    }
                }
            }
            case APPROVAL -> {
                require(node.approval() != null, node, "approval configuration is required");
                requireTarget(graph, node, node.approval().onApprove(), "onApprove");
                requireTarget(graph, node, node.approval().onReject(), "onReject");
            }
            default -> { /* AGENT / PASS / PAUSE / CUSTOM: no extra structural checks */ }
        }
    }

    private void requireTarget(StateGraph graph, NodeSpec node, String target, String field) {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException(
                "approval node " + node.id() + " requires " + field + " target");
        }
        if (!graph.nodes().containsKey(target) && !StateGraph.END.equals(target)) {
            throw new IllegalArgumentException(
                "approval node " + node.id() + " " + field + " target '" + target + "' is not a node");
        }
    }

    private static void require(boolean condition, NodeSpec node, String message) {
        if (!condition) {
            throw new IllegalArgumentException("node " + node.id() + ": " + message);
        }
    }
}
