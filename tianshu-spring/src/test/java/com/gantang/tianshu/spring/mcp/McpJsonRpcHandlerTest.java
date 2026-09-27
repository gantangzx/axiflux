package com.gantang.tianshu.spring.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.tianshu.api.mcp.McpEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpJsonRpcHandlerTest {

    private ObjectMapper om;
    private McpEndpoint endpoint;
    private McpJsonRpcHandler handler;

    @BeforeEach
    void setUp() {
        om = new ObjectMapper();
        endpoint = mock(McpEndpoint.class);
        handler = new McpJsonRpcHandler(om, endpoint);
    }

    private JsonNode rpc(String method, ObjectNode params, int id) {
        ObjectNode req = om.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        if (params != null) req.set("params", params);
        req.put("id", id);
        return handler.handle(req);
    }

    @Test
    void initializeReturnsProtocolAndCapabilities() {
        JsonNode resp = rpc("initialize", null, 1);
        assertEquals("2.0", resp.path("jsonrpc").asText());
        assertEquals(1, resp.path("id").asInt());
        JsonNode result = resp.path("result");
        assertEquals(McpJsonRpcHandler.PROTOCOL_VERSION, result.path("protocolVersion").asText());
        assertTrue(result.path("capabilities").has("tools"));
        assertEquals("tianshu-agent", result.path("serverInfo").path("name").asText());
    }

    @Test
    void pingReturnsEmptyResult() {
        JsonNode resp = rpc("ping", null, 2);
        assertTrue(resp.path("result").isObject());
        assertEquals(0, resp.path("result").size());
    }

    @Test
    void toolsListExposesTools() {
        ObjectNode schema = om.createObjectNode().put("type", "object");
        when(endpoint.listTools()).thenReturn(List.of(
            new McpEndpoint.McpTool("calc", "calculator", schema)));
        JsonNode resp = rpc("tools/list", null, 3);
        JsonNode tools = resp.path("result").path("tools");
        assertEquals(1, tools.size());
        assertEquals("calc", tools.get(0).path("name").asText());
        assertEquals("calculator", tools.get(0).path("description").asText());
        assertTrue(tools.get(0).has("inputSchema"));
    }

    @Test
    void toolsCallSuccessReturnsTextContent() {
        ObjectNode content = om.createObjectNode();
        content.put("type", "text");
        content.put("text", "42");
        when(endpoint.callTool(eq("calc"), any())).thenReturn(
            new McpEndpoint.McpCallResult(true, content, null));
        ObjectNode params = om.createObjectNode();
        params.put("name", "calc");
        params.set("arguments", om.createObjectNode().put("expr", "6*7"));
        JsonNode resp = rpc("tools/call", params, 4);
        JsonNode result = resp.path("result");
        assertFalse(result.path("isError").asBoolean());
        assertEquals("42", result.path("content").get(0).path("text").asText());
    }

    @Test
    void toolsCallFailureMarksError() {
        when(endpoint.callTool(eq("boom"), any())).thenReturn(
            new McpEndpoint.McpCallResult(false, null, "not found"));
        ObjectNode params = om.createObjectNode();
        params.put("name", "boom");
        JsonNode resp = rpc("tools/call", params, 5);
        assertTrue(resp.path("result").path("isError").asBoolean());
        assertEquals("not found", resp.path("result").path("content").get(0).path("text").asText());
    }

    @Test
    void toolsCallThreadsAuthenticatedCaller() {
        ObjectNode content = om.createObjectNode().put("type", "text").put("text", "ok");
        when(endpoint.callTool(eq("calc"), any(), any(com.gantang.tianshu.api.agent.AgentContext.class)))
            .thenReturn(new McpEndpoint.McpCallResult(true, content, null));
        com.gantang.tianshu.api.agent.AgentContext caller = com.gantang.tianshu.api.agent.AgentContext.builder()
            .userId("alice").sessionId("default-alice")
            .metadata(java.util.Map.of(com.gantang.tianshu.api.auth.CallerIdentity.META_SCOPES,
                java.util.Set.of("tool:net")))
            .build();
        ObjectNode params = om.createObjectNode();
        params.put("name", "calc");
        params.set("arguments", om.createObjectNode().put("expr", "1+1"));
        ObjectNode req = om.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", "tools/call");
        req.set("params", params);
        req.put("id", 7);

        JsonNode resp = handler.handle(req, caller);

        assertFalse(resp.path("result").path("isError").asBoolean());
        var captor = org.mockito.ArgumentCaptor.forClass(com.gantang.tianshu.api.agent.AgentContext.class);
        verify(endpoint).callTool(eq("calc"), any(), captor.capture());
        assertEquals("alice", captor.getValue().userId());
        assertEquals("default-alice", captor.getValue().sessionId());
    }

    @Test
    void unknownMethodReturnsMethodNotFound() {
        JsonNode resp = rpc("does/not/exist", null, 6);
        assertEquals(-32601, resp.path("error").path("code").asInt());
    }

    @Test
    void notificationHasNoResponse() {
        ObjectNode req = om.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", "notifications/initialized");
        assertNull(handler.handle(req));
    }

    @Test
    void batchProcessesAllRequests() {
        ObjectNode a = om.createObjectNode().put("jsonrpc", "2.0").put("method", "ping").put("id", 1);
        ObjectNode b = om.createObjectNode().put("jsonrpc", "2.0").put("method", "ping").put("id", 2);
        JsonNode batch = om.createArrayNode().add(a).add(b);
        JsonNode resp = handler.handle(batch);
        assertTrue(resp.isArray());
        assertEquals(2, resp.size());
    }

    @Test
    void missingToolNameIsInvalidParams() {
        ObjectNode params = om.createObjectNode();
        JsonNode resp = rpc("tools/call", params, 7);
        assertEquals(-32602, resp.path("error").path("code").asInt());
    }
}
