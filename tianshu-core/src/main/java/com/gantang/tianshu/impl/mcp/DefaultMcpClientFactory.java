package com.gantang.tianshu.impl.mcp;

import com.gantang.tianshu.api.mcp.McpClient;
import com.gantang.tianshu.api.mcp.McpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link McpClientFactory} implementation.
 *
 * <p>Caches clients by server key so repeated calls reuse the same
 * subprocess. Clients are initialized lazily.
 *
 * <p>Design pattern: <b>Singleton per key</b> via internal cache + <b>Factory Method</b>.
 */
public class DefaultMcpClientFactory implements McpClientFactory {

    private static final Logger log = LoggerFactory.getLogger(DefaultMcpClientFactory.class);

    private final Map<String, McpClient> clientCache = new ConcurrentHashMap<>();

    /**
     * Whether SSE endpoints on private/loopback addresses are permitted. Defaults
     * to false: an SSE URL comes from config or a tool argument, and without a
     * guard a prompt-injection could point it at a cloud-metadata endpoint
     * (169.254.169.254) or an internal service for SSRF. Set true only when MCP
     * servers legitimately live on the local network.
     */
    private final boolean allowPrivateNetwork;

    /** Optional egress proxy for SSE transports (P1-5); null = direct. */
    private final java.net.ProxySelector egressProxy;

    public DefaultMcpClientFactory() {
        this(false);
    }

    public DefaultMcpClientFactory(boolean allowPrivateNetwork) {
        this(allowPrivateNetwork, null);
    }

    public DefaultMcpClientFactory(boolean allowPrivateNetwork, java.net.ProxySelector egressProxy) {
        this.allowPrivateNetwork = allowPrivateNetwork;
        this.egressProxy = egressProxy;
    }

    @Override
    public McpClient create(McpClientConfig config) {
        String key = cacheKey(config);

        return clientCache.computeIfAbsent(key, k -> {
            McpClient client = switch (config.transport().toLowerCase()) {
                case "stdio" -> new StdioMcpClient(
                    config.command(), config.args(), config.env());
                case "sse" -> new SseMcpClient(requireAllowedSseUrl(config.url()),
                    SseMcpClient.DEFAULT_IDLE_TIMEOUT_MS, egressProxy);
                default -> throw new IllegalArgumentException(
                    "Unsupported MCP transport: " + config.transport());
            };
            log.info("Created MCP client for {} (transport={})", key, config.transport());
            return client;
        });
    }

    /**
     * SSRF guard for SSE endpoints: block non-http(s) schemes and private/loopback/
     * link-local/cloud-metadata targets unless private networking was explicitly
     * enabled. Returns the URL unchanged when allowed; throws otherwise.
     */
    private String requireAllowedSseUrl(String url) {
        String reason = com.gantang.tianshu.impl.tool.support.EgressGuard.denyReason(url, allowPrivateNetwork);
        if (reason != null) {
            throw new IllegalArgumentException("MCP SSE endpoint blocked: " + reason);
        }
        return url;
    }

    public void shutdown() {
        clientCache.values().forEach(c -> {
            try { c.close(); } catch (Exception ignored) {}
        });
        clientCache.clear();
    }

    private String cacheKey(McpClientConfig config) {
        if ("stdio".equals(config.transport())) {
            return "stdio:" + config.command() + " " + String.join(" ", config.args());
        }
        return config.transport() + ":" + config.url();
    }
}
