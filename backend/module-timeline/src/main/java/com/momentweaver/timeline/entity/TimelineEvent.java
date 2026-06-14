package com.momentweaver.timeline.entity;

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
 * 时间线事件（MongoDB）。
 *
 * <p>三类事件统一记录：
 * <ul>
 *   <li>interview_message —— 每条 assistant 消息一条</li>
 *   <li>asset_uploaded —— 素材上传后一条</li>
 *   <li>ai_summary —— 摘要生成后一条</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("timeline_event")
public class TimelineEvent {

    @Id
    private String id;

    @Indexed
    private String projectId;

    @Indexed
    private String subjectId;

    /** interview_message | asset_uploaded | ai_summary */
    @Indexed
    private String type;

    @Indexed
    private LocalDateTime eventAt;

    /** 关联的资源 id（消息 id / asset id / session id） */
    private String refId;

    /** 显示标题 */
    private String title;

    /** 摘要预览（用于时间线展示） */
    private String preview;

    /** 类型相关 metadata（消息角色、文件名、MIME 等） */
    private Map<String, Object> metadata;
}