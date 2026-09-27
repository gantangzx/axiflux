package com.gantang.tianshu.storage.pgvector;

import com.gantang.tianshu.api.memory.LongTermMemory;
import com.gantang.tianshu.api.memory.MemoryItem;
import com.gantang.tianshu.api.memory.ScoredMemory;
import com.gantang.tianshu.api.memory.SummaryGenerator;
import com.gantang.tianshu.storage.embedding.HttpEmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * PostgreSQL + pgvector-backed long-term memory.
 *
 * <p>Stores embeddings in a {@code vector} column (pgvector extension) and structured
 * metadata in the {@code memory_items} table.  Both halves share the same primary key so
 * deletes and updates are atomic within a single connection.
 *
 * <p>Requires: PostgreSQL 13+ with the {@code pgvector} extension installed.
 * Install once per database: {@code CREATE EXTENSION IF NOT EXISTS vector;}
 *
 * <p>DDL (run via Flyway or manually):
 * <pre>{@code
 * CREATE EXTENSION IF NOT EXISTS vector;
 *
 * CREATE TABLE memory_items (
 *     id          VARCHAR(64)  PRIMARY KEY,
 *     user_id     VARCHAR(64)  NOT NULL,
 *     content     TEXT         NOT NULL,
 *     summary     TEXT,
 *     tags        TEXT[],
 *     importance  INTEGER  DEFAULT 5,
 *     embedding   vector(1536),
 *     created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 *     updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
 * );
 *
 * CREATE INDEX ON memory_items USING hnsw (embedding vector_cosine_ops)
 *   WITH (m = 16, ef_construction = 64);
 * CREATE INDEX idx_memory_items_user_id ON memory_items(user_id);
 * }</pre>
 *
 * <p>Dimension is configurable via {@code tianshu.vector.dimension} (default 1536).
 */
public class PGVectorLongTermMemory implements LongTermMemory {

    private static final Logger log = LoggerFactory.getLogger(PGVectorLongTermMemory.class);

    private final DataSource ds;
    private final int dimension;
    private volatile boolean dimensionMismatch = false;
    private final HttpEmbeddingClient embeddingClient;
    private final SummaryGenerator summaryGenerator;

    public PGVectorLongTermMemory(DataSource ds, Config config) {
        this(ds, config, SummaryGenerator.truncating());
    }

    public PGVectorLongTermMemory(DataSource ds, Config config, SummaryGenerator summaryGenerator) {
        this(ds, config, summaryGenerator, null);
    }

    /**
     * @param proxy optional egress {@link java.net.ProxySelector} (P1-5) applied to
     *              the embedding HTTP client; null = direct connection.
     */
    public PGVectorLongTermMemory(DataSource ds, Config config, SummaryGenerator summaryGenerator,
                                  java.net.ProxySelector proxy) {
        this.ds = ds;
        this.dimension = config.dimension();
        this.embeddingClient = new HttpEmbeddingClient(
            config.embedUrl(), config.embedApiKey(),
            config.embedModel(), dimension, proxy);
        this.summaryGenerator = summaryGenerator != null ? summaryGenerator : SummaryGenerator.truncating();
        initTable();
    }

    // ─── Initialization ────────────────────────────────────────────────────────

    private volatile boolean tableReady = false;

    /**
     * Whether the memory_items table was verified present after DDL ran.
     * Exposes readiness for health probes and fail-fast checks (P2-1).
     */
    public boolean available() {
        return tableReady;
    }

