package com.momentweaver.memory.controller;

import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.common.Result;
import com.momentweaver.memory.dto.InterviewSendRequest;
import com.momentweaver.memory.dto.InterviewSessionVO;
import com.momentweaver.memory.dto.InterviewStartRequest;
import com.momentweaver.memory.service.InterviewService;
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
        return Result.ok(interviewService.close(CurrentUser.requireId(), id));
    }

    @PostMapping("/sessions/{id}/summarize")
    @Operation(summary = "手动触发一次摘要生成（同步等结果）")
    public Result<InterviewSessionVO> summarize(@PathVariable String id) {
        return Result.ok(interviewService.summarizeNow(CurrentUser.requireId(), id));
    }

    @PostMapping(value = "/sessions/{id}/message", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "发送一条用户消息；返回 SSE 流，token 级别推送 AI 回复")
    public SseEmitter send(@PathVariable String id, @Valid @RequestBody InterviewSendRequest req) {
        Long userId = CurrentUser.requireId();
        SseEmitter emitter = new SseEmitter(180_000L); // 3 分钟超时
        Flux<String> flux = interviewService.streamMessage(userId, id, req.getContent());

        // 发送首包，立刻打开客户端通道
        try {
            emitter.send(SseEmitter.event().name("start").data("{}"));
        } catch (IOException e) {
            log.warn("SSE start send failed", e);
        }

        // 心跳：每 15s 一次注释行，避免代理超时
        var beatHandle = heartbeat.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("hb"));
            } catch (Exception ignored) {}
        }, 15, 15, TimeUnit.SECONDS);

        flux.subscribe(
            token -> {
                try {
                    emitter.send(SseEmitter.event().name("token").data(token));
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            },
            err -> {
                beatHandle.cancel(true);
                try {
                    emitter.send(SseEmitter.event().name("error").data(
                        "{\"message\":\"" + err.getMessage().replace("\"", "'") + "\"}"
                    ));
                } catch (IOException ignored) {}
                emitter.completeWithError(err);
            },
            () -> {
                beatHandle.cancel(true);
                try {
                    emitter.send(SseEmitter.event().name("done").data("{}"));
                } catch (IOException ignored) {}
                emitter.complete();
            }
        );

        emitter.onTimeout(() -> {
            beatHandle.cancel(true);
            emitter.complete();
        });
        emitter.onCompletion(() -> beatHandle.cancel(true));

        return emitter;
    }
}
