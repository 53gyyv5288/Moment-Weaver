package com.momentweaver.timeline.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 调 AI service 的请求体（M4）。
 * 与 ai/app/routers/narrative.py 的 NarrativeRequest 字段对齐。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiNarrativeRequest {

    /** person-template-v1 | family-template-v1 */
    private String templateId;

    private List<SubjectItem> subjects;
    private List<FactItem> facts;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SubjectItem {
        private String subjectId;
        private String name;
        private String relation;
        private Integer birthYear;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FactItem {
        private String factId;
        /** interview | asset_caption | note */
        private String source;
        private String text;
        private String subjectId;
        /**
         * ISO 8601 字符串（不是 JSON 数组！Pydantic / FastAPI 会 422）。
         * 用 @JsonFormat 强制走字符串序列化，避免依赖全局 jackson 配置或 WebClient 自带 ObjectMapper。
         */
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime timestamp;
    }
}
