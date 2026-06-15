package com.momentweaver.timeline.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新单个 section 请求。
 *
 * <p>两种模式：
 * <ul>
 *   <li>content != null → 人工编辑（provenance: ai/mixed → mixed；空 → human）</li>
 *   <li>rewriteStyle != null → AI 重写（M4 阶段 3 接入）</li>
 * </ul>
 */
@Data
public class UpdateSectionRequest {

    /** 人工编辑内容（与 rewriteStyle 互斥） */
    @Size(max = 8000)
    private String content;

    /** AI 重写风格（与 content 互斥）：warmer | concise | vivid | formal */
    private String rewriteStyle;
}
