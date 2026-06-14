-- ============================================================
-- Moment Weaver · MySQL 初始 Schema
-- 适用：MySQL 8.0+
-- 库名：moment_weaver
-- 说明：M0 阶段只建库 + 占位表，M1 起按 module 迭代
-- ============================================================

-- 1. 建库
CREATE DATABASE IF NOT EXISTS moment_weaver
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE moment_weaver;

-- 2. 通用字段约定
--    id          BIGINT       主键（雪花算法，由 MyBatis-Plus ASSIGN_ID 生成）
--    created_at  DATETIME     创建时间
--    updated_at  DATETIME     更新时间
--    deleted     TINYINT(1)   软删除（0=正常，1=已删）

-- 3. 账号相关
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
  `id`              BIGINT       NOT NULL,
  `phone`           VARCHAR(20)  DEFAULT NULL COMMENT '手机号（脱敏存储）',
  `email`           VARCHAR(128) DEFAULT NULL,
  `password_hash`   VARCHAR(128) NOT NULL COMMENT 'BCrypt',
  `display_name`    VARCHAR(64)  NOT NULL,
  `avatar_url`      VARCHAR(512) DEFAULT NULL,
  `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '0=禁用，1=正常',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_phone` (`phone`),
  UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户账号';

-- 4. 工作区
DROP TABLE IF EXISTS `workspace`;
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

-- 5. 项目
DROP TABLE IF EXISTS `project`;
CREATE TABLE `project` (
  `id`              BIGINT       NOT NULL,
  `workspace_id`    BIGINT       NOT NULL,
  `owner_id`        BIGINT       NOT NULL,
  `type`            VARCHAR(16)  NOT NULL COMMENT 'family | personal（team 二期）',
  `name`            VARCHAR(128) NOT NULL,
  `description`     VARCHAR(512) DEFAULT NULL,
  `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '0=归档，1=进行中',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_workspace` (`workspace_id`),
  KEY `idx_owner` (`owner_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目';

-- 6. 人物（被采访者）
DROP TABLE IF EXISTS `subject`;
CREATE TABLE `subject` (
  `id`              BIGINT       NOT NULL,
  `project_id`      BIGINT       NOT NULL,
  `display_name`    VARCHAR(64)  NOT NULL,
  `relation`        VARCHAR(32)  DEFAULT NULL COMMENT '与 Owner 的关系',
  `has_account`     TINYINT(1)   NOT NULL DEFAULT 0,
  `linked_user_id`  BIGINT       DEFAULT NULL,
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='被采访者';

-- 7. 授权（合规重头戏）
DROP TABLE IF EXISTS `authorization`;
CREATE TABLE `authorization` (
  `id`                  BIGINT       NOT NULL,
  `subject_id`          BIGINT       NOT NULL,
  `project_id`          BIGINT       NOT NULL,
  `token`               VARCHAR(64)  NOT NULL COMMENT '一次性链接 token',
  `scopes`              VARCHAR(512) NOT NULL COMMENT 'JSON 数组：record/derive/publish',
  `status`              VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending|granted|revoked|expired',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='被采访者授权记录';

-- 8. 分享链接（M5 落地，先建表占位）
DROP TABLE IF EXISTS `share_link`;
CREATE TABLE `share_link` (
  `id`              BIGINT       NOT NULL,
  `project_id`      BIGINT       NOT NULL,
  `token`           VARCHAR(64)  NOT NULL,
  `scope`           VARCHAR(32)  NOT NULL COMMENT 'project | draft',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分享链接';

-- 9. 删除申请（合规：30 天软删 → 物理删）
DROP TABLE IF EXISTS `deletion_request`;
CREATE TABLE `deletion_request` (
  `id`              BIGINT       NOT NULL,
  `user_id`         BIGINT       NOT NULL,
  `scope`           VARCHAR(32)  NOT NULL COMMENT 'account | project',
  `target_id`       BIGINT       DEFAULT NULL,
  `reason`          VARCHAR(512) DEFAULT NULL,
  `status`          VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending|restored|executed',
  `effective_at`    DATETIME     NOT NULL COMMENT '30 天后物理删',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='删除申请';
