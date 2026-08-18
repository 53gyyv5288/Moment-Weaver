package com.momentweaver.heartcove.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发送一条用户消息。
 * AI 回复走单独的端点（流式 SSE），见 HeartcoveChatController。
 */
@Data
public class SendHeartcoveMessageRequest {
    @NotNull
    private Long sessionId;

    @NotBlank
    @Size(max = 2000, message = "单条消息不超过 2000 字")
    private String content;
}