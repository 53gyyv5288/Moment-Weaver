package com.momentweaver.compliance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 回收站条目 VO (M5-B.2)。
 * 统一封装：type + id + title + deletedAt + daysUntilPermanentDelete
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecycleBinItemVO {
    /** project | subject | asset | draft */
    private String type;
    private String id;
    private String title;
    private LocalDateTime deletedAt;
    /** 距永久删除还有几天（基于 deletion_request.grace_expires_at） */
    private Long daysUntilPermanentDelete;
    /** 关联的 deletion_request id（用于恢复） */
    private Long deletionRequestId;
}
