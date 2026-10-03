package com.gantang.reaxon.api.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * Client for communicating with an MCP (Model Context Protocol) server.
 *
 * <p>MCP servers expose tools, resources, and prompts over stdio or SSE.
 * This interface abstracts the transport so callers don't care whether
 * the server is a local process or a remote HTTP endpoint.
 *
 * <p>Note: This is the <b>client-side</b> interface (connecting to external
 * MCP servers). For exposing Axiflux's own tools via MCP, see {@link McpEndpoint}.
 *
 * <p>Design patterns:
 * <ul>
 *   <li><b>Adapter</b> — adapts MCP server's JSON-RPC protocol to a clean Java API</li>
 *   <li><b>Factory</b> — {@link McpClientFactory} creates clients by transport</li>
 * </ul>
 */
public interface McpClient extends AutoCloseable {

    /**
     * Initialize the connection (handshake).
     * Must be called before other methods.
     */
    void initialize();

    /**
     * List tools available on the MCP server.
     */
    List<RemoteTool> listTools();

    /**
     * Call a tool on the MCP server.
     *
     * @param toolName   name of the tool to call
     * @param arguments  tool arguments
     * @return           result content
     */
    ToolCallResult callTool(String toolName, Map<String, Object> arguments);

    /**
     * Reactive tool call. Default bridges the blocking {@link #callTool} onto
     * boundedElastic; transports with non-blocking HTTP (SSE/streamable)
     * override this so the JSON-RPC round-trip holds no thread.
     */
    default Mono<ToolCallResult> callToolReactive(String toolName, Map<String, Object> arguments) {
        return Mono.fromCallable(() -> callTool(toolName, arguments))
            .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Reactive tool listing. Default bridges {@link #listTools} onto boundedElastic.
     */
    default Mono<List<RemoteTool>> listToolsReactive() {
        return Mono.fromCallable(this::listTools)
            .subscribeOn(Schedulers.boundedElastic());
    }

    /** Whether this client is connected and ready. */
    boolean isConnected();

    /** Server name reported during initialization. */
    String serverName();

    // ─── Data types ─────────────────────────────────────────────────────

    /**
     * Descriptor for a tool exposed by a remote MCP server.
     */
    record RemoteTool(
        String name,
        String description,
        JsonNode inputSchema
    ) {}

    /**
     * Result of an MCP tool call.
     *
     * @param isError whether the tool execution failed
     * @param text    concatenated text content from result blocks
     * @param blocks  structured content blocks
     */
    record ToolCallResult(
        boolean isError,
        String text,
        List<ContentBlock> blocks
    ) {
        public record ContentBlock(String type, String text) {}

        public static ToolCallResult text(String text) {
            return new ToolCallResult(false, text,
                List.of(new ContentBlock("text", text)));
        }

        public static ToolCallResult error(String text) {
            return new ToolCallResult(true, text,
                List.of(new ContentBlock("text", text)));
        }
    }
}
