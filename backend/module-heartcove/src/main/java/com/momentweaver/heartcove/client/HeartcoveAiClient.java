package com.momentweaver.heartcove.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 心声信箱客户端：调用 FastAPI /api/v1/heartcove/stream，
 * 把 SSE 流（event: token | meta | error | done）原样转发给前端。
 *
 * <p>设计：拿原始字节流（DataBuffer）而不是 String，由调用方按 SSE 协议解析。
 * 之所以不直接 bodyToFlux(String.class)，是因为 WebClient 的 SSE 字符串解码行为依赖
 * Spring/Reactor 版本——可能按行切、可能按事件块汇总；都不如自己读字节可预测。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeartcoveAiClient {

    private final WebClient aiWebClient;

    /**
     * 调用 /api/v1/heartcove/stream，返回原始字节流 Flux<DataBuffer>。
     * 调用方按 SSE 协议（\n\n 分事件块）攒 buffer 解析。
     */
    public Flux<DataBuffer> streamChat(HeartcoveStreamBody body) {
        return aiWebClient.post()
            .uri("/api/v1/heartcove/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(body)
            .retrieve()
            .bodyToFlux(DataBuffer.class)
            .timeout(Duration.ofSeconds(180));   // 流式总时长上限（含 SSE）
    }

    /**
     * 调用 /api/v1/heartcove/summary 拿滚动摘要（非流式）。
     */
    public String summarize(String sessionId, String subjectId,
                            String lastSummary, List<Map<String, String>> recentDialog) {
        Map<String, Object> body = new HashMap<>();
        body.put("session_id", sessionId);
        body.put("subject_id", subjectId);
        body.put("last_summary", lastSummary == null ? "" : lastSummary);
        body.put("recent_dialog", recentDialog);
        try {
            Map resp = aiWebClient.post()
                .uri("/api/v1/heartcove/summary")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(60));
            if (resp == null) return lastSummary == null ? "" : lastSummary;
            Object s = resp.get("summary");
            return s == null ? "" : s.toString();
        } catch (Exception e) {
            log.warn("heartcove summarize failed: {}", e.toString());
            return lastSummary == null ? "" : lastSummary;
        }
    }

    /**
     * 调用 /api/v1/heartcove/persona-summary, 一次性生成 subject 的人格摘要(M14+ 体验修复)。
     *
     * <p>设计: enable 接口在主事务提交后 fire-and-forget 调这个端点; LLM 失败 / 输入为空
     * 时返回 fallback=true, Java 端决定是否写库。</p>
     *
     * @param quoteChunks 来自 MongoDB interview_message 中 role=user 的发言片段
     * @return [persona_summary, fallback]
     */
    public PersonaSummaryResult buildPersonaSummary(String subjectId, String displayName,
                                                    String ageHint, String relation,
                                                    List<Map<String, String>> quoteChunks,
                                                    String previousSummary) {
        Map<String, Object> body = new HashMap<>();
        body.put("subject_id", subjectId);
        body.put("display_name", displayName);
        body.put("age_hint", ageHint);
        body.put("relation", relation);
        body.put("quote_chunks", quoteChunks);
        body.put("previous_summary", previousSummary == null ? "" : previousSummary);
        try {
            Map resp = aiWebClient.post()
                .uri("/api/v1/heartcove/persona-summary")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(90));   // 抽取 300-500 字摘要, LLM 通常 10-30s
            if (resp == null) {
                log.warn("persona-summary returned null (subject={})", subjectId);
                return new PersonaSummaryResult(_FALLBACK_SUMMARY, true);
            }
            Object s = resp.get("persona_summary");
            Object fb = resp.get("fallback");
            String text = s == null ? _FALLBACK_SUMMARY : s.toString();
            boolean fallback = fb instanceof Boolean ? (Boolean) fb : false;
            return new PersonaSummaryResult(text, fallback);
        } catch (Exception e) {
            log.warn("persona-summary failed (subject={}): {}", subjectId, e.toString());
            return new PersonaSummaryResult(_FALLBACK_SUMMARY, true);
        }
    }

    /** 默认模板: 与 Python 侧 _DEFAULT_PERSONA_TEMPLATE 保持一致 */
    private static final String _FALLBACK_SUMMARY =
        "暂无既往采访内容可用;按温和长辈的基本形象应对,被问到具体经历时坦诚说自己记不清、请对方讲讲。";

    /** persona_summary 调用的返回值封装 */
    public record PersonaSummaryResult(String personaSummary, boolean fallback) {}

    // ---- request body ----

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HeartcoveStreamBody {
        private String session_id;
        private String subject_id;
        private String display_name;
        private String age_hint;
        private String style_tone;
        private String persona_summary;
        private List<Map<String, String>> recent_dialog;
        private List<Map<String, String>> related_quotes;
        private String user_msg;
    }
}