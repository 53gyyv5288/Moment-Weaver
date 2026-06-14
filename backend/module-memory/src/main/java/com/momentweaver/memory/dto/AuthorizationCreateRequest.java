package com.momentweaver.memory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class AuthorizationCreateRequest {

    @NotNull(message = "被采访者 ID 不能为空")
    private Long subjectId;

    @NotEmpty(message = "至少选择一个授权范围")
    private List<String> scopes;

    /** 自定义有效期（天），可选；缺省走 yml default-ttl-days。Integer 用 @Min/@Max，不是 @Size */
    @Min(value = 1, message = "有效期至少 1 天")
    @Max(value = 365, message = "有效期最多 365 天")
    private Integer ttlDays;
}
