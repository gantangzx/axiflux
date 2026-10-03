-- V10: skill install ledger (本地台账).
-- 文件系统是运行时存储（skills/<name>/），数据库只做台账/注册表：
-- 记录技能来源（git:owner/repo@ref / zip URL / local:路径 / builtin）与内容校验和，
-- 支撑启动对账、市场页「已安装」状态与后续注册表同步。
ALTER TABLE skills ADD COLUMN IF NOT EXISTS source   VARCHAR(512);
ALTER TABLE skills ADD COLUMN IF NOT EXISTS checksum VARCHAR(64);
