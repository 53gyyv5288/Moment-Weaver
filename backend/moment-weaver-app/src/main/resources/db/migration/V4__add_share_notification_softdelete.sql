-- =============================================================
-- V4: 分享 / 通知 / 软删增强（M5 阶段 A + B）
-- =============================================================
-- 背景：
--   V1 已经"提前"建了 share_link 和 deletion_request 两张表（M5 预备），
--   V4 在此基础上补充 M5 实际需要的字段，并新增 audit_log 表。
--   注意：share_link.password_hash 原长度 128，留给 BCrypt 完全够用，
--   计划文档里写改 255 是过度设计，这里不调整。

-- 1) share_link: 增强字段
ALTER TABLE `share_link`
  ADD COLUMN `draft_id`         BIGINT       NULL COMMENT '关联的成稿 id' AFTER `project_id`,
  ADD COLUMN `subject_ids`      VARCHAR(512) NULL COMMENT '限定可见 subject id，逗号分隔' AFTER `draft_id`,
  ADD COLUMN `view_count`       INT          NOT NULL DEFAULT 0 COMMENT '访问计数' AFTER `allow_download`,
  ADD COLUMN `created_by_name`  VARCHAR(64)  NULL COMMENT '冗余创建者显示名（公开端避免反查 user）' AFTER `view_count`,
  ADD COLUMN `last_accessed_at` DATETIME     NULL COMMENT '最近一次成功访问时间' AFTER `created_by_name`,
  ADD KEY `idx_share_draft` (`draft_id`),
  ADD KEY `idx_share_project_created` (`project_id`, `created_at`);

-- 2) deletion_request: 增强字段
ALTER TABLE `deletion_request`
  ADD COLUMN `scope_target_type`  VARCHAR(32) NOT NULL DEFAULT 'project' COMMENT 'project|subject|asset|draft' AFTER `scope`,
  ADD COLUMN `requested_user_id`  BIGINT      NOT NULL DEFAULT 0 COMMENT '申请人 userId（V1 user_id 字段已废弃但保留兼容）' AFTER `user_id`,
  ADD COLUMN `grace_expires_at`   DATETIME    NULL COMMENT '宽限期截止时间（= effective_at + 30 天）' AFTER `effective_at`,
  ADD COLUMN `executed_at`        DATETIME    NULL COMMENT '物理删除执行时间' AFTER `grace_expires_at`,
  ADD COLUMN `export_oss_key`     VARCHAR(255) NULL COMMENT '导出包 OSS key（export 场景使用）' AFTER `executed_at`,
  ADD KEY `idx_deletion_grace` (`status`, `grace_expires_at`),
  ADD KEY `idx_deletion_user_created` (`requested_user_id`, `created_at`);

-- 3) audit_log: 新建（M5 合规自检要求审计日志）
CREATE TABLE `audit_log` (
  `id`            BIGINT       NOT NULL,
  `user_id`       BIGINT       NOT NULL COMMENT '操作人 userId',
  `action`        VARCHAR(64)  NOT NULL COMMENT 'login|logout|export_create|export_download|delete_request|delete_restore|delete_execute|revoke|share_create|share_revoke|share_access|consent_accept',
  `target_type`   VARCHAR(32)  NULL COMMENT '资源类型 project|subject|asset|draft|share|deletion|export',
  `target_id`     VARCHAR(64)  NULL COMMENT '资源 id（字符串，兼容 MongoDB ObjectId）',
  `ip`            VARCHAR(45)  NULL COMMENT '客户端 IP（支持 IPv6）',
  `ua`            VARCHAR(255) NULL COMMENT '客户端 User-Agent',
  `metadata`      JSON         NULL COMMENT '附加元数据（不记录敏感字段）',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `deleted`       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_audit_user_action` (`user_id`, `action`),
  KEY `idx_audit_created_at` (`created_at`),
  KEY `idx_audit_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计日志';
