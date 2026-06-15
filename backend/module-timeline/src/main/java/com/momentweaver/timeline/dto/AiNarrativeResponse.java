package com.momentweaver.timeline.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * AI service 响应的解析体（M4）。
 * 与 ai/app/routers/narrative.py 的 NarrativeResponse 字段对齐。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiNarrativeResponse {

    private String templateId;
    private String title;
    private List<SectionOut> sections;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SectionOut {
        private String sectionId;
        private String sectionTitle;
        private String content;
        private List<String> factsUsed;
    }
}
