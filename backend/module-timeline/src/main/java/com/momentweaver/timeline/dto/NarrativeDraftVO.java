package com.momentweaver.timeline.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 成稿响应 VO。
 * 与 {@link com.momentweaver.timeline.entity.NarrativeDraft} 字段对齐，主键字段名统一为 id。
 */
@Data
public class NarrativeDraftVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private String id;

    @JsonSerialize(using = ToStringSerializer.class)
    private String projectId;

    @JsonSerialize(using = ToStringSerializer.class)
    private String workspaceId;

    @JsonSerialize(using = ToStringSerializer.class)
    private String ownerId;

    private String templateId;
    private String scope;
    private List<String> subjectIds;
    private List<String> subjectDisplayNames;
    private String title;
    /** pending | draft | published | archived */
    private String status;
    private List<SectionVO> sections;
    private List<FactSnapshotVO> factsSnapshot;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime publishedAt;
    private Long version;
}
