package com.momentweaver.share.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 创建分享请求。
 *
 * <p>scope 必须是 public 或 password；password 时 password 必填（4-32 字符）。
 * expiresInDays 必须在 1-90 之间（M5 限制，避免永久链接）。
 * subjectIds 可选：限定哪些 subject 出现在分享内容里；null/空 = 不限。
 */
@Data
@Schema(description = "创建分享请求")
public class CreateShareRequest {

    /** 关联的成稿 id（MongoDB ObjectId 字符串，如 24 位十六进制）。 */
    @NotBlank
    private String draftId;

    @NotBlank
    @Pattern(regexp = "^(public|password)$", message = "scope 必须是 public 或 password")
    private String scope;

    /** scope=password 时必填，4-32 字符。 */
    private String password;

    @Min(1) @Max(90)
    @Schema(description = "有效天数 1-90", example = "30")
    private Integer expiresInDays = 30;

    private Boolean allowCopy = false;
    private Boolean allowDownload = false;

    /** 限定可见 subject；null/空 = 不限。多个用逗号分隔。 */
    private String subjectIds;
}
