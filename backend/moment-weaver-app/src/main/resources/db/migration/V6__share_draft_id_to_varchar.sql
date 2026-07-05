-- V6: change share_link.draft_id from BIGINT to VARCHAR(64)
--
-- 背景：draft 实际数据存储在 MongoDB（NarrativeDraft._id 是 24 位十六进制 ObjectId）。
-- 之前 V4 错误的把 share_link.draft_id 声明为 BIGINT（按 MySQL 自增 ID 假设），导致：
--   1. 前端发 ObjectId 字符串（如 "6a2fc3cfde28804634778dc5"）时，Jackson 反序列化为 Long 失败；
--   2. 即使能写入，BIGINT 也存不下 24 位 hex 转十进制后的 10^28 量级（远超 BIGINT 上限 ~9.2*10^18）。
--
-- 本迁移把字段类型改为 VARCHAR(64)，彻底兼容 MongoDB ObjectId 字符串。
-- 历史数据如果 draft_id 原本是 NULL 或为空字符串，MODIFY COLUMN 会自动转换。
-- 如果已有非 NULL 数字数据，需要先转字符串（这里假设没有遗留历史数据；如需迁移可手动处理）。

ALTER TABLE `share_link`
    MODIFY COLUMN `draft_id` VARCHAR(64) NULL COMMENT '关联的成稿 id（MongoDB ObjectId 字符串）';

-- idx_share_draft 索引类型 BIGINT → VARCHAR(64)，MySQL 会自动重建索引，无需 DROP/ADD。
-- 但为保险起见，重建一次索引（同名同字段，幂等）：
ALTER TABLE `share_link` DROP INDEX `idx_share_draft`;
ALTER TABLE `share_link` ADD KEY `idx_share_draft` (`draft_id`);