package com.gantang.tianshu.impl.workflow;

import com.gantang.tianshu.api.workflow.ApprovalSpec;
import com.gantang.tianshu.api.workflow.NodeKind;
import com.gantang.tianshu.api.workflow.NodeSpec;
import com.gantang.tianshu.api.workflow.StateGraph;
import org.yaml.snakeyaml.Yaml;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses a {@link StateGraph} from YAML (or from a Markdown document whose first
 * YAML front-matter block holds the definition), using SnakeYAML — the same YAML
 * stack as {@code MarkdownSkillLoader}.
 *
 * <p>Expected shape:
 * <pre>{@code
 * name: triage
 * maxSteps: 40
 * nodes:
 *   - id: classify
 *     type: decision
 *     routes: { escalate: "needs a human", answer: "can be auto-answered" }
 *   - id: answer
 *     type: agent
 *     query: "Answer ${input}"
 *   - id: escalate
 *     type: tool
 *     tool: open_ticket
 * edges:
 *   - { from: __start__, to: classify }
 *   - { from: answer, to: __end__ }
 *   - { from: escalate, when: "count > 3", to: answer }
 * }</pre>
 */
public final class YamlStateGraphLoader {

    private static final String FRONT_FENCE = "---";

    /** Parse a graph from a raw YAML string. */
    public StateGraph load(String yaml) {
        Object root = new Yaml().load(yaml);
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("graph YAML must be a mapping at the top level");
        }
        return parse(asMap(map));
    }

    /** Parse a graph from YAML, or from Markdown containing a YAML front-matter block. */
    public StateGraph loadDocument(String document) {
        String yaml = extractFrontMatter(document);
        return load(yaml);
    }

    private StateGraph parse(Map<String, Object> root) {
        StateGraph.Builder builder = StateGraph.builder()
            .name(asString(root.get("name"), "graph"));

        Object maxSteps = root.get("maxSteps");
        if (maxSteps instanceof Number n) {
            builder.maxSteps(n.intValue());
        }

        for (NodeSpec node : parseNodes(asList(root.get("nodes")))) {
            builder.addNode(node);
        }
        for (com.gantang.tianshu.api.workflow.EdgeSpec edge : parseEdges(asList(root.get("edges")))) {
            builder.edge(edge);
        }
        return builder.build();
    }

    private List<NodeSpec> parseNodes(List<Object> rawNodes) {
        List<NodeSpec> nodes = new ArrayList<>();
        for (Object item : rawNodes) {
            Map<String, Object> m = asMap(item);
            nodes.add(parseNode(m));
        }
        return nodes;
    }

    private NodeSpec parseNode(Map<String, Object> m) {
        String id = requireString(m, "id");
        NodeKind kind = parseKind(asString(m.get("type"), "agent"));
        NodeSpec.Builder b = NodeSpec.builder(id, kind)
            .label(asString(m.get("label"), null))
            .query(asString(m.get("query"), null))
            .systemPrompt(asString(m.get("systemPrompt"), null))
            .outputVar(asString(m.get("outputVar"), null))
            .tool(asString(m.get("tool"), null))
            .skill(asString(m.get("skill"), null))
            .waitFor(asString(m.get("waitFor"), null));

        Object params = m.get("params");
        if (params instanceof Map<?, ?> p) {
            b.params(toStringObjectMap(p));
        }
        Object routes = m.get("routes");
        if (routes instanceof Map<?, ?> r) {
            b.routes(toStringStringMap(r));
        }
        Object branches = m.get("branches");
        if (branches instanceof List<?> l) {
            b.branches(toStringList(l));
        }
        Object approval = m.get("approval");
        if (approval instanceof Map<?, ?> a) {
            b.approval(parseApproval(asMap(a)));
        }
        Object metadata = m.get("metadata");
        if (metadata instanceof Map<?, ?> md) {
            b.metadata(toStringObjectMap(md));
        }
        return b.build();
    }

    private ApprovalSpec parseApproval(Map<String, Object> m) {
        ApprovalSpec.Builder b = ApprovalSpec.builder()
            .subject(asString(m.get("subject"), "approval"))
            .description(asString(m.get("description"), ""))
            .onApprove(asString(m.get("onApprove"), null))
            .onReject(asString(m.get("onReject"), null));
        Object timeout = m.get("timeoutSeconds");
        if (timeout instanceof Number n) {
            b.timeout(Duration.ofSeconds(n.longValue()));
        }
        return b.build();
    }

    private List<com.gantang.tianshu.api.workflow.EdgeSpec> parseEdges(List<Object> rawEdges) {
        List<com.gantang.tianshu.api.workflow.EdgeSpec> edges = new ArrayList<>();
        for (Object item : rawEdges) {
            Map<String, Object> m = asMap(item);
            String from = requireString(m, "from");
            String to = requireString(m, "to");
            String when = asString(m.get("when"), null);
            edges.add(when == null || when.isBlank()
                ? com.gantang.tianshu.api.workflow.EdgeSpec.of(from, to)
                : com.gantang.tianshu.api.workflow.EdgeSpec.when(from, to, when));
        }
        return edges;
    }

    // === front-matter handling ===

    private String extractFrontMatter(String document) {
        if (document == null) {
            throw new IllegalArgumentException("empty graph document");
        }
        String trimmed = document.stripLeading();
        if (!trimmed.startsWith(FRONT_FENCE)) {
            return document; // treat the whole thing as YAML
        }
        int firstNl = trimmed.indexOf('\n');
        if (firstNl < 0) {
            throw new IllegalArgumentException("malformed front-matter fence");
        }
        int closing = trimmed.indexOf(FRONT_FENCE, firstNl + 1);
        if (closing < 0) {
            throw new IllegalArgumentException("unterminated front-matter fence");
        }
        return trimmed.substring(firstNl + 1, closing).strip();
    }

    // === coercion helpers (SnakeYAML yields untyped maps/lists) ===

    private NodeKind parseKind(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "agent" -> NodeKind.AGENT;
            case "tool" -> NodeKind.TOOL;
            case "skill" -> NodeKind.SKILL;
            case "decision", "router" -> NodeKind.DECISION;
            case "parallel", "fanout", "fan_out" -> NodeKind.PARALLEL;
            case "pause", "wait" -> NodeKind.PAUSE;
            case "approval", "human" -> NodeKind.APPROVAL;
            case "pass", "noop", "no_op" -> NodeKind.PASS;
            default -> throw new IllegalArgumentException("unknown node type: " + raw);
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException("expected a mapping but got: " + o);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        if (o == null) {
            return List.of();
        }
        if (o instanceof List<?> l) {
            return (List<Object>) l;
        }
        throw new IllegalArgumentException("expected a list but got: " + o);
    }

    private static Map<String, Object> toStringObjectMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static Map<String, String> toStringStringMap(Map<?, ?> m) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()),
                e.getValue() == null ? "" : String.valueOf(e.getValue()));
        }
        return out;
    }

    private static List<String> toStringList(List<?> l) {
        List<String> out = new ArrayList<>();
        for (Object o : l) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    private static String asString(Object o, String fallback) {
        return o == null ? fallback : String.valueOf(o);
    }

    private static String requireString(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("missing required field '" + key + "' in: " + m);
        }
        return String.valueOf(v);
    }
}
