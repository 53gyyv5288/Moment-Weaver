package com.momentweaver.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ProjectCreateRequest {

    @NotBlank
    private String name;

    @NotBlank
    @Pattern(regexp = "family|personal", message = "项目类型必须是 family 或 personal")
    private String type;

    private String description;

    /**
     * M10+ Family：可选的家族 ID。
     * <ul>
     *   <li>不传（null）→ 创建个人项目（默认 workspace 下）</li>
     *   <li>传具体 ID   → 在指定家族下创建项目（要求当前 user 是该家族成员）</li>
     * </ul>
     */
    private Long familyId;
}
