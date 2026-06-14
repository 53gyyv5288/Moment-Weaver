package com.momentweaver.timeline.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class TimelineItemVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private String id;

    @JsonSerialize(using = ToStringSerializer.class)
    private String projectId;

    @JsonSerialize(using = ToStringSerializer.class)
    private String subjectId;

    /** interview_message | asset_uploaded | ai_summary */
    private String type;

    private LocalDateTime eventAt;

    /** 关联的资源 id */
    private String refId;

    private String title;
    private String preview;
    private Map<String, Object> metadata;
}