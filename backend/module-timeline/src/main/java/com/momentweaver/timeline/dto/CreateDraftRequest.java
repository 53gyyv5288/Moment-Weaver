package com.momentweaver.timeline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 创建成稿请求。M4 阶段 1：仅创建空 draft + 收集 facts，不调 AI。
 */
@Data
public class CreateDraftRequest {

    /** person-template-v1 | family-template-v1 */
    @NotBlank
    private String templateId;

    /** 关联人物 id 列表。person 模板必须单 subject；family 模板至少 1 个。 */
    @NotEmpty
    private List<Long> subjectIds;

    /** 可选：自定义标题（不传则前端显示「未命名成稿」） */
    private String title;
}
