-- ============================================================
-- V11：补齐 V8/V9/V10 没做完的数据迁移
-- ============================================================
-- 背景：
--   V8 (user.is_family_admin)、V9 (family / family_member 表) 都成功。
--   V10 是 Java Migration，列建好了（Duplicate column 报错说明列已存在），
--   但数据迁移部分（建家族、加成员、挂项目）因为雪花 ID 越界 BIGINT 失败了。
--
--   本 migration 用 SQL 存储过程幂等地补齐剩余工作：
--     1. 确保 project.family_id 列存在（其实已经在了，仅做幂等保护）
--     2. 把 gyy_5288 标记为家族管理员
--     3. 给 gyy_5288 自动建「我的家族」（如果还没建）
--     4. 把 gyy_5288 加进 family_member (role=admin)
--     5. 把 gyy_5288 的现有项目挂到家族
--
--   全部幂等：重复跑不会出错。
-- ============================================================

-- 1. project.family_id 列（存储过程模拟 IF NOT EXISTS）
DROP PROCEDURE IF EXISTS ensure_project_family_id;
DELIMITER //
CREATE PROCEDURE ensure_project_family_id()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'project'
          AND column_name = 'family_id'
    ) THEN
        ALTER TABLE `project`
            ADD COLUMN `family_id` BIGINT NULL
                COMMENT '所属家族（NULL=个人项目）';
        ALTER TABLE `project` ADD KEY `idx_family` (`family_id`);
    END IF;
END //
DELIMITER ;
CALL ensure_project_family_id();
DROP PROCEDURE ensure_project_family_id;

-- 2. 把 gyy_5288 标记为家族管理员
UPDATE `user`
SET `is_family_admin` = 1
WHERE `email` = 'gyy_5288@qq.com'
  AND (`is_family_admin` IS NULL OR `is_family_admin` = 0);

-- 3. 给 gyy_5288 自动建「我的家族」+ 加入成员 + 挂项目
-- 全部用 INSERT ... SELECT ... WHERE NOT EXISTS 模式保证幂等

-- 3.1 创建家族
INSERT INTO family (id, name, description, owner_user_id, created_at, updated_at, deleted)
SELECT
    -- 用 admin_user_id + 50 作为 family id（保证在 BIGINT 范围内，且与 user_id 不冲突）
    (SELECT id FROM `user` WHERE email = 'gyy_5288@qq.com' LIMIT 1) + 50,
    '我的家族',
    '迁移时自动创建',
    (SELECT id FROM `user` WHERE email = 'gyy_5288@qq.com' LIMIT 1),
    NOW(),
    NOW(),
    0
FROM DUAL
WHERE EXISTS (SELECT 1 FROM `user` WHERE email = 'gyy_5288@qq.com')
  AND NOT EXISTS (
      SELECT 1 FROM family
      WHERE owner_user_id = (SELECT id FROM `user` WHERE email = 'gyy_5288@qq.com' LIMIT 1)
  );

-- 3.2 把 gyy_5288 加入 family_member (admin)
INSERT INTO family_member (id, family_id, user_id, role, joined_at, created_at, updated_at, deleted)
SELECT
    -- 用 owner_user_id + 100 当 family_member.id（保证在 BIGINT 范围内）
    f.owner_user_id + 100,
    f.id,
    f.owner_user_id,
    'admin',
    NOW(),
    NOW(),
    NOW(),
    0
FROM family f
WHERE f.owner_user_id = (SELECT id FROM `user` WHERE email = 'gyy_5288@qq.com' LIMIT 1)
  AND NOT EXISTS (
      SELECT 1 FROM family_member
      WHERE family_id = f.id AND user_id = f.owner_user_id
  );

-- 3.3 把 gyy_5288 的现有项目挂到家族
UPDATE project p
JOIN family f ON f.owner_user_id = (SELECT id FROM `user` WHERE email = 'gyy_5288@qq.com' LIMIT 1)
SET p.family_id = f.id
WHERE p.owner_id = f.owner_user_id
  AND (p.family_id IS NULL OR p.family_id = 0);
