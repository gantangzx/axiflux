-- V12: fact expiry / supersession (P1-4 temporal memory).
--
-- valid_until   NULL = the fact is currently true and retrievable.
--               Timestamp = when it stopped being true (superseded by a newer
--               same-key fact, or an explicitly short-lived fact expiring).
-- superseded_by id of the memory row that replaced this one (audit chain).
--
-- All retrieval paths (vector / scored / keyword) filter
-- (valid_until IS NULL OR valid_until > now()); getAll for the management UI
-- keeps expired rows visible. The runtime PGVectorLongTermMemory DDL applies
-- the same ALTERs idempotently for non-Flyway deployments.

ALTER TABLE memory_items ADD COLUMN IF NOT EXISTS valid_until   TIMESTAMPTZ;
ALTER TABLE memory_items ADD COLUMN IF NOT EXISTS superseded_by VARCHAR(64);

-- Active-fact retrieval is always scoped by user; partial index keeps it small.
CREATE INDEX IF NOT EXISTS idx_memory_items_user_valid
    ON memory_items (user_id, valid_until);
