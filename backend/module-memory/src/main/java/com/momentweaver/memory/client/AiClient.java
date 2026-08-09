package com.momentweaver.memory.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
     * 流式 AI 输出分片。kind:
     * <ul>
     *   <li>"text" —— 可见文本片段，应渲染到 AI 消息正文</li>
     *   <li>"think" —— 思考链片段，应渲染到折叠面板并持久化</li>
     * </ul>
     */
    public record StreamChunk(String kind, String content) {
        public boolean isText() { return "text".equals(kind); }
        public boolean isThink() { return "think".equals(kind); }
    }

    /**
     * 流式调用 AI。
     *
     * <p>FastAPI 端 SSE 多事件协议：
     * <pre>
     *   event: token      data: &lt;text token&gt;
     *   event: thinking   data: &lt;think token&gt;
     *   event: error      data: &lt;msg&gt;
     *   event: done       data:
     * </pre>
     *
     * @param sessionId  会话 ID（用于后端日志关联）
     * @param subjectHint 被采访者画像 hint
     * @param messages  完整消息历史
     * @return Flux<StreamChunk>，event:done 之后流自然结束（empty）
     */
    public Flux<StreamChunk> streamInterview(String sessionId, String subjectHint, List<AiMessage> messages) {
        Map<String, Object> body = Map.of(
            "session_id", sessionId,
            "subject_hint", subjectHint == null ? "" : subjectHint,
            "messages", messages
        );
        ParameterizedTypeReference<ServerSentEvent<String>> type =
            new ParameterizedTypeReference<ServerSentEvent<String>>() {};
        return aiWebClient.post()
            .uri("/api/v1/interview/stream")
            .bodyValue(body)
            .retrieve()
            .bodyToFlux(type)
            .flatMap(sse -> route(sse))
            .doOnError(e -> {
                if (e instanceof WebClientResponseException w) {
                    log.error("AI upstream {} {}", w.getStatusCode(), w.getResponseBodyAsString());
                } else if (!(e instanceof BusinessException)) {
                    log.error("AI stream error", e);
                }
            })
            .onErrorMap(e -> {
                if (e instanceof BusinessException) return e;
                if (e instanceof WebClientResponseException) {
                    return new BusinessException(ResultCode.AI_UPSTREAM_ERROR, "AI 服务返回异常");
                }
                return new BusinessException(ResultCode.AI_UPSTREAM_ERROR, "AI 调用失败: " + e.getMessage());
            });
    }

    /**
     * 把 AI 服务的 SSE 帧路由成本服务的 StreamChunk。
     *   event:token     → StreamChunk("text", data)
     *   event:thinking  → StreamChunk("think", data)
     *   event:error     → BusinessException（终止流）
     *   event:done      → Mono.empty()（自然结束）
     *   其它            → Mono.empty()（心跳 / 注释，丢弃）
     */
    private Mono<StreamChunk> route(ServerSentEvent<String> sse) {
        String event = sse.event();
        String data = sse.data();
        if (event == null) {
            return Mono.empty();
        }
        switch (event) {
            case "token":
                return Mono.just(new StreamChunk("text", data == null ? "" : data));
            case "thinking":
                return Mono.just(new StreamChunk("think", data == null ? "" : data));
            case "error":
                log.error("AI upstream error event: {}", data);
                return Mono.error(new BusinessException(ResultCode.AI_UPSTREAM_ERROR,
                    data == null ? "AI 调用失败" : data));
            case "done":
                // 终止：让 .flatMap 终止。Flux 自身在 done 之后到达也只会收到 onComplete。
                return Mono.empty();
            default:
                log.debug("ignore AI SSE event: {}", event);
                return Mono.empty();
        }
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