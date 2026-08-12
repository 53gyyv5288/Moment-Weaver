package com.momentweaver.memory.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * session-keyed 最新 RAG 结果缓存。
 *
 * <p>用途：当 RAG 完成晚于 LLM 流（罕见但 RAG 慢时会发生），
 * 把 evidence 缓存起来，等下一轮用户消息开始时作为前置 evidence 事件推送。
 *
 * <p>API：
 * <ul>
 *   <li>{@link #put(String, List)} —— RAG 回调：流已结束，缓存下轮用</li>
 *   <li>{@link #takeAndClear(String)} —— InterviewController.send() 入口：取并清空</li>
 *   <li>{@link #clear(String)} —— close() 时调用，避免缓存悬挂</li>
 * </ul>
 *
 * <p>TTL 10 分钟（用户大概不会更久才发下一条），周期清理。
 *
 * <p>**不会**做：跨 session 共享（每 session 独立），需要时改 key 为 subjectId+lastQueryHash（v1.1）。
 */
@Slf4j
@Service
public class RagCacheService {

    private static final long TTL_MS = 10 * 60 * 1000L;

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public void put(String sid, List<EvidenceItem> items) {
        if (items == null || items.isEmpty()) return;
        cache.put(sid, new Entry(items, System.currentTimeMillis()));
        log.debug("RagCacheService: cached {} evidence for sid={}", items.size(), sid);
    }

    /**
     * 取并清空（一次性）。
     *
     * @return 缓存的 evidence；若无缓存返回 null
     */
    public List<EvidenceItem> takeAndClear(String sid) {
        Entry e = cache.remove(sid);
        if (e == null) return null;
        if (System.currentTimeMillis() - e.timestamp > TTL_MS) {
            log.debug("RagCacheService: drop expired cache for sid={}", sid);
            return null;
        }
        return e.items;
    }

    /** 会话关闭时调用，避免悬挂。 */
    public void clear(String sid) {
        cache.remove(sid);
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000L, initialDelay = 5 * 60 * 1000L)
    public void evictExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (var it = cache.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (now - e.getValue().timestamp > TTL_MS) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.info("RagCacheService: evicted {} stale entries (remaining={})", removed, cache.size());
        }
    }

    /** 测试用。 */
    public int size() {
        return cache.size();
    }

    /** Evidence 缓存项载荷（snake_case 与现有 EvidenceChunk 一致，前端通用）。 */
    @Builder
    public record EvidenceItem(
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("text") String text,
        @JsonProperty("score") double score
    ) {}

    private record Entry(List<EvidenceItem> items, long timestamp) {}
}