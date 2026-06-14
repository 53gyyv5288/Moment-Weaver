package com.momentweaver.compliance.controller;

import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.Result;
import com.momentweaver.common.ResultCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * OSS STS 直传凭证（M3 素材上传用）。
 * 一期占位：M3 阶段对接阿里云 RAM STS 真实接口。
 * 这里返回前端可直接 PUT 的占位 URL，前端会拿到 403 —— 这是预期行为，提醒需要 M3 实装。
 */
@Tag(name = "OSS 直传凭证")
@RestController
@RequestMapping("/api/v1/oss")
public class OssStsController {

    @Value("${aliyun.oss.sts.enabled:false}")
    private boolean stsEnabled;

    @Value("${aliyun.oss.bucket:moment-weaver-dev}")
    private String bucket;

    @Value("${aliyun.oss.region:oss-cn-hangzhou}")
    private String region;

    @GetMapping("/sts")
    @Operation(summary = "获取 OSS 直传凭证（一期占位）")
    public Result<StsResponse> sts() {
        CurrentUser.requireId(); // 必须登录
        if (!stsEnabled) {
            // 一期未启用：明确告诉前端，避免静默 403
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "OSS STS 尚未启用（M3 阶段实装）");
        }
        StsResponse r = new StsResponse();
        r.setBucket(bucket);
        r.setRegion(region);
        r.setUploadPrefix("uploads/" + LocalDateTime.now().getYear());
        return Result.ok(r);
    }

    @Data
    public static class StsResponse {
        private String bucket;
        private String region;
        private String uploadPrefix;
    }
}
