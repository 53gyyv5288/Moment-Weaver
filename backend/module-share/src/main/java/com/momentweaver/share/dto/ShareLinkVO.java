package com.momentweaver.share.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分享链接 owner 端视图。
 *
 * <p>包含 token（owner 才能看到）、访问 URL、统计信息、状态。
 * 公开端不应返回此 VO；公开端用 PublicShareVO。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "分享链接 owner 视图")
public class ShareLinkVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long projectId;

    private String draftId;
    private String draftTitle;
    private String scope;
    private String token;
    private String shareUrl;
    private Boolean allowCopy;
    private Boolean allowDownload;
    private Integer viewCount;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime lastAccessedAt;

    /** active | expired | revoked。 */
    private String status;

    /** 是否需要密码（scope=password）。 */
    private Boolean hasPassword;
}
