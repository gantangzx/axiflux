package com.gantang.tianshu.impl.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gantang.tianshu.api.mcp.McpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the reactive SSE transport against a fake MCP server:
 * endpoint event over a long-lived SSE GET, JSON-RPC responses pushed as
 * {@code message} events, POSTs acknowledged with 202.
 */
class SseMcpClientReactiveTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private HttpServer server;
    private SseMcpClient client;
    private final AtomicReference<OutputStream> sseStream = new AtomicReference<>();
    private final CountDownLatch sseReady = new CountDownLatch(1);
    private final CountDownLatch holdOpen = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/sse", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream os = exchange.getResponseBody();
            sseStream.set(os);
            os.write("event: endpoint\ndata: /message\n\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            sseReady.countDown();
            try {
                holdOpen.await(); // keep the event stream open until the test ends
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        server.createContext("/message", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            JsonNode req = OM.readTree(body);
            exchange.sendResponseHeaders(202, -1);
            exchange.close();

            if (!req.has("id")) return; // notification — no response expected
            long id = req.path("id").asLong();
            String method = req.path("method").asText();

            ObjectNode result = OM.createObjectNode();
            switch (method) {
                case "initialize" -> {
                    ObjectNode info = OM.createObjectNode();
                    info.put("name", "fake-sse");
                    info.put("version", "1.0");
                    result.set("serverInfo", info);
                }
                case "tools/list" -> {
                    ArrayNode tools = OM.createArrayNode();
                    ObjectNode tool = OM.createObjectNode();
                    tool.put("name", "echo");
                    tool.put("description", "echoes input");
                    tool.set("inputSchema", OM.createObjectNode());
                    tools.add(tool);
                    result.set("tools", tools);
                }
                case "tools/call" -> {
                    ArrayNode content = OM.createArrayNode();
                    ObjectNode block = OM.createObjectNode();
                    block.put("type", "text");
                    block.put("text", "MCP_REACTIVE_OK");
                    content.add(block);
                    result.set("content", content);
                }
                default -> { }
            }

            ObjectNode resp = OM.createObjectNode();
            resp.put("jsonrpc", "2.0");
            resp.put("id", id);
            resp.set("result", result);

            try {
                assertTrue(sseReady.await(10, java.util.concurrent.TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            OutputStream os = sseStream.get();
            synchronized (os) {
                os.write(("event: message\ndata: " + resp + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        });

        server.start();

        client = new SseMcpClient("http://127.0.0.1:" + server.getAddress().getPort() + "/sse");
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
        holdOpen.countDown();
        if (server != null) server.stop(2);
    }

    @Test
    void initialize_handshakeReportsServerName() {
        assertEquals("fake-sse", client.serverName());
        assertTrue(client.isConnected());
    }

    @Test
    void callToolReactive_roundTripsJsonRpcOverSse() {
        McpClient.ToolCallResult result = client
            .callToolReactive("echo", Map.of("text", "hello"))
            .block(Duration.ofSeconds(20));

        assertNotNull(result);
        assertFalse(result.isError());
        assertEquals("MCP_REACTIVE_OK", result.text());
    }

    @Test
    void listToolsReactive_returnsDiscoveredTools() {
        var tools = client.listToolsReactive().block(Duration.ofSeconds(20));
        assertNotNull(tools);
        assertEquals(1, tools.size());
        assertEquals("echo", tools.get(0).name());
    }

    @Test
    void callToolReactive_beforeInitializeReturnsErrorNotCrash() {
        SseMcpClient unconnected = new SseMcpClient("http://127.0.0.1:1/sse");
        McpClient.ToolCallResult result = unconnected
            .callToolReactive("echo", Map.of())
            .block(Duration.ofSeconds(5));
        assertNotNull(result);
        assertTrue(result.isError());
    }
}
