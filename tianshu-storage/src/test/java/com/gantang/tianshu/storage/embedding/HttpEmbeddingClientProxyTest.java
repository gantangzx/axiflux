package com.gantang.tianshu.storage.embedding;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-5: when an egress proxy is configured, the embedding client's outbound
 * HTTP call must TRANSIT that proxy instead of connecting directly to the
 * embedding endpoint. A plain {@code http://} request through a forward proxy
 * is sent to the proxy with the absolute target URI, so a mock proxy that
 * records hits proves the traffic went through it.
 */
class HttpEmbeddingClientProxyTest {

    @Test
    void embedRequestTransitsConfiguredEgressProxy() throws Exception {
        AtomicInteger proxyHits = new AtomicInteger();
        // Mock forward proxy: accepts anything, returns a valid embedding payload.
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int dim = 8;
        String body = "{\"data\":[{\"embedding\":[" + "0.1,".repeat(dim - 1) + "0.1]}]}";
        proxy.createContext("/", ex -> {
            proxyHits.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        proxy.start();
        try {
            int proxyPort = proxy.getAddress().getPort();
            ProxySelector selector = ProxySelector.of(new InetSocketAddress("127.0.0.1", proxyPort));
            // The target endpoint is deliberately unreachable directly; only the
            // proxy answers, so a successful embed proves the proxy was used.
            HttpEmbeddingClient client = new HttpEmbeddingClient(
                "http://10.255.255.1:9/v1/embeddings", "k", "m", dim, selector);

            float[] vec = client.embedOrNull("hello");
            assertNotNull(vec, "embedding should succeed because the mock proxy answered");
            assertEquals(dim, vec.length);
            assertTrue(proxyHits.get() > 0, "the egress proxy must have received the request");
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void noProxyMeansDirectConnection() {
        // Null proxy keeps the legacy direct-connect behaviour (unreachable → null).
        HttpEmbeddingClient client = new HttpEmbeddingClient(
            "http://127.0.0.1:1/v1/embeddings", "k", "m", 8, null);
        assertNull(client.embedOrNull("some text"));
    }
}
