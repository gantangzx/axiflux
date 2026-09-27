-- V11: installed skill version (Sprint C market client).
-- 台账记录已安装技能的版本号，用于市场页「可更新」标记与批量更新。
-- 来源：registry 安装时 .skill-origin.json 的 version；其余来源可由 SKILL.md frontmatter 回填。
ALTER TABLE skills ADD COLUMN IF NOT EXISTS version VARCHAR(32);
