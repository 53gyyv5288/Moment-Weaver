package com.momentweaver.timeline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * real 模式 OSS 直传后回调：前端调本接口登记 metadata。
 * mock 模式不需要走这个接口，multipart 直接在后端搞定。
 */
@Data
public class AssetCreateRequest {

    /** image | audio */
    @NotBlank
    private String kind;

    /** oss | local（real 模式只可能是 oss） */
    @NotBlank
    private String storage;

    @NotBlank
    private String ossKey;

    @NotBlank
    private String ossBucket;

    @NotBlank
    private String ossRegion;

    private String originalName;
    private String mimeType;
    private Long sizeBytes;
    private Integer width;
    private Integer height;
    private String caption;

    private Long subjectId;
    private String interviewId;
    private LocalDateTime takenAt;
}