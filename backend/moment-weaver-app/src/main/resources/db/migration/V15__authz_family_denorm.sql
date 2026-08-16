-- ============================================================
-- V15：Authorization 表加 family_member_id / family_id 冗余字段
-- ============================================================
-- 背景：
--   RAG 检索兜底授权校验（InternalAuthzController.check）当前只看
--   `subject_id == ?` 是否 granted。同一个人物在同家族内多个项目
--   里被加为多个 Subject 时，每个 subject 都要独立 grant 才能用 RAG。
--
--   本次改造目标：
--     - 同 familyMember 在同 family 内的 grant 共享给所有同 familyMember
--       subject（同家族不同项目只 grant 一次即可）
--     - 跨 family 硬隔离（user 必须属于 family；Milvus filter 双重防护）
--
--   实现：denorm family_member_id + family_id 到 authorization 表，
--   grant 时一次性写入，避免运行时 JOIN。
--   旧数据：family_member_id/family_id 默认为 NULL，按原 subject 粒度
--   行为继续生效。
-- ============================================================

ALTER TABLE `authorization`
    ADD COLUMN `family_member_id` BIGINT NULL
        COMMENT '冗余：subject 对应的 family_member（NULL=匿名 subject 或旧数据）'
        AFTER `subject_id`,
    ADD COLUMN `family_id` BIGINT NULL
        COMMENT '冗余：subject 关联 project 的 family（NULL=个人项目或旧数据）'
        AFTER `family_member_id`,
    ADD KEY `idx_authorization_family_member` (`family_member_id`, `family_id`, `status`);

-- 历史数据回填：subject.family_member_id + project.family_id
-- 注：subject.family_member_id 自身也是 V12 加的，旧 subject 可能为 NULL
UPDATE `authorization` a
JOIN `subject`  s ON s.id = a.subject_id
JOIN `project`  p ON p.id = a.project_id
SET a.family_member_id = s.family_member_id,
    a.family_id        = p.family_id
WHERE a.family_member_id IS NULL;
