package com.momentweaver.share.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 公开分享页视图（无 JWT 也能访问）。
 *
 * <p>只暴露公开端需要的字段：
 * - draftId、draftTitle、sections 摘要（不含 factsUsed 等内部信息）
 * - subjectNames（展示用）
 * - createdByName（冗余字段）
 * - ai 标识（hasAiContent = true）
 * - allowCopy / allowDownload 控制前端是否允许复制 / 下载
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "公开分享页视图（无 JWT）")
public class PublicShareVO {

    private String token;
    private String draftId;
    private String draftTitle;
    private String scope;
    private Boolean allowCopy;
    private Boolean allowDownload;
    private String createdByName;
    private String createdAt;            // ISO 字符串
    private String expiresAt;            // ISO 字符串
    private List<String> subjectNames;
    private Boolean hasAiContent;        // 永远 true（M5 合规自检要求）
    private String aiLabel;              // "本文含 AI 生成内容"

    /**
     * 章节摘要（不含 factsUsed 等内部信息）。
     * 字段尽量与 SectionVO 对齐但删减。
     */
    private List<PublicSection> sections;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PublicSection {
        private String sectionId;
        private String sectionTitle;
        private Integer order;
        private String content;
        /** ai | human | mixed | system（与 M4 provenance 同源）。 */
        private String provenance;
        private Boolean aiGenerated;
    }
}
