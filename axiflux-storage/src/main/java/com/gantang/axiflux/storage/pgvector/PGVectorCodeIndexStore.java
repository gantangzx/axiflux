package com.gantang.axiflux.storage.pgvector;

import com.gantang.reaxon.api.codeindex.CodeIndexStore;
import com.gantang.axiflux.storage.embedding.HttpEmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/**
 * PostgreSQL + pgvector backed {@link CodeIndexStore}.
 *
 * <p>Chunks live in a dedicated {@code code_chunks} table (kept separate from
 * {@code memory_items}, which stores conversational memory). The chunk id is a
 * deterministic UUID from {@code root|relPath|chunkIndex} so re-indexing a file
 * upserts cleanly. DDL is created idempotently at construction (fault-tolerant:
 * a server without the pgvector extension logs a warning and leaves
 * {@link #available()} false rather than failing startup).
 */
public final class PGVectorCodeIndexStore implements CodeIndexStore {

    private static final Logger log = LoggerFactory.getLogger(PGVectorCodeIndexStore.class);

    private final DataSource ds;
    private final int dimension;
    private final HttpEmbeddingClient embeddingClient;
    private volatile boolean ready;

    public PGVectorCodeIndexStore(DataSource ds, PGVectorLongTermMemory.Config config) {
        this(ds, config, null);
    }

    /**
     * @param proxy optional egress {@link java.net.ProxySelector} (P1-5) applied to
     *              the embedding HTTP client; null = direct connection.
     */
    public PGVectorCodeIndexStore(DataSource ds, PGVectorLongTermMemory.Config config,
                                  java.net.ProxySelector proxy) {
        this.ds = ds;
        this.dimension = config.dimension();
        this.embeddingClient = new HttpEmbeddingClient(
            config.embedUrl(), config.embedApiKey(), config.embedModel(), dimension, proxy);
        initTable();
    }

    private void initTable() {
        String sql = """
            DO $ci$\n
            BEGIN
                CREATE EXTENSION IF NOT EXISTS vector;

                EXECUTE $ddl$
                    CREATE TABLE IF NOT EXISTS code_chunks (
                        id          VARCHAR(64)  PRIMARY KEY,
                        root        VARCHAR(512) NOT NULL,
                        rel_path    VARCHAR(512) NOT NULL,
                        lang        VARCHAR(32),
                        chunk_idx   INTEGER      NOT NULL,
                        start_line  INTEGER,
                        end_line    INTEGER,
                        content     TEXT         NOT NULL,
                        file_hash   VARCHAR(64),
                        embedding   vector(%d),
                        updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
                    )
                $ddl$;

                BEGIN
                    EXECUTE $ddl$
                        CREATE INDEX IF NOT EXISTS idx_code_chunks_embedding
                            ON code_chunks USING hnsw (embedding vector_cosine_ops)
                            WITH (m = 16, ef_construction = 64)
                    $ddl$;
                EXCEPTION WHEN OTHERS THEN
                    RAISE NOTICE 'code_chunks HNSW index not created (pgvector too old?): %%', SQLERRM;
                END;

                EXECUTE $ddl$
                    CREATE INDEX IF NOT EXISTS idx_code_chunks_root_path
                        ON code_chunks(root, rel_path)
                $ddl$;

                RAISE NOTICE 'PGVector code_chunks table ready';
            EXCEPTION WHEN OTHERS THEN
                RAISE NOTICE 'PGVector code_chunks setup skipped (pgvector extension unavailable?): %%', SQLERRM;
            END
            $ci$;
            """.formatted(dimension);

        try (var conn = ds.getConnection(); var s = conn.createStatement()) {
            s.execute(sql);
            // Verify the table actually exists (the DO block swallows errors).
            try (var rs = s.executeQuery("SELECT to_regclass('public.code_chunks')")) {
                ready = rs.next() && rs.getString(1) != null;
            }
            log.info("PGVector code_chunks DDL executed (dimension={}, ready={})", dimension, ready);
        } catch (SQLException e) {
            ready = false;
            log.warn("Could not initialize code_chunks table (pgvector installed?): {}", e.getMessage());
        }
    }

    @Override
    public boolean available() {
        return ready;
    }

