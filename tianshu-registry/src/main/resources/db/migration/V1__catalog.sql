-- Skill registry catalog (Sprint B, V1)

CREATE TABLE skill_catalog (
    slug            VARCHAR(80)  PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    author          VARCHAR(128),
    description     TEXT,
    tags            TEXT[],
    latest_version  VARCHAR(32)  NOT NULL,
    total_downloads BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE skill_version (
    id             BIGSERIAL    PRIMARY KEY,
    slug           VARCHAR(80)  NOT NULL REFERENCES skill_catalog(slug) ON DELETE CASCADE,
    version        VARCHAR(32)  NOT NULL,
    sha256         VARCHAR(64)  NOT NULL,
    size_bytes     BIGINT       NOT NULL,
    blob_path      TEXT         NOT NULL,
    triggers       TEXT[],
    required_tools TEXT[],
    downloads      BIGINT       NOT NULL DEFAULT 0,
    published_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_skill_version_slug_version UNIQUE (slug, version)
);

CREATE INDEX idx_skill_version_slug ON skill_version(slug);
