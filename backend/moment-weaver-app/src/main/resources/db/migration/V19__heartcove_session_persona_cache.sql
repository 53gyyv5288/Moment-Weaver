-- ============================================================
-- V19：心声信箱 session 加 persona_summary 缓存字段
-- ============================================================
-- 背景：
--   之前 HeartcoveChatService.assembleContext 每条消息都现算 persona_summary
--   （含 subject.generation 与 user 绑定的 FamilyMember.generation 的代际文案），
--   每次都要查 subject + family_member 两张表。
--
--   用户决策：session 启动时一次性算好 persona_summary，缓存进 session 行，
--   后续每条消息直接从 session 行读，不再查库。代价是若中途修改 subject.gen
--   或 user 的 FamilyMember 绑定，缓存不自动刷新（需重新进入 session 才会重建）。
--
--   这个权衡可接受——代际信息在采访期间基本稳定，且每个 session 寿命通常
--   在分钟~小时级。重启 session 即可重新算。
--
-- 字段：
--   cached_persona_summary TEXT NULL
--     - NULL：旧 session 行，HeartcoveChatService 检测到 NULL 时走旧的"现算"路径
--     - 非空：已缓存的 persona_summary（含代际文案），直接读
-- ============================================================

ALTER TABLE `heartcove_session`
    ADD COLUMN `cached_persona_summary` TEXT NULL
        COMMENT 'session 启动时缓存的 persona_summary（含代际文案）；NULL=走旧路径现算'
        AFTER `client_ua`;