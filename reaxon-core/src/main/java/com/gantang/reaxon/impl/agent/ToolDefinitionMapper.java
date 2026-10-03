package com.gantang.reaxon.impl.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.reaxon.api.tool.Tool;
import com.gantang.reaxon.api.tool.ToolRegistry;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Maps registered {@link Tool}s to the JSON Schema node list LLM clients expect.
 *
 * <p>Each node carries {@code name}, {@code description}, {@code properties}
 * and {@code required} (the shape {@code LangChain4jLlmClientAdapter} and the
 * eval replay client consume). Extracted from {@link ReactiveAgent} as a
 * stateless helper: it depends only on Jackson and the tool SPI.
 */
final class ToolDefinitionMapper {

    private final ObjectMapper om;

    ToolDefinitionMapper(ObjectMapper om) {
        this.om = om != null ? om : new ObjectMapper();
    }

    /** Build the non-hidden tools' schema list, in registry order. */
    List<JsonNode> definitions(ToolRegistry registry) {
        return registry.getAll().stream()
            .filter(t -> !t.hidden())
            .map(this::toNode)
            .collect(Collectors.toList());
    }

    JsonNode toArray(List<JsonNode> nodes) {
        return om.valueToTree(nodes);
    }

    private JsonNode toNode(Tool tool) {
        try {
            ObjectNode node = om.createObjectNode();
            node.put("name", tool.name());
            node.put("description", tool.description() != null ? tool.description() : "");
            node.set("properties", toProperties(tool.parameters()));
            node.set("required", toRequired(tool.parameters()));
            return node;
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert tool " + tool.name() + " to JSON Schema", e);
        }
    }

    private JsonNode toProperties(JsonNode params) {
        if (params == null) return om.createObjectNode();
        JsonNode props = params.get("properties");
        return props != null ? props.deepCopy() : om.createObjectNode();
    }

    private JsonNode toRequired(JsonNode params) {
        if (params == null) return om.createArrayNode();
        JsonNode req = params.get("required");
        return req != null ? req.deepCopy() : om.createArrayNode();
    }
}
