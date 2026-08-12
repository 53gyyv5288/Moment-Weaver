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
 * <p>startTurnIndex：本轮新增消息在 session 中的起始 turn 索引（用于 chunk_id 稳定）。
 * 例如已有 5 轮对话，本轮新增的是第 6 轮（turn_5）。
 */
@Getter
public class InterviewMessageAppendedEvent extends ApplicationEvent {

    private final String subjectId;
    private final String sessionId;
    private final List<InterviewMessage> appendedMessages;
    private final int startTurnIndex;

    public InterviewMessageAppendedEvent(Object source,
                                         String subjectId,
                                         String sessionId,
                                         List<InterviewMessage> appendedMessages,
                                         int startTurnIndex) {
        super(source);
        this.subjectId = subjectId;
        this.sessionId = sessionId;
        this.appendedMessages = appendedMessages == null ? List.of() : List.copyOf(appendedMessages);
        this.startTurnIndex = Math.max(0, startTurnIndex);
    }
}