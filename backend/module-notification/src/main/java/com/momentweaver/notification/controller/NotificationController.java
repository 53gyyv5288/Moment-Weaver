package com.momentweaver.notification.controller;

import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.common.Result;
import com.momentweaver.common.ResultCode;
import com.momentweaver.notification.dto.NotificationVO;
import com.momentweaver.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 通知中心接口 (M5-A.2)。
 * 鉴权：所有端点需要登录（默认 require auth）。
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;

    @GetMapping
    public Result<Page<NotificationVO>> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "false") boolean unreadOnly
    ) {
        Long userId = CurrentUser.requireId();
        return Result.ok(service.list(userId, unreadOnly, page, size));
    }

    @GetMapping("/unread-count")
    public Result<Map<String, Long>> unreadCount() {
        Long userId = CurrentUser.requireId();
        Map<String, Long> body = new HashMap<>();
        body.put("count", service.unreadCount(userId));
        return Result.ok(body);
    }

    @PatchMapping("/{nid}/read")
    public Result<Void> markRead(@PathVariable String nid) {
        Long userId = CurrentUser.requireId();
        service.markRead(userId, nid);
        return Result.ok();
    }

    @PatchMapping("/read-all")
    public Result<Map<String, Integer>> markAllRead() {
        Long userId = CurrentUser.requireId();
        int n = service.markAllRead(userId);
        Map<String, Integer> body = new HashMap<>();
        body.put("updated", n);
        return Result.ok(body);
    }

    // 兜底（万一 Spring 路由没匹配到）
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleBad(IllegalArgumentException e) {
        return Result.fail(ResultCode.BAD_REQUEST, e.getMessage());
    }
}
