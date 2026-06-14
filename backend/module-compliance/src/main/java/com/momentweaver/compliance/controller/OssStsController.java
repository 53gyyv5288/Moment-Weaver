package com.momentweaver.compliance.controller;

import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.Result;
import com.momentweaver.common.ResultCode;
import com.momentweaver.compliance.service.StsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OSS STS 直传凭证（M3 素材上传用）。
 *
 * <p>返回结构由前端决定上传策略：
 * <ul>
 *   <li>mode=mock —— 前端拿到凭证后用 multipart 回退到后端中转</li>
 *   <li>mode=real —— 前端用 ali-oss SDK 直传到 OSS，回调本服务登记 metadata</li>
 * </ul>
 */
@Tag(name = "OSS 直传凭证")
@RestController
@RequestMapping("/api/v1/oss")
@RequiredArgsConstructor
public class OssStsController {

    private final StsService stsService;

    @GetMapping("/sts")
    @Operation(summary = "获取 OSS 直传凭证（mock / real 模式由 yml 决定）")
    public Result<StsResponse> sts() {
        CurrentUser.requireId(); // 必须登录
        try {
            StsService.StsResult r = stsService.assumeRole();
            return Result.ok(toResponse(r));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 防止 yml 配错或外部依赖异常时静默成功
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "获取 STS 失败: " + e.getMessage());
        }
    }

    private StsResponse toResponse(StsService.StsResult r) {
        StsResponse vo = new StsResponse();
        vo.setMode(r.getMode());
        vo.setAccessKeyId(r.getAccessKeyId());
        vo.setAccessKeySecret(r.getAccessKeySecret());
        vo.setSecurityToken(r.getSecurityToken());
        vo.setExpiration(r.getExpiration());
        vo.setBucket(r.getBucket());
        vo.setRegion(r.getRegion());
        vo.setUploadPrefix(r.getUploadPrefix());
        return vo;
    }

    @Data
    public static class StsResponse {
        private String mode;
        private String accessKeyId;
        private String accessKeySecret;
        private String securityToken;
        private java.time.LocalDateTime expiration;
        private String bucket;
        private String region;
        private String uploadPrefix;
    }
}