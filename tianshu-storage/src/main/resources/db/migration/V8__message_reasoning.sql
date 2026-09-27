-- V8: persist deep-thinking/reasoning text on assistant messages.
-- reasoning_content is streamed live as THINKING_TOKEN events; without a column
-- it vanished on page refresh. Stored verbatim (nullable; only ASSISTANT rows use it).
ALTER TABLE messages ADD COLUMN IF NOT EXISTS reasoning TEXT;
