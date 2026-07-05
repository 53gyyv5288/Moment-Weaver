-- =============================================================
-- V5: 数据导出请求表（M5-B.1）
-- =============================================================
-- 背景：
--   V1 已经建了 deletion_request 表（M5 预备）；
--   V4 增强了 deletion_request 字段并新建 audit_log；
--   V5 新建 export_request（异步导出状态机）。

CREATE TABLE `export_request` (
  `id`                  BIGINT       NOT NULL,
  `user_id`             BIGINT       NOT NULL COMMENT '申请人',
  `scope`               VARCHAR(16)  NOT NULL DEFAULT 'all' COMMENT 'all | project | subject',
  `scope_target_id`     VARCHAR(64)  NULL COMMENT 'scope=project/subject 时记录目标 id',
  `status`              VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending|ready|failed|expired',
  `oss_key`             VARCHAR(255) NULL COMMENT 'OSS 对象 key（status=ready 后有效）',
  `signed_url_expires_at` DATETIME   NULL COMMENT '签名 URL 过期时间',
  `fail_reason`         VARCHAR(512) NULL COMMENT '失败原因',
  `created_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `completed_at`        DATETIME     NULL COMMENT '完成时间（成功 / 失败都记）',
  `deleted`             TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_export_user_created` (`user_id`, `created_at`),
  KEY `idx_export_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据导出请求（异步任务状态机）';
