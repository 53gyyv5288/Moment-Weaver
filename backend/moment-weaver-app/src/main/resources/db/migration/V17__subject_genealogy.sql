-- ============================================================
-- V17：人物（Subject）加家谱字段（M14+ 家族关系图）
-- ============================================================
-- 背景：
--   用户的核心愿景："一直传下去，让家族有记忆点，传到几十上百代"。
--   之前 Subject 表完全没有代际/父子关系，关系信息只能塞在 32 字符的
--   relation 字符串里，无法做家族树可视化、代际权限、未来 GEDCOM 导出。
--
--   本 migration 给 Subject 加 3 个字段,让家族结构第一次成为一等公民：
--     - generation INT           代际（负数=长辈，0=本人辈，正数=晚辈）
--     - parent_subject_id BIGINT 父/母节点 subject.id（同项目内；NULL=父不在项目里）
--     - parent_relation_type     father|mother|guardian（v2 多配偶/复杂关系预留）
--
-- 设计决策：
--   1. generation 允许 NULL —— 录入时不强求；前端渲染"未分代"灰色区
--   2. generation 允许负数 —— 新增长辈无需 UPDATE 全表，几代后不漂移（长辈走 -N，自己 0，晚辈 +N）
--   3. parent_subject_id 不加 FK 约束 —— Subject 是项目级，跨项目语义不强；
--      业务层 SubjectService.validateGenealogy() 校验同 project_id
--   4. 不做 backfill —— 三字段全 NULL 合法状态，前端兜底；错的 generation 比空的更难修
--   5. generation 锚定策略 v2 引入 family.generation_epoch 后再处理（届时一次性 backfill）
-- ============================================================

ALTER TABLE `subject`
    ADD COLUMN `generation` INT NULL
        COMMENT '代际：0=本人辈，负数=长辈（-1=父母辈，-2=祖辈），正数=晚辈（1=儿女辈，2=孙辈）'
        AFTER `relation`,
    ADD COLUMN `parent_subject_id` BIGINT NULL
        COMMENT '父/母节点 subject.id（同项目内；NULL=父不在项目里）'
        AFTER `generation`,
    ADD COLUMN `parent_relation_type` VARCHAR(16) NULL
        COMMENT 'father|mother|guardian；为 v2 多配偶/复杂关系预留'
        AFTER `parent_subject_id`,
    ADD KEY `idx_parent_subject` (`parent_subject_id`),
    ADD KEY `idx_project_generation` (`project_id`, `generation`);

-- 注：
--   - 不加 FK 约束（subject 是项目级，且允许 parent 跨项目边界语义）
--   - 不加 CHECK 约束（generation 范围由业务层校验）
--   - 业务层校验由 SubjectService.validateGenealogy() 完成（环检测 + 自环 + 一致性警告）
