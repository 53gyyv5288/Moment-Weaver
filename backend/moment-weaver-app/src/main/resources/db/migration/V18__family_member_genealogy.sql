-- ============================================================
-- V18：家族成员（family_member）加家谱字段
-- ============================================================
-- 背景：
--   M14+ 用户决策：家族成员（FamilyMember）是家谱节点的"源头"，
--   被采访者（Subject）上的 generation / parentSubjectId 是冗余缓存。
--   V17 给 Subject 加了 3 个 genealogy 字段作为 MVP 占位；
--   V18 给 family_member 加正式字段，让家族管理员在「家族 → 成员管理」
--   创建家族成员时一次性录入代际和上一代。
--
-- 数据流（设计）：
--   family_member (source of truth)
--     ├─ generation                INT           -- 0=本人辈，负数=长辈，正数=晚辈
--     ├─ parent_family_member_id   BIGINT        -- 同家族的上一代 family_member.id
--     └─ parent_member_relation_type VARCHAR(16) -- father|mother|guardian
--
--   Subject (cache，V17 字段保留)
--     ├─ family_member_id  → 家族成员账号（NULL = 匿名 Subject）
--     ├─ generation        ← 创建/更新时从 FamilyMember 复制（匿名时手填）
--     └─ parent_subject_id ← 创建时映射 parent_family_member_id → 对应 Subject.id
--
--   FamilyTree.vue 直接渲染 Subject（已 JOIN 过 FamilyMember 在树聚合时填充）
--
-- 设计决策：
--   1. parent_family_member_id 不加 FK 约束（family_member 是软删；跨家族语义不强）
--   2. 业务层校验由 FamilyMemberService.validateGenealogy() 完成（同家族 + 环检测）
--   3. 不做 backfill —— V17 之前家族成员没代际字段，全 NULL 合法状态
--   4. Subject 表的 genealogy 字段保留（V17 不动）—— 双写是性能换简单度的权衡
-- ============================================================

ALTER TABLE `family_member`
    ADD COLUMN `generation` INT NULL
        COMMENT '代际：0=本人辈，负数=长辈（-1=父母辈，-2=祖辈），正数=晚辈（1=儿女辈，2=孙辈）；NULL=未分代'
        AFTER `role`,
    ADD COLUMN `parent_family_member_id` BIGINT NULL
        COMMENT '上一代 family_member.id（同家族内；NULL=上一代不在家族里）'
        AFTER `generation`,
    ADD COLUMN `parent_member_relation_type` VARCHAR(16) NULL
        COMMENT 'father|mother|guardian；为 v2 多配偶/复杂关系预留'
        AFTER `parent_family_member_id`,
    ADD KEY `idx_parent_family_member` (`parent_family_member_id`),
    ADD KEY `idx_family_generation` (`family_id`, `generation`);

-- 注：
--   - 不加 FK 约束（family_member 可能软删；业务层校验由 FamilyMemberService 完成）
--   - Subject 表的 genealogy 字段（V17）保留作为冗余缓存 —— 不回滚
