package com.momentweaver.compliance.dto;

import com.momentweaver.compliance.entity.DeletionRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 删除申请 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeletionRequestVO {
    private Long id;
    private String scopeTargetType;
    private String scopeTargetId;
    private String status;
    private LocalDateTime effectiveAt;
    private LocalDateTime graceExpiresAt;
    private Long daysUntilExpiry;
    private LocalDateTime executedAt;
    private LocalDateTime createdAt;

    public static DeletionRequestVO from(DeletionRequest d) {
        if (d == null) return null;
        Long daysLeft = null;
        if (d.getGraceExpiresAt() != null) {
            long secs = java.time.Duration.between(LocalDateTime.now(), d.getGraceExpiresAt()).getSeconds();
            daysLeft = Math.max(0, secs / 86400);
        }
        return DeletionRequestVO.builder()
            .id(d.getId())
            .scopeTargetType(d.getScopeTargetType())
            .scopeTargetId(d.getScopeTargetId())
            .status(d.getStatus())
            .effectiveAt(d.getEffectiveAt())
            .graceExpiresAt(d.getGraceExpiresAt())
            .daysUntilExpiry(daysLeft)
            .executedAt(d.getExecutedAt())
            .createdAt(d.getCreatedAt())
            .build();
    }
}
