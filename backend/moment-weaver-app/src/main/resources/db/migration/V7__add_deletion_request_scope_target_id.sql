-- =============================================================
-- V7: deletion_request.scope_target_id 补字段（M5-B.1 漏建修复）
-- =============================================================
-- 背景：V4 注释里声称 "已经建好 scope_target_type / requested_user_id /
--       grace_expires_at / executed_at / export_oss_key"，但实际 SQL 只加了
--       5 个，遗漏了 scope_target_id。
--       DeletionRequest.java 实体类声明了 private String scopeTargetId，
--       MyBatis-Plus 默认按驼峰转下划线映射成 scope_target_id，
--       导致 RecycleBinService.list() 触发：
--           Unknown column 'scope_target_id' in 'field list'
-- 修复：补建 VARCHAR(64) 列（兼容 MongoDB ObjectId 字符串）。
--       V1 旧的 target_id BIGINT 列保留不动（已有数据兼容），新代码
--       统一使用 scope_target_id 写入（写代码侧也建议同步处理）。

ALTER TABLE `deletion_request`
    ADD COLUMN `scope_target_id` VARCHAR(64) NULL
    COMMENT '目标 id（字符串，兼容 MongoDB ObjectId 与自增 id 字符串化）'
    AFTER `scope_target_type`;

-- 索引：按 (类型, 目标 id) 查，例如"这个 subject 当前有几个 pending 删除申请"
ALTER TABLE `deletion_request`
    ADD KEY `idx_deletion_target` (`scope_target_type`, `scope_target_id`);