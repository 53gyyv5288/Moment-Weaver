-- ============================================================
-- V12：人物（Subject）关联家族成员（M11 Phase 2）
-- ============================================================
-- 背景：
--   原 Subject 表只有 has_account / linked_user_id 字段，标记被采访者
--   是否注册过。本 migration 加 family_member_id 字段，标记"这个被采访者
--   是从家族成员里选出来的"：
--
--   - family_member_id NOT NULL：被采访者直接对应一个家族成员（已注册）
--   - family_member_id NULL    ：纯匿名被采访者（老数据 / 一次性 token 授权）
--
--   区别：
--     has_account=0/1 + linked_user_id IS NULL/NOT NULL  ← 老字段不动
--     family_member_id NULL/NOT NULL                     ← 新字段（独立）
--
--   用一个新字段的好处：
--     - 老数据不动（family_member_id 默认为 NULL）
--     - 未来可加 FK 约束到 family_member 表
--     - 查询"哪些被采访者是家族成员" = WHERE family_member_id IS NOT NULL
-- ============================================================

ALTER TABLE `subject`
    ADD COLUMN `family_member_id` BIGINT NULL
        COMMENT '关联的家族成员 id（NULL=匿名被采访者；非空=从家族成员选出来的）'
        AFTER `linked_user_id`,
    ADD KEY `idx_family_member` (`family_member_id`);

-- 注：暂不加外键约束（family_member 可能在某些部署里被软删）
-- 业务层校验由 SubjectService.create() 完成
