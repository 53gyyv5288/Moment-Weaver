-- ============================================================
-- Moment Weaver · Flyway V2
-- M2 补齐：subject.note（仅 owner 自己可见的备注小抄）
-- ============================================================

ALTER TABLE `subject`
  ADD COLUMN `note` VARCHAR(512) DEFAULT NULL COMMENT '备注：仅 owner 自己可见' AFTER `linked_user_id`;
