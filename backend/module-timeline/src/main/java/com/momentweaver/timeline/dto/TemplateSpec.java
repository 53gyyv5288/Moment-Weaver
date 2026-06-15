package com.momentweaver.timeline.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 模板元数据（静态，hardcode 在后端；M4 阶段 1 用）。
 * 后续可挪到 DB 配表或配置文件，行为不变。
 */
public final class TemplateSpec {

    private TemplateSpec() {}

    public static final String PERSON_V1 = "person-template-v1";
    public static final String FAMILY_V1 = "family-template-v1";

    /** 人物小传：6 个章节 */
    public static final List<SectionMeta> PERSON_SECTIONS = List.of(
        SectionMeta.of("intro", "开篇", 100, 200, "ai"),
        SectionMeta.of("earlyLife", "童年时光", 200, 400, "ai"),
        SectionMeta.of("family", "家庭亲情", 200, 400, "ai"),
        SectionMeta.of("achievements", "人生成就", 200, 400, "ai"),
        SectionMeta.of("personality", "性格特点", 200, 400, "ai"),
        SectionMeta.of("reflection", "结语", 100, 200, "mixed")
    );

    /** 家族小传：5 个章节 */
    public static final List<SectionMeta> FAMILY_SECTIONS = List.of(
        SectionMeta.of("intro", "家族简介", 200, 300, "ai"),
        SectionMeta.of("origins", "家族渊源", 300, 500, "ai"),
        SectionMeta.of("milestones", "关键时刻", 300, 500, "ai"),
        SectionMeta.of("values", "家族价值观", 300, 500, "ai"),
        SectionMeta.of("reflection", "展望", 200, 300, "mixed")
    );

    public static List<SectionMeta> sectionsOf(String templateId) {
        if (PERSON_V1.equals(templateId)) return PERSON_SECTIONS;
        if (FAMILY_V1.equals(templateId)) return FAMILY_SECTIONS;
        throw new IllegalArgumentException("未知模板：" + templateId);
    }

    public static boolean isValid(String templateId) {
        return PERSON_V1.equals(templateId) || FAMILY_V1.equals(templateId);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SectionMeta {
        private String sectionId;
        private String sectionTitle;
        private Integer targetCharsMin;
        private Integer targetCharsMax;
        /** ai | human_first | mixed | system */
        private String markPolicy;

        public static SectionMeta of(String id, String title, int min, int max, String policy) {
            return SectionMeta.builder()
                .sectionId(id)
                .sectionTitle(title)
                .targetCharsMin(min)
                .targetCharsMax(max)
                .markPolicy(policy)
                .build();
        }
    }
}
