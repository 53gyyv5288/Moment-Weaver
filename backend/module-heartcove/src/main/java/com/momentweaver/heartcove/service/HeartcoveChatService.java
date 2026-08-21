package com.momentweaver.heartcove.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import com.momentweaver.common.entity.InterviewMessage;
import com.momentweaver.heartcove.client.HeartcoveAiClient;
import com.momentweaver.heartcove.entity.HeartcoveAuditLog;
import com.momentweaver.heartcove.entity.HeartcoveMemorySummary;
import com.momentweaver.heartcove.entity.HeartcoveMessage;
import com.momentweaver.heartcove.entity.HeartcoveSession;
import com.momentweaver.heartcove.mapper.HeartcoveAuditLogMapper;
import com.momentweaver.heartcove.mapper.HeartcoveMemorySummaryMapper;
import com.momentweaver.heartcove.mapper.HeartcoveMessageMapper;
import com.momentweaver.heartcove.mapper.HeartcoveSessionMapper;
import com.momentweaver.memory.entity.InterviewSession;
import com.momentweaver.memory.entity.Subject;
import com.momentweaver.memory.mapper.SubjectMapper;
import com.momentweaver.memory.repo.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 心声信箱对话业务：上下文组装 + AI 调用编排 + 持久化 + 中期记忆更新。
 *
 * <p><b>上下文来源（绝不进 milvus）</b>：
 *   <ul>
 *     <li>短期：会话内最近 8 条消息（business member 列表）</li>
 *     <li>中期：滚动摘要（heartcove_memory_summary）</li>
 *     <li>长期：从 MongoDB interview_message 检索（关键词匹配；本期用最简单的 contains）</li>
 *   </ul>
 * </p>
 *
 * <p><b>流式输出</b>：从 AI 服务的 SSE 流逐 token 推给前端，全部 token 收齐后落库 AI 消息。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeartcoveChatService {

    private static final int RECENT_TURNS = 8;
    private static final int MAX_RELATED_QUOTES = 5;

    private final SubjectMapper subjectMapper;
    private final InterviewSessionRepository interviewSessionRepository;
    private final HeartcoveSessionMapper sessionMapper;
    private final HeartcoveMessageMapper messageMapper;
    private final HeartcoveMemorySummaryMapper memoryMapper;
    private final HeartcoveAuditLogMapper auditLogMapper;
    private final HeartcoveSessionService sessionService;
    private final HeartcoveAiClient aiClient;

    /** 上下文组装：从 MongoDB interview_message 中按关键词简单匹配 */
    public Map<String, Object> assembleContext(Long userId, Long sessionId, String userMsg) {
        HeartcoveSession session = mustOwnedSession(userId, sessionId);
        Subject subject = mustEnabledSubject(session.getSubjectId());

        // 1) 短期记忆：本会话最近 8 条
        List<HeartcoveMessage> recent = messageMapper.selectList(
            new LambdaQueryWrapper<HeartcoveMessage>()
                .eq(HeartcoveMessage::getSessionId, sessionId)
                .orderByDesc(HeartcoveMessage::getCreatedAt)
                .last("LIMIT " + RECENT_TURNS)
        );
        Collections.reverse(recent);
        List<Map<String, String>> recentDialog = recent.stream()
            .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
            .collect(Collectors.toList());

        // 2) 中期记忆：滚动摘要
        HeartcoveMemorySummary mem = memoryMapper.selectOne(
            new LambdaQueryWrapper<HeartcoveMemorySummary>()
                .eq(HeartcoveMemorySummary::getSubjectId, session.getSubjectId())
                .eq(HeartcoveMemorySummary::getUserId, userId)
                .last("LIMIT 1")
        );
        String lastSummary = mem == null ? "" : mem.getSummary();

        // 3) 长期记忆：从 MongoDB interview_message 中检索（关键词包含；MVP 简化版）
        List<Map<String, String>> relatedQuotes = retrieveRelatedQuotes(subject.getId(), userMsg);

        // 4) 组装
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("display_name", subject.getDisplayName() == null ? "先辈" : subject.getDisplayName());
        // M14+: 删 age_hint（硬模板会限制先辈类型, 改为 persona_summary 决定人设）
        // 新增 relation：用户对先辈的称呼, 供 prompt 第 1 段 fallback 用
        ctx.put("relation", subject.getRelation() == null ? "先辈" : subject.getRelation());
        ctx.put("style_tone", "温和长辈");
        // M14+ 家族关系图：persona_summary（含代际文案）由 HeartcoveSessionService.openOrCreate
        // 在 session 启动时一次性算好，缓存进 heartcove_session.cached_persona_summary。
        // 这里直接读缓存，避免每条消息都查 subject + family_member。
        // 兼容：NULL 走旧路径（老 session 行 / 缓存字段上线前的历史数据）
        HeartcoveSession cachedSession = sessionMapper.selectById(sessionId);
        String cachedPersona = cachedSession == null ? null : cachedSession.getCachedPersonaSummary();
        if (cachedPersona == null) {
            String basePersona = subject.getHeartcovePersonaSummary() == null
                ? "（暂无摘要）" : subject.getHeartcovePersonaSummary();
            // 旧 session 兜底：仅基于 subject.generation 单边注入（不带 user 对比）
            if (subject.getGeneration() != null) {
                int g = subject.getGeneration();
                String generationHint;
                if (g > 0) generationHint = String.format("\n\n[家族代际] 你是用户的第 %d 代长辈。", g);
                else if (g == 0) generationHint = "\n\n[家族代际] 你是用户同辈。";
                else generationHint = String.format("\n\n[家族代际] 你是用户的第 %d 代晚辈。", Math.abs(g));
                basePersona = basePersona + generationHint;
            }
            cachedPersona = basePersona;
        }
        ctx.put("persona_summary", cachedPersona);
        ctx.put("recent_dialog", recentDialog);
        ctx.put("related_quotes", relatedQuotes);
        ctx.put("last_summary", lastSummary);
        return ctx;
    }

    /** 关键词匹配：MVP 用 contains；后续可替换为 MongoDB 文本索引 / RAG 召回 */
    private List<Map<String, String>> retrieveRelatedQuotes(Long subjectId, String userMsg) {
        if (userMsg == null || userMsg.isBlank()) return Collections.emptyList();
        String subjectIdStr = String.valueOf(subjectId);
        List<InterviewSession> sessions = interviewSessionRepository.findBySubjectIdOrderByLastMessageAtDesc(subjectIdStr);
        if (sessions.isEmpty()) return Collections.emptyList();

        // 简单的中文 2-gram 拆词 + contains；不实现复杂分词
        List<String> tokens = tokenize(userMsg);
        if (tokens.isEmpty()) return Collections.emptyList();

        List<Map<String, String>> hits = new ArrayList<>();
        for (InterviewSession s : sessions) {
            if (s.getMessages() == null) continue;
            for (InterviewMessage m : s.getMessages()) {
                // ⛔ 修正:采访里 role=user 才是被访者本人(爷爷/外婆)的发言,role=assistant 是 AI 采访员提问。
                // 旧实现取 role=assistant,等于把"AI 问过的问题"当成"先辈发言"喂给 LLM,导致 AI 输出事实虚构。
                if (!"user".equals(m.getRole())) continue;
                if (m.getContent() == null || m.getContent().isBlank()) continue;
                String content = m.getContent();
                // 任一 token 命中即收录
                boolean matched = false;
                for (String t : tokens) {
                    if (t.length() >= 2 && content.contains(t)) { matched = true; break; }
                }
                if (!matched) continue;
                Map<String, String> item = new HashMap<>();
                // ⛔ 溯源修复:MongoDB interview_session.messages 是嵌入式数组,
                // 单条 message 没有 _id,只能用 InterviewSession._id + InterviewMessage.turnId 联合定位。
                // 前端"查看原话"按钮与合规追溯靠这两个字段定位。
                item.put("interview_session_id", s.getId() == null ? "" : s.getId());
                item.put("turn_id", m.getTurnId() == null ? "" : m.getTurnId().toString());
                item.put("content", trim(content, 200));
                item.put("source", "采访原话");
                hits.add(item);
                if (hits.size() >= MAX_RELATED_QUOTES) return hits;
            }
        }
        return hits;
    }

    /** 流式调用 AI 并把 SSE 帧转发给前端；消息落库在最后做。
     *
     * <p>新接口 {@link #streamReplyEvents} 直接返回 {@code Flux<SsePair>}，
     * 由 controller 包成 {@code ServerSentEvent<String>} 后输出——
     * 唯一能让 Spring WebFlux 正确写出 {@code "event: <name>\ndata: <data>\n\n"}
     * 帧结构的方式（{@code Flux<String>} / {@code Flux<byte[]>} 都会被自动套
     * {@code "data: "} 前缀，破坏已有帧结构）。
     *
     * <p>本方法保留为内部兼容入口（其它 service 仍可调用）。
     */
    public reactor.core.publisher.Flux<String> streamReply(Long userId, Long sessionId, String userMsg, String ip, String ua) {
        return streamReplyBytes(userId, sessionId, userMsg, ip, ua)
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    /** SSE 事件二元组（event 名 + data）。data 可能为 null（保留字段用于 done 帧）。 */
    public record SsePair(String event, String data) {}

    /**
     * 流式调用 AI，把每个 SSE 帧拆成 (event, data) 二元组透传给 controller。
     * Controller 端用 {@code ServerSentEvent.builder().event(...).data(...)} 包一下，
     * Spring 才会按 SSE 协议原样写出 {@code "event: <name>\ndata: <data>\n\n"}。
     */
    public reactor.core.publisher.Flux<SsePair> streamReplyEvents(Long userId, Long sessionId, String userMsg, String ip, String ua) {
        return streamReplyBytes(userId, sessionId, userMsg, ip, ua)
            .flatMap(bytes -> {
                // bytes 是完整 SSE 帧（可能含多行 event/data）；按 SSE 协议拆成多个 SsePair
                String frame = new String(bytes, StandardCharsets.UTF_8);
                return reactor.core.publisher.Flux.fromIterable(parseFrameToPairs(frame));
            });
    }

    /**
     * 旧接口，保留兼容。controller 不要再用——{@code Flux<byte[]>} 仍会被
     * Spring WebFlux 套 SSE encoder 导致帧结构被破坏。详见 controller 注释。
     */
    public reactor.core.publisher.Flux<byte[]> streamReplyBytes(Long userId, Long sessionId, String userMsg, String ip, String ua) {
        Map<String, Object> ctx = assembleContext(userId, sessionId, userMsg);

        // 1) 持久化用户消息
        HeartcoveMessage userMsgEntity = sessionService.saveUserMessage(userId, sessionId, userMsg);

        // 2) 构造 AI body
        HeartcoveAiClient.HeartcoveStreamBody body = new HeartcoveAiClient.HeartcoveStreamBody();
        body.setSession_id(String.valueOf(sessionId));
        HeartcoveSession s = sessionMapper.selectById(sessionId);
        body.setSubject_id(String.valueOf(s.getSubjectId()));
        body.setDisplay_name((String) ctx.get("display_name"));
        // M14+: 删 age_hint, 新增 relation
        body.setRelation((String) ctx.get("relation"));
        body.setStyle_tone((String) ctx.get("style_tone"));
        body.setPersona_summary((String) ctx.get("persona_summary"));
        body.setRecent_dialog((List<Map<String, String>>) ctx.get("recent_dialog"));
        body.setRelated_quotes((List<Map<String, String>>) ctx.get("related_quotes"));
        body.setUser_msg(userMsg);

        // 3) 流式调用 AI（拿原始字节，自己做 SSE 解析——避免依赖 WebClient SSE 字符串解码行为）
        reactor.core.publisher.Flux<DataBuffer> aiStream = aiClient.streamChat(body);

        // 4) 在流末端聚合 AI 回复，落库；前端看到的是原 SSE 帧（字节）+ 末尾 done 帧
        //    lambda 捕获必须是 effectively final，所以用 1-元素数组当可变容器
        StringBuilder aiReply = new StringBuilder();
        long startMs = System.currentTimeMillis();
        final String[] unknownType = {null};
        final String[] safetyFlag = {null};
        final String[] sourceIds = {""};

        // 攒原始字节到一个 StringBuilder，按 \n\n 切事件块逐个解析；切出来的帧也透传给前端
        final StringBuilder[] buf = { new StringBuilder() };
        reactor.core.publisher.Flux<byte[]> enriched = aiStream
            .map(buf2 -> {
                byte[] bytes = new byte[buf2.readableByteCount()];
                buf2.read(bytes);
                DataBufferUtils.release(buf2);
                return new String(bytes, StandardCharsets.UTF_8);
            })
            .flatMap(chunk -> {
                buf[0].append(chunk);
                List<byte[]> frames = new ArrayList<>();
                int idx;
                while ((idx = buf[0].indexOf("\n\n")) >= 0) {
                    String frame = buf[0].substring(0, idx);
                    buf[0].delete(0, idx + 2);
                    if (frame.isBlank()) continue;
                    parseAndAccumulate(frame, aiReply, unknownType, safetyFlag, sourceIds);
                    frames.add((frame + "\n\n").getBytes(StandardCharsets.UTF_8));
                }
                return reactor.core.publisher.Flux.fromIterable(frames);
            })
            .concatWith(reactor.core.publisher.Flux.defer(() -> {
                if (buf[0].length() == 0) return reactor.core.publisher.Flux.empty();
                String tail = buf[0].toString();
                buf[0].setLength(0);
                // 流结束时残余 buffer 仍然跑一次解析（部分 event 可能横跨在末尾）
                parseAndAccumulate(tail, aiReply, unknownType, safetyFlag, sourceIds);
                return reactor.core.publisher.Flux.just(tail.getBytes(StandardCharsets.UTF_8));
            }))
            .doOnComplete(() -> {
                try {
                    long cost = System.currentTimeMillis() - startMs;
                    String content = aiReply.toString();
                    if (!content.isBlank()) {
                        sessionService.saveAiMessage(
                            sessionId, content, sourceIds[0], unknownType[0],
                            (int) cost, safetyFlag[0]);
                    }
                    logAction(userId, s.getSubjectId(), "chat",
                        "reply=" + content.length() + "chars unknown=" + unknownType[0], ip, ua);
                    // 触发中期记忆压缩（每 6 轮一次）
                    triggerRollingSummary(userId, s.getSubjectId());
                } catch (Exception e) {
                    log.error("heartcove save ai message failed", e);
                }
            });

        return enriched;
    }

    /** 触发滚动摘要（每 6 轮对话调用一次 AI summarize） */
    @Transactional
    public void triggerRollingSummary(Long userId, Long subjectId) {
        HeartcoveSession lastSession = sessionMapper.selectOne(
            new LambdaQueryWrapper<HeartcoveSession>()
                .eq(HeartcoveSession::getSubjectId, subjectId)
                .eq(HeartcoveSession::getUserId, userId)
                .orderByDesc(HeartcoveSession::getLastMessageAt)
                .last("LIMIT 1")
        );
        if (lastSession == null) return;

        Integer mc = lastSession.getMessageCount();
        if (mc == null || mc % 6 != 0) return;    // 每 6 轮触发一次

        // 收集最近 8 条
        List<HeartcoveMessage> recent = messageMapper.selectList(
            new LambdaQueryWrapper<HeartcoveMessage>()
                .eq(HeartcoveMessage::getSessionId, lastSession.getId())
                .orderByDesc(HeartcoveMessage::getCreatedAt)
                .last("LIMIT 8")
        );
        Collections.reverse(recent);
        List<Map<String, String>> dialog = recent.stream()
            .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
            .collect(Collectors.toList());

        // 读旧摘要
        HeartcoveMemorySummary existing = memoryMapper.selectOne(
            new LambdaQueryWrapper<HeartcoveMemorySummary>()
                .eq(HeartcoveMemorySummary::getSubjectId, subjectId)
                .eq(HeartcoveMemorySummary::getUserId, userId)
                .last("LIMIT 1")
        );
        String lastSummary = existing == null ? "" : existing.getSummary();
        Integer lastTurn = existing == null ? 0 : existing.getTurnCount();

        String newSummary = aiClient.summarize(
            String.valueOf(lastSession.getId()), String.valueOf(subjectId),
            lastSummary, dialog);

        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            HeartcoveMemorySummary m = new HeartcoveMemorySummary();
            m.setSubjectId(subjectId);
            m.setUserId(userId);
            m.setSummary(newSummary);
            m.setTurnCount(lastTurn + 6);
            m.setLastMessageId(recent.isEmpty() ? null : recent.get(recent.size()-1).getId());
            m.setGeneratedAt(now);
            m.setCreatedAt(now);
            m.setUpdatedAt(now);
            memoryMapper.insert(m);
        } else {
            existing.setSummary(newSummary);
            existing.setTurnCount(lastTurn + 6);
            existing.setLastMessageId(recent.isEmpty() ? existing.getLastMessageId()
                : recent.get(recent.size()-1).getId());
            existing.setGeneratedAt(now);
            existing.setUpdatedAt(now);
            memoryMapper.updateById(existing);
        }
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

    private HeartcoveSession mustOwnedSession(Long userId, Long sessionId) {
        HeartcoveSession s = sessionMapper.selectById(sessionId);
        if (s == null || !userId.equals(s.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在");
        }
        return s;
    }

    // M14+: 删 inferAgeHint, 不再用硬编码模板生成"长辈(relation)" 这种 age_hint;
    // 人物年龄/年代信息由 persona_summary 表达, 运行时 prompt 不再硬塞。

    private List<String> tokenize(String text) {
        if (text == null) return Collections.emptyList();
        // 极简中文分词：2~4 字滑动窗口
        Set<String> set = new LinkedHashSet<>();
        for (int n = 4; n >= 2; n--) {
            for (int i = 0; i + n <= text.length(); i++) {
                set.add(text.substring(i, i + n));
            }
        }
        return new ArrayList<>(set);
    }

    private String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 从 "data: <x>\n\n" 一段中提取 <x> */
    private String extractData(String line) {
        int idx = line.indexOf("data: ");
        if (idx < 0) return null;
        String rest = line.substring(idx + 6).trim();
        if (rest.isEmpty()) return null;
        return rest;
    }

    /**
     * 解析单个 SSE 事件块（形如 "event: token\ndata: hello"），按 event 类型累加到对应容器。
     * 这是 WebClient.bodyToFlux(String) 上层唯一的可靠 SSE 解析方式—— WebClient 对 SSE 是按
     * 物理行切还是按事件块切，依赖 Spring/Reactor 版本，不能假设。
     *
     * <p>M14+: 新增 event: thinking 帧 (推理模型的思考链)。本方法只累加
     * event: token 到 aiReply, event: thinking 被忽略(不会被错加进正文),
     * 由 enriched.flatMap 把整帧字节原样透传给前端,前端 onThinking 回调单独展示。</p>
     */
    private void parseAndAccumulate(String frame, StringBuilder aiReply,
                                    String[] unknownType, String[] safetyFlag, String[] sourceIds) {
        String event = "message";
        StringBuilder data = new StringBuilder();
        for (String l : frame.split("\n")) {
            if (l.startsWith("event:")) {
                event = l.substring(6).trim();
            } else if (l.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                data.append(l.substring(5).trim());
            }
        }
        if (data.length() == 0 && !"done".equals(event)) return;

        if ("token".equals(event)) {
            aiReply.append(data);
        } else if ("meta".equals(event)) {
            String dataStr = data.toString();
            if (dataStr.isEmpty()) return;
            try {
                com.fasterxml.jackson.databind.ObjectMapper om =
                    new com.fasterxml.jackson.databind.ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, Object> m = om.readValue(dataStr, Map.class);
                Object ut = m.get("unknown_type");
                if (ut != null) unknownType[0] = ut.toString();
                Object ids = m.get("source_quote_ids");
                if (ids != null) sourceIds[0] = ids.toString();
            } catch (Exception ignored) {}
        } else if ("error".equals(event)) {
            safetyFlag[0] = "ai_error";
        }
    }

    /**
     * 把单个 SSE 帧（形如 "event: token\ndata: 哎，遇到\n\n"）拆成 (event, data) 二元组。
     * 多行 data 用 '\n' 拼成单个字符串（符合 SSE 规范对多行 data 的处理）。
     */
    private List<SsePair> parseFrameToPairs(String frame) {
        String event = "message";
        StringBuilder data = new StringBuilder();
        for (String l : frame.split("\n")) {
            if (l.startsWith("event:")) {
                event = l.substring(6).trim();
            } else if (l.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                data.append(l.substring(5).trim());
            }
        }
        // 空帧（心跳/注释）跳过
        if (event.isEmpty() && data.length() == 0) return List.of();
        List<SsePair> out = new ArrayList<>();
        out.add(new SsePair(event, data.toString()));
        return out;
    }

    private void logAction(Long userId, Long subjectId, String action, String detail, String ip, String ua) {
        HeartcoveAuditLog l = new HeartcoveAuditLog();
        l.setUserId(userId);
        l.setSubjectId(subjectId);
        l.setAction(action);
        l.setDetail(detail);
        l.setIp(ip);
        l.setUa(ua);
        l.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(l);
    }
}