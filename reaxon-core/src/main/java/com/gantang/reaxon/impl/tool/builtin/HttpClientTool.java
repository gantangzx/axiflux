package com.gantang.reaxon.impl.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.config.LiveSettings;
import com.gantang.reaxon.api.tool.Tool;
import com.gantang.reaxon.api.tool.ToolResult;
import com.gantang.reaxon.api.tool.policy.RiskLevel;
import com.gantang.reaxon.impl.tool.support.EgressGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Built-in HTTP client tool.
 * Supports GET/POST/PUT/DELETE, custom headers, JSON body.
 * <p>
 * Response body is truncated to 8 KB by default to protect the LLM context.
 * Failure returns HTTP status + first 512 bytes of body.
 * <p>
 * Security: automatic redirects are disabled and 3xx responses are followed
 * manually (max {@value #MAX_REDIRECTS} hops). Every hop — including the
 * redirect target — is re-validated by {@link EgressGuard}, so a public URL
 * cannot 302-bounce the request into a private/loopback/metadata address.
 */
public class HttpClientTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(HttpClientTool.class);
    private static final int MAX_REDIRECTS = 5;

    private static final JsonNode SCHEMA = SchemaSupport.parse("""
        {
          "type": "object",
          "properties": {
            "method":  { "type": "string", "enum": ["GET", "POST", "PUT", "DELETE", "PATCH"], "description": "HTTP method" },
            "url":     { "type": "string", "description": "Absolute URL" },
            "headers": { "type": "object", "description": "Optional request headers" },
            "body":    { "type": "string", "description": "Raw request body (JSON string or plain text)" },
            "timeoutSeconds": { "type": "integer", "description": "Request timeout (default 30)" },
            "maxResponseBytes": { "type": "integer", "description": "Truncate response body to N bytes (default 8192)" }
          },
          "required": ["method", "url"]
        }
        """);

    private final HttpClient client;
    private final int defaultMaxBytes;
    private final boolean allowPrivateNetwork;
    private volatile LiveSettings live;

    /** Wire live settings so the private-network egress flag applies without restart. */
    public HttpClientTool withLiveSettings(LiveSettings live) { this.live = live; return this; }

    private boolean allowPrivate() {
        LiveSettings ls = this.live;
        return ls != null ? ls.snapshot().allowPrivateNetwork() : allowPrivateNetwork;
    }

    public HttpClientTool() {
        this(8192, false);
    }

    public HttpClientTool(int defaultMaxBytes) {
        this(defaultMaxBytes, false);
    }

    public HttpClientTool(int defaultMaxBytes, boolean allowPrivateNetwork) {
        this(defaultMaxBytes, allowPrivateNetwork, null);
    }

    /**
     * @param proxy optional forward/egress proxy (e.g. an allowlisting proxy in
     *              a locked-down deployment). All requests — including manual
     *              redirect hops — are routed through it; the per-hop
     *              {@link EgressGuard} still runs.
     */
    public HttpClientTool(int defaultMaxBytes, boolean allowPrivateNetwork, java.net.ProxySelector proxy) {
        HttpClient.Builder b = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // NEVER: we follow redirects manually so each hop passes the SSRF guard.
            .followRedirects(HttpClient.Redirect.NEVER);
        if (proxy != null) {
            b.proxy(proxy);
        }
        this.client = b.build();
        this.defaultMaxBytes = defaultMaxBytes;
        this.allowPrivateNetwork = allowPrivateNetwork;
    }

    @Override public String name()        { return "http_client"; }
    @Override public String description() { return "Send an HTTP request (GET/POST/PUT/DELETE/PATCH) and return the response."; }
    @Override public JsonNode parameters(){ return SCHEMA; }
    @Override public String group()       { return "builtin"; }
    @Override public RiskLevel riskLevel() { return RiskLevel.NETWORK; }

    @Override
    public ToolResult execute(String callId, Map<String, Object> params, AgentContext context) {
        String method = String.valueOf(params.getOrDefault("method", "GET")).toUpperCase();
        String url = String.valueOf(params.getOrDefault("url", ""));
        if (url.isBlank()) return ToolResult.failure(callId, "url is required");

        String initialDeny = EgressGuard.denyReason(url, allowPrivate());
        if (initialDeny != null) {
            return ToolResult.failure(callId, "Request blocked by SSRF guard: " + initialDeny);
        }

        int timeoutSec = intOr(params, "timeoutSeconds", 30);
        int maxBytes = intOr(params, "maxResponseBytes", defaultMaxBytes);

        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) params.getOrDefault("headers", Map.of());
        String body = params.get("body") != null ? String.valueOf(params.get("body")) : "";

        try {
            String currentUrl = url;
            String currentMethod = method;
            boolean hasBody = !body.isEmpty();
            HttpResponse<byte[]> resp = null;

            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(currentUrl))
                    .timeout(Duration.ofSeconds(timeoutSec));
                headers.forEach((k, v) -> rb.header(k, String.valueOf(v)));

                HttpRequest.BodyPublisher publisher = hasBody
                    ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                    : HttpRequest.BodyPublishers.noBody();

                switch (currentMethod) {
                    case "GET"    -> rb.GET();
                    case "POST"   -> rb.POST(publisher);
                    case "PUT"    -> rb.PUT(publisher);
                    case "DELETE" -> rb.DELETE();
                    case "PATCH"  -> rb.method("PATCH", publisher);
                    default -> {
                        return ToolResult.failure(callId, "Unsupported method: " + currentMethod);
                    }
                }

                resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());

                String location = resp.headers().firstValue("Location").orElse(null);
                boolean isRedirect = resp.statusCode() >= 300 && resp.statusCode() < 400
                    && location != null && resp.statusCode() != 304;
                if (!isRedirect) {
                    break;
                }
                if (hop == MAX_REDIRECTS) {
                    return ToolResult.failure(callId,
                        "Too many redirects (>" + MAX_REDIRECTS + ") for " + url);
                }
                URI next = URI.create(currentUrl).resolve(location);
                String deny = EgressGuard.denyReason(next.toString(), allowPrivate());
                if (deny != null) {
                    return ToolResult.failure(callId,
                        "Redirect target blocked by SSRF guard (" + location + " -> " + next + "): " + deny);
                }
                currentUrl = next.toString();
                // 307/308 preserve method and body; 301/302/303 downgrade to GET per common client behaviour.
                if (resp.statusCode() != 307 && resp.statusCode() != 308) {
                    currentMethod = "GET";
                    hasBody = false;
                }
            }

            byte[] raw = resp.body() != null ? resp.body() : new byte[0];
            boolean truncated = raw.length > maxBytes;
            String bodyStr = new String(
                truncated ? java.util.Arrays.copyOf(raw, maxBytes) : raw,
                StandardCharsets.UTF_8
            );
            if (truncated) bodyStr += "\n... [truncated " + (raw.length - maxBytes) + " bytes]";

            Map<String, Object> meta = new HashMap<>();
            meta.put("status", resp.statusCode());
            meta.put("headers", resp.headers().map());
            meta.put("bytes", raw.length);
            meta.put("truncated", truncated);
            meta.put("finalUrl", currentUrl);

            String contentText = "HTTP " + resp.statusCode() + " " + currentMethod + " " + currentUrl + "\n\n" + bodyStr;
            return resp.statusCode() >= 400
                ? ToolResult.failure(callId, contentText, meta)
                : ToolResult.success(callId, contentText, meta);
        } catch (Exception e) {
            log.warn("http_client failed: {}", e.getMessage());
            return ToolResult.failure(callId, "HTTP error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static int intOr(Map<String, Object> p, String key, int fallback) {
        Object v = p.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }
}