    @Override
    public Map<String, String> fileHashes(String root) {
        Map<String, String> out = new HashMap<>();
        String sql = "SELECT rel_path, MAX(file_hash) FROM code_chunks WHERE root = ? GROUP BY rel_path";
        try (var conn = ds.getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, root);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException e) {
            throw new RuntimeException("code index fileHashes failed", e);
        }
        return out;
    }

    @Override
    public void upsertFile(String root, String relPath, String lang, String fileHash, List<Chunk> chunks) {
        try (var conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (var del = conn.prepareStatement(
                        "DELETE FROM code_chunks WHERE root = ? AND rel_path = ?")) {
                    del.setString(1, root);
                    del.setString(2, relPath);
                    del.executeUpdate();
                }
                if (!chunks.isEmpty()) {
                    try (var ins = conn.prepareStatement("""
                        INSERT INTO code_chunks
                          (id, root, rel_path, lang, chunk_idx, start_line, end_line, content, file_hash, embedding, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector, NOW())
                        """)) {
                        for (Chunk c : chunks) {
                            float[] emb = embeddingClient.embedOrNull(c.content());
                            if (emb == null) {
                                throw new IllegalStateException(
                                    "embedding endpoint returned no vector for " + relPath
                                        + "#" + c.chunkIndex() + " — check axiflux.vector.embed-* config");
                            }
                            ins.setString(1, chunkId(root, relPath, c.chunkIndex()));
                            ins.setString(2, root);
                            ins.setString(3, relPath);
                            ins.setString(4, lang);
                            ins.setInt(5, c.chunkIndex());
                            ins.setInt(6, c.startLine());
                            ins.setInt(7, c.endLine());
                            ins.setString(8, c.content());
                            ins.setString(9, fileHash);
                            ins.setString(10, vectorToPg(emb));
                            ins.addBatch();
                        }
                        ins.executeBatch();
                    }
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("code index upsert failed for " + relPath, e);
        }
    }

    @Override
    public void deleteFiles(String root, Collection<String> relPaths) {
        if (relPaths == null || relPaths.isEmpty()) return;
        try (var conn = ds.getConnection();
             var ps = conn.prepareStatement("DELETE FROM code_chunks WHERE root = ? AND rel_path = ?")) {
            conn.setAutoCommit(false);
            try {
                for (String p : relPaths) {
                    ps.setString(1, root);
                    ps.setString(2, p);
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
            throw new RuntimeException("code index deleteFiles failed", e);
        }
    }

    @Override
    public List<CodeHit> search(String root, String query, int topK) {
        if (query == null || query.isBlank()) return List.of();
        float[] qvec = embeddingClient.embedOrNull(query);
        if (qvec == null) {
            log.warn("Embedding unavailable for codebase query; returning no hits");
            return List.of();
        }
        boolean allRoots = root == null || root.isBlank();
        String sql = allRoots
            ? """
              SELECT rel_path, lang, start_line, end_line, content, (embedding <=> ?::vector) AS distance
              FROM code_chunks WHERE embedding IS NOT NULL
              ORDER BY distance LIMIT ?
              """
            : """
              SELECT rel_path, lang, start_line, end_line, content, (embedding <=> ?::vector) AS distance
              FROM code_chunks WHERE root = ? AND embedding IS NOT NULL
              ORDER BY distance LIMIT ?
              """;
        List<CodeHit> hits = new ArrayList<>();
        try (var conn = ds.getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, vectorToPg(qvec));
            if (allRoots) {
                ps.setInt(2, Math.max(1, topK));
            } else {
                ps.setString(2, root);
                ps.setInt(3, Math.max(1, topK));
            }
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    double distance = rs.getDouble(6);
                    double score = Math.max(0.0, 1.0 - distance);
                    hits.add(new CodeHit(
                        rs.getString(1), rs.getString(2),
                        rs.getInt(3), rs.getInt(4), score, rs.getString(5)));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("code index vector search failed", e);
        }
        return hits;
    }

    @Override
    public IndexStats stats(String root) {
        boolean allRoots = root == null || root.isBlank();
        String sql = allRoots
            ? "SELECT COUNT(DISTINCT rel_path), COUNT(*) FROM code_chunks"
            : "SELECT COUNT(DISTINCT rel_path), COUNT(*) FROM code_chunks WHERE root = ?";
        try (var conn = ds.getConnection(); var ps = conn.prepareStatement(sql)) {
            if (!allRoots) ps.setString(1, root);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) return new IndexStats(allRoots ? "(all roots)" : root, rs.getInt(1), rs.getInt(2));
            }
        } catch (SQLException e) {
            throw new RuntimeException("code index stats failed", e);
        }
        return new IndexStats(allRoots ? "(all roots)" : root, 0, 0);
    }

    @Override
    public void clearRoot(String root) {
        try (var conn = ds.getConnection();
             var ps = conn.prepareStatement("DELETE FROM code_chunks WHERE root = ?")) {
            ps.setString(1, root);
            int n = ps.executeUpdate();
            log.info("Cleared {} code chunks for root {}", n, root);
        } catch (SQLException e) {
            throw new RuntimeException("code index clearRoot failed", e);
        }
    }

    private static String chunkId(String root, String relPath, int chunkIndex) {
        String key = root + "|" + relPath + "|" + chunkIndex;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String vectorToPg(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
