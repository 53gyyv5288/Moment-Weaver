-- ============================================================
-- Moment Weaver · 心声信箱 (Heartcove / Digital Twin) Schema
-- 适用：MySQL 8.0+
-- 库名：moment_weaver
-- Flyway 版本：V16
-- 设计原则：
--   1. 心声信箱对话数据物理隔离，绝不进 milvus（详见 CHECK 约束）
--   2. 所有查询走可见性视图（heartcove_message_visible），应用层无法绕过
--   3. Subject 表扩展 4 个字段（heartcoveEnabled / personaSummary / enabledAt / consentVersion）
-- ============================================================

USE moment_weaver;

-- 1. Subject 表扩展（ADD COLUMN 重复执行会报错；Flyway 只在版本未执行时跑一次，安全）
ALTER TABLE `subject`
  ADD COLUMN `heartcove_enabled`          TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '心声邮箱是否已开启（0=否，1=是）',
  ADD COLUMN `heartcove_persona_summary`  TEXT         DEFAULT NULL         COMMENT 'AI 抽取的人格摘要',
  ADD COLUMN `heartcove_enabled_at`       DATETIME     DEFAULT NULL         COMMENT '开启时间',
  ADD COLUMN `heartcove_consent_version`  VARCHAR(16)  DEFAULT NULL         COMMENT '签署的授权书版本号';


-- 2. 心声信箱授权（独立于采访授权；一期单人 MVP 不强制要求多签，仅 Owner 单签即可）
CREATE TABLE IF NOT EXISTS `heartcove_consent` (
  `id`                 BIGINT       NOT NULL,
  `subject_id`         BIGINT       NOT NULL                  COMMENT '关联 subject.id',
  `grantor_id`         BIGINT       NOT NULL                  COMMENT '授权人 userId（个人项目=owner；家族项目=family admin）',
  `consent_version`    VARCHAR(16)  NOT NULL                  COMMENT '签署的数字人格授权书版本号',
  `scopes`             VARCHAR(128) NOT NULL DEFAULT 'chat'   COMMENT '授权范围：chat|emotion_support',
  `signed_at`          DATETIME     NOT NULL                  COMMENT '签署时间',
  `revoked_at`         DATETIME     DEFAULT NULL              COMMENT '撤回时间',
  `ip`                 VARCHAR(64)  DEFAULT NULL,
  `ua`                 VARCHAR(512) DEFAULT NULL,
  `note`               VARCHAR(256) DEFAULT NULL              COMMENT '用户签署时填写的备注（可选）',
  `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`            TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_subject` (`subject_id`),
  KEY `idx_grantor` (`grantor_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='心声邮箱授权书签署记录';


-- 3. 对话会话（MySQL 强事务：用户只能查自己的）
CREATE TABLE IF NOT EXISTS `heartcove_session` (
  `id`              BIGINT       NOT NULL,
  `subject_id`      BIGINT       NOT NULL                  COMMENT '关联 subject.id',
  `user_id`         BIGINT       NOT NULL                  COMMENT '对话用户 userId（被倾诉者）',
  `status`          VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active|closed',
  `message_count`   INT          NOT NULL DEFAULT 0        COMMENT '消息计数（缓存）',
  `last_message_at` DATETIME     DEFAULT NULL              COMMENT '最后消息时间',
  `started_at`      DATETIME     NOT NULL                  COMMENT '会话开始时间',
  `closed_at`       DATETIME     DEFAULT NULL              COMMENT '会话结束时间',
  `client_ip`       VARCHAR(64)  DEFAULT NULL,
  `client_ua`       VARCHAR(512) DEFAULT NULL,
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_subject_user` (`subject_id`, `user_id`),
  KEY `idx_user_lastmsg` (`user_id`, `last_message_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='心声邮箱对话会话';


-- 4. 对话消息（强约束：永远不向量化；自包含审计字段 is_milvus_synced）
CREATE TABLE IF NOT EXISTS `heartcove_message` (
  `id`                   BIGINT       NOT NULL,
  `session_id`           BIGINT       NOT NULL,
  `role`                 VARCHAR(16)  NOT NULL COMMENT 'user|ai',
  `content`              TEXT         NOT NULL,
  -- ===== 审计 / 防泄漏护栏 =====
  `is_milvus_synced`     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '永远为 0；任何尝试改成非 0 的 UPDATE 会被 CHECK 约束拒绝',
  -- ===== AI 消息专用字段 =====
  `source_message_ids`   VARCHAR(512) DEFAULT NULL         COMMENT '引用的采访原话 ID 列表（JSON 数组字符串）',
  `unknown_type`         VARCHAR(32)  DEFAULT NULL         COMMENT '不知道话术分类：modern_topic|no_material|emotion_support|boundary|null',
  `generation_ms`        INT          DEFAULT NULL         COMMENT 'AI 生成耗时（毫秒）',
  `safety_flag`          VARCHAR(32)  DEFAULT NULL         COMMENT '安全标记：crisis_detected（检测到危机，触发人工引导）',
  -- ===== user 消息专用字段 =====
  `safety_score`         DECIMAL(4,3) DEFAULT NULL         COMMENT '情感分析得分（0~1，>0.8 触发危机干预）',
  -- ===== 通用 =====
  `created_at`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `deleted`              TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_session` (`session_id`),
  -- 物理护栏：拒绝任何 is_milvus_synced != 0 的尝试
  CONSTRAINT `chk_heartcove_no_milvus` CHECK (`is_milvus_synced` = 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='心声邮箱对话消息（绝不进 milvus）';


-- 5. 中期记忆滚动摘要（每个 subject 在每个 user 视角下的会话摘要；不入 milvus）
CREATE TABLE IF NOT EXISTS `heartcove_memory_summary` (
  `id`              BIGINT       NOT NULL,
  `subject_id`      BIGINT       NOT NULL,
  `user_id`         BIGINT       NOT NULL,
  `summary`         TEXT         NOT NULL COMMENT '滚动摘要（覆盖最近 ~30 条对话）',
  `turn_count`      INT          NOT NULL DEFAULT 0 COMMENT '已纳入摘要的对话轮数',
  `last_message_id` BIGINT       DEFAULT NULL,
  `generated_at`    DATETIME     NOT NULL,
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_subject_user` (`subject_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='心声邮箱中期记忆滚动摘要';


-- 6. 可见性视图（应用层只能通过此视图查询，应用代码无法绕过）
-- 注意：视图不强制 user_id 过滤（MySQL 视图不能直接取会话变量）
-- 应用层必须在 WHERE 中加 s.user_id = ? 条件
DROP VIEW IF EXISTS `heartcove_message_visible`;
CREATE VIEW `heartcove_message_visible` AS
SELECT m.*
FROM `heartcove_message` m
JOIN `heartcove_session` s ON m.`session_id` = s.`id`
WHERE m.`deleted` = 0
  AND s.`deleted` = 0;


-- 7. 心声信箱操作审计日志（独立于审计中心；便于合规复查）
CREATE TABLE IF NOT EXISTS `heartcove_audit_log` (
  `id`           BIGINT       NOT NULL,
  `subject_id`   BIGINT       NOT NULL,
  `user_id`      BIGINT       NOT NULL,
  `action`       VARCHAR(32)  NOT NULL COMMENT 'enable|disable|chat|crisis_detected',
  `detail`       VARCHAR(512) DEFAULT NULL,
  `ip`           VARCHAR(64)  DEFAULT NULL,
  `ua`           VARCHAR(512) DEFAULT NULL,
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `deleted`      TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_subject` (`subject_id`),
  KEY `idx_user_action` (`user_id`, `action`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='心声邮箱操作审计';