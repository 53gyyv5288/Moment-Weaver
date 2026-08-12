package com.momentweaver.timeline.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Timeline 语义搜索结果 VO（plan §4.3.B）。
 *
 * <p>把 RAG EvidenceChunk + Asset 元数据融合，便于前端展示。
 */
@Data
public class TimelineSearchVO {

    /** 命中 chunk_id */
    private String chunkId;

    /** refId：关联的 asset_id（前端用来打开素材详情） */
    @JsonSerialize(using = ToStringSerializer.class)
    private String refId;

    /** 项目 ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private String projectId;

    /** 人物 ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private String subjectId;

    /** 素材类型：image / audio / video */
    private String kind;

    /** 拍摄 / 上传时间 */
    private LocalDateTime takenAt;

    /** 预览文本（parent_text 前 80 字） */
    private String preview;

    /** URL（前端直接用的可访问 URL） */
    private String url;

    /** 检索得分（reranker score，0-1） */
    private double score;

    /** 额外 metadata（kind / file_url / role 等） */
    private Map<String, Object> metadata;
}