package com.momentweaver.heartcove.service;

import com.momentweaver.common.entity.InterviewMessage;
import com.momentweaver.heartcove.client.HeartcoveAiClient;
import com.momentweaver.heartcove.event.HeartcoveEnableEvent;
import com.momentweaver.memory.entity.InterviewSession;
import com.momentweaver.memory.entity.Subject;
import com.momentweaver.memory.mapper.SubjectMapper;
import com.momentweaver.memory.repo.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 心声信箱 enable 后的 persona_summary 异步生成器（M14+ 体验修复）。
 *
 * <p>监听 {@link HeartcoveEnableEvent}, 异步从 MongoDB 采样 subject 的
 * 既往采访发言 (role=user), 调 AI 生成 300-500 字的人格摘要, 写回
 * {@code Subject.heartcove_persona_summary}。</p>
 *
 * <p>失败兜底：AI 调用失败 / MongoDB 拉不到任何发言时, 用默认模板写入,
 * 至少保证后续对话不会拿空 persona_summary 去问 LLM。</p>
 *
 * <p>线程池：{@code heartcovePersonaExecutor}（core=1 max=2 queue=8），
 * 满队列 caller-runs。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeartcovePersonaGenerator {

    private static final int MAX_QUOTE_CHARS = 200;     // 单条发言截断
    private static final int MAX_QUOTES = 30;            // 采样条数

    private final InterviewSessionRepository interviewSessionRepository;
    private final SubjectMapper subjectMapper;
    private final HeartcoveAiClient aiClient;

    @Async("heartcovePersonaExecutor")
    @EventListener(HeartcoveEnableEvent.class)
    public void onEnable(HeartcoveEnableEvent event) {
        Long subjectId = event.getSubjectId();
        log.info("heartcove persona generation start (subject={})", subjectId);
        try {
            Subject subject = subjectMapper.selectById(subjectId);
            if (subject == null) {
                log.warn("persona gen: subject {} not found", subjectId);
                return;
            }

            List<Map<String, String>> quoteChunks = collectQuotes(subjectId);
            String previous = subject.getHeartcovePersonaSummary();

            HeartcoveAiClient.PersonaSummaryResult result = aiClient.buildPersonaSummary(
                String.valueOf(subjectId),
                subject.getDisplayName() == null ? "先辈" : subject.getDisplayName(),
                inferAgeHint(subject),
                subject.getRelation() == null ? "先辈" : subject.getRelation(),
                quoteChunks,
                previous
            );

            persistSummary(subjectId, result.personaSummary());
            log.info("heartcove persona generation done (subject={}, fallback={}, quotes={})",
                subjectId, result.fallback(), quoteChunks.size());
        } catch (Exception e) {
            log.error("heartcove persona generation failed (subject={})", subjectId, e);
            // 兜底: 写默认模板, 防止 persona_summary 一直是 null
            try {
                persistSummary(subjectId,
                    "暂无既往采访内容可用;按温和长辈的基本形象应对,被问到具体经历时坦诚说自己记不清、请对方讲讲。");
            } catch (Exception inner) {
                log.error("heartcove persona fallback persist failed (subject={})", subjectId, inner);
            }
        }
    }

    /**
     * 从 MongoDB interview_session 中按 lastMessageAt 倒序采集 role=user 的发言。
     * 采样规则：每个 session 取最近 5 条 user 发言, 跨 session 总数上限 MAX_QUOTES。
     */
    private List<Map<String, String>> collectQuotes(Long subjectId) {
        String sid = String.valueOf(subjectId);
        List<InterviewSession> sessions = interviewSessionRepository.findBySubjectIdOrderByLastMessageAtDesc(sid);
        if (sessions.isEmpty()) return Collections.emptyList();

        List<Map<String, String>> quotes = new ArrayList<>();
        for (InterviewSession s : sessions) {
            if (s.getMessages() == null || s.getMessages().isEmpty()) continue;
            // 取本 session 内 role=user 的发言, 最多 5 条
            List<InterviewMessage> userMsgs = new ArrayList<>();
            for (InterviewMessage m : s.getMessages()) {
                if ("user".equals(m.getRole())) userMsgs.add(m);
            }
            int start = Math.max(0, userMsgs.size() - 5);
            for (int i = start; i < userMsgs.size(); i++) {
                InterviewMessage m = userMsgs.get(i);
                if (m.getContent() == null || m.getContent().isBlank()) continue;
                Map<String, String> item = new HashMap<>();
                item.put("turn_id", m.getTurnId() == null ? "" : m.getTurnId().toString());
                item.put("interview_session_id", s.getId() == null ? "" : s.getId());
                item.put("content", trim(m.getContent(), MAX_QUOTE_CHARS));
                item.put("source", "采访原话");
                quotes.add(item);
                if (quotes.size() >= MAX_QUOTES) return quotes;
            }
        }
        return quotes;
    }

    @Transactional
    public void persistSummary(Long subjectId, String summary) {
        Subject s = subjectMapper.selectById(subjectId);
        if (s == null) return;
        s.setHeartcovePersonaSummary(summary);
        s.setUpdatedAt(LocalDateTime.now());
        subjectMapper.updateById(s);
    }

    private String inferAgeHint(Subject subject) {
        // MVP: 没有 birth_year 字段, 用 relation 给个 hint
        return "长辈（" + (subject.getRelation() == null ? "先辈" : subject.getRelation()) + "）";
    }

    private String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
