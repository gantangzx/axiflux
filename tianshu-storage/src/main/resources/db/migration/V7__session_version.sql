-- V7__session_version.sql
-- Flyway migration: add optimistic-locking version column to sessions

ALTER TABLE sessions ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;