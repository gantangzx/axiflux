-- Per-agent tool permissions: widen agent_definition with an optional tool
-- whitelist (comma-separated) and a risk ceiling (SAFE/READ/NETWORK/WRITE/DESTRUCTIVE).
-- NULL/empty means "no extra restriction" (the deployment policy still applies).
ALTER TABLE agent_definition ADD COLUMN allowed_tools text;
ALTER TABLE agent_definition ADD COLUMN risk_ceiling varchar(16);