    private void initTable() {
        // Idempotent, fault-tolerant DDL. If pgvector is not installed on the
        // server the whole block is skipped with a NOTICE (caught below as a
        // warning) instead of breaking startup; deployments on qdrant/none must
        // not fail. Flyway-managed databases run the same DDL via V6 migration.
        // EXECUTE makes the custom vector type resolve at runtime, after the
        // extension is created inside the block.
        String sql = """
            DO $oc$\n
            BEGIN
                CREATE EXTENSION IF NOT EXISTS vector;

                EXECUTE $ddl$
                    CREATE TABLE IF NOT EXISTS memory_items (
                        id            VARCHAR(64)  PRIMARY KEY,
                        user_id       VARCHAR(64)  NOT NULL,
                        content       TEXT         NOT NULL,
                        summary       TEXT,
                        tags          TEXT[],
                        importance    INTEGER  DEFAULT 5,
                        embedding     vector(%d),
                        created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        valid_until   TIMESTAMPTZ,
                        superseded_by VARCHAR(64)
                    )
                $ddl$;

                -- P1-4: fact expiry / supersession (added after initial deploy).
                -- valid_until = NULL means the fact is current; a timestamp means
                -- the moment it stopped being true (superseded or explicitly TTL).
                EXECUTE $ddl$
                    ALTER TABLE memory_items ADD COLUMN IF NOT EXISTS valid_until   TIMESTAMPTZ
                $ddl$;
                EXECUTE $ddl$
                    ALTER TABLE memory_items ADD COLUMN IF NOT EXISTS superseded_by VARCHAR(64)
                $ddl$;
                EXECUTE $ddl$
                    CREATE INDEX IF NOT EXISTS idx_memory_items_user_valid
                        ON memory_items(user_id, valid_until)
                $ddl$;

                BEGIN
                    EXECUTE $ddl$
                        CREATE INDEX IF NOT EXISTS idx_memory_items_embedding
                            ON memory_items USING hnsw (embedding vector_cosine_ops)
                            WITH (m = 16, ef_construction = 64)
                    $ddl$;
                EXCEPTION WHEN OTHERS THEN
                    RAISE NOTICE 'HNSW index not created (pgvector too old?): %%', SQLERRM;
                END;

                EXECUTE $ddl$
                    CREATE INDEX IF NOT EXISTS idx_memory_items_user_id ON memory_items(user_id)
                $ddl$;

                RAISE NOTICE 'PGVector memory_items table ready';
            EXCEPTION WHEN OTHERS THEN
                RAISE NOTICE 'PGVector memory setup skipped (pgvector extension unavailable?): %%', SQLERRM;
            END
            $oc$;
            """.formatted(dimension);

        try (var conn = ds.getConnection(); var s = conn.createStatement()) {
            s.execute(sql);
            // Verify the table actually exists (the DO block swallows errors).
            try (var rs = s.executeQuery("SELECT to_regclass('public.memory_items')")) {
                tableReady = rs.next() && rs.getString(1) != null;
            }
            log.info("PGVector memory_items DDL executed (dimension={}, ready={})", dimension, tableReady);
            reconcileColumnDimension();
        } catch (SQLException e) {
            tableReady = false;
            log.warn("Could not initialize memory_items table (pgvector installed?): {}", e.getMessage());
        }
    }

    // ─── LongTermMemory ───────────────────────────────────────────────────────

