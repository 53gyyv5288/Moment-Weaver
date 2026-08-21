package com.momentweaver.heartcove.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.momentweaver.account.entity.FamilyMember;
import com.momentweaver.account.mapper.FamilyMemberMapper;
import com.momentweaver.account.security.ProjectAccessChecker;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import com.momentweaver.heartcove.dto.HeartcoveMessageVO;
import com.momentweaver.heartcove.dto.HeartcoveSessionVO;
import com.momentweaver.heartcove.entity.HeartcoveMessage;
import com.momentweaver.heartcove.entity.HeartcoveSession;
import com.momentweaver.heartcove.mapper.HeartcoveMessageMapper;
import com.momentweaver.heartcove.mapper.HeartcoveSessionMapper;
import com.momentweaver.memory.entity.Subject;
import com.momentweaver.memory.mapper.SubjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 心声信箱会话 + 消息服务。
 *
 * <p><b>可见性规则</b>：会话与消息的读取永远走 userId 过滤。
 * 即便 Subject Owner 是另一个用户，B 也不能读 A 的对话（隐私硬底线）。</p>
 *
 * <p><b>写入规则</b>：user 消息创建 → 自动落库 + 计数+1 + 更新 lastMessageAt。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeartcoveSessionService {

    private final SubjectMapper subjectMapper;
    private final ProjectAccessChecker projectAccessChecker;
    private final HeartcoveSessionMapper sessionMapper;
    private final HeartcoveMessageMapper messageMapper;
    /** M14+：用于查 user 绑定的 FamilyMember.generation 算代际文案 */
    private final FamilyMemberMapper familyMemberMapper;

    /**
     * 获取或创建当前用户的活动会话（active）。
     * 一个用户对一个 subject 最多同时一个 active 会话。
     */
    @Transactional
    public HeartcoveSessionVO openOrCreate(Long userId, Long subjectId, String ip, String ua) {
        Subject subject = mustEnabledSubject(subjectId);
        projectAccessChecker.requireMember(subject.getProjectId(), userId);

        HeartcoveSession existing = sessionMapper.selectOne(
            new LambdaQueryWrapper<HeartcoveSession>()
                .eq(HeartcoveSession::getSubjectId, subjectId)
                .eq(HeartcoveSession::getUserId, userId)
                .eq(HeartcoveSession::getStatus, "active")
                .orderByDesc(HeartcoveSession::getLastMessageAt)
                .last("LIMIT 1")
        );

        HeartcoveSession session;
        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            session = existing;
            // M14+：existing session 行 cached_persona_summary 可能为 NULL（老 session），
            // 也可能在 subject.gen / user.fm.gen 变更后过期。这里强制覆盖一次——
            // 重新进入采访意味着用户期望"最新人设"，缓存失效是可接受的代价。
            session.setCachedPersonaSummary(buildPersonaSummary(userId, subject));
            session.setUpdatedAt(now);
            sessionMapper.updateById(session);
        } else {
            session = new HeartcoveSession();
            session.setSubjectId(subjectId);
            session.setUserId(userId);
            session.setStatus("active");
            session.setMessageCount(0);
            session.setStartedAt(now);
            session.setClientIp(ip);
            session.setClientUa(ua);
            // M14+：session 启动时一次性算 persona_summary（含代际文案）
            session.setCachedPersonaSummary(buildPersonaSummary(userId, subject));
            session.setCreatedAt(now);
            session.setUpdatedAt(now);
            sessionMapper.insert(session);
        }
        return toVO(session, subject, Collections.emptyList());
    }

    /** 列出当前用户的所有会话（仅自己） */
    public List<HeartcoveSessionVO> listMySessions(Long userId, Long subjectId) {
        Subject subject = mustEnabledSubject(subjectId);
        projectAccessChecker.requireMember(subject.getProjectId(), userId);

        List<HeartcoveSession> sessions = sessionMapper.selectList(
            new LambdaQueryWrapper<HeartcoveSession>()
                .eq(HeartcoveSession::getSubjectId, subjectId)
                .eq(HeartcoveSession::getUserId, userId)
                .orderByDesc(HeartcoveSession::getLastMessageAt)
        );
        return sessions.stream().map(s -> toVO(s, subject, Collections.emptyList())).collect(Collectors.toList());
    }

    /** 读取会话（含完整消息历史） */
    public HeartcoveSessionVO get(Long userId, Long sessionId) {
        HeartcoveSession s = mustOwnedSession(userId, sessionId);
        Subject subject = mustEnabledSubject(s.getSubjectId());

        List<HeartcoveMessage> messages = messageMapper.selectList(
            new LambdaQueryWrapper<HeartcoveMessage>()
                .eq(HeartcoveMessage::getSessionId, sessionId)
                .orderByAsc(HeartcoveMessage::getCreatedAt)
        );
        return toVO(s, subject, messages);
    }

    /** 关闭会话（用户主动结束） */
    @Transactional
    public HeartcoveSessionVO close(Long userId, Long sessionId) {
        HeartcoveSession s = mustOwnedSession(userId, sessionId);
        if ("closed".equals(s.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "会话已关闭");
        }
        LocalDateTime now = LocalDateTime.now();
        s.setStatus("closed");
        s.setClosedAt(now);
        s.setUpdatedAt(now);
        sessionMapper.updateById(s);
        return get(userId, sessionId);
    }

    /** 保存一条用户消息（前置给 ChatService 调用） */
    @Transactional
    public HeartcoveMessage saveUserMessage(Long userId, Long sessionId, String content) {
        HeartcoveSession s = mustOwnedSession(userId, sessionId);

        LocalDateTime now = LocalDateTime.now();
        HeartcoveMessage m = new HeartcoveMessage();
        m.setSessionId(sessionId);
        m.setRole("user");
        m.setContent(content);
        m.setIsMilvusSynced(0);    // 永远 0（CHECK 约束保护）
        m.setCreatedAt(now);
        messageMapper.insert(m);

        // 计数 +1 + 更新 lastMessageAt
        sessionMapper.update(null, new LambdaUpdateWrapper<HeartcoveSession>()
            .eq(HeartcoveSession::getId, sessionId)
            .set(HeartcoveSession::getMessageCount, s.getMessageCount() + 1)
            .set(HeartcoveSession::getLastMessageAt, now)
            .set(HeartcoveSession::getUpdatedAt, now)
        );
        return m;
    }

    /** 保存 AI 回复（ChatService 调用） */
    @Transactional
    public HeartcoveMessage saveAiMessage(Long sessionId, String content,
                                          String sourceMessageIds, String unknownType,
                                          Integer generationMs, String safetyFlag) {
        LocalDateTime now = LocalDateTime.now();
        HeartcoveMessage m = new HeartcoveMessage();
        m.setSessionId(sessionId);
        m.setRole("ai");
        m.setContent(content);
        m.setIsMilvusSynced(0);
        m.setSourceMessageIds(sourceMessageIds);
        m.setUnknownType(unknownType);
        m.setGenerationMs(generationMs);
        m.setSafetyFlag(safetyFlag);
        m.setCreatedAt(now);
        messageMapper.insert(m);

        sessionMapper.update(null, new LambdaUpdateWrapper<HeartcoveSession>()
            .eq(HeartcoveSession::getId, sessionId)
            .set(HeartcoveSession::getMessageCount,
                sessionMapper.selectById(sessionId).getMessageCount() + 1)
            .set(HeartcoveSession::getLastMessageAt, now)
            .set(HeartcoveSession::getUpdatedAt, now)
        );
        return m;
    }

    // ---- helpers ----

    private Subject mustEnabledSubject(Long subjectId) {
        Subject s = subjectMapper.selectById(subjectId);
        if (s == null) throw new BusinessException(ResultCode.SUBJECT_NOT_FOUND);
        if (s.getHeartcoveEnabled() == null || s.getHeartcoveEnabled() == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN, "该人物未开启心声信箱");
        }
        return s;
    }

    /** 校验会话归属（session.user_id == userId） */
    private HeartcoveSession mustOwnedSession(Long userId, Long sessionId) {
        HeartcoveSession s = sessionMapper.selectById(sessionId);
        if (s == null) throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在");
        if (!userId.equals(s.getUserId())) {
            // 不告诉调用者"无权访问"，统一返回 NOT_FOUND 防探测
            throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在");
        }
        return s;
    }

    private HeartcoveSessionVO toVO(HeartcoveSession s, Subject subject, List<HeartcoveMessage> messages) {
        HeartcoveSessionVO vo = new HeartcoveSessionVO();
        vo.setId(s.getId());
        vo.setSubjectId(s.getSubjectId());
        vo.setSubjectDisplayName(subject.getDisplayName());
        vo.setStatus(s.getStatus());
        vo.setMessageCount(s.getMessageCount());
        vo.setLastMessageAt(s.getLastMessageAt());
        vo.setStartedAt(s.getStartedAt());
        vo.setClosedAt(s.getClosedAt());
        vo.setMessages(messages.stream().map(this::toMessageVO).collect(Collectors.toList()));
        return vo;
    }

    private HeartcoveMessageVO toMessageVO(HeartcoveMessage m) {
        HeartcoveMessageVO vo = new HeartcoveMessageVO();
        vo.setId(m.getId());
        vo.setRole(m.getRole());
        vo.setContent(m.getContent());
        vo.setSourceMessageIds(m.getSourceMessageIds());
        vo.setUnknownType(m.getUnknownType());
        vo.setSafetyFlag(m.getSafetyFlag());
        vo.setCreatedAt(m.getCreatedAt());
        return vo;
    }

    /**
     * M14+：构造 session 启动时缓存的 persona_summary（basePersona + 代际文案）。
     *
     * <p>口径：subject.gen / user.gen 都是相对家族根的代际偏移量（0=本人辈，正数=晚辈，负数=长辈），
     * 数字越大辈分越小。subject.gen - user.gen 即两人代际差：
     *   <ul>
     *     <li>差 = 0 → 同辈</li>
     *     <li>差 > 0 → subject 更年轻 → subject 是 user 的晚辈</li>
     *     <li>差 < 0 → subject 更年长 → subject 是 user 的长辈</li>
     *   </ul>
     * 任一方 generation 为 null 则跳过代际注入（个人项目 / 自传场景）。</p>
     */
    private String buildPersonaSummary(Long userId, Subject subject) {
        String basePersona = subject.getHeartcovePersonaSummary() == null
            ? "（暂无摘要）" : subject.getHeartcovePersonaSummary();

        Integer subjectGen = subject.getGeneration();
        if (subjectGen == null) {
            return basePersona;
        }

        // 拿 user 绑定的 FamilyMember.generation（user 没绑 fm → generation=null → 跳过）
        Integer userGen = null;
        List<FamilyMember> fms = familyMemberMapper.selectList(
            new LambdaQueryWrapper<FamilyMember>()
                .eq(FamilyMember::getUserId, userId)
                .last("LIMIT 1")
        );
        if (!fms.isEmpty()) {
            userGen = fms.get(0).getGeneration();
        }

        if (userGen == null) {
            return basePersona;
        }

        int diff = subjectGen - userGen;
        int absDiff = Math.abs(diff);
        String hint;
        if (diff == 0) {
            hint = "\n\n[家族代际] 你是用户同辈。";
        } else if (diff > 0) {
            // subject 是 user 的晚辈
            if (absDiff == 1) hint = "\n\n[家族代际] 你是用户的儿女辈。";
            else if (absDiff == 2) hint = "\n\n[家族代际] 你是用户的孙辈。";
            else if (absDiff == 3) hint = "\n\n[家族代际] 你是用户的曾孙辈。";
            else hint = String.format("\n\n[家族代际] 你是用户的第 %d 代晚辈。", absDiff);
        } else {
            // diff < 0，subject 是 user 的长辈
            if (absDiff == 1) hint = "\n\n[家族代际] 你是用户的父母辈。";
            else if (absDiff == 2) hint = "\n\n[家族代际] 你是用户的祖辈。";
            else if (absDiff == 3) hint = "\n\n[家族代际] 你是用户的曾祖辈。";
            else hint = String.format("\n\n[家族代际] 你是用户的第 %d 代长辈。", absDiff);
        }

        return basePersona + hint;
    }
}