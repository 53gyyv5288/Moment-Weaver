-- ============================================================
-- V13：project.workspace_id 改为可空（家族项目不需要 workspace）
-- ============================================================
-- 背景：
--   M11 Phase 3 之前，所有项目都假设有 workspace_member 关系。
--   但家族项目实际上是按 family_member 关系管理的，workspace_id 没意义。
--   之前 editor 通过 admin 创建账号后，没有 workspace_member 记录，
--   所以创建项目时 defaultWorkspaceIdOf 抛 "工作区不存在"。
--
--   修复：workspace_id 改为 NULL，家族项目存 NULL；个人项目继续用。
--   涉及改动：
--     - ProjectService.create()：familyId 非空时不再取 workspace_id
--     - ProjectService.get/update/delete 兼容 workspace_id 为 NULL
-- ============================================================

ALTER TABLE `project`
    MODIFY COLUMN `workspace_id` BIGINT NULL COMMENT '个人项目所属工作区（NULL=家族项目）';
