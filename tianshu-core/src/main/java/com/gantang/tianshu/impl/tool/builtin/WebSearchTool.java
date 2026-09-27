package com.gantang.tianshu.impl.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.tool.Tool;
import com.gantang.tianshu.api.tool.ToolResult;
import com.gantang.tianshu.impl.tool.support.EgressGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Web search tool using DuckDuckGo HTML endpoint (no API key required).
 * Returns title + URL + snippet for each result.
 *
 * <p>Resilience features:
 * <ul>
 *   <li>Automatic retry on transient failures (max 2 retries)</li>
 *   <li>HTML structure change detection via result count validation</li>
 *   <li>Graceful degradation with clear error messages</li>
 * </ul>
 */
public class WebSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final ObjectMapper OM = new ObjectMapper();

    private static final JsonNode SCHEMA = SchemaSupport.parse("""
        {
          "type": "object",
          "properties": {
            "query": { "type": "string", "description": "Search query" },
            "maxResults": { "type": "integer", "description": "Number of results (default 5, max 10)" }
          },
          "required": ["query"]
        }
        """);

    private static final String DDG_HTML = "https://html.duckduckgo.com/html/?q=";
    private static final int MAX_RETRIES = 2;
    private static final int MAX_REDIRECTS = 5;
    private static final int MIN_EXPECTED_RESULTS = 1; // Alert if 0 results for common queries

    private final HttpClient client;

    public WebSearchTool() {
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // NEVER: follow redirects manually so each hop passes the SSRF guard.
            // With NORMAL a 302 from the search endpoint could silently reach a
            // private/link-local address (169.254.169.254, RFC1918) without any
            // egress validation.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @Override public String name()        { return "web_search"; }
    @Override public String description() { return "Search the web using DuckDuckGo. Returns titles, URLs, and snippets."; }
    @Override public JsonNode parameters(){ return SCHEMA; }
    @Override public String group()       { return "builtin"; }

    @Override
    public ToolResult execute(String callId, Map<String, Object> params, AgentContext context) {
        String query = String.valueOf(params.getOrDefault("query", ""));
        if (query.isBlank()) return ToolResult.failure(callId, "query is required");

        int maxResults = intOr(params, "maxResults", 5);
        maxResults = Math.max(1, Math.min(10, maxResults));

        // Retry logic for transient failures
        Exception lastException = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                if (attempt > 0) {
                    // Exponential backoff: 1s, 2s
                    Thread.sleep(1000L * attempt);
                    log.info("web_search retry attempt {} for query: {}", attempt, query);
                }

                String url = DDG_HTML + URLEncoder.encode(query, StandardCharsets.UTF_8);
                // SSRF guard: validate the request target before sending (the endpoint
                // is a fixed DDG host, but defence-in-depth keeps this consistent with
                // web_fetch and protects any future endpoint override).
                String initialDeny = EgressGuard.denyReason(url, false);
                if (initialDeny != null) {
                    return ToolResult.failure(callId, "Search blocked by SSRF guard: " + initialDeny);
                }

                // Follow redirects manually, re-validating every hop so a 302 cannot
                // pivot to a private/internal address.
                String currentUrl = url;
                HttpResponse<String> resp = null;
                for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(currentUrl))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "Mozilla/5.0 (compatible; Tianshu/1.0)")
                        .GET()
                        .build();

                    resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                    String location = resp.headers().firstValue("Location").orElse(null);
                    boolean isRedirect = resp.statusCode() >= 300 && resp.statusCode() < 400
                        && location != null && resp.statusCode() != 304;
                    if (!isRedirect) break;
                    if (hop == MAX_REDIRECTS) {
                        return ToolResult.failure(callId, "Too many redirects (>" + MAX_REDIRECTS + ")");
                    }
                    URI next = URI.create(currentUrl).resolve(location);
                    String deny = EgressGuard.denyReason(next.toString(), false);
                    if (deny != null) {
                        return ToolResult.failure(callId,
                            "Redirect target blocked by SSRF guard (" + location + " -> " + next + "): " + deny);
                    }
                    currentUrl = next.toString();
                }

                // Retry on 5xx server errors
                if (resp.statusCode() >= 500 && attempt < MAX_RETRIES) {
                    log.warn("web_search got HTTP {}, will retry", resp.statusCode());
                    continue;
                }

                if (resp.statusCode() >= 400) {
                    return ToolResult.failure(callId, "Search returned HTTP " + resp.statusCode());
                }

                List<SearchResult> results = parseDdgHtml(resp.body(), maxResults);

                // HTML structure change detection: if we got 0 results but HTML is non-empty,
                // it might indicate a structure change (DDG changed their HTML layout)
                if (results.isEmpty()) {
                    String body = resp.body();
                    if (body != null && !body.isEmpty() && body.contains("No results")) {
                        // Legitimate "no results" from DDG
                        return ToolResult.success(callId, "No results found for: " + query);
                    }
                    // Potential HTML structure change - warn in logs
                    if (body != null && body.length() > 1000 && !body.contains("result__a")) {
                        log.error("web_search: DuckDuckGo HTML structure may have changed! " +
                            "Missing expected 'result__a' class. HTML length: {}", body.length());
                        return ToolResult.failure(callId,
                            "Search service returned unexpected format. HTML structure may have changed.");
                    }
                    return ToolResult.success(callId, "No results found for: " + query);
                }

                StringBuilder sb = new StringBuilder("Search results for: ").append(query).append("\n\n");
                for (int i = 0; i < results.size(); i++) {
                    SearchResult r = results.get(i);
                    sb.append(i + 1).append(". ").append(r.title).append('\n');
                    sb.append("   URL: ").append(r.url).append('\n');
                    if (r.snippet != null && !r.snippet.isBlank()) {
                        sb.append("   ").append(r.snippet, 0, Math.min(r.snippet.length(), 200)).append('\n');
                    }
                    sb.append('\n');
                }

                Map<String, Object> meta = new java.util.HashMap<>();
                meta.put("count", results.size());
                meta.put("retries", attempt);
                return ToolResult.success(callId, sb.toString(), meta);

            } catch (Exception e) {
                lastException = e;
                log.warn("web_search attempt {} failed: {}", attempt, e.getMessage());
                // Don't retry on client errors (4xx) or non-retryable exceptions
                if (e instanceof java.net.SocketTimeoutException && attempt < MAX_RETRIES) {
                    continue; // Retry on timeout
                }
                if (e instanceof java.net.ConnectException && attempt < MAX_RETRIES) {
                    continue; // Retry on connection refused
                }
                // For other exceptions, fail fast
                break;
            }
        }

        String errorMsg = lastException != null
            ? "Search error after " + (MAX_RETRIES + 1) + " attempts: " + lastException.getClass().getSimpleName() + ": " + lastException.getMessage()
            : "Search failed after retries";
        log.error("web_search ultimately failed for query '{}': {}", query, errorMsg);
        return ToolResult.failure(callId, errorMsg);
    }

    /**
     * Parse DuckDuckGo HTML results. Each result is in a <div class="result"> block.
     * Links have class "result__a" (title + url), snippets have class "result__snippet".
     */
    static List<SearchResult> parseDdgHtml(String html, int max) {
        List<SearchResult> results = new ArrayList<>();
        if (html == null || html.isEmpty()) return results;

        int idx = 0;
        while (results.size() < max) {
            // Find result link
            int linkStart = html.indexOf("class=\"result__a\"", idx);
            if (linkStart < 0) linkStart = html.indexOf("class='result__a'", idx);
            if (linkStart < 0) break;

            int hrefStart = html.indexOf("href=\"", linkStart);
            if (hrefStart < 0) break;
            hrefStart += 6;
            int hrefEnd = html.indexOf('"', hrefStart);
            if (hrefEnd < 0) break;
            String rawUrl = html.substring(hrefStart, hrefEnd);

            // DDG wraps URLs in a redirect; extract actual URL from uddg= param
            String url = extractDdgUrl(rawUrl);

            // Title text between > and </a>
            int tagEnd = html.indexOf('>', linkStart);
            int tagClose = html.indexOf("</a>", tagEnd);
            if (tagEnd < 0 || tagClose < 0) { idx = linkStart + 10; continue; }
            String title = html.substring(tagEnd + 1, tagClose)
                .replaceAll("<[^>]+>", "")
                .trim();

            // Snippet
            String snippet = null;
            int snipStart = html.indexOf("class=\"result__snippet\"", tagClose);
            if (snipStart > 0) {
                int snipTagEnd = html.indexOf('>', snipStart);
                int snipClose = html.indexOf("</a>", snipTagEnd);
                if (snipClose < 0) snipClose = html.indexOf("</div>", snipTagEnd);
                if (snipTagEnd > 0 && snipClose > snipTagEnd) {
                    snippet = html.substring(snipTagEnd + 1, snipClose)
                        .replaceAll("<[^>]+>", "")
                        .trim();
                }
            }

            if (!title.isEmpty() && !url.isEmpty()) {
                results.add(new SearchResult(title, url, snippet));
            }
            idx = tagClose + 4;
        }
        return results;
    }

    private static String extractDdgUrl(String raw) {
        // DDG redirect format: //duckduckgo.com/l/?uddg=<encoded>&...
        int uddg = raw.indexOf("uddg=");
        if (uddg >= 0) {
            int start = uddg + 5;
            int end = raw.indexOf('&', start);
            String encoded = end > start ? raw.substring(start, end) : raw.substring(start);
            return java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        }
        // If protocol-relative, add https:
        if (raw.startsWith("//")) return "https:" + raw;
        return raw;
    }

    private static int intOr(Map<String, Object> p, String key, int fallback) {
        Object v = p.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    record SearchResult(String title, String url, String snippet) {}
}
