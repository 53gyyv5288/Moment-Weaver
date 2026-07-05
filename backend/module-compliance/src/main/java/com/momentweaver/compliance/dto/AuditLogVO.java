package com.momentweaver.compliance.dto;

import com.momentweaver.compliance.entity.AuditLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 审计日志 VO (M5-B.1)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogVO {
    private Long id;
    private String action;
    private String targetType;
    private String targetId;
    private String ip;
    private String ua;
    private String metadata;
    private LocalDateTime createdAt;

    public static AuditLogVO from(AuditLog a) {
        if (a == null) return null;
        return AuditLogVO.builder()
            .id(a.getId())
            .action(a.getAction())
            .targetType(a.getTargetType())
            .targetId(a.getTargetId())
            .ip(a.getIp())
            .ua(a.getUa())
            .metadata(a.getMetadata())
            .createdAt(a.getCreatedAt())
            .build();
    }
}
