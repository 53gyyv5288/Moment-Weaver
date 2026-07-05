package com.momentweaver.compliance.dto;

import com.momentweaver.compliance.entity.ExportRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 导出请求 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportRequestVO {
    private Long id;
    private String scope;
    private String scopeTargetId;
    private String status;
    private String signedUrl;       // 仅 status=ready 且未过期时返回
    private LocalDateTime signedUrlExpiresAt;
    private String failReason;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    public static ExportRequestVO from(ExportRequest e, String signedUrl) {
        if (e == null) return null;
        return ExportRequestVO.builder()
            .id(e.getId())
            .scope(e.getScope())
            .scopeTargetId(e.getScopeTargetId())
            .status(e.getStatus())
            .signedUrl(signedUrl)
            .signedUrlExpiresAt(e.getSignedUrlExpiresAt())
            .failReason(e.getFailReason())
            .createdAt(e.getCreatedAt())
            .completedAt(e.getCompletedAt())
            .build();
    }
}
