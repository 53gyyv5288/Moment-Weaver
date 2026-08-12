package com.momentweaver.rag.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.Map;

/**
 * RAG 检索结果：small-to-big 的父片 + 元数据。
 * <p>Spring 端三处集成（采访 / 时间线 / 成稿）都消费这个结构。
 *
 * <p>字段命名用 snake_case 与 FastAPI EvidenceChunk 对齐。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record EvidenceChunk(
    String chunkId,
    String parentText,
    String chunkText,
    double score,
    Map<String, Object> metadata
) {
    public String subjectId() {
        return metadata == null ? null : (String) metadata.get("subject_id");
    }
    public String sessionId() {
        return metadata == null ? null : (String) metadata.get("session_id");
    }
    public Object assetId() {
        return metadata == null ? null : metadata.get("asset_id");
    }
}