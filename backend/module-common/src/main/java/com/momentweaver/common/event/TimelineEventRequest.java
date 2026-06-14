package com.momentweaver.common.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 时间线事件载体（M3 跨模块解耦用）。
 *
 * <p>放在 module-common 是为了打破模块间循环依赖：
 * module-memory 发布此事件，module-timeline 监听并落库。
 *
 * <p>发布者用法：
 * <pre>
 *   applicationEventPublisher.publishEvent(new TimelineEventRequest(
 *       projectId, subjectId, type, refId, title, preview, metadata
 *   ));
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TimelineEventRequest {
    private String projectId;
    private String subjectId;
    private String type;
    private String refId;
    private String title;
    private String preview;
    private Map<String, Object> metadata;
}