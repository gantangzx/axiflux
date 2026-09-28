package com.gantang.tianshu.api.workflow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable state threaded through every node of a {@link StateGraph} run.
 *
 * <p>A state carries three things:
 * <ul>
 *   <li>{@link #variables()} — cross-node working variables (the graph's "blackboard"),
 *       including the initial input and whatever each node writes;</li>
 *   <li>{@link #outputs()} — the last result of every executed node keyed by node id;</li>
 *   <li>{@link #nodeVisits()} — how many times each node has run (used to detect cycles
 *       and enforce the step budget).</li>
 * </ul>
 *
 * <p>Instances are immutable; nodes produce a new state via the {@code with*} helpers
 * rather than mutating the previous one.
 */
public record GraphState(
    Map<String, Object> variables,
    Map<String, Object> outputs,
    Map<String, Integer> nodeVisits
) {

    /** Standard variable holding the graph's initial input. */
    public static final String INPUT = "input";
    /** Standard variable holding the most recent node's textual output. */
    public static final String LAST_OUTPUT = "lastOutput";

    public GraphState {
        variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
        nodeVisits = Collections.unmodifiableMap(new LinkedHashMap<>(nodeVisits));
    }

    /** A fresh state seeded with a single {@link #INPUT} value. */
    public static GraphState ofInput(Object input) {
        Map<String, Object> vars = new LinkedHashMap<>();
        if (input != null) {
            vars.put(INPUT, input);
        }
        return new GraphState(vars, Map.of(), Map.of());
    }

    /** Read a variable; returns {@code null} when absent. */
    public Object var(String key) {
        return variables.get(key);
    }

    /** Read a variable as text; {@code null} when absent. */
    public String varString(String key) {
        Object v = variables.get(key);
        return v == null ? null : v.toString();
    }

    /** Return a copy with one variable added/overwritten. */
    public GraphState withVariable(String key, Object value) {
        Map<String, Object> vars = new LinkedHashMap<>(variables);
        if (value == null) {
            vars.remove(key);
        } else {
            vars.put(key, value);
        }
        return new GraphState(vars, outputs, nodeVisits);
    }

    /** Return a copy merging all the supplied variables. */
    public GraphState withVariables(Map<String, Object> additions) {
        Map<String, Object> vars = new LinkedHashMap<>(variables);
        if (additions != null) {
            vars.putAll(additions);
        }
        return new GraphState(vars, outputs, nodeVisits);
    }

    /** Return a copy recording a node's output and bumping its visit counter. */
    public GraphState withNodeOutput(String nodeId, Object output) {
        Map<String, Object> outs = new LinkedHashMap<>(outputs);
        outs.put(nodeId, output);

        Map<String, Integer> visits = new LinkedHashMap<>(nodeVisits);
        visits.merge(nodeId, 1, Integer::sum);

        Map<String, Object> vars = new LinkedHashMap<>(variables);
        if (output != null) {
            vars.put(LAST_OUTPUT, output);
        }
        return new GraphState(vars, outs, visits);
    }
}
