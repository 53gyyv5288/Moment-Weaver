package com.momentweaver.timeline.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 素材：图片 / 音频（M3 第一版不支持视频）。
 *
 * <p>storage 决定 URL 拼装策略：
 * <ul>
 *   <li>oss —— oss_key 是 OSS 完整 key，前端用签名 URL</li>
 *   <li>local —— oss_key 是 ./uploads 下的相对路径，前端走后端中转 /api/v1/assets/{id}/file</li>
 * </ul>
 */
@Data
@TableName("asset")
public class Asset {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long projectId;
    private Long subjectId;
    private String interviewId;
    private Long uploaderId;

    /** image | audio | video */
    private String kind;
    /** oss | local */
    private String storage;

    private String ossKey;
    private String ossBucket;
    private String ossRegion;

    private String originalName;
    private String mimeType;
    private Long sizeBytes;
    private Integer width;
    private Integer height;
    private Long durationMs;
    private String caption;
    private LocalDateTime takenAt;

    /** pending | clean | flagged */
    private String scanStatus;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}