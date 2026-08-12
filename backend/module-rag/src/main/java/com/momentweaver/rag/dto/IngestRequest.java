package com.momentweaver.rag.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;

/**
 * ingest 请求 payload（与 FastAPI 端 /api/v1/rag/ingest 对齐）。
 *
 * <p>字段命名用 snake_case 与 FastAPI Pydantic 模型对齐。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IngestRequest(
    String collection,
    boolean isCuratedForFacts,
    List<ChunkUpsert> chunks
) {
    /**
     * 内层 chunk 也用 snake_case（@JsonNaming 不会自动级联到嵌套类型）。
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ChunkUpsert(
        String chunkId,
        String parentId,
        String chunkText,
        String parentText,
        String subjectId,
        Map<String, Object> metadata
    ) {}
}