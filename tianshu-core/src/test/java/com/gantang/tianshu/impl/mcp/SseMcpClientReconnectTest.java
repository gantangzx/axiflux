package com.gantang.tianshu.impl.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.tianshu.api.mcp.McpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link SseMcpClient} recovery across an MCP server restart —
 * the real-world failure this reconnection loop exists for.
 *
 * <p>Sequence: client initialises against server #1 → server #1 is killed
 * (all TCP connections dropped) → the reader thread's exponential-backoff
 * loop retries → server #2 comes up on the same port → the endpoint event
 * triggers the re-initialize handshake → tool calls resume.
 */
class SseMcpClientReconnectTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private int port;
    private HttpServer server;
    private SseMcpClient client;

    private final AtomicInteger sseConnections = new AtomicInteger(0);
    private final AtomicReference<OutputStream> sseOsRef = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> sseUpLatch = new AtomicReference<>();
    /** When true the SSE handler emits a periodic comment keepalive. */
    private volatile boolean keepaliveEnabled = false;

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
        if (server != null) server.stop(0);
    }

    /** Boot an MCP-over-SSE server on {@link #port} with the standard handlers. */
    private HttpServer startServer() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        s.setExecutor(Executors.newCachedThreadPool());

        s.createContext("/sse", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream os = exchange.getResponseBody();
            sseOsRef.set(os);

            // Mandatory first event: where to POST JSON-RPC.
            os.write("event: endpoint\ndata: /message\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();

            sseConnections.incrementAndGet();
            CountDownLatch up = sseUpLatch.get();
            if (up != null) up.countDown();

            if (keepaliveEnabled) {
                // Comment-line keepalive: proves the idle watchdog treats any
                // inbound byte as liveness and does NOT recycle a chatty stream.
                try {
                    while (true) {
                        Thread.sleep(200);
                        synchronized (os) {
                            os.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                            os.flush();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    // stream closed — handler exits
                }
                return;
            }

            // Hold the event stream open and completely mute.
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        s.createContext("/message", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            JsonNode req = OM.readTree(body);
            exchange.sendResponseHeaders(202, -1);
            exchange.close();

            if (!req.has("id")) return; // notification
            ObjectNode result = OM.createObjectNode();
            switch (req.path("method").asText()) {
                case "initialize" -> {
                    ObjectNode info = OM.createObjectNode();
                    info.put("name", "reconnect-sse");
                    info.put("version", "1.0");
                    result.set("serverInfo", info);
                }
                case "tools/list" -> {
                    ArrayNode arr = OM.createArrayNode();
                    ObjectNode t = OM.createObjectNode();
                    t.put("name", "pong");
                    t.put("description", "pongs");
                    t.set("inputSchema", OM.createObjectNode());
                    arr.add(t);
                    result.set("tools", arr);
                }
                case "tools/call" -> {
                    ArrayNode c = OM.createArrayNode();
                    ObjectNode b = OM.createObjectNode();
                    b.put("type", "text");
                    b.put("text", "AFTER_RECONNECT");
                    c.add(b);
                    result.set("content", c);
                }
                default -> { }
            }

            ObjectNode resp = OM.createObjectNode();
            resp.put("jsonrpc", "2.0");
            resp.put("id", req.path("id").asLong());
            resp.set("result", result);

            // JSON-RPC responses travel back on the SSE stream, not the POST.
            OutputStream active = sseOsRef.get();
            if (active != null) {
                synchronized (active) {
                    active.write(("event: message\ndata: " + resp + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                    active.flush();
                }
            }
        });

        s.start();
        return s;
    }

    @Test
    void clientRecoversAcrossServerRestart() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }

        // ── Phase 1: server #1, normal operation ────────────────────
        sseUpLatch.set(new CountDownLatch(1));
        server = startServer();
        client = new SseMcpClient("http://127.0.0.1:" + port + "/sse");
        client.initialize();

        assertTrue(client.isConnected());
        assertEquals("reconnect-sse", client.serverName());
        assertEquals(1, sseConnections.get());

        var before = client.listToolsReactive().block(Duration.ofSeconds(20));
        assertNotNull(before);
        assertEquals("pong", before.get(0).name());

        // ── Phase 2: kill server #1 ─────────────────────────────────
        // Drops the SSE TCP connection; the reader thread sees the stream
        // die, flips the client to disconnected, and starts backing off.
        sseUpLatch.set(new CountDownLatch(1));
        server.stop(0);
        server = null;

        long disconnectDeadline = System.currentTimeMillis() + 15_000;
        while (client.isConnected() && System.currentTimeMillis() < disconnectDeadline) {
            Thread.sleep(50);
        }
        assertFalse(client.isConnected(), "client must flip to disconnected when the stream dies");

        // Calls must fail fast rather than hang while disconnected.
        McpClient.ToolCallResult whileDown = client
            .callToolReactive("pong", Map.of())
            .block(Duration.ofSeconds(10));
        assertNotNull(whileDown);
        assertTrue(whileDown.isError(), "calls while disconnected must error, not hang");

        // ── Phase 3: server #2 on the same port ─────────────────────
        server = startServer();

        assertTrue(sseUpLatch.get().await(40, TimeUnit.SECONDS),
            "reader thread never re-established the SSE stream");

        long reconnectDeadline = System.currentTimeMillis() + 20_000;
        while (!client.isConnected() && System.currentTimeMillis() < reconnectDeadline) {
            Thread.sleep(100);
        }
        assertTrue(client.isConnected(), "client must re-initialize after reconnecting");
        assertTrue(sseConnections.get() >= 2, "expected a second SSE connection");

        // ── Phase 4: traffic resumes on the new stream ──────────────
        var after = client.listToolsReactive().block(Duration.ofSeconds(20));
        assertNotNull(after);
        assertEquals("pong", after.get(0).name());

        McpClient.ToolCallResult call = client
            .callToolReactive("pong", Map.of())
            .block(Duration.ofSeconds(20));
        assertNotNull(call);
        assertFalse(call.isError(), "call after reconnect must succeed");
        assertEquals("AFTER_RECONNECT", call.text());
    }

    @Test
    void idleWatchdogRecyclesSilentlyDeadStream() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }

        // Server holds the stream open but never sends another byte. Nothing
        // closes it, so reconnect-on-stream-end can never fire: only the idle
        // watchdog can notice. This is the silent half-open case that the
        // "5-minute disconnect" bug actually was.
        sseUpLatch.set(new CountDownLatch(1));
        server = startServer();

        // 2s idle ceiling so the test doesn't wait out the 5-minute default.
        client = new SseMcpClient("http://127.0.0.1:" + port + "/sse", 2_000);
        client.initialize();
        assertTrue(client.isConnected());
        assertEquals(1, sseConnections.get());

        // Arm a latch for the recycled stream, then just wait: no server
        // restart, no FIN, no error — the watchdog must act on its own.
        sseUpLatch.set(new CountDownLatch(1));
        assertTrue(sseUpLatch.get().await(40, TimeUnit.SECONDS),
            "idle watchdog never recycled the mute stream");
        assertTrue(sseConnections.get() >= 2, "expected a recycled SSE connection");

        long deadline = System.currentTimeMillis() + 20_000;
        while (!client.isConnected() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(client.isConnected(), "client must re-initialize on the recycled stream");

        McpClient.ToolCallResult call = client
            .callToolReactive("pong", Map.of())
            .block(Duration.ofSeconds(20));
        assertNotNull(call);
        assertFalse(call.isError(), "call after recycle must succeed");
        assertEquals("AFTER_RECONNECT", call.text());
    }

    @Test
    void idleWatchdogLeavesKeepalivedStreamAlone() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }

        // Same tight idle ceiling, but the server now sends comment keepalives
        // every 200ms. A healthy chatty stream must never be recycled.
        keepaliveEnabled = true;
        sseUpLatch.set(new CountDownLatch(1));
        server = startServer();

        client = new SseMcpClient("http://127.0.0.1:" + port + "/sse", 2_000);
        client.initialize();
        assertTrue(client.isConnected());
        assertEquals(1, sseConnections.get());

        // Sit well past several idle windows.
        Thread.sleep(7_000);

        assertEquals(1, sseConnections.get(),
            "keepalived stream must not be recycled");
        assertTrue(client.isConnected(), "client must stay connected");

        McpClient.ToolCallResult call = client
            .callToolReactive("pong", Map.of())
            .block(Duration.ofSeconds(20));
        assertNotNull(call);
        assertFalse(call.isError());
        assertEquals("AFTER_RECONNECT", call.text());
    }
}