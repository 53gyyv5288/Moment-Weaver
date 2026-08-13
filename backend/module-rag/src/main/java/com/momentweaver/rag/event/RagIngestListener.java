package com.momentweaver.rag.event;

import com.momentweaver.rag.service.RagIngestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * RAG ingest 监听器。
 *
 * <p>AFTER_COMMIT 阶段处理：
 * <ul>
 *   <li>InterviewMessageAppendedEvent → ingestInterviewSession</li>
 *   <li>AssetUpsertedEvent            → ingestAsset</li>
 * </ul>
 *
 * <p>异步（@Async）+ 失败仅 log，保证业务事务不被 RAG 拖垮。
 *
 * <p>fallbackExecution = true：发布事件时若无活跃事务（InterviewService 内部
 * 部分 save 没包 @Transactional），仍能直接触发监听器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagIngestListener {

    private final RagIngestService ragIngestService;

    @Async("ragIngestExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onInterviewAppended(InterviewMessageAppendedEvent e) {
        try {
            // Step 1.4+：传 turnId 给 ingest，用于 chunk_id 稳定锚点
            boolean ok = ragIngestService.ingestInterviewSession(
                e.getSubjectId(), e.getSessionId(),
                e.getTurnId(),
                e.getAppendedMessages(), e.getStartTurnIndex());
            log.debug("RAG ingest for session {} turn={} (appended={}, startTurnIndex={}): {}",
                e.getSessionId(), e.getTurnId(),
                e.getAppendedMessages().size(),
                e.getStartTurnIndex(), ok ? "ok" : "skipped");
        } catch (Exception ex) {
            log.warn("RAG ingest for session {} failed: {}", e.getSessionId(), ex.toString());
        }
    }

    @Async("ragIngestExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAssetUpserted(AssetUpsertedEvent e) {
        try {
            boolean ok = ragIngestService.ingestAsset(e.getAsset(), e.getLinkedMessages());
            log.debug("RAG ingest for asset {}: {}",
                e.getAsset() == null ? "null" : String.valueOf(e.getAsset().id()), ok ? "ok" : "skipped");
        } catch (Exception ex) {
            log.warn("RAG ingest for asset failed: {}", ex.toString());
        }
    }
}