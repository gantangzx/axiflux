-- V2: observability + cleanup

-- 1) Add user_id to tool_executions for per-user audit/usage analysis.
ALTER TABLE tool_executions ADD COLUMN IF NOT EXISTS user_id VARCHAR(64);

-- 2) Drop orphaned memory_chunks table. It had no writer: long-term memory is
--    implemented by PGVectorLongTermMemory in the memory_vectors table. The
--    corresponding entity/repository have been removed.
DROP TABLE IF EXISTS memory_chunks;
