package com.gantang.reaxon.impl.tool.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.ProxySelector;

/**
 * Shared egress forward-proxy parsing ({@code host:port}) for every outbound
 * {@link java.net.http.HttpClient} the runtime builds — HTTP tools, embedding,
 * skill-registry / ClawHub installs and MCP SSE (P1-5). Previously the proxy was
 * only honoured by {@code http_client}/{@code web_fetch}, leaving LLM-adjacent
 * and supply-chain traffic to connect directly even in locked-down deployments
 * that require all egress to transit an audited proxy.
 *
 * <p>The parser fails fast on a malformed value rather than silently sending
 * traffic direct, and returns {@code null} when unconfigured so callers can keep
 * a direct connection as the default.
 */
public final class EgressProxy {

    private static final Logger log = LoggerFactory.getLogger(EgressProxy.class);

    private EgressProxy() {
    }

    /**
     * Parse a {@code host:port} egress proxy into a {@link ProxySelector}, or
     * return {@code null} when {@code raw} is null/blank.
     *
     * @throws IllegalArgumentException on a malformed host/port (fail fast)
     */
    public static ProxySelector parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        int colon = trimmed.lastIndexOf(':');
        if (colon <= 0 || colon == trimmed.length() - 1) {
            throw new IllegalArgumentException(
                "egress proxy must be 'host:port', got: " + raw);
        }
        String host = trimmed.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(trimmed.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "egress proxy port is not a number: " + raw);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                "egress proxy port out of range: " + port);
        }
        log.info("Outbound HTTP routing through egress proxy {}:{}", host, port);
        // createUnresolved: do not DNS-resolve the proxy at startup (offline dev /
        // late DNS); the HttpClient resolves it when the first request goes out.
        return ProxySelector.of(InetSocketAddress.createUnresolved(host, port));
    }

    /**
     * Apply {@code proxy} to the builder when non-null; returns the same builder
     * for fluent use. Null proxy = direct connection (unchanged default).
     */
    public static java.net.http.HttpClient.Builder applyTo(
            java.net.http.HttpClient.Builder builder, ProxySelector proxy) {
        if (proxy != null) {
            builder.proxy(proxy);
        }
        return builder;
    }
}
