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