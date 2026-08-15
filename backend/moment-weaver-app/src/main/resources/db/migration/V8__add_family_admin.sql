-- ============================================================
-- V8：家族管理员体系 - User 表新增字段
-- ============================================================
-- 背景：
--   原账号体系只有「注册用户」一种身份，无法区分「家族管理员」。
--   本迁移在 user 表新增 3 个字段，为 Family 功能铺路：
--     1) is_family_admin       —— 标记是否是家族管理员（创建家族后自动置 1）
--     2) must_change_password  —— 标记是否下次登录必须改密（管理员创建账号场景）
--     3) created_by_user_id    —— 记录是谁创建的本账号（NULL=自注册；非空=被管理员创建）
--
-- 数据迁移：
--   把现有测试账号 gyy_5288@qq.com 标记为家族管理员
--   （满足用户需求："目前我测试的账户后面也应该变成管理员"）
-- ============================================================

ALTER TABLE `user`
  ADD COLUMN `is_family_admin` TINYINT(1) NOT NULL DEFAULT 0
    COMMENT '是否为家族管理员（创建家族后自动置 1）',
  ADD COLUMN `must_change_password` TINYINT(1) NOT NULL DEFAULT 0
    COMMENT '下次登录是否强制改密（管理员创建账号场景）',
  ADD COLUMN `created_by_user_id` BIGINT NULL
    COMMENT '创建本账号的管理员 userId（NULL=自注册）',
  ADD KEY `idx_created_by` (`created_by_user_id`);

-- 把现有测试账号升级为家族管理员
UPDATE `user`
SET `is_family_admin` = 1
WHERE `email` = 'gyy_5288@qq.com';
