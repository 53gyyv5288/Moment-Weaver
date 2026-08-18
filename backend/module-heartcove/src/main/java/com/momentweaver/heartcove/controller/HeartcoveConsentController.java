package com.momentweaver.heartcove.controller;

import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.common.Result;
import com.momentweaver.heartcove.dto.HeartcoveConsentText;
import com.momentweaver.heartcove.dto.HeartcoveEnableRequest;
import com.momentweaver.heartcove.dto.HeartcoveStatusVO;
import com.momentweaver.heartcove.service.HeartcoveConsentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 心声信箱授权：开启 / 关闭 / 状态查询 / 拉取授权书正文。
 *
 * <p>设计原则：与项目模块完全解耦——路由直接挂在 subjectId 上，
 * 不挂 projectId；Subject 是心声信箱的"根资源"，项目只是间接上下文。</p>
 */
@Tag(name = "心声信箱 / Heartcove Consent")
@RestController
@RequestMapping("/api/v1/heartcove/subjects/{subjectId}")
@RequiredArgsConstructor
public class HeartcoveConsentController {

    private final HeartcoveConsentService service;

    @GetMapping("/status")
    @Operation(summary = "心声邮箱状态（是否开启 + 距门槛还差几轮）")
    public Result<HeartcoveStatusVO> status(@PathVariable Long subjectId) {
        return Result.ok(service.getStatus(CurrentUser.requireId(), subjectId));
    }

    @GetMapping("/consent-text")
    @Operation(summary = "《数字人格授权书》正文 + 当前版本号")
    public Result<java.util.Map<String, String>> consentText() {
        java.util.Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put("version", HeartcoveConsentText.VERSION);
        body.put("title", HeartcoveConsentText.TITLE);
        body.put("body", HeartcoveConsentText.BODY);
        return Result.ok(body);
    }

    @PostMapping("/enable")
    @Operation(summary = "开启心声邮箱（需勾选同意授权书 + 通过采访门槛）")
    public Result<HeartcoveStatusVO> enable(@PathVariable Long subjectId,
                                            @Valid @RequestBody HeartcoveEnableRequest req,
                                            HttpServletRequest httpReq) {
        return Result.ok(service.enable(
            CurrentUser.requireId(), subjectId, req,
            clientIp(httpReq), httpReq.getHeader("User-Agent")));
    }

    @PostMapping("/disable")
    @Operation(summary = "关闭心声邮箱（30 天软删除窗口）")
    public Result<HeartcoveStatusVO> disable(@PathVariable Long subjectId,
                                             HttpServletRequest httpReq) {
        return Result.ok(service.disable(
            CurrentUser.requireId(), subjectId,
            clientIp(httpReq), httpReq.getHeader("User-Agent")));
    }

    private String clientIp(HttpServletRequest req) {
        String h = req.getHeader("X-Forwarded-For");
        if (h != null && !h.isBlank()) return h.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}