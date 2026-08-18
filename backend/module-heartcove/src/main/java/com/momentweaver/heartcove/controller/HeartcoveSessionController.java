package com.momentweaver.heartcove.controller;

import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.common.Result;
import com.momentweaver.heartcove.dto.HeartcoveSessionVO;
import com.momentweaver.heartcove.service.HeartcoveSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 心声信箱会话管理：打开 / 列出 / 读取 / 关闭。
 *
 * <p>AI 流式对话在 {@link HeartcoveChatController}（独立端点）。</p>
 */
@Tag(name = "心声信箱 / Heartcove Session")
@RestController
@RequestMapping("/api/v1/heartcove")
@RequiredArgsConstructor
public class HeartcoveSessionController {

    private final HeartcoveSessionService service;

    /**
     * 打开（或创建）一个会话。
     * 一个用户对一个 subject 最多同时一个 active 会话。
     */
    @PostMapping("/sessions/open")
    @Operation(summary = "打开或创建会话（一个用户对一个 subject 仅一个 active 会话）")
    public Result<HeartcoveSessionVO> open(@RequestParam Long subjectId,
                                           HttpServletRequest req) {
        return Result.ok(service.openOrCreate(
            CurrentUser.requireId(), subjectId,
            clientIp(req), req.getHeader("User-Agent")));
    }

    @GetMapping("/subjects/{subjectId}/sessions")
    @Operation(summary = "列出当前用户在该 subject 上的所有会话（仅自己）")
    public Result<List<HeartcoveSessionVO>> list(@PathVariable Long subjectId) {
        return Result.ok(service.listMySessions(CurrentUser.requireId(), subjectId));
    }

    @GetMapping("/sessions/{sessionId}")
    @Operation(summary = "读取会话详情（含完整消息历史）")
    public Result<HeartcoveSessionVO> get(@PathVariable Long sessionId) {
        return Result.ok(service.get(CurrentUser.requireId(), sessionId));
    }

    @PostMapping("/sessions/{sessionId}/close")
    @Operation(summary = "关闭会话")
    public Result<HeartcoveSessionVO> close(@PathVariable Long sessionId) {
        return Result.ok(service.close(CurrentUser.requireId(), sessionId));
    }

    private String clientIp(HttpServletRequest req) {
        String h = req.getHeader("X-Forwarded-For");
        if (h != null && !h.isBlank()) return h.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}