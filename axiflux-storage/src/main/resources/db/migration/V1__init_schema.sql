-- V1__init_schema.sql
-- Flyway migration: initial schema for axiflux-storage

-- Sessions
CREATE TABLE sessions (
    id              VARCHAR(64) PRIMARY KEY,
    user_id         VARCHAR(64) NOT NULL,
    agent_id        VARCHAR(64) NOT NULL,
    channel         VARCHAR(32) NOT NULL DEFAULT 'web',
    state           VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    metadata        JSONB,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    last_active_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sessions_user_id ON sessions(user_id);
CREATE INDEX idx_sessions_state_active ON sessions(state, last_active_at);

-- Messages (short-term memory)
CREATE TABLE messages (
    id              VARCHAR(64) PRIMARY KEY,
    session_id      VARCHAR(64) NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    role            VARCHAR(16) NOT NULL,
    content         TEXT NOT NULL,
    tool_call_id    VARCHAR(64),
    tool_name       VARCHAR(128),
    tool_calls      JSONB,
    attachments     JSONB,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_messages_session_created ON messages(session_id, created_at DESC);

-- Structured memory chunks
CREATE TABLE memory_chunks (
    id              BIGSERIAL PRIMARY KEY,
    user_id         VARCHAR(64) NOT NULL,
    memory_key      VARCHAR(256) NOT NULL,
    content         TEXT,
    summary         TEXT,
    tags            TEXT[],
    importance      INT DEFAULT 5,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, memory_key)
);
CREATE INDEX idx_memory_chunks_user_key ON memory_chunks(user_id, memory_key);
CREATE INDEX idx_memory_chunks_user_id ON memory_chunks(user_id);
CREATE INDEX idx_memory_chunks_tags ON memory_chunks USING gin(tags);

-- Skills registry
CREATE TABLE skills (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(128) UNIQUE NOT NULL,
    description     TEXT,
    path            VARCHAR(512) NOT NULL,
    triggers        TEXT[],
    enabled         BOOLEAN DEFAULT TRUE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX idx_skills_name ON skills(name);

-- Scheduled tasks
CREATE TABLE scheduled_tasks (
    id              VARCHAR(64) PRIMARY KEY,
    name            VARCHAR(256) NOT NULL,
    type            VARCHAR(32) NOT NULL,
    schedule        VARCHAR(256) NOT NULL,
    payload         JSONB NOT NULL,
    session_id      VARCHAR(64),
    user_id         VARCHAR(64) NOT NULL,
    enabled         BOOLEAN DEFAULT TRUE,
    next_run        TIMESTAMP,
    last_run        TIMESTAMP,
    run_count       INT DEFAULT 0,
    error_count     INT DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_scheduled_tasks_user_enabled ON scheduled_tasks(user_id, enabled);

-- Tool execution log (observability)
CREATE TABLE tool_executions (
    id              BIGSERIAL PRIMARY KEY,
    session_id      VARCHAR(64) NOT NULL,
    tool_name       VARCHAR(128) NOT NULL,
    call_id         VARCHAR(64),
    params          JSONB,
    success         BOOLEAN,
    duration_ms     INT,
    error           TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_tool_executions_session ON tool_executions(session_id);
CREATE INDEX idx_tool_executions_created ON tool_executions(created_at);
