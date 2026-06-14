package com.momentweaver.memory.controller;

import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.common.Result;
import com.momentweaver.memory.dto.AuthorizationCreateRequest;
import com.momentweaver.memory.dto.AuthorizationVO;
import com.momentweaver.memory.entity.Authorization;
import com.momentweaver.memory.service.AuthorizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "授权 / Authorization")
@RestController
@RequiredArgsConstructor
public class AuthorizationController {

    private final AuthorizationService authorizationService;

    // ========== Owner 端（需 JWT） ==========

    @PostMapping("/api/v1/projects/{projectId}/authorizations")
    @Operation(summary = "为某人物发起授权（返回公开链接）")
    public Result<AuthorizationVO> create(@PathVariable Long projectId,
                                          @Valid @RequestBody AuthorizationCreateRequest req) {
        return Result.ok(authorizationService.create(CurrentUser.requireId(), projectId, req));
    }

    @GetMapping("/api/v1/projects/{projectId}/authorizations")
    @Operation(summary = "项目的授权列表")
    public Result<List<AuthorizationVO>> listByProject(@PathVariable Long projectId) {
        return Result.ok(authorizationService.listByProject(CurrentUser.requireId(), projectId));
    }

    @GetMapping("/api/v1/subjects/{subjectId}/authorizations")
    @Operation(summary = "某人物的授权历史")
    public Result<List<AuthorizationVO>> listBySubject(@PathVariable Long subjectId) {
        return Result.ok(authorizationService.listBySubject(CurrentUser.requireId(), subjectId));
    }

    @DeleteMapping("/api/v1/authorizations/{id}")
    @Operation(summary = "撤销授权（仅项目 Owner）")
    public Result<Void> revoke(@PathVariable Long id) {
        authorizationService.revoke(CurrentUser.requireId(), id);
        return Result.ok();
    }

    // ========== 公开端（无 JWT，靠 token 鉴权） ==========

    @GetMapping("/api/v1/public/authz/{token}")
    @Operation(summary = "公开：被采访者打开授权链接，看同意书")
    public Result<AuthorizationVO> viewPublic(@PathVariable String token) {
        Authorization a = authorizationService.fetchByToken(token);
        return Result.ok(authorizationService.toVO(a, null));
    }

    @PostMapping("/api/v1/public/authz/{token}/grant")
    @Operation(summary = "公开：同意授权")
    public Result<AuthorizationVO> grant(@PathVariable String token, HttpServletRequest req) {
        Authorization a = authorizationService.grant(token, clientIp(req), req.getHeader("User-Agent"));
        return Result.ok(authorizationService.toVO(a, null));
    }

    @PostMapping("/api/v1/public/authz/{token}/deny")
    @Operation(summary = "公开：拒绝授权")
    public Result<AuthorizationVO> deny(@PathVariable String token, HttpServletRequest req) {
        Authorization a = authorizationService.deny(token, clientIp(req), req.getHeader("User-Agent"));
        return Result.ok(authorizationService.toVO(a, null));
    }

    // ---- helpers ----

    private String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            return fwd.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
