-- ============================================================
-- Moment Weaver · Flyway V3
-- M3 素材：asset 表
-- ============================================================

CREATE TABLE `asset` (
  `id`            BIGINT       NOT NULL,
  `project_id`    BIGINT       NOT NULL,
  `subject_id`    BIGINT       DEFAULT NULL,
  `interview_id`  VARCHAR(32)  DEFAULT NULL,
  `uploader_id`   BIGINT       NOT NULL,
  `kind`          VARCHAR(16)  NOT NULL COMMENT 'image|audio|video',
  `storage`       VARCHAR(16)  NOT NULL DEFAULT 'oss' COMMENT 'oss|local',
  `oss_key`       VARCHAR(512) NOT NULL,
  `oss_bucket`    VARCHAR(64)  NOT NULL,
  `oss_region`    VARCHAR(32)  NOT NULL,
  `original_name` VARCHAR(256) DEFAULT NULL,
  `mime_type`     VARCHAR(64)  DEFAULT NULL,
  `size_bytes`    BIGINT       DEFAULT NULL,
  `width`         INT          DEFAULT NULL,
  `height`        INT          DEFAULT NULL,
  `duration_ms`   BIGINT       DEFAULT NULL,
  `caption`       VARCHAR(512) DEFAULT NULL,
  `taken_at`      DATETIME     DEFAULT NULL,
  `scan_status`   VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending|clean|flagged',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_project` (`project_id`),
  KEY `idx_subject` (`subject_id`),
  KEY `idx_interview` (`interview_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='素材';