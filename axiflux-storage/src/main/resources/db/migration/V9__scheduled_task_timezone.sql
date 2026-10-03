-- V9: timezone support for cron scheduled tasks.
-- Null means the server's default timezone (pre-V9 behaviour).
ALTER TABLE scheduled_tasks ADD COLUMN IF NOT EXISTS timezone VARCHAR(64);
