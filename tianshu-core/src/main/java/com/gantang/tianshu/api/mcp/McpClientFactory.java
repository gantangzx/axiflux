package com.gantang.tianshu.api.mcp;

import java.util.List;

/**
 * Factory for creating {@link McpClient} instances by transport type.
 *
 * <p>Supported transports:
 * <ul>
 *   <li>{@code stdio} — spawns a local process communicating over stdin/stdout</li>
 *   <li>{@code sse} — connects to a remote MCP server over Server-Sent Events</li>
 * </ul>
 *
 * <p>Design pattern: <b>Factory Method</b> — encapsulates client creation
 * so callers don't depend on concrete transport implementations.
 */
public interface McpClientFactory {

    /**
     * Create an MCP client.
     *
     * @param config client configuration
     * @return a connected (but not yet initialized) client
     */
    McpClient create(McpClientConfig config);

    /**
     * Configuration for an MCP client connection.
     *
     * @param transport  "stdio" or "sse"
     * @param command    for stdio: the command to launch
     * @param args       for stdio: command arguments
     * @param url        for sse: the server URL
     * @param env        environment variables for stdio
     */
    record McpClientConfig(
        String transport,
        String command,
        List<String> args,
        String url,
        java.util.Map<String, String> env
    ) {
        public static McpClientConfig stdio(String command, List<String> args) {
            return new McpClientConfig("stdio", command, args, null, java.util.Map.of());
        }

        public static McpClientConfig sse(String url) {
            return new McpClientConfig("sse", null, List.of(), url, java.util.Map.of());
        }
    }
}
