-- V2 (Sprint D): skill lifecycle + install stats + content signing.
-- status: published（正常）| deprecated（下架，客户端更新检查时提示）
ALTER TABLE skill_catalog ADD COLUMN IF NOT EXISTS status        VARCHAR(16) NOT NULL DEFAULT 'published';
ALTER TABLE skill_catalog ADD COLUMN IF NOT EXISTS total_installs BIGINT      NOT NULL DEFAULT 0;
-- Ed25519 signature over the sha256 hex (base64); null when signing is disabled.
ALTER TABLE skill_version ADD COLUMN IF NOT EXISTS signature TEXT;
