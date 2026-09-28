-- V26: durable checkpoints for state-graph workflow runs
--
-- Holds paused runs so they survive restarts and can be resumed. The full
-- Checkpoint (including GraphState) is stored as JSON in payload; denormalized
-- columns support listing/filtering. status is an atomic latch: a resume only
-- claims a PAUSED row (flipping it to RESUMING), preventing two concurrent
-- resumes from driving the same run twice.
CREATE TABLE graph_checkpoint (
    run_id      VARCHAR(64)  NOT NULL PRIMARY KEY,
    graph_name  VARCHAR(128) NOT NULL,
    node_id     VARCHAR(128) NOT NULL,
    user_id     VARCHAR(64),
    session_id  VARCHAR(64),
    status      VARCHAR(16)  NOT NULL DEFAULT 'PAUSED',
    reason      VARCHAR(256),
    payload     TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_graph_checkpoint_user   ON graph_checkpoint (user_id);
CREATE INDEX idx_graph_checkpoint_status ON graph_checkpoint (status);

COMMENT ON TABLE  graph_checkpoint IS 'Paused state-graph workflow runs awaiting resume.';
COMMENT ON COLUMN graph_checkpoint.status IS 'Atomic resume latch: PAUSED (waiting) or RESUMING (claimed, run executing).';
