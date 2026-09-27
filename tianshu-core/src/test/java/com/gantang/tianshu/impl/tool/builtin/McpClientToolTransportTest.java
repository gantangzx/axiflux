package com.gantang.tianshu.impl.tool.builtin;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.mcp.McpClient;
import com.gantang.tianshu.api.mcp.McpClientFactory;
import com.gantang.tianshu.api.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-2 (audit-2026-09-14): McpClientTool must expose a {@code transport} param so
 * the agent can reach remote MCP servers over SSE instead of only spawning local
 * stdio subprocesses. Backward-compatible: no transport => stdio.
 */
class McpClientToolTransportTest {

    private final AgentContext ctx = AgentContext.builder()
        .sessionId("t").userId("u").currentQuery("").build();

    /** Records the config it was asked to create, then returns a scripted client. */
    static final class RecordingFactory implements McpClientFactory {
        McpClientFactory.McpClientConfig lastConfig;
        private final McpClient client;
        RecordingFactory(McpClient client) { this.client = client; }
        @Override public McpClient create(McpClientFactory.McpClientConfig config) {
            this.lastConfig = config;
            return client;
        }
    }

    static final class FakeClient implements McpClient {
        @Override public void initialize() {}
        @Override public List<RemoteTool> listTools() { return List.of(); }
        @Override public ToolCallResult callTool(String toolName, Map<String, Object> arguments) {
            return McpClient.ToolCallResult.text("ok");
        }
        @Override public boolean isConnected() { return true; }
        @Override public String serverName() { return "fake"; }
        @Override public void close() {}
    }

    @Test
    void noTransport_defaultsToStdio_andBuildsStdioConfig() {
        RecordingFactory factory = new RecordingFactory(new FakeClient());
        ToolResult r = new McpClientTool(factory).execute("t1",
            Map.of("action", "list", "command", "npx"), ctx);
        assertTrue(r.success(), r.errorMessage());
        assertEquals("stdio", factory.lastConfig.transport());
        assertEquals("npx", factory.lastConfig.command());
    }

    @Test
    void explicitStdio_requiresAllowedCommand() {
        RecordingFactory factory = new RecordingFactory(new FakeClient());
        ToolResult r = new McpClientTool(factory).execute("t2",
            Map.of("action", "list", "transport", "stdio", "command", "bash"), ctx);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("not an allowed MCP launcher"), r.errorMessage());
        assertNull(factory.lastConfig, "refused command must not reach the factory");
    }

    @Test
    void sse_buildsSseConfig_fromUrl() {
        RecordingFactory factory = new RecordingFactory(new FakeClient());
        ToolResult r = new McpClientTool(factory).execute("t3",
            Map.of("action", "list", "transport", "sse", "url", "http://localhost:3001/sse"), ctx);
        assertTrue(r.success(), r.errorMessage());
        assertEquals("sse", factory.lastConfig.transport());
        assertEquals("http://localhost:3001/sse", factory.lastConfig.url());
        assertNull(factory.lastConfig.command(), "sse config carries no command");
    }

    @Test
    void sse_requiresUrl() {
        RecordingFactory factory = new RecordingFactory(new FakeClient());
        ToolResult r = new McpClientTool(factory).execute("t4",
            Map.of("action", "list", "transport", "sse"), ctx);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("url is required"), r.errorMessage());
        assertNull(factory.lastConfig);
    }

    @Test
    void sse_rejectsNonHttpUrl() {
        RecordingFactory factory = new RecordingFactory(new FakeClient());
        ToolResult r = new McpClientTool(factory).execute("t5",
            Map.of("action", "list", "transport", "sse", "url", "file:///etc/passwd"), ctx);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("http://"), r.errorMessage());
        assertNull(factory.lastConfig);
    }

    @Test
    void unknownTransport_isRejected() {
        RecordingFactory factory = new RecordingFactory(new FakeClient());
        ToolResult r = new McpClientTool(factory).execute("t6",
            Map.of("action", "list", "transport", "grpc", "url", "http://x"), ctx);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("Unknown transport"), r.errorMessage());
        assertNull(factory.lastConfig);
    }

    @Test
    void reactivePath_sse_buildsSseConfig() {
        RecordingFactory factory = new RecordingFactory(new FakeClient());
        ToolResult r = new McpClientTool(factory).executeReactive("t7",
                Map.of("action", "list", "transport", "sse", "url", "https://mcp.example.com/sse"), ctx)
            .block();
        assertNotNull(r);
        assertTrue(r.success(), r.errorMessage());
        assertEquals("sse", factory.lastConfig.transport());
        assertEquals("https://mcp.example.com/sse", factory.lastConfig.url());
    }
}
