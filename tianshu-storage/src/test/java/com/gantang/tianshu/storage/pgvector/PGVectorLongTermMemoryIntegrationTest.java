package com.gantang.tianshu.storage.pgvector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.api.memory.LongTermMemory;
import com.gantang.tianshu.api.memory.MemoryItem;
import com.gantang.tianshu.api.memory.SummaryGenerator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full-chain integration test for {@link PGVectorLongTermMemory}: real
 * PostgreSQL with the pgvector extension (containerised) and a local stub
 * embedding service, so no external API is involved.
 *
 * <p>Requires Docker. When Docker is unavailable the whole class is skipped
 * (not failed): run it in CI / on a Docker host to exercise store → embedding
 * → vector search → delete. The stub returns deterministic 8-d vectors keyed
 * on topic words, so cosine ranking is verifiable without a real model.
 */
class PGVectorLongTermMemoryIntegrationTest {

    private static final int DIM = 8;
    private static PostgreSQLContainer<?> pg;
    private static HttpServer embedServer;
    private static LongTermMemory memory;
    private static final AtomicInteger embedCalls = new AtomicInteger();

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
            "Docker not available; skipping pgvector integration test");

        pg = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"));
        pg.start();

        embedServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        embedServer.createContext("/v1/embeddings", exchange -> {
            embedCalls.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            float[] v = topicVector(body);
            String json = new ObjectMapper().writeValueAsString(java.util.Map.of(
                "data", List.of(java.util.Map.of("embedding", toBoxed(v))),
                "model", "stub-8"));
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        embedServer.start();

        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(pg.getJdbcUrl());
        ds.setUser(pg.getUsername());
        ds.setPassword(pg.getPassword());

        memory = new PGVectorLongTermMemory(ds,
            new PGVectorLongTermMemory.Config(DIM,
                "http://127.0.0.1:" + embedServer.getAddress().getPort() + "/v1/embeddings",
                "stub-key", "stub-8"),
            SummaryGenerator.truncating());
    }

    @AfterAll
    static void tearDown() {
        if (embedServer != null) embedServer.stop(0);
        if (pg != null) pg.stop();
    }

    /** Deterministic stub embeddings: topic words push the vector onto distinct axes. */
    private static float[] topicVector(String text) {
        String t = text.toLowerCase();
        float[] v = new float[DIM];
        if (t.contains("java") || t.contains("spring")) v[0] = 1.0f;
        if (t.contains("python")) v[1] = 1.0f;
        if (t.matches(".*\\b0\\b.*")) v[2] = 0.0001f; // keep all-vectors non-degenerate
        return v;
    }

    private static Float[] toBoxed(float[] v) {
        Float[] out = new Float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = v[i];
        return out;
    }

    private static MemoryItem item(String id, String content) {
        Instant now = Instant.now();
        return new MemoryItem(id, "it-user", content, content,
            List.of(), 5, now, now);
    }

    @Test
    void storeAndVectorSearchRanksByEmbeddingSimilarity() {
        memory.store("it-user", item("m-java", "Notes about the Java Spring framework and reactive web")).block();
        memory.store("it-user", item("m-py", "Python scripting and data processing notes")).block();
        assertTrue(embedCalls.get() >= 2, "stub embedding service should have been called");

        List<MemoryItem> hits = memory.search("it-user", "Tell me about Spring and Java", 2).block();
        assertNotNull(hits);
        assertFalse(hits.isEmpty(), "semantic search should return the java note first");
        assertEquals("m-java", hits.get(0).id(),
            "the java/spring note must outrank the python note for a java query");
    }

    @Test
    void getAllAndDeleteRoundTrip() {
        memory.store("it-user", item("m-del", "A note that will be deleted")).block();
        List<MemoryItem> all = memory.getAll("it-user").block();
        assertTrue(all.stream().anyMatch(m -> "m-del".equals(m.id())));

        Boolean deleted = memory.delete("m-del").block();
        assertTrue(deleted);
        List<MemoryItem> after = memory.getAll("it-user").block();
        assertTrue(after.stream().noneMatch(m -> "m-del".equals(m.id())));
    }

    @Test
    void keywordSearchFindsStoredContent() {
        memory.store("kw-user", item("m-kw", "Quarterly infrastructure budget numbers")).block();
        List<MemoryItem> hits = memory.searchByKeyword("kw-user", "budget").block();
        assertTrue(hits.stream().anyMatch(m -> "m-kw".equals(m.id())),
            "keyword fallback search should find the note by a content word");
    }
}
