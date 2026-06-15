package com.momentweaver.timeline.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 成稿章节 VO。
 * 与 {@link com.momentweaver.timeline.entity.NarrativeDraft.Section} 字段对齐。
 */
@Data
public class SectionVO {

    private String sectionId;
    private String sectionTitle;
    private Integer order;
    private Integer targetCharsMin;
    private Integer targetCharsMax;
    private String markPolicy;
    private String content;
    /** ai | human | mixed | system */
    private String provenance;
    private Boolean aiGenerated;
    private List<String> factsUsed;
    private String lastRewriteStyle;
    private Integer rewriteCount;
    private LocalDateTime manuallyEditedAt;
}
