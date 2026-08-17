package com.momentweaver.rag.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * RAG search 请求 payload（与 FastAPI 端 /api/v1/rag/search 对齐）。
 *
 * <p>字段命名用 snake_case 与 FastAPI Pydantic 模型对齐；不能用全局 SNAKE_CASE，
 * 否则 accessToken 等前端字段也会被改名 → 前端 401。
 *
 * <p>V15：familyId / familyMemberId 是冗余透传字段。
 * FastAPI 端实际从 authorization.check_subject_authorization 拿 authCtx，
 * 这俩字段保留以备未来"切到 Spring 自己的 family 上下文"用。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SearchRequest(
    String scenario,
    String query,
    String subjectId,
    Long userId,
    Integer topK,
    String extraFilter,
    Long familyId,
    Long familyMemberId
) {
    public static SearchRequest of(String scenario, String query, String subjectId, Long userId) {
        return new SearchRequest(scenario, query, subjectId, userId, null, null, null, null);
    }
    public static SearchRequest of(String scenario, String query, String subjectId, Long userId, int topK) {
        return new SearchRequest(scenario, query, subjectId, userId, topK, null, null, null);
    }

    /** 三处场景常量（与 AI 端 Scenario Literal 对齐）。 */
    public static final String SCENARIO_INTERVIEW = "interview";
    public static final String SCENARIO_TIMELINE = "timeline";
    public static final String SCENARIO_NARRATIVE_FACTS = "narrative_facts";

    public static List<String> allScenarios() {
        return List.of(SCENARIO_INTERVIEW, SCENARIO_TIMELINE, SCENARIO_NARRATIVE_FACTS);
    }
}