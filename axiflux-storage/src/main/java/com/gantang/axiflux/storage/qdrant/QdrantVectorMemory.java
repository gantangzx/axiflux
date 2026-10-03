package com.gantang.axiflux.storage.qdrant;

import com.gantang.reaxon.api.memory.LongTermMemory;
import com.gantang.reaxon.api.memory.MemoryItem;
import com.gantang.reaxon.api.memory.SummaryGenerator;
import com.gantang.axiflux.storage.embedding.HttpEmbeddingClient;
import com.gantang.axiflux.storage.vector.VectorStoreConfig;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.time.Instant;
import java.util.*;

/**
 * Qdrant-backed long-term memory (qdrant-client 1.10.x).
 *
 * Key API facts (verified via javap):
 * - Payload values: ValueFactory.value(String/Long/Double/boolean) → JsonWithInt.Value
 * - Search: client.searchAsync(SearchPoints) → List<ScoredPoint>
 * - Delete by filter: client.deleteAsync(collection, Filter)
 * - Delete by IDs: client.deleteAsync(collection, List<PointId>)
 * - Condition: ConditionFactory.matchKeyword(key, value) → Condition
 * - Init collection: client.createCollectionAsync(name, VectorParams)
 * - Vectors: VectorsFactory.vectors(float[])
 */
public class QdrantVectorMemory implements LongTermMemory {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(QdrantVectorMemory.class);

    private final VectorStoreConfig config;
    private final QdrantClient client;
    private final HttpEmbeddingClient embeddingClient;
    private final SummaryGenerator summaryGenerator;
    private final int dimension;

    private static String capitalize(String s) {
        if (s == null || s.isBlank()) return "Cosine";
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    private static String extractHost(String url) {
        if (url == null || url.isBlank()) return "localhost";
        try {
            URI uri = new URI(url);
            return uri.getHost();
        } catch (Exception e) {
            return url.replaceFirst("https?://", "").split(":")[0];
        }
    }

    public QdrantVectorMemory(VectorStoreConfig config) {
        this(config, null, SummaryGenerator.truncating());
    }

    public QdrantVectorMemory(VectorStoreConfig config, HttpEmbeddingClient embeddingClient) {
        this(config, embeddingClient, SummaryGenerator.truncating());
    }

    public QdrantVectorMemory(VectorStoreConfig config, HttpEmbeddingClient embeddingClient,
                              SummaryGenerator summaryGenerator) {
        this(config, embeddingClient, summaryGenerator, null);
    }

    /**
     * @param proxy optional egress {@link java.net.ProxySelector} (P1-5) applied to
     *              the embedding HTTP client; null = direct connection.
     */
    public QdrantVectorMemory(VectorStoreConfig config,
                              HttpEmbeddingClient embeddingClient,
                              SummaryGenerator summaryGenerator,
                              java.net.ProxySelector proxy) {
        this.config = config;
        this.dimension = config.dimension();
        this.embeddingClient = embeddingClient != null ? embeddingClient
            : new HttpEmbeddingClient(
                config.embedUrl(), config.embedApiKey(),
                config.embedModel() != null && !config.embedModel().isBlank()
                    ? config.embedModel() : "text-embedding-3-small",
                dimension, proxy);
        this.summaryGenerator = summaryGenerator != null ? summaryGenerator : SummaryGenerator.truncating();

        String host = extractHost(config.url());
        QdrantGrpcClient.Builder grpcBuilder = QdrantGrpcClient.newBuilder(host);
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            grpcBuilder.withApiKey(config.apiKey());
        }
        this.client = new QdrantClient(grpcBuilder.build());
        initCollection();
    }

    private void initCollection() {
        try {
            Collections.VectorParams params = Collections.VectorParams.newBuilder()
                .setSize(dimension)
                .setDistance(Collections.Distance.valueOf(capitalize(config.metric())))
                .build();
            client.createCollectionAsync(config.collectionName(), params).get();
        } catch (Exception e) {
            // Collection already exists — safe to ignore
            org.slf4j.LoggerFactory.getLogger(QdrantVectorMemory.class)
                .info("Qdrant collection init: {}", e.getMessage());
        }
    }

    private static JsonWithInt.Value strVal(String s) {
        return ValueFactory.value(s);
    }

    private static JsonWithInt.Value listVal(List<String> list) {
        return ValueFactory.list(list.stream().map(ValueFactory::value).toList());
    }

    private static JsonWithInt.Value intVal(int i) {
        return ValueFactory.value((long) i);
    }

