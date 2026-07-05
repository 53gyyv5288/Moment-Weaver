package com.momentweaver.share.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 公开分享密码验证请求（无 JWT）。
 */
@Data
@Schema(description = "公开分享密码验证请求")
public class PublicShareVerifyRequest {

    @NotBlank
    private String password;
}
