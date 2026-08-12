package com.momentweaver.memory.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.common.Result;
import com.momentweaver.memory.client.AiClient;
import com.momentweaver.memory.dto.InterviewSendRequest;
import com.momentweaver.memory.dto.InterviewSessionVO;
import com.momentweaver.memory.dto.InterviewStartRequest;
import com.momentweaver.memory.service.InterviewService;
import com.momentweaver.memory.service.RagCacheService;
import com.momentweaver.memory.stream.ActiveStream;
import com.momentweaver.memory.stream.StreamingSessionRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Tag(name = "采访 / Interview")
@RestController
@RequestMapping("/api/v1/interview")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewService interviewService;
    private final StreamingSessionRegistry streamingRegistry;
    private final RagCacheService ragCache;
    private final ObjectMapper objectMapper;

    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    @PostMapping("/sessions")
    @Operation(summary = "启动一个采访会话")
    public Result<InterviewSessionVO> start(@Valid @RequestBody InterviewStartRequest req) {
        return Result.ok(interviewService.start(CurrentUser.requireId(), req));
    }

    @GetMapping("/sessions")
    @Operation(summary = "列出项目的所有采访会话")
    public Result<List<InterviewSessionVO>> list(@RequestParam Long projectId) {
        return Result.ok(interviewService.listByProject(CurrentUser.requireId(), projectId));
    }

    @GetMapping("/sessions/{id}")
    @Operation(summary = "获取会话详情（含消息历史）")
    public Result<InterviewSessionVO> get(@PathVariable String id) {
        return Result.ok(interviewService.get(CurrentUser.requireId(), id));
    }

    @PostMapping("/sessions/{id}/close")
    @Operation(summary = "关闭会话")
    public Result<InterviewSessionVO> close(@PathVariable String id) {
        // 关 cache：避免悬挂 + 避免下个用户复用同一 sid 时拿到旧 evidence
        ragCache.clear(id);
        return Result.ok(interviewService.close(CurrentUser.requireId(), id));
    }

    @PostMapping("/sessions/{id}/summarize")
    @Operation(summary = "手动触发一次摘要生成（同步等结果）")
    public Result<InterviewSessionVO> summarize(@PathVariable String id) {
        return Result.ok(interviewService.summarizeNow(CurrentUser.requireId(), id));
    }

    /**
     * 发送一条用户消息；返回 SSE 流。
     *
     * <p>SSE 事件（按顺序）：
     * <ul>
     *   <li>{@code start}     —— 流开始</li>
     *   <li>{@code evidence}  —— RAG 历史片段，可能出现 0-2 次：
     *       ①上轮缓存的 evidence（在 token 之前推），
     *       ②本轮 RAG 完成后中途推（在 token 之前）</li>
     *   <li>{@code thinking}  —— AI 思考链片段</li>
     *   <li>{@code token}     —— AI 可见正文片段</li>
     *   <li>{@code done}      —— 流结束</li>
     *   <li>{@code error}     —— 流异常</li>
     * </ul>
     *
     * <p>顺序保证：sync RAG 模式下 callback 必在 LLM token 之前触发，
     * evidence 事件必在 token 之前到达前端。
     */
    @PostMapping(value = "/sessions/{id}/message", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "发送一条用户消息；返回 SSE 流，token 级别推送 AI 回复（含思考链）")
    public SseEmitter send(@PathVariable String id, @Valid @RequestBody InterviewSendRequest req) {
        Long userId = CurrentUser.requireId();
        SseEmitter emitter = new SseEmitter(180_000L); // 3 分钟超时

        // 1) 立即注册到会话级注册表，让 RAG 回调能找到 emitter
        //    （必须在 streamMessage 之前；sync 模式下 RAG callback 在 streamMessage 阻塞阶段触发）
        streamingRegistry.register(id, emitter);

        // 2) 取上一轮缓存的 RAG evidence（如有）—— 下轮用户消息前作为前置事件推
        List<RagCacheService.EvidenceItem> cachedEvidence = ragCache.takeAndClear(id);

        // 3) 发送首包，立刻打开客户端通道
        try {
            emitter.send(SseEmitter.event().name("start").data("{}"));
        } catch (IOException e) {
            log.warn("SSE start send failed", e);
        }

        // 4) 推上轮缓存的 evidence（如果有）—— 必须在 LLM token 之前
        if (cachedEvidence != null && !cachedEvidence.isEmpty()) {
            try {
                String json = objectMapper.writeValueAsString(cachedEvidence);
                emitter.send(SseEmitter.event().name("evidence").data(json));
                log.debug("Sent cached RAG evidence for sid={} (n={}) before LLM",
                    id, cachedEvidence.size());
            } catch (Exception e) {
                log.debug("cached evidence emit failed: {}", e.toString());
            }
        }

        // 5) 心跳：每 15s 一次注释行，避免代理超时
        var beatHandle = heartbeat.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("hb"));
            } catch (Exception ignored) {}
        }, 15, 15, TimeUnit.SECONDS);

        // 6) 启动 LLM 流：streamMessage 内部会同步阻塞 7s 拉 RAG，然后才调 LLM
        //    callback 在阻塞阶段同步触发（emitter 必然 active 且流未完成），evidence 必在 token 之前到前端
        InterviewService.RagEmitterCallback ragCallback = (sid, items) -> {
            // RAG 完成回调（同步模式：callback 触发时 emitter 必然 active）
            String json;
            try {
                json = objectMapper.writeValueAsString(items);
            } catch (Exception e) {
                log.debug("evidence JSON serialize failed: {}", e.toString());
                return;
            }
            boolean emitted = streamingRegistry.tryEmitEvidence(sid, "evidence", json);
            if (!emitted) {
                // 兜底：流已结束（理论不会发生，sync 模式下 callback 早于 flux.subscribe），
                // 缓存到下轮
                ragCache.put(sid, items);
                log.debug("RAG evidence sid={} cached for next turn (defensive fallback)", sid);
            } else {
                log.debug("RAG evidence sid={} pushed mid-stream (n={})", sid, items.size());
            }
        };

        Flux<AiClient.StreamChunk> flux = interviewService.streamMessage(
            userId, id, req.getContent(), ragCallback);

        flux.subscribe(
            chunk -> {
                try {
                    String eventName = chunk.isThink() ? "thinking" : "token";
                    emitter.send(SseEmitter.event().name(eventName).data(chunk.content()));
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            },
            err -> {
                beatHandle.cancel(true);
                streamingRegistry.unregister(id);
                try {
                    emitter.send(SseEmitter.event().name("error").data(
                        "{\"message\":\"" + err.getMessage().replace("\"", "'") + "\"}"
                    ));
                } catch (IOException ignored) {}
                emitter.completeWithError(err);
            },
            () -> {
                beatHandle.cancel(true);
                // 在 lock 上 markStreamCompleted → 推 done，保证 RAG 回调能看到正确状态
                ActiveStream active = streamingRegistry.get(id);
                if (active != null) {
                    synchronized (active.getLock()) {
                        streamingRegistry.markStreamCompleted(id);
                        try {
                            emitter.send(SseEmitter.event().name("done").data("{}"));
                        } catch (IOException ignored) {}
                    }
                } else {
                    try {
                        emitter.send(SseEmitter.event().name("done").data("{}"));
                    } catch (IOException ignored) {}
                }
                streamingRegistry.unregister(id);
                emitter.complete();
            }
        );

        emitter.onTimeout(() -> {
            beatHandle.cancel(true);
            streamingRegistry.unregister(id);
            emitter.complete();
        });
        emitter.onCompletion(() -> {
            beatHandle.cancel(true);
            streamingRegistry.unregister(id);
        });

        return emitter;
    }
}