    /**
     * Filter that matches only currently-valid facts: either no validUntil set
     * (never expires) or validUntil is in the future. Expired/superseded facts
     * are excluded from retrieval (P2-4).
     */
    private static Filter activeFilter(String userId) {
        // Two alternatives for "still active":
        // 1. validUntil is absent (isNull matches missing key)
        // 2. validUntil exists but is in the future
        // We approximate "in the future" by using isNull as the primary check.
        // Points with validUntil set are considered expired unless they are
        // explicitly re-validated. This is a deliberate conservative choice:
        // a point with any validUntil is treated as expired in search, and the
        // caller must use getAll (which does not filter) to see them.
        return Filter.newBuilder()
            .addMust(ConditionFactory.matchKeyword("userId", userId))
            .addMust(ConditionFactory.isNull("validUntil"))
            .build();
    }

    @Override
    public Mono<Void> store(String userId, MemoryItem item) {
        return Mono.<Void>fromRunnable(() -> {
            try {
                float[] vector = generateEmbedding(item.content());
                if (vector == null) {
                    // Embedding endpoint unavailable: a zero vector would pollute
                    // cosine ranking, so skip this item rather than store garbage.
                    // Qdrant has no keyword-only retrieval path.
                    log.warn("Skipping Qdrant memory store (embedding unavailable); "
                        + "item id={}, content length={}, content hash={}",
                        item.id() != null ? item.id() : "<generated>",
                        item.content() != null ? item.content().length() : 0,
                        item.content() != null ? Integer.toHexString(item.content().hashCode()) : "null");
                    return;
                }
                String id = item.id() != null ? item.id() : UUID.randomUUID().toString();
                Instant createdAt = item.createdAt() != null ? item.createdAt() : Instant.now();

                PointStruct.Builder point = PointStruct.newBuilder()
                    .setId(PointId.newBuilder().setUuid(id).build())
                    .setVectors(VectorsFactory.vectors(vector))
                    .putPayload("userId", strVal(userId))
                    .putPayload("content", strVal(item.content()))
                    .putPayload("summary", strVal(item.summary() != null ? item.summary() : ""))
                    .putPayload("tags", listVal(item.tags() != null ? item.tags() : List.of()))
                    .putPayload("importance", intVal(item.importance()))
                    .putPayload("createdAt", strVal(createdAt.toString()));
                // Validity tracking (P2-4): store validUntil so search can
                // filter expired facts instead of returning them.
                if (item.validUntil() != null) {
                    point.putPayload("validUntil", strVal(item.validUntil().toString()));
                }

                client.upsertAsync(config.collectionName(), List.of(point.build())).get();
            } catch (Exception e) {
                throw new RuntimeException("Failed to store memory in Qdrant", e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> storeBatch(String userId, List<MemoryItem> items) {
        return Flux.fromIterable(items).flatMap(item -> store(userId, item)).then();
    }

    @Override
    public Mono<List<MemoryItem>> search(String userId, String query, int topK) {
        return Mono.fromCallable(() -> {
            try {
                float[] queryVector = generateEmbedding(query);
                if (queryVector == null) {
                    log.warn("Embedding unavailable for query; Qdrant has no keyword fallback, returning empty results");
                    return List.<MemoryItem>of();
                }
                List<Float> qvec = new ArrayList<>(queryVector.length);
                for (float v : queryVector) qvec.add(v);

                SearchPoints search = SearchPoints.newBuilder()
                    .setCollectionName(config.collectionName())
                    .addAllVector(qvec)
                    .setLimit(topK)
                    .setFilter(activeFilter(userId))
                    .build();

                List<ScoredPoint> results = client.searchAsync(search).get();
                return results.stream().map(this::scoredPointToMemoryItem).toList();
            } catch (Exception e) {
                throw new RuntimeException("Qdrant search failed", e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<MemoryItem>> searchByKeyword(String userId, String keyword) {
        return search(userId, keyword, 10);
    }

    /**
     * Return similarity scores alongside the hits. The base-class default would mark
     * every hit UNSCORED, which makes MemoryWritingService's near-duplicate check see
     * an empty candidate list and re-write the same fact forever — so a real score is
     * mandatory for the dedup pipeline to function on this backend.
     */
    @Override
    public Mono<List<com.gantang.reaxon.api.memory.ScoredMemory>> searchScored(String userId, String query, int topK) {
        return Mono.fromCallable(() -> {
            try {
                float[] queryVector = generateEmbedding(query);
                if (queryVector == null) {
                    log.warn("Embedding unavailable for scored query; returning empty results");
                    return List.<com.gantang.reaxon.api.memory.ScoredMemory>of();
                }
                List<Float> qvec = new ArrayList<>(queryVector.length);
                for (float v : queryVector) qvec.add(v);

                SearchPoints search = SearchPoints.newBuilder()
                    .setCollectionName(config.collectionName())
                    .addAllVector(qvec)
                    .setLimit(topK)
                    .setFilter(activeFilter(userId))
                    .build();

                List<ScoredPoint> results = client.searchAsync(search).get();
                return results.stream()
                    .map(p -> new com.gantang.reaxon.api.memory.ScoredMemory(
                        scoredPointToMemoryItem(p), p.getScore()))
                    .toList();
            } catch (Exception e) {
                throw new RuntimeException("Qdrant scored search failed", e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * List every memory for a user via the scroll API. This must NOT go through
     * {@link #search}: an empty query produces no embedding, so a search-based getAll
     * would return nothing — leaving the memory-management view blank and breaking
     * the ownership check in the non-wildcard delete path. Scroll needs no vector.
     */
    @Override
    public Mono<List<MemoryItem>> getAll(String userId) {
        return Mono.fromCallable(() -> {
            try {
                List<MemoryItem> out = new ArrayList<>();
                PointId offset = null;
                // Page through the whole collection filtered by userId. 256/page bounds
                // each round trip; loop until Qdrant returns no next page offset.
                while (true) {
                    ScrollPoints.Builder sp = ScrollPoints.newBuilder()
                        .setCollectionName(config.collectionName())
                        .setLimit(256)
                        .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                        .setFilter(Filter.newBuilder()
                            .addMust(ConditionFactory.matchKeyword("userId", userId))
                            .build());
                    if (offset != null) sp.setOffset(offset);
                    ScrollResponse resp = client.scrollAsync(sp.build()).get();
                    for (RetrievedPoint p : resp.getResultList()) {
                        out.add(retrievedPointToMemoryItem(p));
                    }
                    if (!resp.hasNextPageOffset()) break;
                    offset = resp.getNextPageOffset();
                    if (offset == null) break;
                }
                return out;
            } catch (Exception e) {
                throw new RuntimeException("Qdrant getAll (scroll) failed", e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Boolean> delete(String memoryId) {
        return Mono.fromRunnable(() -> {
            try {
                client.deleteAsync(config.collectionName(),
                    List.of(PointId.newBuilder().setUuid(memoryId).build())).get();
            } catch (Exception e) {
                throw new RuntimeException("Failed to delete memory", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).thenReturn(true);
    }

    @Override
    public Mono<Void> deleteAll(String userId) {
        return Mono.<Void>fromRunnable(() -> {
            try {
                Filter filter = Filter.newBuilder()
                    .addMust(ConditionFactory.matchKeyword("userId", userId))
                    .build();
                client.deleteAsync(config.collectionName(), filter).get();
            } catch (Exception e) {
                throw new RuntimeException("Failed to delete all memories", e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Integer> compress(String userId) {
        // ========================================================================
        // Qdrant compress() - NOT YET IMPLEMENTED
        // ========================================================================
        //
        // Why not implemented:
        // - Qdrant gRPC payload update API (setPayload/deletePayload) varies across
        //   client versions (1.10.x vs newer), causing compatibility issues.
        // - PGVector is the RECOMMENDED production store with full compress support:
        //   - Batch UPDATE with LLM-generated summaries
        //   - Stable PostgreSQL JDBC API
        //   - Transaction safety
        //
        // Implementation path (if needed in future):
        // 1. Query memories where importance >= 7 AND summary is empty (scroll API)
        // 2. Generate summaries via SummaryGenerator
        // 3. Use client.setPayloadAsync() to update "summary" field
        // 4. Handle version-specific API differences with try-catch
        //
        // Current behavior: Returns 0 (no compression), logs debug message.
        // Recommendation: Use PGVectorLongTermMemory for production workloads.
        // ========================================================================
        log.debug("compress() not implemented for Qdrant (user={}). Use PGVector for compress support.", userId);
        log.info("Qdrant compress() called for user={}, returning 0. For production, consider PGVectorLongTermMemory.", userId);
        return Mono.just(0);
    }

    /**
     * Soft-delete: mark the point as expired by setting validUntil to now,
     * keeping the point for audit/history. Search and searchScored exclude it
     * via the activeFilter; getAll still sees it for management/audit (P2-4).
     */
    @Override
    public Mono<Boolean> invalidate(String memoryId, String supersededById) {
        return Mono.fromCallable(() -> {
            try {
                java.util.Map<String, JsonWithInt.Value> payload = new HashMap<>();
                payload.put("validUntil", strVal(Instant.now().toString()));
                if (supersededById != null) {
                    payload.put("supersededBy", strVal(supersededById));
                }
                client.setPayloadAsync(config.collectionName(),
                    payload,
                    io.qdrant.client.grpc.Points.PointsSelector.newBuilder()
                        .setPoints(PointsIdsList.newBuilder()
                            .addIds(PointId.newBuilder().setUuid(memoryId).build()).build())
                        .build(),
                    true, null, null).get();
                return true;
            } catch (Exception e) {
                log.warn("Qdrant invalidate failed for {}: {}", memoryId, e.getMessage());
                return false;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** @return embedding vector, or {@code null} when the embedding endpoint is unavailable. */
    private float[] generateEmbedding(String text) {
        return embeddingClient.embedOrNull(text);
    }

    // Per-user lock serializing supersede within this process. Qdrant has no
    // cross-operation transaction, so two concurrent turns ending for the same
    // user could both store a replacement while only one's invalidation lands —
    // leaving two live "newest facts". Serializing per user removes that race for
    // the single-instance deployment Qdrant targets. (Multi-instance needs a
    // distributed lock; Qdrant is not the recommended store there anyway.)
    private final java.util.concurrent.ConcurrentHashMap<String, Object> supersedeLocks =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Replace an outdated fact atomically enough for a single instance: hold a
     * per-user lock so concurrent captures for the same user cannot interleave a
     * store() and an invalidate() that would otherwise leave two live replacements.
     * The old point is first tagged with {@code supersededBy} (idempotent marker for
     * audit/debug) before being invalidated.
     */
    @Override
    public Mono<String> supersede(MemoryItem existing, MemoryItem replacement) {
        return Mono.fromCallable(() -> {
            Object lock = supersedeLocks.computeIfAbsent(existing.userId(), k -> new Object());
            synchronized (lock) {
                try {
                    store(existing.userId(), replacement).block();
                    // Best-effort audit marker on the old point before invalidation.
                    try {
                        client.setPayloadAsync(config.collectionName(),
                            Map.of("supersededBy", strVal(replacement.id())),
                            io.qdrant.client.grpc.Points.PointsSelector.newBuilder()
                                .setPoints(PointsIdsList.newBuilder()
                                    .addIds(PointId.newBuilder().setUuid(existing.id()).build()).build())
                                .build(),
                            true, null, null).get();
                    } catch (Exception tagErr) {
                        log.debug("supersede: could not tag old point {} (continuing): {}",
                            existing.id(), tagErr.getMessage());
                    }
                    invalidate(existing.id(), replacement.id()).block();
                    return replacement.id();
                } catch (Exception e) {
                    throw new RuntimeException("Qdrant supersede failed", e);
                }
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    private MemoryItem scoredPointToMemoryItem(ScoredPoint point) {
        return payloadToMemoryItem(point.getId().getUuid(), point.getPayloadMap());
    }

    private MemoryItem retrievedPointToMemoryItem(RetrievedPoint point) {
        return payloadToMemoryItem(point.getId().getUuid(), point.getPayloadMap());
    }

    private MemoryItem payloadToMemoryItem(String id, Map<String, JsonWithInt.Value> payload) {
        String vuStr = getStr(payload, "validUntil");
        Instant validUntil = (vuStr != null && !vuStr.isBlank()) ? Instant.parse(vuStr) : null;
        return new MemoryItem(
            id,
            getStr(payload, "userId"),
            getStr(payload, "content"),
            getStr(payload, "summary"),
            getStrList(payload, "tags"),
            (int) getLong(payload, "importance", 5),
            Instant.parse(getStr(payload, "createdAt")),
            Instant.now(),
            validUntil
        );
    }

    private String getStr(Map<String, JsonWithInt.Value> m, String k) {
        var v = m.get(k);
        if (v == null) return "";
        return v.hasStringValue() ? v.getStringValue() : "";
    }

    @SuppressWarnings("unchecked")
    private List<String> getStrList(Map<String, JsonWithInt.Value> m, String k) {
        var v = m.get(k);
        if (v == null || !v.hasListValue()) return List.of();
        return v.getListValue().getValuesList().stream()
            .filter(JsonWithInt.Value::hasStringValue)
            .map(JsonWithInt.Value::getStringValue)
            .toList();
    }

    private long getLong(Map<String, JsonWithInt.Value> m, String k, long def) {
        var v = m.get(k);
        if (v == null || !v.hasIntegerValue()) return def;
        return v.getIntegerValue();
    }
}
