package com.momentweaver.account.security;

/**
 * 当前登录用户上下文。
 * 写入 SecurityContext 的 principal，Controller 用 @CurrentUser Long userId 注入。
 */
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.Authentication;

import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;

public final class CurrentUser {
    private CurrentUser() {}

    /** 从 SecurityContext 取 userId。未登录则抛 UNAUTHORIZED。 */
    public static Long requireId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Object p = auth.getPrincipal();
        if (p instanceof Long l) return l;
        if (p instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(p.toString());
        } catch (NumberFormatException e) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
    }

    /**
     * M5 新增：字符串化的 userId。
     * 用在 MongoDB document（如 notification.userId）等不方便装 Long 的场景。
     * 避免把 Long 序列化为 JSON number 时 JS 端精度丢失。
     */
    public static String requireIdString() {
        return String.valueOf(requireId());
    }
}
