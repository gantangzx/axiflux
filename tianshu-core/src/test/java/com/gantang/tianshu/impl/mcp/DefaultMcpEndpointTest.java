package com.gantang.tianshu.impl.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.mcp.McpEndpoint;
import com.gantang.tianshu.api.tool.*;
import com.gantang.tianshu.impl.tool.DefaultToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultMcpEndpointTest {

    private DefaultToolRegistry registry;
    private DefaultMcpEndpoint endpoint;
    private static final ObjectMapper OM = new ObjectMapper();

    @BeforeEach
    void setUp() {
        registry = new DefaultToolRegistry();
        endpoint = new DefaultMcpEndpoint(registry);
    }

    @Test
    void listTools_returnsAllNonHiddenTools() {
        registry.register(new EchoTool("echo", false));
        registry.register(new EchoTool("secret", true));

        List<McpEndpoint.McpTool> tools = endpoint.listTools();

        assertEquals(1, tools.size());
        assertEquals("echo", tools.get(0).name());
        assertTrue(tools.get(0).description().contains("Echo"));
    }

    @Test
    void callTool_existingTool_returnsSuccess() {
        registry.register(new EchoTool("echo", false));
        ObjectNode args = OM.createObjectNode();
        args.put("text", "hello");

        McpEndpoint.McpCallResult result = endpoint.callTool("echo", args);

        assertTrue(result.success());
        assertNotNull(result.content());
        assertEquals("Echo: hello", result.content().path("text").asText());
        assertNull(result.error());
    }

    @Test
    void callTool_unknownTool_returnsError() {
        McpEndpoint.McpCallResult result = endpoint.callTool("nonexistent", OM.createObjectNode());

        assertFalse(result.success());
        assertTrue(result.error().contains("not found"));
    }

    @Test
    void callTool_approvalRequired_returnsError() {
        // Register a tool that requires approval
        Tool approvalTool = new Tool() {
            @Override public String name() { return "dangerous"; }
            @Override public String description() { return "Dangerous op"; }
            @Override public JsonNode parameters() { return OM.createObjectNode(); }
            @Override public boolean requiresApproval() { return true; }
            @Override public ToolResult execute(String callId, Map<String, Object> params, AgentContext ctx) {
                return ToolResult.success(callId, "should not reach here");
            }
        };
        registry.register(approvalTool);

        McpEndpoint.McpCallResult result = endpoint.callTool("dangerous", OM.createObjectNode());

        assertFalse(result.success());
        assertTrue(result.error().contains("approval"));
    }

    @Test
    void listResources_returnsEmptyByDefault() {
        assertTrue(endpoint.listResources().isEmpty());
    }

    @Test
    void listPrompts_returnsEmptyByDefault() {
        assertTrue(endpoint.listPrompts().isEmpty());
    }

    // ─── Test tool ────────────────────────────────────────────────────

    private static class EchoTool implements Tool {
        private final String name;
        private final boolean hidden;

        EchoTool(String name, boolean hidden) {
            this.name = name;
            this.hidden = hidden;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "Echo tool: " + name; }
        @Override public JsonNode parameters() {
            ObjectNode schema = OM.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = OM.createObjectNode();
            ObjectNode textProp = OM.createObjectNode();
            textProp.put("type", "string");
            props.set("text", textProp);
            schema.set("properties", props);
            return schema;
        }
        @Override public boolean hidden() { return hidden; }
        @Override
        public ToolResult execute(String callId, Map<String, Object> params, AgentContext ctx) {
            String text = String.valueOf(params.getOrDefault("text", ""));
            return ToolResult.success(callId, "Echo: " + text);
        }
    }
}
