package com.gantang.tianshu.impl.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolResult;
import com.gantang.tianshu.impl.tool.DefaultToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct unit test for {@link ToolDefinitionMapper}: hidden tools are excluded,
 * schema fields map through, and tools with null / partial parameters degrade
 * to empty properties/required rather than failing.
 */
class ToolDefinitionMapperDirectTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private Tool tool(String name, boolean hidden, JsonNode parameters) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " tool"; }
            @Override public boolean hidden() { return hidden; }
            @Override public JsonNode parameters() { return parameters; }
            @Override public ToolResult execute(String callId, Map<String, Object> params, AgentContext ctx) {
                return ToolResult.success(callId, "ok");
            }
        };
    }

    @Test
    void mapsVisibleTools_andSkipsHidden_andHandlesNullParams() {
        ObjectNode params = OM.createObjectNode();
        ObjectNode props = OM.createObjectNode();
        ObjectNode textProp = OM.createObjectNode();
        textProp.put("type", "string");
        props.set("q", textProp);
        params.set("properties", props);
        var required = OM.createArrayNode();
        required.add("q");
        params.set("required", required);

        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(tool("search", false, params));
        registry.register(tool("secret", true, params));
        registry.register(tool("noparams", false, null));

        ToolDefinitionMapper mapper = new ToolDefinitionMapper(OM);
        List<JsonNode> defs = mapper.definitions(registry);

        assertEquals(2, defs.size(), "hidden tool must be excluded");
        JsonNode search = defs.get(0);
        assertEquals("search", search.path("name").asText());
        assertEquals("search tool", search.path("description").asText());
        assertEquals("string", search.path("properties").path("q").path("type").asText());
        assertEquals(1, search.path("required").size());
        assertEquals("q", search.path("required").get(0).asText());

        JsonNode noParams = defs.get(1);
        assertEquals("noparams", noParams.path("name").asText());
        assertTrue(noParams.path("properties").isObject(), "null parameters degrade to empty object");
        assertEquals(0, noParams.path("properties").size());
        assertTrue(noParams.path("required").isArray(), "missing required degrades to empty array");
        assertEquals(0, noParams.path("required").size());
    }

    @Test
    void toArray_wrapsNodeList() {
        ToolDefinitionMapper mapper = new ToolDefinitionMapper(OM);
        ObjectNode n = OM.createObjectNode();
        n.put("name", "x");
        JsonNode arr = mapper.toArray(List.of(n));
        assertTrue(arr.isArray());
        assertEquals(1, arr.size());
        assertEquals("x", arr.get(0).path("name").asText());
    }
}
