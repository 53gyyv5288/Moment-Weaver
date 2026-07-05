package com.momentweaver.common.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 通知事件载体（M5 跨模块解耦用）。
 *
 * <p>与 {@link TimelineEventRequest} 模式完全一致：
 * module-share / module-timeline 等业务模块在关键动作后发布本事件，
 * module-notification 监听并落库到 MongoDB notification collection。
 *
 * <p>发布者用法：
 * <pre>
 *   applicationEventPublisher.publishEvent(new NotificationRequest(
 *       userId, "DRAFT_PUBLISHED", "新成稿已发布",
 *       "《童年时光》已发布到《张三的家族小传》", draftId, "/drafts/" + draftId + "/read",
 *       Map.of("draftId", draftId, "projectId", projectId)
 *   ));
 * </pre>
 *
 * <p>注意：title ≤ 20 字，body ≤ 60 字，deepLink 是前端路由路径（不含 baseURL）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationRequest {
    /** 收件人 userId。 */
    private Long userId;
    /** 事件类型常量（参考 NotificationTypes 类）。 */
    private String type;
    /** 标题（≤ 20 字）。 */
    private String title;
    /** 正文（≤ 60 字）。 */
    private String body;
    /** 关联资源 id（draftId / shareId / deletionId 等）。 */
    private String refId;
    /** 前端路由深链（如 /drafts/123/read、/recycle-bin）。 */
    private String deepLink;
    /** 附加元数据。 */
    private Map<String, Object> metadata;
}
