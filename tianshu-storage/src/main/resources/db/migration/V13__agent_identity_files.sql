-- Per-agent identity files: widen agent_definition with three optional persona
-- files modelled on native agent workspace files, persisted per agent so
-- they are editable from the console and shared across all instances (DB, not fs).
--   soul                  SOUL.md  - persona, tone, opinions, boundaries
--   user_profile          USER.md  - who the user is and how to address them
--   operating_instructions AGENTS.md - operating rules / how to behave
-- All NULL/empty mean "no extra block"; the engine prepends whatever is present.
ALTER TABLE agent_definition ADD COLUMN soul text;
ALTER TABLE agent_definition ADD COLUMN user_profile text;
ALTER TABLE agent_definition ADD COLUMN operating_instructions text;

COMMENT ON COLUMN agent_definition.soul IS 'SOUL.md identity file: persona, tone, opinions, boundaries (optional).';
COMMENT ON COLUMN agent_definition.user_profile IS 'USER.md identity file: who the user is and how to address them (optional).';
COMMENT ON COLUMN agent_definition.operating_instructions IS 'AGENTS.md identity file: operating rules / how to behave (optional).';
