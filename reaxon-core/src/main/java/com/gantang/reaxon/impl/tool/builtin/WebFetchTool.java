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
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Fetch a web page and return its text content (HTML stripped).
 * Truncated to maxBytes to protect LLM context.
 * <p>
 * Security: automatic redirects are disabled and followed manually (max
 * {@value #MAX_REDIRECTS} hops), with each hop re-validated by
 * {@link EgressGuard} to prevent SSRF via 302 redirects to private addresses.
 */
public class WebFetchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);
    private static final int MAX_REDIRECTS = 5;

    private static final JsonNode SCHEMA = SchemaSupport.parse("""
        {
          "type": "object",
          "properties": {
            "url": { "type": "string", "description": "Absolute URL to fetch (http/https)" },
            "maxBytes": { "type": "integer", "description": "Max response bytes after stripping HTML (default 8192)" },
            "timeoutSeconds": { "type": "integer", "description": "Request timeout (default 15)" }
          },
          "required": ["url"]
        }
        """);

    private static final Pattern SCRIPT_STYLE = Pattern.compile(
        "(?is)<(script|style|noscript|iframe)[^>]*>.*?</\\1>");
    private static final Pattern TAG_STRIP = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern MULTI_NEWLINE = Pattern.compile("\\n{3,}");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t]{2,}");

    private final HttpClient client;
    private final boolean allowPrivateNetwork;
    private volatile LiveSettings live;

    /** Wire live settings so the private-network egress flag applies without restart. */
    public WebFetchTool withLiveSettings(LiveSettings live) { this.live = live; return this; }

    private boolean allowPrivate() {
        LiveSettings ls = this.live;
        return ls != null ? ls.snapshot().allowPrivateNetwork() : allowPrivateNetwork;
    }

    public WebFetchTool() {
        this(false);
    }

    public WebFetchTool(boolean allowPrivateNetwork) {
        this(allowPrivateNetwork, null);
    }

    /**
     * @param proxy optional forward/egress proxy through which all requests are
     *              routed (per-hop SSRF guard still applies).
     */
    public WebFetchTool(boolean allowPrivateNetwork, java.net.ProxySelector proxy) {
        this.allowPrivateNetwork = allowPrivateNetwork;
        HttpClient.Builder b = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // NEVER: follow redirects manually so each hop passes the SSRF guard.
            .followRedirects(HttpClient.Redirect.NEVER);
        if (proxy != null) {
            b.proxy(proxy);
        }
        this.client = b.build();
    }

    @Override public String name()        { return "web_fetch"; }
    @Override public String description() { return "Fetch a URL and return the page content as plain text (HTML stripped)."; }
    @Override public JsonNode parameters(){ return SCHEMA; }
    @Override public String group()       { return "builtin"; }
    @Override public RiskLevel riskLevel() { return RiskLevel.NETWORK; }

    @Override
    public ToolResult execute(String callId, Map<String, Object> params, AgentContext context) {
        String url = String.valueOf(params.getOrDefault("url", ""));
        if (url.isBlank()) return ToolResult.failure(callId, "url is required");
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.failure(callId, "url must start with http:// or https://");
        }
        String initialDeny = EgressGuard.denyReason(url, allowPrivate());
        if (initialDeny != null) {
            return ToolResult.failure(callId, "Fetch blocked by SSRF guard: " + initialDeny);
        }

        int maxBytes = intOr(params, "maxBytes", 8192);
        int timeoutSec = intOr(params, "timeoutSeconds", 15);

        try {
            String currentUrl = url;
            HttpResponse<String> resp = null;

            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                HttpRequest req = HttpRequest.newBuilder(URI.create(currentUrl))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .header("User-Agent", "Axiflux/1.0 (WebFetchTool)")
                    .GET()
                    .build();

                resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

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
            }

            String raw = resp.body() != null ? resp.body() : "";

            String text = htmlToText(raw);
            boolean truncated = text.length() > maxBytes;
            if (truncated) {
                text = text.substring(0, maxBytes) + "\n... [truncated " + (text.length() - maxBytes) + " chars]";
            }

            String content = "HTTP " + resp.statusCode() + " " + currentUrl + "\n\n" + text;
            return resp.statusCode() >= 400
                ? ToolResult.failure(callId, content)
                : ToolResult.success(callId, content, Map.of(
                    "status", resp.statusCode(),
                    "bytes", raw.length(),
                    "textBytes", text.length(),
                    "truncated", truncated,
                    "finalUrl", currentUrl));
        } catch (Exception e) {
            log.warn("web_fetch failed: {}", e.getMessage());
            return ToolResult.failure(callId, "Fetch error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static String htmlToText(String html) {
        if (html == null || html.isEmpty()) return "";
        String s = SCRIPT_STYLE.matcher(html).replaceAll("");
        s = TAG_STRIP.matcher(s).replaceAll("\n");
        s = MULTI_SPACE.matcher(s).replaceAll(" ");
        s = MULTI_NEWLINE.matcher(s).replaceAll("\n\n");
        return s.trim();
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
