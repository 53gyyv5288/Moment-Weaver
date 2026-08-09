package com.momentweaver.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.momentweaver.account.entity.Project;
import com.momentweaver.account.entity.WorkspaceMember;
import com.momentweaver.account.mapper.ProjectMapper;
import com.momentweaver.account.mapper.WorkspaceMemberMapper;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import com.momentweaver.common.event.TimelineEventRequest;
import com.momentweaver.common.event.TimelineEventTypes;
import com.momentweaver.memory.client.AiClient;
import com.momentweaver.memory.dto.InterviewSessionVO;
import com.momentweaver.memory.dto.InterviewStartRequest;
import com.momentweaver.memory.entity.Authorization;
import com.momentweaver.memory.entity.InterviewMessage;
import com.momentweaver.memory.entity.InterviewSession;
import com.momentweaver.memory.entity.Subject;
import com.momentweaver.memory.mapper.AuthorizationMapper;
import com.momentweaver.memory.mapper.SubjectMapper;
import com.momentweaver.memory.repo.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewService {

    private final InterviewSessionRepository sessionRepo;
    private final SubjectMapper subjectMapper;
    private final AuthorizationMapper authorizationMapper;
    private final ProjectMapper projectMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;
    private final AiClient aiClient;
    private final ApplicationEventPublisher eventPublisher;

    /** 启动一个新采访会话（不立即调 AI）。 */
    public InterviewSessionVO start(Long userId, InterviewStartRequest req) {
        Project p = mustProject(req.getProjectId());
        ensureMember(p.getWorkspaceId(), userId);

        Subject s = mustSubject(req.getSubjectId());
        if (!s.getProjectId().equals(p.getId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "人物不属于该项目");
        }

        Authorization authz;
        if (req.getAuthorizationId() != null) {
            authz = mustAuth(req.getAuthorizationId());
            if (!authz.getProjectId().equals(p.getId()) || !authz.getSubjectId().equals(s.getId())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "授权与人物/项目不匹配");
            }
        } else {
            authz = authorizationMapper.selectOne(
                new LambdaQueryWrapper<Authorization>()
                    .eq(Authorization::getSubjectId, s.getId())
                    .eq(Authorization::getStatus, "granted")
                    .orderByDesc(Authorization::getGrantedAt)
                    .last("LIMIT 1")
            );
            if (authz == null) {
                throw new BusinessException(ResultCode.AUTHORIZATION_INVALID, "该人物尚未同意授权");
            }
        }
        if (!"granted".equals(authz.getStatus())) {
            throw new BusinessException(ResultCode.AUTHORIZATION_INVALID, "授权状态: " + authz.getStatus());
        }

        InterviewSession sess = new InterviewSession();
        sess.setProjectId(String.valueOf(p.getId()));
        sess.setSubjectId(String.valueOf(s.getId()));
        sess.setAuthorizationId(String.valueOf(authz.getId()));
        sess.setStatus("active");
        sess.setSubjectDisplayName(s.getDisplayName());
        sess.setProjectName(p.getName());

        LocalDateTime now = LocalDateTime.now();
        sess.setStartedAt(now);
        sess.setLastMessageAt(now);

        // 系统提示作为第一条消息
        InterviewMessage sys = InterviewMessage.builder()
            .role("system")
            .source("human") // 提示词由后端注入，标记 human 即可
            .content(buildSystemHint(s))
            .createdAt(now)
            .build();
        sess.getMessages().add(sys);

        InterviewSession saved = sessionRepo.save(sess);
        return toVO(saved);
    }

    /**
     * 流式发送用户消息。返回 Flux<StreamChunk>：
     *   - kind="text"   —— AI 可见正文片段
     *   - kind="think"  —— 推理模型思考链片段（持久化到 Mongo，不进时间线预览）
     *
     * 副作用：先追加 user 消息，等流结束再追加 assistant 消息（content + thinking）。
     */
    public Flux<AiClient.StreamChunk> streamMessage(Long userId, String sessionId, String userContent) {
        InterviewSession sess = mustSession(sessionId);
        Project p = mustProject(Long.parseLong(sess.getProjectId()));
        ensureMember(p.getWorkspaceId(), userId);

        // 1) 追加 user 消息
        LocalDateTime now = LocalDateTime.now();
        InterviewMessage userMsg = InterviewMessage.builder()
            .role("user")
            .source("human")
            .content(userContent)
            .createdAt(now)
            .build();
        sess.getMessages().add(userMsg);
        sess.setLastMessageAt(now);
        sessionRepo.save(sess);

        // 2) 组装要发给 AI 的 messages
        List<AiClient.AiMessage> aiMsgs = new ArrayList<>();
        for (InterviewMessage m : sess.getMessages()) {
            aiMsgs.add(new AiClient.AiMessage(m.getRole(), m.getContent()));
        }

        // 3) 流式调 AI，分别累计可见文本与思考链
        String subjectHint = sess.getSubjectDisplayName() + " | " + p.getName();
        StringBuilder accText = new StringBuilder();
        StringBuilder accThink = new StringBuilder();

        return aiClient.streamInterview(sessionId, subjectHint, aiMsgs)
            .doOnNext(chunk -> {
                if (chunk.isText()) {
                    accText.append(chunk.content());
                } else if (chunk.isThink()) {
                    accThink.append(chunk.content());
                }
            })
            .doOnComplete(() -> {
                String full = accText.toString();
                String thinking = accThink.toString();
                if (!full.isEmpty()) {
                    InterviewMessage assistant = InterviewMessage.builder()
                        .role("assistant")
                        .source("ai_generated")
                        .content(full)
                        // 仅在真有思考链内容时落库，避免给老格式文档写一堆空串
                        .thinking(thinking.isEmpty() ? null : thinking)
                        .createdAt(LocalDateTime.now())
                        .build();
                    sess.getMessages().add(assistant);
                }
                sess.setLastMessageAt(LocalDateTime.now());
                sessionRepo.save(sess);
                log.info("Interview session {} assistant message saved (text={} chars, thinking={} chars)",
                    sessionId, accText.length(), accThink.length());

                // 触发时间线事件（M3 解耦：module-timeline 监听并写库）
                // 注意：思考链不进时间线预览，避免污染 Timeline 列表。
                if (!full.isEmpty()) {
                    String preview = full.length() > 80 ? full.substring(0, 80) + "…" : full;
                    eventPublisher.publishEvent(new TimelineEventRequest(
                        sess.getProjectId(),
                        sess.getSubjectId(),
                        TimelineEventTypes.INTERVIEW_MESSAGE,
                        sessionId,
                        "AI 采访官 · " + (sess.getSubjectDisplayName() == null ? "采访" : sess.getSubjectDisplayName()),
                        preview,
                        java.util.Map.of("sessionId", sessionId, "role", "assistant")
                    ));
                }
            })
            .doOnError(e -> log.error("Interview session {} stream error", sessionId, e));
    }

    public InterviewSessionVO get(Long userId, String sessionId) {
        InterviewSession sess = mustSession(sessionId);
        Project p = mustProject(Long.parseLong(sess.getProjectId()));
        ensureMember(p.getWorkspaceId(), userId);
        return toVO(sess);
    }

    public List<InterviewSessionVO> listByProject(Long userId, Long projectId) {
        Project p = mustProject(projectId);
        ensureMember(p.getWorkspaceId(), userId);
        return sessionRepo.findByProjectIdOrderByLastMessageAtDesc(String.valueOf(projectId))
            .stream()
            .map(this::toVO)
            .toList();
    }

    /**
     * 关闭会话。
     * 关闭后若尚无 summary，异步触发一次摘要生成（不阻塞 close 接口）。
     */
    public InterviewSessionVO close(Long userId, String sessionId) {
        InterviewSession sess = mustSession(sessionId);
        Project p = mustProject(Long.parseLong(sess.getProjectId()));
        if (!p.getOwnerId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅项目 Owner 可关闭会话");
        }
        sess.setStatus("closed");
        sess.setClosedAt(LocalDateTime.now());
        InterviewSession saved = sessionRepo.save(sess);

        // 触发异步摘要
        if (saved.getSummary() == null && hasEnoughContent(saved)) {
            summarizeAsync(saved.getId());
        }
        return toVO(saved);
    }

    /** 手动触发一次摘要（M3 阶段；前端「重新生成摘要」按钮）。 */
    public InterviewSessionVO summarizeNow(Long userId, String sessionId) {
        InterviewSession sess = mustSession(sessionId);
        Project p = mustProject(Long.parseLong(sess.getProjectId()));
        ensureMember(p.getWorkspaceId(), userId);
        summarizeSync(sess);
        return toVO(sess);
    }

    /** 异步：关 close 后自动调一次 */
    @Async("summaryExecutor")
    public void summarizeAsync(String sessionId) {
        try {
            InterviewSession sess = sessionRepo.findById(sessionId).orElse(null);
            if (sess == null) {
                log.warn("summarizeAsync: session {} not found", sessionId);
                return;
            }
            summarizeSync(sess);
        } catch (Exception e) {
            log.error("summarizeAsync failed for session {}", sessionId, e);
        }
    }

    private void summarizeSync(InterviewSession sess) {
        if (!hasEnoughContent(sess)) {
            log.info("summarizeSync: session {} has too few user/assistant turns, skip", sess.getId());
            return;
        }
        List<AiClient.AiMessage> aiMsgs = new ArrayList<>();
        for (InterviewMessage m : sess.getMessages()) {
            aiMsgs.add(new AiClient.AiMessage(m.getRole(), m.getContent()));
        }
        String subjectHint = sess.getSubjectDisplayName() + " | " + sess.getProjectName();
        AiClient.SummaryResult r = aiClient.summarize(sess.getId(), subjectHint, aiMsgs);

        InterviewSession.InterviewSummary s = InterviewSession.InterviewSummary.builder()
            .title(r.title == null ? "本次采访" : r.title)
            .goldenQuotes(r.goldenQuotes == null ? new ArrayList<>() : r.goldenQuotes)
            .keyMoments(
                r.keyMoments == null
                    ? new ArrayList<>()
                    : r.keyMoments.stream()
                        .map(km -> InterviewSession.KeyMoment.builder()
                            .timestamp(km.timestamp == null ? "" : km.timestamp)
                            .text(km.text == null ? "" : km.text)
                            .build())
                        .toList()
            )
            .generatedAt(LocalDateTime.now())
            .generatedBy("ai")
            .build();
        sess.setSummary(s);
        sessionRepo.save(sess);
        log.info("Session {} summary persisted (title={})", sess.getId(), s.getTitle());

        // 触发时间线事件：ai_summary 一条
        eventPublisher.publishEvent(new TimelineEventRequest(
            sess.getProjectId(),
            sess.getSubjectId(),
            TimelineEventTypes.AI_SUMMARY,
            sess.getId(),
            "AI 摘要 · " + sess.getSubjectDisplayName(),
            s.getTitle(),
            java.util.Map.of(
                "sessionId", sess.getId(),
                "quoteCount", String.valueOf(s.getGoldenQuotes() == null ? 0 : s.getGoldenQuotes().size()),
                "momentCount", String.valueOf(s.getKeyMoments() == null ? 0 : s.getKeyMoments().size())
            )
        ));
    }

    /** 至少要 2 轮 user/assistant 才值得摘要 */
    private boolean hasEnoughContent(InterviewSession sess) {
        if (sess.getMessages() == null) return false;
        long turns = sess.getMessages().stream()
            .filter(m -> "user".equals(m.getRole()) || "assistant".equals(m.getRole()))
            .count();
        return turns >= 4;
    }

    // ---- helpers ----

    private InterviewSession mustSession(String id) {
        return sessionRepo.findById(id)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "采访会话不存在"));
    }

    private Project mustProject(Long projectId) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);
        return p;
    }

    private Subject mustSubject(Long subjectId) {
        Subject s = subjectMapper.selectById(subjectId);
        if (s == null) throw new BusinessException(ResultCode.SUBJECT_NOT_FOUND);
        return s;
    }

    private Authorization mustAuth(Long id) {
        Authorization a = authorizationMapper.selectById(id);
        if (a == null) throw new BusinessException(ResultCode.AUTHORIZATION_NOT_FOUND);
        return a;
    }

    private void ensureMember(Long workspaceId, Long userId) {
        Long cnt = workspaceMemberMapper.selectCount(
            new LambdaQueryWrapper<WorkspaceMember>()
                .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                .eq(WorkspaceMember::getUserId, userId)
        );
        if (cnt == null || cnt == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN, "非工作区成员");
        }
    }

    private InterviewSessionVO toVO(InterviewSession s) {
        InterviewSessionVO vo = new InterviewSessionVO();
        vo.setId(s.getId());
        vo.setProjectId(s.getProjectId());
        vo.setSubjectId(s.getSubjectId());
        vo.setAuthorizationId(s.getAuthorizationId());
        vo.setStatus(s.getStatus());
        vo.setSubjectDisplayName(s.getSubjectDisplayName());
        vo.setProjectName(s.getProjectName());
        vo.setMessages(s.getMessages());
        vo.setSummary(s.getSummary());
        vo.setStartedAt(s.getStartedAt());
        vo.setLastMessageAt(s.getLastMessageAt());
        vo.setClosedAt(s.getClosedAt());
        return vo;
    }

    private String buildSystemHint(Subject s) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位温暖、有耐心、尊重长辈的 AI 采访官。");
        sb.append("正在采访 ").append(s.getDisplayName());
        if (s.getRelation() != null && !s.getRelation().isBlank()) {
            sb.append("（").append(s.getRelation()).append("）");
        }
        sb.append("。");
        sb.append("请用第一人称视角（'您''您当年'）发问；问题要短而具体，1 句话即可；");
        sb.append("围绕人生关键节点：童年、求学、初恋、职业生涯、家庭、子女、退休、遗憾与骄傲。");
        sb.append("避免宗教、政治敏感话题；如对方答非所问或沉默，温柔换一个角度。");
        return sb.toString();
    }
}