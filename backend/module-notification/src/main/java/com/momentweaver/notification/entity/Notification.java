package com.momentweaver.notification.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 通知实体 (M5-A.2)。落 MongoDB notification collection。
 *
 * <p>由 NotificationListener 消费 NotificationRequest 事件后写入；
 * 由 NotificationService 提供查询 / 已读 / 计数能力。
 *
 * <p>索引：userId（按人查）、createdAt desc（列表）、read（未读过滤）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "notification")
public class Notification {

    @Id
    private String id;

    @Indexed
    private Long userId;

    /** 事件类型（参考 NotificationTypes 常量）。 */
    private String type;

    private String title;

    private String body;

    private String refId;

    private String deepLink;

    @Indexed
    private Boolean read;

    private LocalDateTime readAt;

    @Indexed
    private LocalDateTime createdAt;

    /** 附加元数据，前端按 type 决定是否使用。 */
    private Map<String, Object> metadata;
}
