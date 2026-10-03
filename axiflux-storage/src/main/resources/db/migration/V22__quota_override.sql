-- P3-4: manual per-org quota grants / overrides (admin bonus, comp, enterprise deal).
-- A row overrides the plan's default limit for one dimension in one period,
-- or (period IS NULL) for the current and all future periods until revoked.
-- limit semantics mirror the plan caps: 0 = unlimited.
CREATE TABLE IF NOT EXISTS quota_override (
    id              BIGSERIAL PRIMARY KEY,
    org_id          VARCHAR(64)  NOT NULL REFERENCES organization(id),
    period          VARCHAR(7),                       -- NULL = ongoing (all periods)
    dimension       VARCHAR(16)  NOT NULL,            -- tokens | turns
    limit_value     BIGINT       NOT NULL,            -- 0 = unlimited
    reason          VARCHAR(255),
    granted_by      VARCHAR(64),
    revoked         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_quota_override_lookup
    ON quota_override (org_id, period, dimension, revoked);
