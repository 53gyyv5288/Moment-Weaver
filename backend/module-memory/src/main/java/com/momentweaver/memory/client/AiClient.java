package com.momentweaver.memory.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 客户端：调 FastAPI 各种 AI 能力。
 *
 * <p>流式对话：{@link #streamInterview} 调 /api/v1/interview/stream。
 * <p>非流式摘要：{@link #summarize} 调 /api/v1/summarize，攒齐再返回。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiClient {

    private final WebClient aiWebClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public static class AiMessage {
        public String role;
        public String content;
        public AiMessage() {}
        public AiMessage(String role, String content) { this.role = role; this.content = content; }
    }

    /** 摘要结果 */
    public static class SummaryResult {
        public String title;
        public List<String> goldenQuotes = new ArrayList<>();
        public List<KeyMoment> keyMoments = new ArrayList<>();

        public static class KeyMoment {
            public String timestamp;
            public String text;
            public KeyMoment() {}
            public KeyMoment(String t, String x) { this.timestamp = t; this.text = x; }
        }
    }

    /**
     * 流式调用 AI。
     * @param sessionId  会话 ID（用于后端日志关联）
     * @param subjectHint 被采访者画像 hint（如 "父亲，1948年生，安徽人"）
     * @param messages  完整消息历史
     * @return Flux<String>，每个元素是 AI 输出的一个 token 片段
     */
    public Flux<String> streamInterview(String sessionId, String subjectHint, List<AiMessage> messages) {
        Map<String, Object> body = Map.of(
            "session_id", sessionId,
            "subject_hint", subjectHint == null ? "" : subjectHint,
            "messages", messages
        );
        return aiWebClient.post()
            .uri("/api/v1/interview/stream")
            .bodyValue(body)
            .retrieve()
            .bodyToFlux(String.class)
            .doOnError(e -> {
                if (e instanceof WebClientResponseException w) {
                    log.error("AI upstream {} {}", w.getStatusCode(), w.getResponseBodyAsString());
                } else {
                    log.error("AI stream error", e);
                }
            })
            .onErrorMap(e -> {
                if (e instanceof WebClientResponseException) {
                    return new BusinessException(ResultCode.AI_UPSTREAM_ERROR, "AI 服务返回异常");
                }
                return new BusinessException(ResultCode.AI_UPSTREAM_ERROR, "AI 调用失败: " + e.getMessage());
            });
    }

    /**
     * 调 AI 生成摘要（M3）。
     *
     * <p>FastAI 端保证只输出合法 JSON；这里 try-parse 失败时降级到「空摘要 + log」。
     */
    public SummaryResult summarize(String sessionId, String subjectHint, List<AiMessage> messages) {
        Map<String, Object> body = Map.of(
            "session_id", sessionId,
            "subject_hint", subjectHint == null ? "" : subjectHint,
            "messages", messages
        );
        try {
            String raw = aiWebClient.post()
                .uri("/api/v1/summarize")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
            if (raw == null || raw.isBlank()) {
                throw new BusinessException(ResultCode.AI_UPSTREAM_ERROR, "AI 摘要返回为空");
            }
            // FastAPI 返回 {"summary": {...}}
            JsonNode root = mapper.readTree(raw);
            JsonNode sumNode = root.has("summary") ? root.get("summary") : root;
            SummaryResult s = new SummaryResult();
            if (sumNode.has("title")) s.title = sumNode.get("title").asText();
            if (sumNode.has("goldenQuotes") && sumNode.get("goldenQuotes").isArray()) {
                sumNode.get("goldenQuotes").forEach((JsonNode n) -> s.goldenQuotes.add(n.asText()));
            }
            if (sumNode.has("keyMoments") && sumNode.get("keyMoments").isArray()) {
                sumNode.get("keyMoments").forEach((JsonNode n) -> {
                    s.keyMoments.add(new SummaryResult.KeyMoment(
                        n.path("timestamp").asText(""),
                        n.path("text").asText("")
                    ));
                });
            }
            log.info("AI summarize ok for session {}: title='{}', quotes={}, moments={}",
                sessionId, s.title, s.goldenQuotes.size(), s.keyMoments.size());
            return s;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("AI summarize failed for session {}", sessionId, e);
            throw new BusinessException(ResultCode.AI_UPSTREAM_ERROR, "AI 摘要失败: " + e.getMessage());
        }
    }
}