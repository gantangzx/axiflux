package com.gantang.tianshu.spring.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.api.mcp.McpEndpoint;
import com.gantang.tianshu.spring.auth.CallerGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * McpController bridges the MCP protocol to local tools. The security
 * invariant is that every tool call is pinned to the verified caller identity
 * (via CallerGuard) so a body-supplied userId cannot override the header, and
 * the policy chain evaluates the real principal.
 */
class McpControllerTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private McpEndpoint endpoint;
    private CallerGuard guard;
    private McpController controller;

    @BeforeEach
    void setUp() {
        endpoint = mock(McpEndpoint.class);
        guard = mock(CallerGuard.class);
        controller = new McpController(endpoint, guard);
    }

    // ===== listTools =====

    @Test
    void listToolsReturnsAllTools() {
        JsonNode schema = OM.createObjectNode().put("type", "object");
        when(endpoint.listTools()).thenReturn(List.of(
            new McpEndpoint.McpTool("file_read", "Read a file", schema),
            new McpEndpoint.McpTool("file_write", "Write a file", schema)
        ));

        StepVerifier.create(controller.listTools())
            .assertNext(resp -> {
                assertTrue(resp.success());
                @SuppressWarnings("unchecked")
                var tools = (List<Map<String, Object>>) resp.data().get("tools");
                assertEquals(2, tools.size());
                assertEquals("file_read", tools.get(0).get("name"));
            })
            .verifyComplete();
    }

    @Test
    void listToolsEmptyReturnsEmptyList() {
        when(endpoint.listTools()).thenReturn(List.of());

        StepVerifier.create(controller.listTools())
            .assertNext(resp -> {
                @SuppressWarnings("unchecked")
                var tools = (List<Map<String, Object>>) resp.data().get("tools");
                assertEquals(0, tools.size());
            })
            .verifyComplete();
    }

    // ===== callTool =====

    @Test
    void callToolHappyPathReturnsResult() {
        when(guard.context("alice", null, null, null))
            .thenReturn(new CallerGuard.Caller("alice", "default-alice", null));
        JsonNode content = OM.createObjectNode().put("text", "file contents");
        when(endpoint.callTool(eq("file_read"), any(JsonNode.class), any()))
            .thenReturn(new McpEndpoint.McpCallResult(true, content, null));

        JsonNode body = OM.createObjectNode()
            .put("name", "file_read")
            .set("arguments", OM.createObjectNode().put("path", "/tmp/x"));

        StepVerifier.create(controller.callTool(body, "alice", null))
            .assertNext(resp -> {
                assertTrue(resp.success());
                assertEquals(true, resp.data().get("success"));
            })
            .verifyComplete();
    }

    @Test
    void callToolMissingNameReturns400() {
        JsonNode body = OM.createObjectNode().set("arguments", OM.createObjectNode());

        StepVerifier.create(controller.callTool(body, "alice", null))
            .expectErrorSatisfies(e -> {
                assertInstanceOf(ResponseStatusException.class, e);
                assertEquals(400, ((ResponseStatusException) e).getStatusCode().value());
            })
            .verify();
    }

    @Test
    void callToolBlankNameReturns400() {
        JsonNode body = OM.createObjectNode().put("name", "  ");

        StepVerifier.create(controller.callTool(body, "alice", null))
            .expectError(ResponseStatusException.class)
            .verify();
    }

    @Test
    void callToolErrorResultIsReturned() {
        when(guard.context("alice", null, null, null))
            .thenReturn(new CallerGuard.Caller("alice", "default-alice", null));
        when(endpoint.callTool(eq("shell"), any(JsonNode.class), any()))
            .thenReturn(new McpEndpoint.McpCallResult(false, null, "permission denied"));

        JsonNode body = OM.createObjectNode()
            .put("name", "shell")
            .set("arguments", OM.createObjectNode());

        StepVerifier.create(controller.callTool(body, "alice", null))
            .assertNext(resp -> {
                assertEquals(false, resp.data().get("success"));
                assertEquals("permission denied", resp.data().get("error"));
            })
            .verifyComplete();
    }

    @Test
    void callToolPinsCallerToVerifiedHeader() {
        when(guard.context("alice", "tool:fs", null, null))
            .thenReturn(new CallerGuard.Caller("alice", "default-alice", "tool:fs"));
        when(endpoint.callTool(eq("file_read"), any(JsonNode.class), any()))
            .thenReturn(new McpEndpoint.McpCallResult(true, OM.createObjectNode(), null));

        JsonNode body = OM.createObjectNode()
            .put("name", "file_read")
            .set("arguments", OM.createObjectNode());

        StepVerifier.create(controller.callTool(body, "alice", "tool:fs"))
            .assertNext(resp -> assertTrue(resp.success()))
            .verifyComplete();
        // Guard must be called with the verified header, not any body-supplied identity
        verify(guard).context("alice", "tool:fs", null, null);
    }
}
