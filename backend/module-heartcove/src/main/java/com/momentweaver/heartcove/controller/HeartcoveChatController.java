package com.momentweaver.heartcove.controller;

import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.heartcove.service.HeartcoveChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;

/**
 * 心声信箱流式对话（SSE 出口）。
 *
 * <p>前端用 fetch + ReadableStream 解析 SSE。输出 event 类型：token | meta | error | done。
 *
 * <p><b>SSE 编码坑（填过 2 次了，留个碑）</b>：
 * Spring WebFlux 对 {@code produces=TEXT_EVENT_STREAM_VALUE} 的处理：
 * <ul>
 *   <li>返回 {@code Flux<String>}：走 {@code ServerSentEventHttpMessageWriter}，给每个 String
 *       自动套 {@code "data: <str>\n\n"}，多行 String 每行都被当成独立 data 字段；
 *       后端已经写好的 {@code "event: token\ndata: ..."} 帧结构被破坏，前端拿到的字节是
 *       {@code "data: event: token\ndata: ..."}，导致 {@code event} 永远是默认 {@code "message"}，
 *       onToken 一次都不会触发。</li>
 *   <li>返回 {@code Flux<byte[]>}：同样走 SSE encoder，行为同 String，仍然套 {@code "data: "} 前缀。</li>
 *   <li><b>唯一正确做法</b>：返回 {@code Flux<ServerSentEvent<T>>}。Spring 看到 SSE 类型容器后
 *       才正确输出 {@code "event: <name>\ndata: <data>\n\n"} 帧结构。</li>
 * </ul>
 */
@Slf4j
@Tag(name = "心声信箱 / Heartcove Chat")
@RestController
@RequestMapping("/api/v1/heartcove/sessions")
@RequiredArgsConstructor
public class HeartcoveChatController {

    private final HeartcoveChatService chatService;

    @PostMapping(value = "/{sessionId}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "发送一条用户消息；后端调 AI 并流式返回 AI 回复（SSE）")
    public ResponseEntity<Flux<ServerSentEvent<String>>> stream(
            @org.springframework.web.bind.annotation.PathVariable Long sessionId,
            @RequestParam("user_msg") String userMsg,
            HttpServletRequest req) {
        Flux<ServerSentEvent<String>> body;
        if (userMsg == null || userMsg.isBlank()) {
            body = Flux.just(ServerSentEvent.<String>builder()
                .event("error")
                .data("empty user_msg")
                .build());
        } else {
            String ip = clientIp(req);
            String ua = req.getHeader("User-Agent");
            // 服务层流式产出 (event, data) 二元组；controller 包成 ServerSentEvent。
            body = chatService.streamReplyEvents(CurrentUser.requireId(), sessionId, userMsg, ip, ua)
                .<ServerSentEvent<String>>map(pair -> ServerSentEvent.<String>builder()
                    .event(pair.event())
                    .data(pair.data() == null ? "" : pair.data())
                    .build());
        }
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header("Cache-Control", "no-cache")
            .header("X-Accel-Buffering", "no")  // nginx 兼容：禁止反代缓冲
            .body(body);
    }

    private String clientIp(HttpServletRequest req) {
        String h = req.getHeader("X-Forwarded-For");
        if (h != null && !h.isBlank()) return h.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}