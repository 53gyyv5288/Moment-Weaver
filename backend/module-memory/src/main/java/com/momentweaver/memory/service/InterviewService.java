package com.momentweaver.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.momentweaver.account.entity.Project;
import com.momentweaver.account.mapper.ProjectMapper;
import com.momentweaver.account.security.ProjectAccessChecker;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import com.momentweaver.common.event.TimelineEventRequest;
import com.momentweaver.common.event.TimelineEventTypes;
import com.momentweaver.memory.client.AiClient;
import com.momentweaver.memory.dto.InterviewSessionVO;
import com.momentweaver.memory.dto.InterviewStartRequest;
import com.momentweaver.memory.entity.Authorization;
import com.momentweaver.common.entity.InterviewMessage;
import com.momentweaver.memory.entity.InterviewSession;
import com.momentweaver.memory.entity.Subject;
import com.momentweaver.memory.mapper.AuthorizationMapper;
import com.momentweaver.memory.mapper.SubjectMapper;
import com.momentweaver.memory.repo.InterviewSessionRepository;
import com.momentweaver.rag.client.RagClient;
import com.momentweaver.rag.dto.EvidenceChunk;
import com.momentweaver.rag.dto.SearchRequest;
import com.momentweaver.rag.event.InterviewMessageAppendedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewService {

    private final InterviewSessionRepository sessionRepo;
    private final SubjectMapper subjectMapper;
    private final AuthorizationMapper authorizationMapper;
    private final ProjectMapper projectMapper;
    /** M10+ ProjectAccessChecker：项目级权限校验（自动区分 workspace / family） */
    private final ProjectAccessChecker projectAccessChecker;
    private final AiClient aiClient;
    private final RagClient ragClient;
    private final ApplicationEventPublisher eventPublisher;
    private final ShortTermMemoryService stmService;
    /** MongoTemplate 用于 atomic update（$push / $set），避免多端并发写入丢消息。 */
    private final MongoTemplate mongoTemplate;
    /** M9+ Adaptive RAG：让 LLM 判定「是否需要 RAG」。默认走 DEFAULT_RETRIEVE（容错回退）。 */
    private final AdaptiveRagDecider adaptiveRagDecider;

    /**
     * RAG 同步等待的阻塞上限（ms）。略大于 RagProperties.searchSoftTimeoutMs=6000，
     * 给 AI 服务端到端 + JSON 反序列化留 1s 余量。
     * <p>超过此时间 → 视作 RAG 失败，继续调 LLM（不阻塞首字）。
     */
    private static final long RAG_BLOCK_TIMEOUT_MS = 7_000L;

    /**
     * RAG evidence 完成回调（streamMessage 调用方提供）。
     * <p>实现方应做：作为 SSE event: evidence 推到前端展示（不阻塞 LLM）。
     * <p>设计：streamMessage 是**同步**等 RAG（block 7s），callback 也是同步调用，
     * 所以 controller 收到 callback 时 emitter 必然 active 且流未完成。
     */
    @FunctionalInterface
    public interface RagEmitterCallback {
        void onRagResult(String sessionId, List<RagCacheService.EvidenceItem> items);
    }

    /**
     * 启动一个新采访会话。
     *
     * <p>权限（M11 Phase 3 + 后续代答模式）：
     * <ul>
     *   <li>subject.linkedUserId != null（被采访者有账号）—— 只有 subject.linkedUserId == userId 的
     *       "被采访者本人"才能启动。这防止 userA 替 userB 启动 session 导致 AI 误判对话角色。</li>
     *   <li>subject.linkedUserId == null（匿名 subject，如老人没账号）—— 代答是高权力动作
     *       （产生的对话进入时间线、影响成稿），仅 admin/owner 可启动
     *       （userA 代答模式：userA 当面陪老人，AI 提问 → userA 转述 → userA 代输入）。</li>
     * </ul>
     *
     * <p>历史：`PublicInterviewController.startByToken` 已被移除（公开 token 端点不再承载采访，
     * 老人没账号的场景全部走 userA 代答模式）。
     */
    public InterviewSessionVO start(Long userId, InterviewStartRequest req) {
        Project p = mustProject(req.getProjectId());

        Subject s = mustSubject(req.getSubjectId());
        if (!s.getProjectId().equals(p.getId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "人物不属于该项目");
        }

        if (s.getLinkedUserId() != null) {
            // 路径 1：被采访者有账号 → 只有本人能启动
            // 入口先校验"我是项目成员"以防越权探测别人的 subject
            projectAccessChecker.requireMember(p.getId(), userId);
            if (!s.getLinkedUserId().equals(userId)) {
                throw new BusinessException(ResultCode.FORBIDDEN,
                    "只有被采访者本人才能开始采访（您是项目成员，但不是被采访者本人）");
            }
        } else {
            // 路径 2：匿名 subject（老人没账号）→ userA 代答模式
            // 代答是高权力动作（产生的对话进入时间线、影响成稿），仅 admin/owner 可触发
            projectAccessChecker.requireOwner(p.getId(), userId);
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
        // 记录 session 启动者：
        //   - 路径 1（userB 本人）：等于 subject.linkedUserId
        //   - 路径 2（userA 代答）：等于 userA
        //   streamMessage / canStream 都按"userId == startedByUserId"校验，两路一致
        sess.setStartedByUserId(userId);

        LocalDateTime now = LocalDateTime.now();
        sess.setStartedAt(now);
        sess.setLastMessageAt(now);

        // 系统提示作为第一条消息
        InterviewMessage sys = InterviewMessage.builder()
            .role("system")
            .source("human")
            .content(buildSystemHint(s))
            .createdAt(now)
            .build();
        sess.getMessages().add(sys);

        InterviewSession saved = sessionRepo.save(sess);
        // start() 一定是被采访者本人调，所以 canStream=true
        return toVO(saved, userId);
    }

    /**
     * 流式发送用户消息。返回 Flux<StreamChunk>：
     *   - kind="text"   —— AI 可见正文片段
     *   - kind="think"  —— 推理模型思考链片段（持久化到 Mongo，不进时间线预览）
     *
     * 副作用：先追加 user 消息，等流结束再追加 assistant 消息（content + thinking）。
     *
     * RAG 集成（plan §4.3.A 经典设计 + SSE evidence 推送）：
     *   - **同步** 等 RAG 完成（block RAG_BLOCK_TIMEOUT_MS=7s），把 evidence 注入到 LLM context
     *   - 同步完成后回调 RagEmitterCallback.onRagResult（InterviewController）：
     *       此时 emitter 必然 active 且流未完成 → 作为 SSE `event: evidence` 推到前端展示
     *   - LLM 首字延迟 ~RAG 实际耗时（1-2s）；AI 真用证据回答，且 evidence 面板仍可见
     *   - 失败 / 超时 / 空 → evidence 不注入，LLM 照常调（用户感知：evidence 面板无内容）
     */
    public Flux<AiClient.StreamChunk> streamMessage(Long userId, String sessionId, String userContent,
                                                     RagEmitterCallback ragCallback) {
        InterviewSession sess = mustSession(sessionId);
        Project p = mustProject(Long.parseLong(sess.getProjectId()));
        // M11 Phase 3：只有 session 启动者才能继续讲话
        //   - 路径 1（被采访者本人启动）：只有 userB 自己能说
        //   - 路径 2（userA 代答启动）：只有 userA 自己能说（userB 没账号）
        //   - 防御性兜底：startedByUserId==null（极老数据，无主）→ 退化为 requireEditor
        if (sess.getStartedByUserId() != null && !sess.getStartedByUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN,
                "只有创建本采访的人才能继续对话");
        }
        if (sess.getStartedByUserId() == null) {
            projectAccessChecker.requireEditor(p.getId(), userId);
        }

        // 1) 计算 RAG ingest 起始 turn index（在追加 user 消息之前）
        // 这样 chunk_id 是 stable：interview:{sid}:turn_{startTurnIndex}
        final int startTurnIndex = countExistingTurns(sess);

        // 2) 追加 user 消息（Step 1.2 + Step 2）：
        //    - 生成 turnId，user + assistant 共享
        //    - user 落库时 turnStatus = PENDING（等 assistant）
        //    - 用 mongoTemplate atomic $push 替代 sessionRepo.save(sess)，避免多端并发 save 互相覆盖丢消息
        LocalDateTime now = LocalDateTime.now();
        final String turnId = UUID.randomUUID().toString();
        InterviewMessage userMsg = InterviewMessage.builder()
            .role("user")
            .source("human")
            .content(userContent)
            .turnId(turnId)
            .turnStatus(InterviewMessage.TurnStatus.PENDING)
            .createdAt(now)
            .build();
        // Mongo atomic: $push user message + $set lastMessageAt
        mongoTemplate.updateFirst(
            Query.query(Criteria.where("_id").is(sessionId)),
            new Update().push("messages", userMsg).set("lastMessageAt", now),
            InterviewSession.class
        );
        // 内存视图同步（countExistingTurns 等本地逻辑仍走 sess；Mongo 是权威）
        sess.getMessages().add(userMsg);
        sess.setLastMessageAt(now);

        // 2.5) STM：把 user 消息推入 Redis recent 列表（用于压缩 + 跨 turn 上下文）
        //    - 失败不阻塞（消息已在 Mongo，Redis 挂了走 Mongo 全量降级）
        stmService.appendRecent(sessionId, userMsg);
        List<InterviewMessage> recent = stmService.getRecent(sessionId);
        // 回填：Redis 空（服务重启 / 容器迁移 / TTL 过期）→ 从 Mongo 取最近 K 条
        if (recent.isEmpty() && sess.getMessages() != null && sess.getMessages().size() > 1) {
            stmService.warmUpFromMongo(sessionId, sess.getMessages());
            recent = stmService.getRecent(sessionId);
        }
        if (stmService.shouldCompress(sessionId, recent)) {
            // fire-and-forget：用 summaryExecutor 跑压缩；失败时旧 summary 保留
            stmService.compressAsync(sessionId);
        }

        // 3) 组装要发给 AI 的 messages：从 STM recent + summary 拼装（降级到 Mongo）
        //    顺序：[system 提示] → [RAG evidence（如有）] → [滚动摘要（如有）] → [recent verbatim]
        List<AiClient.AiMessage> aiMsgs = buildAiMsgs(sessionId, sess, recent);

        // 4) Adaptive RAG 决策（M9+ Phase 1）：让 LLM 判定「是否需要检索」
        //    容错语义：decider 异常 → Decision.DEFAULT_RETRIEVE（走原貌，0 风险）
        //    关闭开关（memory.adaptive-rag.enabled=false）→ DEFAULT_RETRIEVE（保留原行为）
        AdaptiveRagDecider.Decision deciderResult = adaptiveRagDecider.decide(
            userContent,
            stmService.getSummary(sessionId).orElse(""),
            recent
        );
        if (!deciderResult.isNeedRetrieval()) {
            log.info("Interview session {} adaptive RAG SKIP (rationale={})",
                sessionId, deciderResult.getRationale());
            // 不注入 evidence，下游 LLM 照常调（与空 evidence 等价）
        } else {
            log.debug("Interview session {} adaptive RAG KEEP (rationale={})",
                sessionId, deciderResult.getRationale());
        }
        List<RagCacheService.EvidenceItem> ragItems = deciderResult.isNeedRetrieval()
            ? fetchRagEvidence(userId, sess, userContent,
                deciderResult.getRewrittenQuery())   // 透传 decider 的改写 query
            : List.of();
        if (!ragItems.isEmpty()) {
            // 推 SSE evidence 事件（controller 拿到时 emitter 必然 active 流未完成 → 中途推）
            if (ragCallback != null) {
                try {
                    ragCallback.onRagResult(sessionId, ragItems);
                } catch (Exception e) {
                    log.debug("RAG callback sid={} failed: {}", sessionId, e.toString());
                }
            }
            // 注入到 aiMsgs：插在原 system 之后、summary 之前（保持 STM recent 位置不变）
            String ragText = buildRagSystemMessage(ragItems);
            List<AiClient.AiMessage> augmented = new ArrayList<>(aiMsgs.size() + 1);
            augmented.add(aiMsgs.get(0));  // 原 system（采访官人设）
            augmented.add(new AiClient.AiMessage("system", ragText));  // RAG evidence
            augmented.addAll(aiMsgs.subList(1, aiMsgs.size()));  // summary + recent
            aiMsgs = augmented;
            log.info("Interview session {} RAG evidence injected before LLM (n={} items)", sessionId, ragItems.size());
        }

        // 5) 流式调 AI，分别累计可见文本与思考链
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
                String fullRaw = accText.toString();
                // M9+ Strategy Planner (Phase 1, prompt-only)：
                // 把 LLM 在回复末尾追加的策略行剥离，存到 InterviewMessage.strategy。
                // 注意：流式期间策略行也会作为 token 推到前端（极少数情况下用户会看到这一行），
                // 这是一个可接受的代价，比起「流式切换 state machine」的复杂度更高。
                // 解析失败 / LLM 未输出策略行 → strategy = null，不影响主流程。
                String[] splitResult = splitOffStrategyLine(fullRaw);
                String full = splitResult[0];
                String strategyFromPrompt = splitResult[1];
                String thinking = accThink.toString();
                List<InterviewMessage> appendedThisTurn = new ArrayList<>();
                appendedThisTurn.add(userMsg); // 本轮 user 也算新增（虽然已在 step2 写入）

                // Step 1.2 + Step 2：原子操作（拆两步，避开 MongoDB code 40 冲突）
                //   - MongoDB 不允许「$set messages.X.字段」和「$push messages」同一次 update：
                //     同一个 path 既被数组本身改又被数组里某元素改会报 "conflict at 'messages'"。
                //   - 拆成两个独立 updateFirst：①$set user 的 turnStatus；②$push assistant。
                //     两个都是原子，毫秒级 race window 内 turnId 唯一锚定，不会冲突。
                if (!full.isEmpty()) {
                    InterviewMessage assistant = InterviewMessage.builder()
                        .role("assistant")
                        .source("ai_generated")
                        .content(full)
                        // 仅在真有思考链内容时落库，避免给老格式文档写一堆空串
                        .thinking(thinking.isEmpty() ? null : thinking)
                        // M9+ Strategy Planner：把解析到的策略值塞进字段
                        .strategy(strategyFromPrompt)
                        .turnId(turnId)
                        .turnStatus(InterviewMessage.TurnStatus.COMPLETED)
                        .createdAt(LocalDateTime.now())
                        .build();
                    // Step A：把 user 转 COMPLETED（只改 messages 数组里的元素字段，不动数组本身）
                    mongoTemplate.updateFirst(
                        Query.query(Criteria.where("_id").is(sessionId)
                            .and("messages.turnId").is(turnId)
                            .and("messages.turnStatus").is(InterviewMessage.TurnStatus.PENDING.name())),
                        new Update()
                            .set("messages.$.turnStatus", InterviewMessage.TurnStatus.COMPLETED.name())
                            .set("lastMessageAt", LocalDateTime.now()),
                        InterviewSession.class
                    );
                    // Step B：$push assistant（只动 messages 数组，不碰元素字段）
                    mongoTemplate.updateFirst(
                        Query.query(Criteria.where("_id").is(sessionId)),
                        new Update()
                            .push("messages", assistant)
                            .set("lastMessageAt", LocalDateTime.now()),
                        InterviewSession.class
                    );
                    appendedThisTurn.add(assistant);

                    // 2.6) STM：assistant 消息也推入 Redis recent（下轮 verbatim 用）。
                    //      - 不阻塞：失败 log warn；Mongo 已有，Redis 降级下次触发 warmUp。
                    stmService.appendRecent(sessionId, assistant);
                } else {
                    // 空响应（模型没产出文本）：只把 user 标记 COMPLETED（不算 FAILED，是合法的"无回答"）
                    // 这条单独 update 没问题：只 $set messages.$.turnStatus，不碰 messages 本身。
                    mongoTemplate.updateFirst(
                        Query.query(Criteria.where("_id").is(sessionId)
                            .and("messages.turnId").is(turnId)
                            .and("messages.turnStatus").is(InterviewMessage.TurnStatus.PENDING.name())),
                        new Update()
                            .set("messages.$.turnStatus", InterviewMessage.TurnStatus.COMPLETED.name())
                            .set("lastMessageAt", LocalDateTime.now()),
                        InterviewSession.class
                    );
                }
                log.info("Interview session {} turn {} completed (text={} chars, thinking={} chars)",
                    sessionId, turnId, accText.length(), accThink.length());

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
                        java.util.Map.of("sessionId", sessionId, "role", "assistant", "turnId", turnId)
                    ));
                }

                // 触发 RAG ingest：本轮 user + assistant 都进 Milvus（AFTER_COMMIT）
                try {
                    eventPublisher.publishEvent(new InterviewMessageAppendedEvent(
                        this, sess.getSubjectId(), sessionId, turnId, appendedThisTurn, startTurnIndex));
                } catch (Exception ex) {
                    log.warn("publish InterviewMessageAppendedEvent failed: {}", ex.toString());
                }
            })
            .doOnError(e -> {
                // Step 1.2：流中断/错误 → 把对应 turn 标记为 FAILED（仅当仍为 PENDING）
                //   - FAILED 表示该 turn 永远不会有 assistant 到位（区别于 COMPLETED）
                //   - 前端可据此显示"已发送未回复"或允许重发
                log.error("Interview session {} turn {} stream error", sessionId, turnId, e);
                markTurnFailed(sessionId, turnId);
            });
    }

    /**
     * 统计当前 session 中已有的 turn 数（用于增量 RAG ingest 起始索引）。
     * <p>Step 1.2+：按 <b>distinct turnId</b> 计数（一个 turnId = 一对 user+assistant）。
     * 旧 session（消息无 turnId）→ fallback 到按 user role 计数，保持向后兼容。
     */
    private int countExistingTurns(InterviewSession sess) {
        if (sess.getMessages() == null) return 0;
        java.util.Set<String> turnIds = new java.util.HashSet<>();
        int userOnlyCount = 0;
        for (InterviewMessage m : sess.getMessages()) {
            if (m.getTurnId() != null) {
                turnIds.add(m.getTurnId());
            }
            if ("user".equals(m.getRole())) {
                userOnlyCount++;
            }
        }
        // 如果整 session 都没有 turnId（旧数据），回退到 user 计数
        return turnIds.isEmpty() ? userOnlyCount : turnIds.size();
    }

    /**
     * 把指定 turnId 的 PENDING user 标记为 FAILED（流中断时调用）。
     * <p>仅匹配 turnStatus=PENDING 的 user 消息，避免覆盖已 COMPLETED 的 turn。
     * <p>用 mongoTemplate atomic update，不读 sess，避免与 doOnComplete 的 $push 产生竞态。
     */
    private void markTurnFailed(String sessionId, String turnId) {
        try {
            mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(sessionId)
                    .and("messages.turnId").is(turnId)
                    .and("messages.turnStatus").is(InterviewMessage.TurnStatus.PENDING.name())),
                new Update().set("messages.$.turnStatus", InterviewMessage.TurnStatus.FAILED.name()),
                InterviewSession.class
            );
        } catch (Exception ex) {
            log.warn("markTurnFailed sid={} turnId={} failed: {}", sessionId, turnId, ex.toString());
        }
    }

    /**
     * 拼装 LLM context messages：从 STM recent + summary 拼装（带降级）。
     *
     * <p>顺序：
     * <ol>
     *   <li>[system] 采访官人设（来自 sess 的 system message）</li>
     *   <li>[system] 滚动摘要（如有 STM summary）</li>
     *   <li>recent verbatim messages（来自 Redis recent，降级用 sess.getMessages()）</li>
     * </ol>
     *
     * <p>RAG evidence 由 streamMessage 步骤 4 在本方法返回后再插入到 [1] 位置，
     * 保证顺序为：人设 → RAG evidence → 滚动摘要 → recent verbatim。
     *
     * <p>降级：recent 为空（Redis 没数据 / Redis 挂）→ 走 Mongo 全量；
     * 但 Mongo 全量只保留 user/assistant（system 不重复，避免人设叠加）。
     */
    private List<AiClient.AiMessage> buildAiMsgs(String sid, InterviewSession sess,
                                                 List<InterviewMessage> recent) {
        List<AiClient.AiMessage> msgs = new ArrayList<>();

        // 1) system 人设（取 sess 的第一条 system）
        String systemHint = null;
        if (sess.getMessages() != null) {
            for (InterviewMessage m : sess.getMessages()) {
                if ("system".equals(m.getRole())) {
                    systemHint = m.getContent();
                    break;
                }
            }
        }
        if (systemHint == null) {
            // 兜底：极少情况（重连老 session 且 system 不在 messages 里）
            systemHint = "你是一位温暖、有耐心、尊重长辈的 AI 采访官。";
        }
        msgs.add(new AiClient.AiMessage("system", systemHint));

        // 2) 滚动摘要
        Optional<String> summary = stmService.getSummary(sid);
        if (summary.isPresent() && !summary.get().isBlank()) {
            msgs.add(new AiClient.AiMessage("system",
                "【对话历史摘要】\n" + summary.get()));
        }

        // 3) recent verbatim（降级：Redis 空 → Mongo 全量但过滤掉 system 避免叠加）
        List<InterviewMessage> effective = recent;
        if (effective == null || effective.isEmpty()) {
            if (sess.getMessages() == null) {
                return msgs;
            }
            List<InterviewMessage> fallback = new ArrayList<>(sess.getMessages().size());
            for (InterviewMessage m : sess.getMessages()) {
                if ("system".equals(m.getRole())) continue;  // 已在 [1]
                fallback.add(m);
            }
            effective = fallback;
            log.debug("STM degraded for sid={}: using mongo fallback ({} msgs)", sid, effective.size());
        }
        for (InterviewMessage m : effective) {
            msgs.add(new AiClient.AiMessage(m.getRole(), m.getContent()));
        }
        return msgs;
    }

    /**
     * 同步拉 RAG evidence。阻塞上限 RAG_BLOCK_TIMEOUT_MS。
     * <p>失败 / 超时 / 空 → 返回空列表，调用方跳过 evidence 注入，LLM 照常调（无感）。
     * <p>M9+：接受 {@code rewrittenQuery} 参数（M9+ Adaptive RAG 透传）。
     * 传 null 或空字符串 → 退回 userContent；非空 → 用改写后的 query 检索。
     */
    private List<RagCacheService.EvidenceItem> fetchRagEvidence(Long userId, InterviewSession sess,
                                                                String userContent, String rewrittenQuery) {
        final String subjectId = sess.getSubjectId();
        final String sid = sess.getId();
        // M9+ Adaptive RAG：透传 LLM 改写的检索词
        final String effectiveQuery = (rewrittenQuery == null || rewrittenQuery.isBlank())
            ? userContent
            : rewrittenQuery;
        try {
            List<EvidenceChunk> chunks = ragClient.searchEvidenceAsync(
                    SearchRequest.SCENARIO_INTERVIEW,
                    effectiveQuery,
                    subjectId,
                    userId
                )
                .block(Duration.ofMillis(RAG_BLOCK_TIMEOUT_MS));
            return toEvidenceItems(chunks);
        } catch (Exception e) {
            // 软超时、连接失败、空集合 —— 都视为 RAG 失败
            log.debug("RAG fetch subject={} sid={} query={} failed (non-fatal): {}",
                subjectId, sid, abbreviate(effectiveQuery, 30), e.toString());
            return List.of();
        }
    }

    /**
     * 把 evidence items 拼成一段 system 消息文本，注入 LLM context。
     * <p>格式：每条带 (sessionId 前 8 位, score 0.xx) 前缀，方便 LLM 引用与溯源。
     */
    private String buildRagSystemMessage(List<RagCacheService.EvidenceItem> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("【相关历史片段（仅供参考，请基于此回答用户问题）】\n");
        for (RagCacheService.EvidenceItem it : items) {
            String sid = it.sessionId() == null ? "" : it.sessionId();
            if (sid.length() > 8) sid = sid.substring(0, 8);
            sb.append("- (").append(sid).append(", score=")
              .append(String.format("%.2f", it.score())).append(") ")
              .append(it.text()).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 把 FastAPI 返回的 EvidenceChunk 列表转成前端可消费的轻量 EvidenceItem 列表。
     * <p>text 截断到 200 字符（前端可展开看完整 parentText）；最多 5 条按 score 降序。
     */
    private List<RagCacheService.EvidenceItem> toEvidenceItems(List<EvidenceChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return List.of();
        // 排序：按 score 降序
        List<EvidenceChunk> sorted = new ArrayList<>(chunks);
        sorted.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<RagCacheService.EvidenceItem> out = new ArrayList<>(Math.min(5, sorted.size()));
        for (EvidenceChunk c : sorted) {
            String text = c.parentText();
            if (text == null) text = c.chunkText();
            if (text == null || text.isBlank()) continue;
            if (text.length() > 200) text = text.substring(0, 200) + "…";
            out.add(RagCacheService.EvidenceItem.builder()
                .sessionId(c.sessionId() == null ? "" : c.sessionId())
                .text(text)
                .score(c.score())
                .build());
            if (out.size() >= 5) break;
        }
        return out;
    }

    public InterviewSessionVO get(Long userId, String sessionId) {
        InterviewSession sess = mustSession(sessionId);
        Project p = mustProject(Long.parseLong(sess.getProjectId()));
        projectAccessChecker.requireMember(p.getId(), userId);
        return toVO(sess, userId);
    }

    public List<InterviewSessionVO> listByProject(Long userId, Long projectId) {
        Project p = mustProject(projectId);
        projectAccessChecker.requireMember(p.getId(), userId);
        return sessionRepo.findByProjectIdOrderByLastMessageAtDesc(String.valueOf(projectId))
            .stream()
            .map(s -> toVO(s, userId))
            .toList();
    }

    /**
     * 关闭会话。
     * 关闭后若尚无 summary，异步触发一次摘要生成（不阻塞 close 接口）。
     *
     * M11 Phase 3：关闭权归"被采访者本人"（即 session 创建者）。
     * 考虑到项目 owner 通常也是被采访者（自传场景），仍然允许项目 owner 关闭。
     */
    public InterviewSessionVO close(Long userId, String sessionId) {
        InterviewSession sess = mustSession(sessionId);
        Project p = mustProject(Long.parseLong(sess.getProjectId()));
        boolean isStarter = sess.getStartedByUserId() != null && sess.getStartedByUserId().equals(userId);
        boolean isProjectOwner = p.getOwnerId().equals(userId);
        if (!isStarter && !isProjectOwner) {
            throw new BusinessException(ResultCode.FORBIDDEN,
                "仅被采访者本人或项目 Owner 可关闭会话");
        }
        sess.setStatus("closed");
        sess.setClosedAt(LocalDateTime.now());
        InterviewSession saved = sessionRepo.save(sess);

        // 触发异步摘要
        if (saved.getSummary() == null && hasEnoughContent(saved)) {
            summarizeAsync(saved.getId());
        }

        // STM：会话关闭 → 先等正在跑的压缩完成，避免压缩 task 在 clear 后写回导致内存泄漏
        try {
            stmService.awaitInflight(sessionId).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("STM awaitInflight sid={} timeout/error: {}", sessionId, e.toString());
        }
        // STM：会话关闭 → 清 Redis 短期记忆（避免悬挂 + 释放内存）
        stmService.clear(sessionId);
        return toVO(saved, userId);
    }

    /** 手动触发一次摘要（M3 阶段；前端「重新生成摘要」按钮）。 */
    public InterviewSessionVO summarizeNow(Long userId, String sessionId) {
        InterviewSession sess = mustSession(sessionId);
        Project p = mustProject(Long.parseLong(sess.getProjectId()));
        projectAccessChecker.requireMember(p.getId(), userId);
        summarizeSync(sess);
        return toVO(sess, userId);
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

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        s = s.strip();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /**
     * M9+ Strategy Planner：剥离 LLM 回复末尾的策略行。
     * <p>输入可能是完整 assistant 文本（含正文 + 末尾策略行）。
     * <p>输出：{@code [剥离后的 content, 策略值或 null]}。
     * <p>LLM 没有输出策略行 / 输出乱码 / 输出在中间 → {@code strategy = null}，原内容不动。
     * <p>三次 fallback（对应 Phase 1B 决策）：
     * <ol>
     *   <li>正则匹配 + 提取 → strategy = 捕获组</li>
     *   <li>正则不命中 → 解析失败，strategy = null，原文返回</li>
     * </ol>
     * <p>用例：被 InterviewService.doOnComplete() 调用，结果进 {@code InterviewMessage.strategy}。
     */
    private static final java.util.regex.Pattern STRATEGY_LINE_PATTERN = java.util.regex.Pattern
        .compile("(?s).*\\u3010next_strategy\\u3011\\s*([a-zA-Z_]+)\\s*$");

    private static String[] splitOffStrategyLine(String full) {
        if (full == null) return new String[]{"", null};
        java.util.regex.Matcher m = STRATEGY_LINE_PATTERN.matcher(full);
        if (m.matches()) {
            String strategy = m.group(1);
            // 找到「【」(u3010) 在原文里的位置，截取 marker 之前的内容
            int markerIdx = full.lastIndexOf('\u3010');
            String stripped = (markerIdx <= 0) ? "" : full.substring(0, markerIdx).strip();
            return new String[]{stripped, strategy};
        }
        return new String[]{full, null};
    }

    private InterviewSessionVO toVO(InterviewSession s) {
        return toVO(s, null);
    }

    /**
     * M11 Phase 3：填充 canStream 字段。
     * canStream = 当前 userId 能否调 streamMessage（即是这个 session 的创建者
     * 或 session 是公开 token 创建的）。
     */
    private InterviewSessionVO toVO(InterviewSession s, Long currentUserId) {
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
        vo.setStartedByUserId(s.getStartedByUserId());

        // canStream 计算：
        //   - session.startedByUserId == null → 公开 token 创建，任何人都能继续（公共端点鉴权）
        //   - session.startedByUserId == currentUserId → 本人
        //   - 其他 → false（不能让 userA 替 userB 说话）
        boolean canStream = false;
        if (s.getStartedByUserId() == null) {
            canStream = true;  // 公开 token 路径
        } else if (currentUserId != null && s.getStartedByUserId().equals(currentUserId)) {
            canStream = true;
        }
        vo.setCanStream(canStream);
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