    @Override
    public Mono<Void> store(String userId, MemoryItem item) {
        return Mono.<Void>fromRunnable(() -> storeSync(userId, item))
            .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Store a batch of memory items. Items are committed in sub-batches of 50
     * (per-transaction). If a sub-batch fails, earlier sub-batches are already
     * committed — the operation is <b>not atomic across the whole batch</b>.
     * Callers needing all-or-nothing semantics should wrap in their own
     * transaction or call {@link #store} individually (P2-10).
     */
    @Override
    public Mono<Void> storeBatch(String userId, List<MemoryItem> items) {
        return Mono.fromCallable(() -> { storeBatchSync(userId, items); return Boolean.TRUE; })
            .subscribeOn(Schedulers.boundedElastic())
            .then();
    }

    @Override
    public Mono<List<MemoryItem>> search(String userId, String query, int topK) {
        return Mono.fromCallable(() -> searchSync(userId, query, topK))
            .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<ScoredMemory>> searchScored(String userId, String query, int topK) {
        return Mono.fromCallable(() -> searchScoredSync(userId, query, topK))
            .subscribeOn(Schedulers.boundedElastic());
    }

    /** Vector search returning cosine similarity (1 - distance) for dedup. */
    private List<ScoredMemory> searchScoredSync(String userId, String query, int topK) {
        if (query == null || query.isBlank()) return List.of();
        float[] queryVec = generateEmbedding(query);
        if (queryVec == null) {
            // No vector to compare: return unscored keyword hits so callers don't
            // falsely treat them as duplicates (unscored ⇒ never near-duplicate).
            return searchByKeywordSync(userId, query).stream()
                .map(ScoredMemory::unscored).toList();
        }
        String sql = """
            SELECT id, user_id, content, summary, tags, importance, created_at, updated_at, valid_until,
                   (embedding <=> ?::vector) AS distance
            FROM   memory_items
            WHERE  user_id = ? AND embedding IS NOT NULL
                   AND (valid_until IS NULL OR valid_until > NOW())
            ORDER  BY distance
            LIMIT  ?
            """;
        try (var conn = ds.getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, vectorToPg(queryVec));
            ps.setString(2, userId);
            ps.setInt(3, topK);
            try (var rs = ps.executeQuery()) {
                List<ScoredMemory> results = new ArrayList<>();
                while (rs.next()) {
                    double distance = rs.getDouble(10);
                    double similarity = rs.wasNull() ? ScoredMemory.UNSCORED : Math.max(0.0, 1.0 - distance);
                    results.add(new ScoredMemory(rowToItem(rs), similarity));
                }
                return results;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Vector scored search failed", e);
        }
    }

    @Override
    public Mono<List<MemoryItem>> searchByKeyword(String userId, String keyword) {
        return Mono.fromCallable(() -> searchByKeywordSync(userId, keyword))
            .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<MemoryItem>> getAll(String userId) {
        return Mono.fromCallable(() -> getAllSync(userId))
            .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Boolean> delete(String memoryId) {
        return Mono.fromCallable(() -> deleteSync(memoryId))
            .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> deleteAll(String userId) {
        return Mono.fromCallable(() -> { deleteAllSync(userId); return Boolean.TRUE; })
            .subscribeOn(Schedulers.boundedElastic())
            .then();
    }

    @Override
    public Mono<Integer> compress(String userId) {
        // LLM summarization: fetch high-importance items, generate summaries,
        // and update them in-place.  Plugs into the same embedUrl / embedApiKey.
        return Mono.fromCallable(() -> compressSync(userId))
            .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Boolean> updateMemory(MemoryItem existing, String content,
                                      String summary, java.util.List<String> tags, int importance) {
        return Mono.fromCallable(() -> updateSync(existing, content, summary, tags, importance))
            .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<java.util.List<String>> listUserIds() {
        return Mono.fromCallable(this::listUserIdsSync)
            .subscribeOn(Schedulers.boundedElastic());
    }

    /** In-place UPDATE: regenerate embedding for the new content, keep id/created_at. */
    private Boolean updateSync(MemoryItem existing, String content, String summary,
                               java.util.List<String> tags, int importance) {
        float[] embedding = generateEmbedding(content);
        // Defense in depth: the controller already validates ownership, but the
        // write itself must never touch another tenant's row even if a caller bug
        // or future code path passes a foreign id.
        String sql = """
            UPDATE memory_items
            SET content = ?, summary = ?, tags = ?, importance = ?,
                embedding = COALESCE(?::vector, embedding), updated_at = NOW()
            WHERE id = ? AND user_id = ?
            """;
        try (var conn = ds.getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, content);
            ps.setString(2, summary);
            ps.setArray(3, conn.createArrayOf("text", tags != null ? tags.toArray() : new String[0]));
            ps.setInt(4, importance);
            // vector param needs an explicit cast (custom type); null keeps the old vector.
            if (embedding != null) ps.setString(5, vectorToPg(embedding)); else ps.setNull(5, java.sql.Types.OTHER);
            ps.setString(6, existing.id());
            ps.setString(7, existing.userId());
            int n = ps.executeUpdate();
            if (n == 0) log.warn("Memory update affected 0 rows (id={} already deleted or not owned by {})", existing.id(), existing.userId());
            return n > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Memory update failed", e);
        }
    }

    private java.util.List<String> listUserIdsSync() {
        String sql = "SELECT DISTINCT user_id FROM memory_items WHERE user_id IS NOT NULL";
        java.util.List<String> ids = new java.util.ArrayList<>();
        try (var conn = ds.getConnection(); var ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            while (rs.next()) ids.add(rs.getString(1));
        } catch (SQLException e) {
            throw new RuntimeException("listUserIds failed", e);
        }
        return ids;
    }

    // ─── Sync implementations (called from boundedElastic) ────────────────────

    private void storeSync(String userId, MemoryItem item) {
        float[] embedding = generateEmbedding(item.content());
        if (embedding == null) {
            log.warn("Storing memory item without a vector (embedding unavailable); "
                + "it remains retrievable by keyword search only");
        }
        String id = (item.id() != null && !item.id().isBlank())
            ? item.id() : UUID.randomUUID().toString();
        String sql = """
            INSERT INTO memory_items (id, user_id, content, summary, tags, importance, embedding, created_at, updated_at, valid_until)
            VALUES (?, ?, ?, ?, ?, ?, ?::vector, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                content    = EXCLUDED.content,
                summary    = EXCLUDED.summary,
                tags       = EXCLUDED.tags,
                importance = EXCLUDED.importance,
                embedding  = EXCLUDED.embedding,
                updated_at = EXCLUDED.updated_at,
                valid_until = EXCLUDED.valid_until
            """;

        try (var conn = ds.getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, userId);
            ps.setString(3, item.content());
            ps.setString(4, item.summary() != null ? item.summary() : "");
            ps.setArray(5, conn.createArrayOf("text",
                item.tags() != null ? item.tags().toArray() : new String[0]));
            ps.setInt(6, item.importance());
            if (embedding != null) ps.setString(7, vectorToPg(embedding));
            else ps.setNull(7, Types.OTHER);
            ps.setTimestamp(8, Timestamp.from(item.createdAt() != null ? item.createdAt() : Instant.now()));
            ps.setTimestamp(9, Timestamp.from(Instant.now()));
            setInstantOrNull(ps, 10, item.validUntil());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to store memory item: " + id, e);
        }
    }

    /**
     * Batch insert with batch size limit to avoid huge transactions.
     * Each 50-item sub-batch is its own transaction: a failure rolls back only
     * the current sub-batch; earlier sub-batches remain committed (P2-10).
     */
    private void storeBatchSync(String userId, List<MemoryItem> items) {
        // Batch insert with batch size limit to avoid huge transactions
        int batchSize = 50;
        for (int i = 0; i < items.size(); i += batchSize) {
            List<MemoryItem> batch = items.subList(i, Math.min(items.size(), i + batchSize));
            try (var conn = ds.getConnection()) {
                conn.setAutoCommit(false);
                try (var ps = conn.prepareStatement("""
                    INSERT INTO memory_items (id, user_id, content, summary, tags, importance, embedding, created_at, updated_at, valid_until)
                    VALUES (?, ?, ?, ?, ?, ?, ?::vector, ?, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET
                        content = EXCLUDED.content, summary = EXCLUDED.summary,
                        tags = EXCLUDED.tags, importance = EXCLUDED.importance,
                        embedding = EXCLUDED.embedding, updated_at = EXCLUDED.updated_at,
                        valid_until = EXCLUDED.valid_until
                    """)) {
                    for (MemoryItem item : batch) {
                        float[] embedding = generateEmbedding(item.content());
                        String id = (item.id() != null && !item.id().isBlank())
                            ? item.id() : UUID.randomUUID().toString();
                        ps.setString(1, id);
                        ps.setString(2, userId);
                        ps.setString(3, item.content());
                        ps.setString(4, item.summary() != null ? item.summary() : "");
                        ps.setArray(5, conn.createArrayOf("text",
                            item.tags() != null ? item.tags().toArray() : new String[0]));
                        ps.setInt(6, item.importance());
                        if (embedding != null) ps.setString(7, vectorToPg(embedding));
                        else ps.setNull(7, Types.OTHER);
                        ps.setTimestamp(8, Timestamp.from(item.createdAt() != null ? item.createdAt() : Instant.now()));
                        ps.setTimestamp(9, Timestamp.from(Instant.now()));
                        setInstantOrNull(ps, 10, item.validUntil());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to store memory batch", e);
            }
        }
    }

    private List<MemoryItem> searchSync(String userId, String query, int topK) {
        if (query == null || query.isBlank()) return List.of();
        float[] queryVec = generateEmbedding(query);
        // Embedding unavailable (endpoint down/misconfigured): a zero vector has
        // undefined cosine distance and would scramble ranking, so degrade to
        // keyword search rather than returning garbage.
        if (queryVec == null) {
            log.warn("Embedding unavailable for query; degrading memory search to keyword matching");
            return searchByKeywordSync(userId, query);
        }
        String sql = """
            SELECT id, user_id, content, summary, tags, importance, created_at, updated_at, valid_until
            FROM   memory_items
            WHERE  user_id = ?
                   AND (valid_until IS NULL OR valid_until > NOW())
            ORDER  BY embedding <=> ?::vector
            LIMIT  ?
            """;

        try (var conn = ds.getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, vectorToPg(queryVec));
            ps.setInt(3, topK);
            try (var rs = ps.executeQuery()) {
                List<MemoryItem> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(rowToItem(rs));
                }
                return results;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Vector search failed", e);
        }
    }

    private List<MemoryItem> searchByKeywordSync(String userId, String keyword) {
        String sql = """
            SELECT id, user_id, content, summary, tags, importance, created_at, updated_at, valid_until
            FROM   memory_items
            WHERE  user_id = ? AND (
                content ILIKE ? OR
                summary ILIKE ? OR
                ? = ANY(tags)
            )
            AND (valid_until IS NULL OR valid_until > NOW())
            LIMIT  100
            """;

        try (var conn = ds.getConnection(); var ps = conn.prepareStatement(sql)) {
            String kw = "%" + keyword + "%";
            ps.setString(1, userId);
            ps.setString(2, kw);
            ps.setString(3, kw);
            ps.setString(4, keyword);
            try (var rs = ps.executeQuery()) {
                List<MemoryItem> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(rowToItem(rs));
                }
                return results;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Keyword search failed", e);
        }
    }

    private List<MemoryItem> getAllSync(String userId) {
        String sql = "SELECT id, user_id, content, summary, tags, importance, created_at, updated_at, valid_until " +
                     "FROM memory_items WHERE user_id = ? ORDER BY created_at DESC LIMIT 1000";

        try (var conn = ds.getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (var rs = ps.executeQuery()) {
                List<MemoryItem> results = new ArrayList<>();
                while (rs.next()) results.add(rowToItem(rs));
                return results;
            }
        } catch (SQLException e) {
            throw new RuntimeException("getAll failed", e);
        }
    }

    private boolean deleteSync(String memoryId) {
        try (var conn = ds.getConnection();
             var ps = conn.prepareStatement("DELETE FROM memory_items WHERE id = ?")) {
            ps.setString(1, memoryId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Delete failed: " + memoryId, e);
        }
    }

    private void deleteAllSync(String userId) {
        try (var conn = ds.getConnection();
             var ps = conn.prepareStatement("DELETE FROM memory_items WHERE user_id = ?")) {
            ps.setString(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("deleteAll failed for user: " + userId, e);
        }
    }

    private int compressSync(String userId) {
        // Fetch items with importance >= 7 that have no summary yet, generate summary
        // via LLM, then update them in DB.
        String selectSql = """
            SELECT id, content FROM memory_items
            WHERE user_id = ? AND importance >= 7 AND (summary IS NULL OR summary = '')
                  AND (valid_until IS NULL OR valid_until > NOW())
            LIMIT 20
            """;

        List<String[]> toUpdate = new ArrayList<>();
        try (var conn = ds.getConnection();
             var ps = conn.prepareStatement(selectSql)) {
            ps.setString(1, userId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    String content = rs.getString("content");
                    String summary = generateSummary(content);
                    toUpdate.add(new String[]{id, summary});
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("compress select failed", e);
        }

        if (toUpdate.isEmpty()) return 0;

        try (var conn = ds.getConnection();
             var ps = conn.prepareStatement(
                 "UPDATE memory_items SET summary = ?, updated_at = NOW() WHERE id = ?")) {
            conn.setAutoCommit(false);
            try {
                for (String[] pair : toUpdate) {
                    ps.setString(1, pair[1]);
                    ps.setString(2, pair[0]);
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("compress update failed", e);
        }
        return toUpdate.size();
    }

    @Override
    public Mono<Boolean> invalidate(String memoryId, String supersededById) {
        return Mono.fromCallable(() -> invalidateSync(memoryId, supersededById))
            .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<String> supersede(MemoryItem existing, MemoryItem replacement) {
        return Mono.fromCallable(() -> supersedeSync(existing, replacement))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private Boolean invalidateSync(String memoryId, String supersededById) {
        String sql = """
            UPDATE memory_items
            SET valid_until = NOW(), superseded_by = ?, updated_at = NOW()
            WHERE id = ? AND valid_until IS NULL
            """;
        try (var conn = ds.getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, supersededById);
            ps.setString(2, memoryId);
            int n = ps.executeUpdate();
            if (n == 0) log.debug("invalidate affected 0 rows (id={}, already invalid or gone)", memoryId);
            return n > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Memory invalidate failed: " + memoryId, e);
        }
    }

    /** Insert the replacement row, then stamp the old row — one transaction so retrieval never sees neither/both. */
    private String supersedeSync(MemoryItem existing, MemoryItem replacement) {
        try (var conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try {
                storeOnConnection(conn, existing.userId(), replacement);
                // Defense in depth: scope the stamp to the owner's row so a
                // foreign id passed by a buggy caller can never invalidate data
                // belonging to another tenant.
                String upd = """
                    UPDATE memory_items
                    SET valid_until = NOW(), superseded_by = ?, updated_at = NOW()
                    WHERE id = ? AND user_id = ? AND valid_until IS NULL
                    """;
                try (var ps = conn.prepareStatement(upd)) {
                    ps.setString(1, replacement.id());
                    ps.setString(2, existing.id());
                    ps.setString(3, existing.userId());
                    int n = ps.executeUpdate();
                    if (n == 0) log.warn("supersede: old row {} not active (already invalid/deleted or not owned by {})", existing.id(), existing.userId());
                }
                conn.commit();
                return replacement.id();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Memory supersede failed: " + existing.id(), e);
        }
    }

    private void storeOnConnection(java.sql.Connection conn, String userId, MemoryItem item) throws SQLException {
        float[] embedding = generateEmbedding(item.content());
        String id = (item.id() != null && !item.id().isBlank())
            ? item.id() : UUID.randomUUID().toString();
        try (var ps = conn.prepareStatement("""
            INSERT INTO memory_items (id, user_id, content, summary, tags, importance, embedding, created_at, updated_at, valid_until)
            VALUES (?, ?, ?, ?, ?, ?, ?::vector, ?, ?, ?)
            """)) {
            ps.setString(1, id);
            ps.setString(2, userId);
            ps.setString(3, item.content());
            ps.setString(4, item.summary() != null ? item.summary() : "");
            ps.setArray(5, conn.createArrayOf("text",
                item.tags() != null ? item.tags().toArray() : new String[0]));
            ps.setInt(6, item.importance());
            if (embedding != null) ps.setString(7, vectorToPg(embedding));
            else ps.setNull(7, Types.OTHER);
            ps.setTimestamp(8, Timestamp.from(item.createdAt() != null ? item.createdAt() : Instant.now()));
            ps.setTimestamp(9, Timestamp.from(Instant.now()));
            setInstantOrNull(ps, 10, item.validUntil());
            ps.executeUpdate();
        }
    }

    private static void setInstantOrNull(java.sql.PreparedStatement ps, int idx, Instant when) throws SQLException {
        if (when != null) ps.setTimestamp(idx, Timestamp.from(when));
        else ps.setNull(idx, Types.TIMESTAMP);
    }

    // ─── Embedding ────────────────────────────────────────────────────────────

    /**
     * Generate embedding via HTTP call to the configured embedUrl.
     * Falls back to a zero vector when the endpoint is unavailable.
     *
     * <p>Expected embedUrl request body (OpenAI-compatible):
     * {@code {"model":"text-embedding-3-small","input":"..."}}
     *
     * @return embedding vector, or {@code null} when the embedding endpoint is unavailable
     *         (callers degrade to keyword search / vectorless store).
     */
    private float[] generateEmbedding(String text) {
        if (dimensionMismatch) {
            // The memory_items.embedding column was created with a different
            // vector dimension than the configured one (e.g. Flyway V6 created
            // vector(1536) but tianshu.vector.dimension=2048). Every INSERT of
            // a mismatched vector would hard-fail at the SQL layer, so degrade
            // to keyword/vectorless instead of throwing on each write (P1-1).
            return null;
        }
        return embeddingClient.embedOrNull(text);
    }

    /**
     * Detect a drift between the live column dimension and the configured one.
     * vector(N) stores N in pg_attribute.atttypmod; -1 means the column is
     * dimensionless (plain vector). A hard mismatch disables vector writes for
     * this process lifetime and is logged at ERROR so operators rebuild.
     */
    private void reconcileColumnDimension() {
        String sql = """
            SELECT a.atttypmod
            FROM pg_attribute a
            WHERE a.attrelid = 'memory_items'::regclass AND a.attname = 'embedding'
            """;
        try (var conn = ds.getConnection(); var ps = conn.prepareStatement(sql); var rs = ps.executeQuery()) {
            if (!rs.next()) return; // table not present yet (initTable skipped)
            int actual = rs.getInt(1);
            if (actual > 0 && actual != dimension) {
                dimensionMismatch = true;
                log.error("memory_items.embedding is vector({}) but tianshu.vector.dimension={}; " +
                    "vector writes disabled (keyword fallback). Recreate the column or restore the dimension.",
                    actual, dimension);
            }
        } catch (SQLException e) {
            // Not fatal: table may not exist on qdrant/none deployments.
            log.debug("Could not verify memory_items.embedding dimension: {}", e.getMessage());
        }
    }

    private String generateSummary(String content) {
        return summaryGenerator.summarize(content);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private MemoryItem rowToItem(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String userId = rs.getString("user_id");
        String content = rs.getString("content");
        String summary = rs.getString("summary");
        List<String> tags = parseTags(rs.getArray("tags"));
        int importance = rs.getInt("importance");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
        Timestamp vu = rs.getTimestamp("valid_until");
        Instant validUntil = vu != null ? vu.toInstant() : null;

        return new MemoryItem(id, userId, content, summary, tags, importance, createdAt, updatedAt, validUntil);
    }

    @SuppressWarnings("unchecked")
    private List<String> parseTags(Array arr) {
        if (arr == null) return List.of();
        try {
            String[] arr2 = (String[]) arr.getArray();
            return List.of(arr2);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Convert a float[] to the pgvector literal format:
     * '[0.123, -0.456, ...]'
     */
    private static String vectorToPg(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    // ─── Config record ────────────────────────────────────────────────────────

    /**
     * Configuration for PGVectorLongTermMemory.
     *
     * @param dimension    embedding dimension (default 1536)
     * @param embedUrl     URL of the embedding API (OpenAI-compatible: {@code /v1/embeddings})
     * @param embedApiKey  API key for the embedding service
     */
    public record Config(
        int dimension,
        String embedUrl,
        String embedApiKey,
        String embedModel
    ) {
        public Config {
            if (dimension <= 0) dimension = 1536;
            if (embedUrl == null) embedUrl = "https://api.openai.com/v1/embeddings";
            if (embedApiKey == null) embedApiKey = "";
            if (embedModel == null || embedModel.isBlank()) embedModel = "text-embedding-3-small";
        }

        public Config(com.gantang.tianshu.storage.vector.VectorStoreConfig c) {
            this(c.dimension(), c.embedUrl(), c.embedApiKey(), c.embedModel());
        }

        public static Config of(int dimension, String embedUrl, String embedApiKey, String embedModel) {
            return new Config(dimension, embedUrl, embedApiKey, embedModel);
        }
    }
}
