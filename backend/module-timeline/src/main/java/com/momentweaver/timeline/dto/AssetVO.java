package com.momentweaver.timeline.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AssetVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private String id;

    @JsonSerialize(using = ToStringSerializer.class)
    private String projectId;

    @JsonSerialize(using = ToStringSerializer.class)
    private String subjectId;

    private String interviewId;
    private String kind;
    private String storage;
    private String url;             // mock 模式 = /api/v1/assets/{id}/file；real 模式 = 签名 URL
    private String originalName;
    private String mimeType;
    private Long sizeBytes;
    private Integer width;
    private Integer height;
    private Long durationMs;
    private String caption;
    private LocalDateTime takenAt;
    private String scanStatus;
    private LocalDateTime createdAt;
}