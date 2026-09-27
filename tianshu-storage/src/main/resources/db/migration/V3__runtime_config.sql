-- V3: runtime configuration overrides
--
-- Stores hot-tunable settings changed through the runtime config API
-- (GET/POST /api/v1/config). Rows here override application.yml at startup so
-- changes made via the API survive restarts. Plain key/value columns keep this
-- portable across PostgreSQL versions (no JSONB needed for a flat map).
CREATE TABLE IF NOT EXISTS runtime_config (
    config_key    VARCHAR(128) PRIMARY KEY,
    config_value  TEXT,
    value_type    VARCHAR(16) NOT NULL DEFAULT 'string',
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_by    VARCHAR(64)
);

COMMENT ON TABLE runtime_config IS 'Hot-tunable config overrides applied over application.yml at startup.';
