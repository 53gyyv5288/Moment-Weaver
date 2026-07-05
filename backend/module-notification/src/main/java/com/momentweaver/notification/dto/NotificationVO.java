package com.momentweaver.notification.dto;

import com.momentweaver.notification.entity.Notification;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 通知 VO (M5-A.2)。
 * 字段对齐前端 NotificationList / NotificationBell。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationVO {
    private String id;
    private String type;
    private String title;
    private String body;
    private String refId;
    private String deepLink;
    private Boolean read;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
    private Map<String, Object> metadata;

    public static NotificationVO from(Notification n) {
        if (n == null) return null;
        return NotificationVO.builder()
            .id(n.getId())
            .type(n.getType())
            .title(n.getTitle())
            .body(n.getBody())
            .refId(n.getRefId())
            .deepLink(n.getDeepLink())
            .read(n.getRead())
            .readAt(n.getReadAt())
            .createdAt(n.getCreatedAt())
            .metadata(n.getMetadata())
            .build();
    }
}
