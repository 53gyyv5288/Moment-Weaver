package com.momentweaver.timeline.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 成稿（M4）。
 *
 * <p>基于采访消息 + 素材 + 人物备注，按模板（人物小传 / 家族小传）生成的结构化叙事稿件。
 * 每个 section 记录 provenance 状态，UI 可明确标识 AI / 人类 / 混合来源。
 *
 * <p>主键：MongoDB ObjectId 字符串（与 {@link TimelineEvent} / {@link InterviewSession} 一致）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("narrative_draft")
public class NarrativeDraft {

    @Id
    private String id;

    @Indexed
    private String projectId;

    @Indexed
    private String workspaceId;

    @Indexed
    private String ownerId;

    /** person-template-v1 | family-template-v1 */
    @Indexed
    private String templateId;

    /** person | family */
    private String scope;

    @Indexed
    private List<String> subjectIds;

    private List<String> subjectDisplayNames;

    private String title;

    /** pending | draft | published | archived */
    @Indexed
    private String status;

    private List<Section> sections = new ArrayList<>();

    private List<FactSnapshot> factsSnapshot = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime publishedAt;

    /** 乐观锁版本号；PATCH 端点用 If-Match header 校验 */
    private Long version;

    /** 单个章节。content 为空表示尚未生成。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Section {
        /** 章节 ID（模板内唯一），如 intro / earlyLife / family */
        private String sectionId;
        private String sectionTitle;
        private Integer order;
        private Integer targetCharsMin;
        private Integer targetCharsMax;
        /** ai | human_first | mixed | system — 模板里标注的本节期望来源策略 */
        private String markPolicy;
        private String content;
        /** ai | human | mixed | system — 实际来源（用户编辑后从 ai 转 mixed） */
        private String provenance;
        private Boolean aiGenerated;
        private List<String> factsUsed;
        /** warmer | concise | vivid | formal — 最近一次 AI 重写风格 */
        private String lastRewriteStyle;
        private Integer rewriteCount;
        private LocalDateTime manuallyEditedAt;
    }

    /** fact 快照：创建 draft 时把已收集的事实冻进文档，便于 AI 重写时复用，避免再次跨表查 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FactSnapshot {
        private String factId;
        /** interview | asset_caption | note */
        private String source;
        private String text;
        private String subjectId;
        private LocalDateTime timestamp;
    }
}
