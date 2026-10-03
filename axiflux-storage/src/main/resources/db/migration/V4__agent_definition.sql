-- V4: agent definitions
--
-- Persists named agent personas managed through the console (create/edit/delete).
-- Every row is an agent identity (display name, avatar emoji, persona/system
-- prompt, optional pinned model provider). The seeded built-in "default" agent is
-- protected from deletion. Sessions reference agents by agent_id.
CREATE TABLE IF NOT EXISTS agent_definition (
    agent_id      VARCHAR(64)  PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    description   VARCHAR(512),
    emoji         VARCHAR(16),
    system_prompt TEXT,
    provider      VARCHAR(64),
    is_builtin    BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE agent_definition IS 'Named, editable agent personas; rows back the /api/v1/agents CRUD API and survive restarts.';
