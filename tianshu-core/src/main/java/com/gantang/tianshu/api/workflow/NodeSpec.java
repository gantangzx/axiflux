package com.gantang.tianshu.api.workflow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Declarative description of one node in a {@link StateGraph}.
 *
 * <p>Only the fields relevant to the node's {@link NodeKind} are populated; e.g.
 * a {@link NodeKind#TOOL} node uses {@link #tool()}, a {@link NodeKind#DECISION}
 * node uses {@link #routes()}, a {@link NodeKind#PARALLEL} node uses
 * {@link #branches()}.
 *
 * @param id         unique node id within the graph
 * @param kind       node type
 * @param label      optional human label (defaults to {@code id})
 * @param query      for AGENT/SKILL: the instruction/input (template with {@code ${var}})
 * @param systemPrompt optional system prompt override for AGENT/DECISION (template)
 * @param outputVar  optional variable name to store this node's textual output in
 * @param tool       for TOOL: the registered tool name
 * @param params     for TOOL: static parameter map (templates resolved against state)
 * @param skill      for SKILL: the registered skill name
 * @param routes     for DECISION: route id → human description of when to take it
 * @param branches   for PARALLEL: node ids to execute concurrently
 * @param waitFor    for PAUSE: a key describing what is being awaited (informational)
 * @param approval   for APPROVAL: the approval configuration
 * @param metadata   free-form node metadata (not interpreted by the engine)
 */
public record NodeSpec(
    String id,
    NodeKind kind,
    String label,
    String query,
    String systemPrompt,
    String outputVar,
    String tool,
    Map<String, Object> params,
    String skill,
    Map<String, String> routes,
    List<String> branches,
    String waitFor,
    ApprovalSpec approval,
    Map<String, Object> metadata
) {

    public NodeSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("node id is required");
        }
        if (kind == null) {
            throw new IllegalArgumentException("node kind is required for node " + id);
        }
        params = params == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        routes = routes == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(routes));
        branches = branches == null ? List.of()
            : Collections.unmodifiableList(List.copyOf(branches));
        metadata = metadata == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /** Create a builder for a node of the given id and kind. */
    public static Builder builder(String id, NodeKind kind) {
        return new Builder(id, kind);
    }

    public static final class Builder {
        private final String id;
        private final NodeKind kind;
        private String label;
        private String query;
        private String systemPrompt;
        private String outputVar;
        private String tool;
        private Map<String, Object> params;
        private String skill;
        private Map<String, String> routes;
        private List<String> branches;
        private String waitFor;
        private ApprovalSpec approval;
        private Map<String, Object> metadata;

        private Builder(String id, NodeKind kind) {
            this.id = id;
            this.kind = kind;
        }

        public Builder label(String v) { this.label = v; return this; }
        public Builder query(String v) { this.query = v; return this; }
        public Builder systemPrompt(String v) { this.systemPrompt = v; return this; }
        public Builder outputVar(String v) { this.outputVar = v; return this; }
        public Builder tool(String v) { this.tool = v; return this; }
        public Builder params(Map<String, Object> v) { this.params = v; return this; }
        public Builder skill(String v) { this.skill = v; return this; }
        public Builder routes(Map<String, String> v) { this.routes = v; return this; }
        public Builder branches(List<String> v) { this.branches = v; return this; }
        public Builder waitFor(String v) { this.waitFor = v; return this; }
        public Builder approval(ApprovalSpec v) { this.approval = v; return this; }
        public Builder metadata(Map<String, Object> v) { this.metadata = v; return this; }

        public NodeSpec build() {
            return new NodeSpec(id, kind, label, query, systemPrompt, outputVar,
                tool, params, skill, routes, branches, waitFor, approval, metadata);
        }
    }
}
