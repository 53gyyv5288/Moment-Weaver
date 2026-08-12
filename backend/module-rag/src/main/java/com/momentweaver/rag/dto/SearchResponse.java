package com.momentweaver.rag.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;

/**
 * RAG search 响应：证据块列表 + 调试信息。
 *
 * <p>字段命名用 snake_case 与 FastAPI SearchResponse 对齐。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SearchResponse(
    List<EvidenceChunk> chunks,
    String rewrittenQuery,
    Map<String, Object> debug
) {}