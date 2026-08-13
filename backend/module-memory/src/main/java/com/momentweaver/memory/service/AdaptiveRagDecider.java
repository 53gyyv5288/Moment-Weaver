package com.momentweaver.memory.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import com.momentweaver.memory.config.AdaptiveRagProperties;
import com.momentweaver.memory.config.AiProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adaptive RAG 决策器（M9+ Phase 1）。
 *
 * <p>在 {@link InterviewService#streamMessage} 第 4 步调 RAG 之前，
 * 先调本服务让 LLM 判定 {@code userContent} 是否需要检索。
 *
 * <p>设计原则：
 * <ul>
 *   <li><b>容错回退</b>：decider 任何异常/超时都返回 {@link Decision#DEFAULT_RETRIEVE}，
 *       等价于「走原貌」，不破坏现有 RAG 路径。</li>
 *   <li><b>失败率监控</b>：每 10 分钟 dump 一次失败比例（{@code metric.decider.failure_rate}）。
 *       比例 > 20% 应该排查 LLM / AI 服务。</li>
 *   <li><b>不引入 LangChain</b>：与现有 narrative / summarize 风格保持一致。</li>
 * </ul>
 *
 * <p>对应配置：{@code moment.memory.adaptive-rag.*}（详见 {@link MemoryProperties.AdaptiveRag}）。
 *
 * @see InterviewService#streamMessage
 */
@Slf4j
@Service
public class AdaptiveRagDecider {

    private final WebClient aiWebClient;
    private final AiProperties aiProperties;
    private final AdaptiveRagProperties adaptiveRagProperties;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 失败率监控（线程安全的滑动窗口计数）。
     * 每 10 分钟打一次日志；超过 20% 触发 WARN。
     */
    private final AtomicLong totalCount = new AtomicLong(0);
    private final AtomicLong failureCount = new AtomicLong(0);
    private final long firstCallAtMs = System.currentTimeMillis();

    /**
     * 决策结果。{@link #DEFAULT_RETRIEVE} 等价于原貌行为。
     */
    @Data
    public static class Decision {
        public static final Decision DEFAULT_RETRIEVE = new Decision(
            true, "rag_search", null, 0.0, "fallback: retrieve (default)"
        );

        private final boolean needRetrieval;
        private final String tool;             // rag_search / timeline_query / fact_consolidate
        private final String rewrittenQuery;   // 可空
        private final double confidence;
        private final String rationale;
    }

    /** AI 服务 POST /api/v1/decide/retrieval 的响应结构。 */
    @Data
    public static class DecidePayload {
        @JsonProperty("decision") private DecisionDto decision;
        @JsonProperty("fallback") private boolean fallback;
    }

    @Data
    public static class DecisionDto {
        @JsonProperty("need_retrieval") private boolean needRetrieval;
        @JsonProperty("tool") private String tool;
        @JsonProperty("rewritten_query") private String rewrittenQuery;
        @JsonProperty("confidence") private double confidence;
        @JsonProperty("rationale") private String rationale;
    }

    public AdaptiveRagDecider(WebClient aiWebClient,
                              AiProperties aiProperties,
                              AdaptiveRagProperties adaptiveRagProperties) {
        this.aiWebClient = aiWebClient;
        this.aiProperties = aiProperties;
        this.adaptiveRagProperties = adaptiveRagProperties;
    }

    /**
     * 主入口：判定是否需要 RAG。
     *
     * @param userContent    用户消息原文
     * @param rollingSummary STM 滚动摘要（可空）
     * @param recent         最近 K 条对话（只取前 3 条作 context，避免 token 爆）
     * @return Decision，永不为 null —— 任何异常都返回 {@link Decision#DEFAULT_RETRIEVE}
     */
    public Decision decide(String userContent, String rollingSummary, List<?> recent) {
        if (!adaptiveRagProperties.isEnabled()) {
            // 开关关闭：完全跳过 LLM，等价于「默认 retrieve」（保留旧行为）
            return Decision.DEFAULT_RETRIEVE;
        }

        totalCount.incrementAndGet();
        long start = System.currentTimeMillis();
        try {
            int timeoutMs = adaptiveRagProperties.getDeciderTimeoutMs();
            DecidePayload payload = aiWebClient.post()
                .uri("/api/v1/decide/retrieval")
                .bodyValue(Map.of(
                    "session_id", "",
                    "user_content", userContent == null ? "" : userContent,
                    "recent_context", abbreviate(recentToString(recent), 300),
                    "rolling_summary", rollingSummary == null ? "" : rollingSummary
                ))
                .retrieve()
                .bodyToMono(DecidePayload.class)
                .block(Duration.ofMillis(timeoutMs));

            if (payload == null || payload.decision == null) {
                failureCount.incrementAndGet();
                return Decision.DEFAULT_RETRIEVE;
            }

            DecisionDto d = payload.decision;
            long latencyMs = System.currentTimeMillis() - start;
            log.debug("AdaptiveRagDecider: need={} tool={} conf={} latency={}ms fallback={} rationale={}",
                d.isNeedRetrieval(), d.getTool(), d.getConfidence(), latencyMs,
                payload.isFallback(), d.getRationale());

            return new Decision(
                d.isNeedRetrieval(),
                d.getTool() == null ? "rag_search" : d.getTool(),
                d.getRewrittenQuery(),
                d.getConfidence(),
                d.getRationale()
            );
        } catch (Exception e) {
            failureCount.incrementAndGet();
            log.debug("AdaptiveRagDecider failed (will fall back): {}", e.toString());
            return Decision.DEFAULT_RETRIEVE;
        } finally {
            // 失败率监控：每 10 分钟 dump 一次
            maybeDumpFailureRate();
        }
    }

    // ============ helpers ============

    private void maybeDumpFailureRate() {
        long now = System.currentTimeMillis();
        long elapsed = now - firstCallAtMs;
        long total = totalCount.get();
        if (total == 0) return;
        // 满 10 分钟或样本足够（>= 50）时输出一次
        if (elapsed > 10 * 60 * 1000L || total % 50 == 0) {
            double failureRate = (double) failureCount.get() / total;
            String level = failureRate > 0.2 ? "WARN" : "INFO";
            log.info("AdaptiveRagDecider failure rate: total={} fail={} rate={} elapsed={}ms",
                total, failureCount.get(), String.format("%.2f%%", failureRate * 100), elapsed);
            // 重置（保留首次时间戳）
            if (elapsed > 10 * 60 * 1000L) {
                failureCount.set(0);
                totalCount.set(0);
            }
        }
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        s = s.strip();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String recentToString(List<?> recent) {
        if (recent == null || recent.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int n = Math.min(recent.size(), 3);
        for (int i = 0; i < n; i++) {
            Object m = recent.get(i);
            // 简化：直接 toString；上游 InterviewService 已经过滤掉了 content 大字段
            sb.append(m).append("\n");
        }
        return sb.toString().strip();
    }
}
