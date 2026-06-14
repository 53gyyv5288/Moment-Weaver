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
}
