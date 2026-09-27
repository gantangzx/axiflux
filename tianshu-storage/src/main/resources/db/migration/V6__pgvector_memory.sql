-- V6: long-term memory store backed by PGVector.
--
-- Runtime owner: PGVectorLongTermMemory (tianshu-storage). The runtime also
-- runs equivalent DDL idempotently on startup as a fallback for non-Flyway
-- deployments; keeping it here puts the schema under migration versioning.
--
-- The whole setup is wrapped in a fault-tolerant PL/pgSQL block: if the
-- pgvector extension is not installed on the server (the extension package is
-- missing entirely, not just CREATE EXTENSION rights), memory DDL is skipped
-- with a NOTICE instead of failing application startup — deployments using the
-- default qdrant vector backend must not be blocked. PGVectorLongTermMemory
-- surfaces a clear warning when the memory_items table is then missing.
--
-- NOTE: the embedding column is fixed at 1536 dimensions (OpenAI
-- text-embedding-ada-002; tianshu.vector.dimension defaults to 1536). DDL runs
-- via EXECUTE so the custom vector type is resolved at runtime, after the
-- extension has been created inside the same block.

DO $v6$
BEGIN
    CREATE EXTENSION IF NOT EXISTS vector;

    EXECUTE $ddl$
        CREATE TABLE IF NOT EXISTS memory_items (
            id         VARCHAR(64)  PRIMARY KEY,
            user_id    VARCHAR(64)  NOT NULL,
            content    TEXT         NOT NULL,
            summary    TEXT,
            tags       TEXT[],
            importance INTEGER  DEFAULT 5,
            embedding  vector(1536),
            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )
    $ddl$;

    -- HNSW needs pgvector >= 0.5; on older versions this is skipped (the table
    -- still works, only ANN search performance is lost).
    BEGIN
        EXECUTE $ddl$
            CREATE INDEX IF NOT EXISTS idx_memory_items_embedding
                ON memory_items USING hnsw (embedding vector_cosine_ops)
                WITH (m = 16, ef_construction = 64)
        $ddl$;
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'HNSW index not created (pgvector too old?): %', SQLERRM;
    END;

    EXECUTE $ddl$
        CREATE INDEX IF NOT EXISTS idx_memory_items_user_id ON memory_items(user_id)
    $ddl$;

    RAISE NOTICE 'PGVector memory_items table ready';
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'PGVector memory setup skipped (pgvector extension unavailable?): %', SQLERRM;
END
$v6$;
