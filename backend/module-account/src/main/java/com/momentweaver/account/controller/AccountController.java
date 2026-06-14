package com.momentweaver.account.controller;

import com.momentweaver.account.dto.LoginRequest;
import com.momentweaver.account.dto.LoginResponse;
import com.momentweaver.account.dto.RegisterRequest;
import com.momentweaver.account.dto.UserVO;
import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.account.service.AccountService;
import com.momentweaver.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "账号")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping("/register")
    @Operation(summary = "注册")
    public Result<LoginResponse> register(@Valid @RequestBody RegisterRequest req) {
        return Result.ok(accountService.register(req));
    }

    @PostMapping("/login")
    @Operation(summary = "登录")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return Result.ok(accountService.login(req));
    }

    @GetMapping("/me")
    @Operation(summary = "当前登录用户")
    public Result<UserVO> me() {
        return Result.ok(accountService.me(CurrentUser.requireId()));
    }

    @PostMapping("/logout")
    @Operation(summary = "登出（客户端清 token 即可）")
    public Result<Void> logout() {
        // 一期无服务端 Token 黑名单；前端清 token 即视为登出
        return Result.ok();
    }
}
