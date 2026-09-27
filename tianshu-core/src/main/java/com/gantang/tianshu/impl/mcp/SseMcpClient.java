package com.gantang.tianshu.impl.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.tianshu.api.mcp.McpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;


import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP client using HTTP+SSE transport.
 *
 * <p>Protocol flow:
 * <ol>
 *   <li>Open GET to the SSE URL; server responds with {@code text/event-stream}</li>
 *   <li>First event is {@code endpoint} containing the POST URL for JSON-RPC</li>
 *   <li>Subsequent events are {@code message} containing JSON-RPC responses</li>
 *   <li>Client POSTs JSON-RPC requests to the endpoint URL</li>
 * </ol>
 *
 * <p>Design pattern: <b>Adapter</b> — adapts SSE+HTTP wire protocol to {@link McpClient}.
 */
public class SseMcpClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(SseMcpClient.class);
    private static final ObjectMapper OM = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private static final long RECONNECT_BASE_MS = 1_000;
    private static final long RECONNECT_MAX_MS = 30_000;

    /**
     * Default idle ceiling for the event stream. Reconnect-on-stream-end only
     * fires when the peer sends a clean FIN; load balancers and forward proxies
     * routinely drop idle connections <em>silently</em>, leaving the reader
     * blocked on a half-open socket forever (the original "5-minute disconnect,
     * no reconnect" symptom). Recycling a stream that has been mute this long
     * guarantees liveness; a needless recycle costs one GET plus one initialize.
     */
    static final long DEFAULT_IDLE_TIMEOUT_MS = 300_000;
    private static final long IDLE_CHECK_INTERVAL_MS = 5_000;

    private final String sseUrl;
    private final HttpClient http;
    /** Recycle the stream after this long with no inbound bytes; 0 disables. */
    private final long idleTimeoutMs;

    private volatile boolean connected = false;
    private volatile boolean initCompleted = false;
    private volatile String serverName = "unknown";
    private volatile String postUrl;        // resolved from endpoint event
    private volatile URI postUri;

    private final AtomicLong requestId = new AtomicLong(0);
    private final Map<Long, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private final List<String> eventBuffer = Collections.synchronizedList(new ArrayList<>());

    private Thread sseReaderThread;
    private Thread idleWatchdogThread;
    private volatile boolean running = false;
    /** Wall clock of the last inbound SSE line; drives the idle watchdog. */
    private volatile long lastInboundAtMillis = 0L;
    /** Live response body, so the watchdog can abort a read blocked on a dead socket. */
    private volatile java.io.InputStream currentBody;

    public SseMcpClient(String sseUrl) {
        this(sseUrl, DEFAULT_IDLE_TIMEOUT_MS);
    }

    /**
     * @param idleTimeoutMs recycle the event stream after this many ms without
     *                      any inbound bytes; {@code 0} disables the watchdog and
     *                      restores pure reconnect-on-stream-end behaviour.
     */
    public SseMcpClient(String sseUrl, long idleTimeoutMs) {
        this(sseUrl, idleTimeoutMs, null);
    }

    /**
     * @param idleTimeoutMs recycle the event stream after this many ms without
     *                      any inbound bytes; {@code 0} disables the watchdog.
     * @param proxy         optional egress {@link java.net.ProxySelector} (P1-5);
     *                      non-null routes the SSE stream and JSON-RPC POSTs
     *                      through the forward proxy. Null = direct connection.
     */
    public SseMcpClient(String sseUrl, long idleTimeoutMs, java.net.ProxySelector proxy) {
        this.sseUrl = Objects.requireNonNull(sseUrl);
        this.idleTimeoutMs = Math.max(0, idleTimeoutMs);
        this.http = com.gantang.tianshu.impl.tool.support.EgressProxy.applyTo(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)), proxy)
            .build();
    }

    @Override
    public void initialize() {
        try {
            running = true;
            startSseListener();
            startIdleWatchdog();
            waitForEndpoint(10, TimeUnit.SECONDS);
            sendInitialize();
            connected = true;
            initCompleted = true;
            log.info("MCP SSE server connected: {} (postUrl={})", serverName, postUrl);
        } catch (Exception e) {
            running = false;
            throw new RuntimeException("Failed to initialize MCP SSE client: " + sseUrl, e);
        }
    }

    @Override
    public List<RemoteTool> listTools() {
        ensureConnected();
        try {
            JsonNode result = sendRequest("tools/list", OM.createObjectNode(), 15);
            return parseToolList(result);
        } catch (Exception e) {
            log.warn("Failed to list MCP tools: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public Mono<List<RemoteTool>> listToolsReactive() {
        if (!connected) {
            return Mono.error(new IllegalStateException(
                "MCP SSE client not connected. Call initialize() first."));
        }
        return sendRequestReactive("tools/list", OM.createObjectNode(), Duration.ofSeconds(15))
            .map(SseMcpClient::parseToolList)
            .onErrorResume(e -> {
                log.warn("Failed to list MCP tools: {}", e.getMessage());
                return Mono.just(List.of());
            });
    }

    private static List<RemoteTool> parseToolList(JsonNode result) {
        if (result == null || !result.has("tools")) return List.of();
        List<RemoteTool> tools = new ArrayList<>();
        for (JsonNode tool : result.get("tools")) {
            tools.add(new RemoteTool(
                tool.path("name").asText(),
                tool.path("description").asText(""),
                tool.path("inputSchema").deepCopy()
            ));
        }
        return tools;
    }

    @Override
    public ToolCallResult callTool(String toolName, Map<String, Object> arguments) {
        ensureConnected();
        try {
            JsonNode result = sendRequest("tools/call", buildCallParams(toolName, arguments), 30);
            return parseCallResult(result);
        } catch (Exception e) {
            return ToolCallResult.error("MCP SSE call failed: " + e.getMessage());
        }
    }

    @Override
    public Mono<ToolCallResult> callToolReactive(String toolName, Map<String, Object> arguments) {
        if (!connected) {
            return Mono.just(ToolCallResult.error(
                "MCP SSE client not connected. Call initialize() first."));
        }
        return sendRequestReactive("tools/call", buildCallParams(toolName, arguments), Duration.ofSeconds(30))
            .<ToolCallResult>map(SseMcpClient::parseCallResult)
            .onErrorResume(e -> Mono.just(ToolCallResult.error("MCP SSE call failed: " + e.getMessage())));
    }

    private static ObjectNode buildCallParams(String toolName, Map<String, Object> arguments) {
        ObjectNode params = OM.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", OM.valueToTree(arguments != null ? arguments : Map.of()));
        return params;
    }

    private static ToolCallResult parseCallResult(JsonNode result) {
        if (result == null) {
            return ToolCallResult.error("Empty response from MCP server");
        }

        boolean isError = result.path("isError").asBoolean(false);
        List<ToolCallResult.ContentBlock> blocks = new ArrayList<>();
        StringBuilder text = new StringBuilder();

        if (result.has("content")) {
            for (JsonNode block : result.get("content")) {
                String type = block.path("type").asText("text");
                String blockText = block.path("text").asText("");
                blocks.add(new ToolCallResult.ContentBlock(type, blockText));
                if (!blockText.isEmpty()) {
                    if (!text.isEmpty()) text.append('\n');
                    text.append(blockText);
                }
            }
        }

        return new ToolCallResult(isError, text.toString(), blocks);
    }

    @Override public boolean isConnected() { return connected; }
    @Override public String serverName() { return serverName; }

    @Override
    public void close() {
        connected = false;
        running = false;
        if (sseReaderThread != null) {
            sseReaderThread.interrupt();
        }
        if (idleWatchdogThread != null) {
            idleWatchdogThread.interrupt();
        }
        pendingRequests.values().forEach(f -> f.completeExceptionally(
            new RuntimeException("MCP client closed")));
        pendingRequests.clear();
        // JDK 21+: aborts the blocking SSE read so the reader thread exits
        // instead of lingering until the server closes the stream.
        try {
            http.close();
        } catch (Exception ignored) {
        }
    }

    // ─── SSE listener ─────────────────────────────────────────────────

    private void startSseListener() {
        sseReaderThread = new Thread(() -> {
            long backoff = RECONNECT_BASE_MS;
            while (running) {
                boolean streamOk = false;
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(sseUrl))
                        .header("Accept", "text/event-stream")
                        // Long-lived event stream: deliberately NO request timeout.
                        // A 5-minute timeout here killed every idle SSE connection
                        // for good (no reconnect), taking all MCP tools down.
                        .GET()
                        .build();

                    HttpResponse<java.io.InputStream> response = http.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());

                    if (response.statusCode() != 200) {
                        log.warn("SSE connection failed: HTTP {}", response.statusCode());
                    } else {
                        streamOk = true;
                        backoff = RECONNECT_BASE_MS;  // reset after a successful connect
                        java.io.InputStream body = response.body();
                        currentBody = body;
                        lastInboundAtMillis = System.currentTimeMillis();
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(body, StandardCharsets.UTF_8))) {
                            String eventType = null;
                            StringBuilder dataBuilder = new StringBuilder();
                            String line;

                            while (running && (line = reader.readLine()) != null) {
                                // Any byte from the peer — including SSE comment
                                // keepalives — proves the socket is still alive.
                                lastInboundAtMillis = System.currentTimeMillis();
                                if (line.isEmpty()) {
                                    // Event dispatch
                                    if (!dataBuilder.isEmpty()) {
                                        handleSseEvent(eventType, dataBuilder.toString());
                                        dataBuilder.setLength(0);
                                    }
                                    eventType = null;
                                    continue;
                                }

                                if (line.startsWith("event:")) {
                                    eventType = line.substring(6).trim();
                                } else if (line.startsWith("data:")) {
                                    if (!dataBuilder.isEmpty()) dataBuilder.append('\n');
                                    dataBuilder.append(line.substring(5).trim());
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    if (running) {
                        log.warn("SSE connection error: {}", e.getMessage());
                    }
                } finally {
                    currentBody = null;
                }

                if (!running) break;

                // Stream ended or failed mid-life: mark the client disconnected so callers
                // fail fast instead of waiting for full request timeouts, then reconnect
                // with exponential backoff. The initialize() handshake is replayed by
                // the endpoint event handler once the new stream is up.
                if (streamOk) {
                    log.warn("MCP SSE stream ended unexpectedly; reconnecting in {}ms", backoff);
                } else {
                    log.warn("MCP SSE connect failed; retrying in {}ms", backoff);
                }
                markDisconnected();
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff = Math.min(backoff * 2, RECONNECT_MAX_MS);
            }
        }, "mcp-sse-reader");
        sseReaderThread.setDaemon(true);
        sseReaderThread.start();
    }

    /**
     * Recycles the event stream when it has gone completely mute for longer than
     * {@link #idleTimeoutMs}. Needed because a silently dropped connection (proxy
     * or LB idle reaping, NAT rebinding) never delivers a FIN, so the reader stays
     * parked in {@code readLine()} and the reconnect loop is never reached.
     * Closing the live response body forces that read to throw, which hands
     * control back to the reconnect loop.
     */
    private void startIdleWatchdog() {
        if (idleTimeoutMs <= 0) return;   // opt-out: pure stream-end reconnect
        idleWatchdogThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(IDLE_CHECK_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!running) return;
                java.io.InputStream body = currentBody;
                long last = lastInboundAtMillis;
                if (body == null || last == 0L) continue;   // between streams
                long idleFor = System.currentTimeMillis() - last;
                if (idleFor < idleTimeoutMs) continue;

                log.warn("MCP SSE stream idle for {}ms (limit {}ms); recycling connection",
                    idleFor, idleTimeoutMs);
                // Abort the blocked read; the reader loop reconnects and the
                // endpoint event replays the initialize handshake.
                try {
                    body.close();
                } catch (Exception e) {
                    log.debug("idle watchdog close failed: {}", e.toString());
                }
            }
        }, "mcp-sse-idle-watchdog");
        idleWatchdogThread.setDaemon(true);
        idleWatchdogThread.start();
    }

    /** Flip to disconnected and fail everything still waiting on the dead stream. */
    private void markDisconnected() {
        connected = false;
        pendingRequests.values().forEach(f -> f.completeExceptionally(
            new RuntimeException("MCP SSE connection lost; reconnecting")));
        pendingRequests.clear();
    }

    /** Replay the initialize handshake after a reconnect (runs off the reader thread). */
    private void reinitializeAfterReconnect() {
        try {
            waitForEndpoint(10, TimeUnit.SECONDS);
            sendInitialize();
            connected = true;
            log.info("MCP SSE server reconnected: {} (postUrl={})", serverName, postUrl);
        } catch (Exception e) {
            log.warn("MCP SSE re-initialize failed: {}", e.getMessage());
        }
    }

    private void handleSseEvent(String eventType, String data) {
        if ("endpoint".equals(eventType)) {
            // data is a relative or absolute URL for POSTing JSON-RPC
            String endpoint = data.trim();
            try {
                URI base = URI.create(sseUrl);
                postUri = base.resolve(endpoint);
                postUrl = postUri.toString();
                log.debug("MCP SSE endpoint resolved: {}", postUrl);
            } catch (Exception e) {
                log.error("Failed to resolve endpoint URL: {}", endpoint, e);
                return;
            }
            // On a reconnected stream the endpoint event marks the moment the wire
            // is ready again; replay the initialize handshake off the reader thread
            // (the handshake waits for responses that this very thread must read).
            if (initCompleted && !connected) {
                Thread reinit = new Thread(this::reinitializeAfterReconnect, "mcp-sse-reinit");
                reinit.setDaemon(true);
                reinit.start();
            }
            return;
        }

        if ("message".equals(eventType) || eventType == null) {
            try {
                JsonNode node = OM.readTree(data);
                if (node.has("id")) {
                    long id = node.path("id").asLong(-1);
                    CompletableFuture<JsonNode> future = pendingRequests.remove(id);
                    if (future != null) {
                        if (node.has("error")) {
                            future.completeExceptionally(new RuntimeException(
                                "MCP error: " + node.path("error").path("message").asText()));
                        } else {
                            future.complete(node.get("result"));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse SSE message: {}", e.getMessage());
            }
        }
    }

    private void waitForEndpoint(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        while (postUrl == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        if (postUrl == null) {
            throw new RuntimeException("Timed out waiting for MCP SSE endpoint event");
        }
    }

    // ─── JSON-RPC over POST ───────────────────────────────────────────

    private void sendInitialize() throws Exception {
        ObjectNode initParams = OM.createObjectNode();
        initParams.put("protocolVersion", PROTOCOL_VERSION);
        initParams.set("capabilities", OM.createObjectNode());
        ObjectNode clientInfo = OM.createObjectNode();
        clientInfo.put("name", "tianshu-agent");
        clientInfo.put("version", "0.1.0");
        initParams.set("clientInfo", clientInfo);

        JsonNode result = sendRequest("initialize", initParams, 10);
        if (result != null && result.has("serverInfo")) {
            serverName = result.get("serverInfo").path("name").asText("unknown");
        }

        // Send initialized notification (fire-and-forget POST)
        postNotification("notifications/initialized", OM.createObjectNode());
    }

    private JsonNode sendRequest(String method, ObjectNode params, long timeoutSeconds)
            throws Exception {
        long id = requestId.incrementAndGet();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        ObjectNode req = OM.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        req.set("params", params);

        postJson(req.toString());

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendingRequests.remove(id);
            throw new RuntimeException("MCP request timed out: " + method);
        }
    }

    // ─── Reactive JSON-RPC over POST (non-blocking; no thread held while waiting) ──

    private Mono<JsonNode> sendRequestReactive(String method, ObjectNode params, Duration timeout) {
        long id = requestId.incrementAndGet();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        ObjectNode req = OM.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        req.set("params", params);

        return postJsonReactive(req.toString())
            .then(Mono.fromFuture(future))
            .timeout(timeout)
            .doOnCancel(() -> {
                pendingRequests.remove(id);
                future.cancel(false);
            })
            .onErrorResume(e -> {
                pendingRequests.remove(id);
                return Mono.error(e);
            });
    }

    private Mono<HttpResponse<String>> postJsonReactive(String json) {
        if (postUri == null) {
            return Mono.error(new IllegalStateException("MCP POST endpoint not resolved yet"));
        }
        HttpRequest request = HttpRequest.newBuilder()
            .uri(postUri)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();

        return Mono.fromFuture(http.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
            .flatMap(response -> {
                int sc = response.statusCode();
                if (sc >= 400) {
                    return Mono.error(new RuntimeException(
                        "MCP POST failed: HTTP " + sc + " - " + response.body()));
                }
                // 200 (request ack) and 202 (notification accepted) are both fine.
                return Mono.just(response);
            });
    }

    private void postNotification(String method, ObjectNode params) throws Exception {
        ObjectNode notif = OM.createObjectNode();
        notif.put("jsonrpc", "2.0");
        notif.put("method", method);
        notif.set("params", params);
        postJson(notif.toString());
    }

    private void postJson(String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(postUri)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 && response.statusCode() != 202) {
            // 202 Accepted is normal for notifications; 200 for requests
            if (response.statusCode() >= 400) {
                throw new RuntimeException("MCP POST failed: HTTP " + response.statusCode()
                    + " - " + response.body());
            }
        }
    }

    private void ensureConnected() {
        if (!connected) throw new IllegalStateException(
            "MCP SSE client not connected. Call initialize() first.");
    }
}
