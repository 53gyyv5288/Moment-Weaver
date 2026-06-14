-- ============================================================
-- Moment Weaver · Flyway V1 初始 Schema
-- ============================================================

CREATE DATABASE IF NOT EXISTS moment_weaver
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE moment_weaver;

-- 通用约定：
--   id          BIGINT       主键（雪花算法）
--   created_at  DATETIME
--   updated_at  DATETIME
--   deleted     TINYINT(1)   软删除（0/1）

-- 账号
CREATE TABLE `user` (
  `id`              BIGINT       NOT NULL,
  `phone`           VARCHAR(20)  DEFAULT NULL,
  `email`           VARCHAR(128) DEFAULT NULL,
  `password_hash`   VARCHAR(128) NOT NULL,
  `display_name`    VARCHAR(64)  NOT NULL,
  `avatar_url`      VARCHAR(512) DEFAULT NULL,
  `status`          TINYINT      NOT NULL DEFAULT 1,
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone` (`phone`),
  UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户账号';

-- 工作区
CREATE TABLE `workspace` (
  `id`              BIGINT       NOT NULL,
  `owner_id`        BIGINT       NOT NULL,
  `name`            VARCHAR(64)  NOT NULL,
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_owner` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作区';

-- 工作区成员
CREATE TABLE `workspace_member` (
  `id`              BIGINT       NOT NULL,
  `workspace_id`    BIGINT       NOT NULL,
  `user_id`         BIGINT       NOT NULL,
  `role`            VARCHAR(16)  NOT NULL DEFAULT 'editor',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workspace_user` (`workspace_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作区成员';

-- 项目
CREATE TABLE `project` (
  `id`              BIGINT       NOT NULL,
  `workspace_id`    BIGINT       NOT NULL,
  `owner_id`        BIGINT       NOT NULL,
  `type`            VARCHAR(16)  NOT NULL,
  `name`            VARCHAR(128) NOT NULL,
  `description`     VARCHAR(512) DEFAULT NULL,
  `status`          TINYINT      NOT NULL DEFAULT 1,
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_workspace` (`workspace_id`),
  KEY `idx_owner` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目';

-- 人物（M2 使用）
CREATE TABLE `subject` (
  `id`              BIGINT       NOT NULL,
  `project_id`      BIGINT       NOT NULL,
  `display_name`    VARCHAR(64)  NOT NULL,
  `relation`        VARCHAR(32)  DEFAULT NULL,
  `has_account`     TINYINT(1)   NOT NULL DEFAULT 0,
  `linked_user_id`  BIGINT       DEFAULT NULL,
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='被采访者';

-- 授权（M2 使用）
CREATE TABLE `authorization` (
  `id`                  BIGINT       NOT NULL,
  `subject_id`          BIGINT       NOT NULL,
  `project_id`          BIGINT       NOT NULL,
  `token`               VARCHAR(64)  NOT NULL,
  `scopes`              VARCHAR(512) NOT NULL,
  `status`              VARCHAR(16)  NOT NULL DEFAULT 'pending',
  `consent_version`     VARCHAR(16)  NOT NULL,
  `granted_at`          DATETIME     DEFAULT NULL,
  `revoked_at`          DATETIME     DEFAULT NULL,
  `expires_at`          DATETIME     NOT NULL,
  `ip`                  VARCHAR(64)  DEFAULT NULL,
  `ua`                  VARCHAR(512) DEFAULT NULL,
  `created_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`             TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_token` (`token`),
  KEY `idx_subject` (`subject_id`),
  KEY `idx_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='授权';

-- 分享链接（M5 使用）
CREATE TABLE `share_link` (
  `id`              BIGINT       NOT NULL,
  `project_id`      BIGINT       NOT NULL,
  `token`           VARCHAR(64)  NOT NULL,
  `scope`           VARCHAR(32)  NOT NULL,
  `password_hash`   VARCHAR(128) DEFAULT NULL,
  `allow_copy`      TINYINT(1)   NOT NULL DEFAULT 0,
  `allow_download`  TINYINT(1)   NOT NULL DEFAULT 0,
  `expires_at`      DATETIME     DEFAULT NULL,
  `revoked`         TINYINT(1)   NOT NULL DEFAULT 0,
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_token` (`token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分享';

-- 删除申请（M5 使用）
CREATE TABLE `deletion_request` (
  `id`              BIGINT       NOT NULL,
  `user_id`         BIGINT       NOT NULL,
  `scope`           VARCHAR(32)  NOT NULL,
  `target_id`       BIGINT       DEFAULT NULL,
  `reason`          VARCHAR(512) DEFAULT NULL,
  `status`          VARCHAR(16)  NOT NULL DEFAULT 'pending',
  `effective_at`    DATETIME     NOT NULL,
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='删除申请';

-- Flyway 元数据表
CREATE TABLE IF NOT EXISTS `flyway_schema_history` (
  `installed_rank`  INT          NOT NULL,
  `version`         VARCHAR(50)  DEFAULT NULL,
  `description`     VARCHAR(200) NOT NULL,
  `type`            VARCHAR(20)  NOT NULL,
  `script`          VARCHAR(1000) NOT NULL,
  `checksum`        INT          DEFAULT NULL,
  `installed_by`    VARCHAR(100) NOT NULL,
  `installed_on`    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `execution_time`  INT          NOT NULL,
  `success`         TINYINT(1)   NOT NULL,
  PRIMARY KEY (`installed_rank`),
  KEY `idx_success` (`success`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
