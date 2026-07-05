package com.momentweaver.share.controller;

import com.momentweaver.common.Result;
import com.momentweaver.share.dto.PublicShareVO;
import com.momentweaver.share.dto.PublicShareVerifyRequest;
import com.momentweaver.share.service.ShareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * M5-A 公开分享 controller。
 *
 * <p>无 JWT 鉴权（白名单见 SecurityConfig）。
 * base path: /api/v1/public/shares
 */
@Tag(name = "share-public", description = "分享链接公开访问（无 JWT）")
@RestController
@RequestMapping("/api/v1/public/shares")
@RequiredArgsConstructor
public class PublicShareController {

    private final ShareService shareService;

    @Operation(summary = "公开预览（仅元信息：标题/是否需要密码/allow_copy 等）")
    @GetMapping("/{token}")
    public Result<PublicShareVO> preview(@PathVariable String token, HttpServletRequest req) {
        return Result.ok(shareService.preview(token, clientIp(req)));
    }

    @Operation(summary = "公开密码验证（scope=password 时；通过后直接返回完整内容）")
    @PostMapping("/{token}/verify")
    public Result<PublicShareVO> verify(@PathVariable String token,
                                        @Valid @RequestBody PublicShareVerifyRequest body,
                                        HttpServletRequest req) {
        return Result.ok(shareService.verifyAndAccess(token, body.getPassword(), clientIp(req)));
    }

    @Operation(summary = "公开访问完整内容（仅 scope=public 适用）")
    @PostMapping("/{token}/access")
    public Result<PublicShareVO> access(@PathVariable String token, HttpServletRequest req) {
        return Result.ok(shareService.access(token, clientIp(req)));
    }

    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
