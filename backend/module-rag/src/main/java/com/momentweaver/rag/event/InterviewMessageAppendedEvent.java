package com.momentweaver.rag.event;

import com.momentweaver.common.entity.InterviewMessage;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.List;

/**
 * 采访消息追加事件（AFTER_COMMIT 后发布）。
 *
 * <p>由 InterviewService.streamMessage 在 doOnComplete 阶段发布；
 * RagIngestListener 收到后切 chunk → 调 FastAPI ingest 写入 Milvus。
 *
 * <p>注意：只发「本轮新增」的消息（不是整段 messages），
 * 减少网络传输 + 让 chunk_id 计算稳定（增量幂等）。
 *
 * <p>Step 1.3+：本轮所有消息共享 {@link #turnId}（user + assistant 一对）。
 * listener 用 turnId 作为 chunk_id 的稳定锚点，比 startTurnIndex 更鲁棒
 * （turnIndex 是「截至调用前已有 turn 数」，并发场景下可能漂移；turnId 是本轮 UUID，唯一稳定）。
 *
 * <p>startTurnIndex：本轮新增消息在 session 中的起始 turn 索引（用于 chunk_id 稳定，兼容老逻辑）。
 * 例如已有 5 轮对话，本轮新增的是第 6 轮（turn_5）。
 */
@Getter
public class InterviewMessageAppendedEvent extends ApplicationEvent {

    private final String subjectId;
    private final String sessionId;
    /** 本轮 user + assistant 共享的 UUID。null 表示事件不含 turn 信息（旧事件 / 兜底）。 */
    private final String turnId;
    private final List<InterviewMessage> appendedMessages;
    private final int startTurnIndex;

    public InterviewMessageAppendedEvent(Object source,
                                         String subjectId,
                                         String sessionId,
                                         String turnId,
                                         List<InterviewMessage> appendedMessages,
                                         int startTurnIndex) {
        super(source);
        this.subjectId = subjectId;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.appendedMessages = appendedMessages == null ? List.of() : List.copyOf(appendedMessages);
        this.startTurnIndex = Math.max(0, startTurnIndex);
    }
}