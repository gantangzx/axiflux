package com.gantang.tianshu.impl.tool.builtin;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.tool.ToolResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that HTTP tools follow redirects manually and re-run the SSRF guard
 * on every hop, so a public URL cannot 302-bounce a request into the cloud
 * metadata endpoint or a private address.
 */
class HttpRedirectSecurityTest {

    private HttpServer server;
    private String base;

    private final AgentContext ctx = AgentContext.builder()
        .sessionId("t").userId("u").currentQuery("").build();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 302 to the cloud metadata endpoint (the classic SSRF target)
        server.createContext("/to-meta", ex -> {
            ex.getResponseHeaders().add("Location",
                "http://169.254.169.254/latest/meta-data/iam/security-credentials/");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        // safe relative redirect within the same origin
        server.createContext("/go", ex -> {
            ex.getResponseHeaders().add("Location", "/final");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/final", ex -> {
            byte[] b = "<html><body>FINAL-OK</body></html>".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (var os = ex.getResponseBody()) {
                os.write(b);
            }
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void webFetch_blocksRedirectToCloudMetadata() {
        // allowPrivateNetwork=true so the initial 127.0.0.1 request is permitted;
        // the metadata redirect must STILL be blocked.
        WebFetchTool tool = new WebFetchTool(true);
        ToolResult r = tool.execute("c1", Map.of("url", base + "/to-meta"), ctx);
        assertFalse(r.success());
        assertNotNull(r.errorMessage());
        assertTrue(r.errorMessage().contains("Redirect target blocked"), r.errorMessage());
        assertTrue(r.errorMessage().contains("169.254.169.254"), r.errorMessage());
    }

    @Test
    void httpClient_blocksRedirectToCloudMetadata() {
        HttpClientTool tool = new HttpClientTool(8192, true);
        ToolResult r = tool.execute("c2", Map.of("method", "GET", "url", base + "/to-meta"), ctx);
        assertFalse(r.success());
        assertNotNull(r.errorMessage());
        assertTrue(r.errorMessage().contains("Redirect target blocked"), r.errorMessage());
    }

    @Test
    void webFetch_followsSafeRedirect() {
        WebFetchTool tool = new WebFetchTool(true);
        ToolResult r = tool.execute("c3", Map.of("url", base + "/go"), ctx);
        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.content().contains("FINAL-OK"));
    }

    @Test
    void httpClient_followsSafeRedirect() {
        HttpClientTool tool = new HttpClientTool(8192, true);
        ToolResult r = tool.execute("c4", Map.of("method", "GET", "url", base + "/go"), ctx);
        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.content().contains("FINAL-OK"));
        assertNotNull(r.metadata());
        assertTrue(String.valueOf(r.metadata().get("finalUrl")).endsWith("/final"));
    }
}
