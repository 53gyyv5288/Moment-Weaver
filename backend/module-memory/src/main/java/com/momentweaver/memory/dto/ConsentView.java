package com.momentweaver.memory.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ConsentView {
    /** 同意书正文（Markdown 文本） */
    private String markdown;
    private String consentVersion;
    @Data
    public static class Scope {
        private String code;
        private String label;
    }
    private List<Scope> scopes;
    private String projectName;
    private String subjectDisplayName;
    private String ownerDisplayName;
    private LocalDateTime expiresAt;
}
