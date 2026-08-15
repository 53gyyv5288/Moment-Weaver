-- ============================================================
-- V9：新建 family + family_member 表
-- ============================================================
-- 设计要点：
--   family 是「组织」维度的容器，与 workspace 并行存在：
--     - workspace：个人 MVP 阶段遗留，每个人默认 1 个，承载「个人项目」
--     - family   ：家族协作场景，多成员共享，承载「家族项目」
--   两类容器互不影响；项目可只挂在 workspace，也可同时挂 family（见 V9）
--
-- family_member.role 三种角色：
--   admin  —— 家族管理员（创建者），可邀请/移除成员、改家族名
--   editor —— 家族编辑者，能创建/编辑家族下项目
--   viewer —— 家族旁观者，只读
-- ============================================================

CREATE TABLE `family` (
  `id`              BIGINT       NOT NULL,
  `name`            VARCHAR(64)  NOT NULL COMMENT '家族名（如：张家、李家）',
  `description`     VARCHAR(512) DEFAULT NULL,
  `owner_user_id`   BIGINT       NOT NULL COMMENT '家族管理员 userId（创建者）',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_owner` (`owner_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家族组织';

CREATE TABLE `family_member` (
  `id`              BIGINT       NOT NULL,
  `family_id`       BIGINT       NOT NULL,
  `user_id`         BIGINT       NOT NULL,
  `role`            VARCHAR(16)  NOT NULL DEFAULT 'editor' COMMENT 'admin | editor | viewer',
  `joined_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_family_user` (`family_id`, `user_id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_role` (`family_id`, `role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家族成员';
