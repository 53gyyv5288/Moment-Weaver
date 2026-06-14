package com.momentweaver.memory.client;

import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * AI 客户端：调 FastAPI /api/v1/interview/stream，返回 token 级别 Flux<String>。
 * FastAPI 端用 Server-Sent Events (data: ...) 协议。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiClient {

    private final WebClient aiWebClient;

    public static class AiMessage {
        public String role;
        public String content;
        public AiMessage() {}
        public AiMessage(String role, String content) { this.role = role; this.content = content; }
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
}
