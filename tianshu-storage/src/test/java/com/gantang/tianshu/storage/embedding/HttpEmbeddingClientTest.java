package com.gantang.tianshu.storage.embedding;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Failure-path contract for the embedding client: an unreachable/misconfigured
 * endpoint must yield {@code null} from {@link HttpEmbeddingClient#embedOrNull}
 * (so callers degrade to keyword search / vectorless store) rather than a zero
 * vector that would silently scramble cosine ranking.
 */
class HttpEmbeddingClientTest {

    // Port 1 is closed on loopback → connection refused, fast and deterministic.
    private static final String UNREACHABLE = "http://127.0.0.1:1/v1/embeddings";

    @Test
    void embedOrNull_returnsNullWhenEndpointUnreachable() {
        HttpEmbeddingClient client = new HttpEmbeddingClient(UNREACHABLE, "k", "m", 8);
        float[] vec = client.embedOrNull("some text to embed");
        assertNull(vec, "unreachable endpoint must degrade to null, not a zero vector");
    }

    @Test
    void embed_returnsZeroVectorForBackwardCompatibility() {
        HttpEmbeddingClient client = new HttpEmbeddingClient(UNREACHABLE, "k", "m", 8);
        float[] vec = client.embed("some text");
        assertEquals(8, vec.length);
        assertTrue(HttpEmbeddingClient.isZeroVector(vec));
    }

    @Test
    void embedOrNull_blankTextReturnsNull() {
        HttpEmbeddingClient client = new HttpEmbeddingClient(UNREACHABLE, "k", "m", 8);
        assertNull(client.embedOrNull(""));
        assertNull(client.embedOrNull(null));
    }

    @Test
    void isZeroVector_detectsZerosAndNull() {
        assertTrue(HttpEmbeddingClient.isZeroVector(null));
        assertTrue(HttpEmbeddingClient.isZeroVector(new float[]{0f, 0f, 0f}));
        assertFalse(HttpEmbeddingClient.isZeroVector(new float[]{0f, 0.1f, 0f}));
    }

    // ===== P2-9: retry on transient 429/5xx =====

    @Test
    void embedOrNull_retriesOn429_thenSucceeds() throws Exception {
        // First request: 429. Second request: 200 with valid embedding.
        AtomicInteger callCount = new AtomicInteger();
        int dim = 4;
        String okBody = "{\"data\":[{\"embedding\":[" + "0.5,".repeat(dim - 1) + "0.5]}]}";
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            int n = callCount.incrementAndGet();
            if (n == 1) {
                ex.sendResponseHeaders(429, -1);
                ex.close();
                return;
            }
            byte[] bytes = okBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (java.io.OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings";
            HttpEmbeddingClient client = new HttpEmbeddingClient(url, "k", "m", dim);
            float[] vec = client.embedOrNull("retry test");
            assertNotNull(vec, "should succeed after 429 retry");
            assertEquals(dim, vec.length);
            assertEquals(2, callCount.get(), "expected exactly 2 HTTP calls (1 retry)");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void embedOrNull_retriesOn500_thenSucceeds() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        int dim = 4;
        String okBody = "{\"data\":[{\"embedding\":[" + "0.3,".repeat(dim - 1) + "0.3]}]}";
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            int n = callCount.incrementAndGet();
            if (n <= 2) {
                ex.sendResponseHeaders(500, -1);
                ex.close();
                return;
            }
            byte[] bytes = okBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (java.io.OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings";
            HttpEmbeddingClient client = new HttpEmbeddingClient(url, "k", "m", dim);
            float[] vec = client.embedOrNull("retry test");
            assertNotNull(vec, "should succeed after two 500 retries");
            assertEquals(dim, vec.length);
            assertEquals(3, callCount.get(), "expected exactly 3 HTTP calls (2 retries)");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void embedOrNull_doesNotRetryOn400() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            callCount.incrementAndGet();
            ex.sendResponseHeaders(400, -1);
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings";
            HttpEmbeddingClient client = new HttpEmbeddingClient(url, "k", "m", 4);
            float[] vec = client.embedOrNull("bad request");
            assertNull(vec, "400 is permanent, must not retry");
            assertEquals(1, callCount.get(), "expected exactly 1 HTTP call (no retry on 400)");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void embedOrNull_exhaustsRetries_returnsNull() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            callCount.incrementAndGet();
            ex.sendResponseHeaders(429, -1);
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings";
            HttpEmbeddingClient client = new HttpEmbeddingClient(url, "k", "m", 4);
            float[] vec = client.embedOrNull("always 429");
            assertNull(vec, "all retries exhausted, must return null");
            // 1 initial + 2 retries = 3 total
            assertEquals(3, callCount.get(), "expected 3 HTTP calls (initial + 2 retries)");
        } finally {
            server.stop(0);
        }
    }
}
