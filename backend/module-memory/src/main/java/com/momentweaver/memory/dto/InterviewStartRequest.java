package com.momentweaver.memory.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class InterviewStartRequest {

    @NotNull(message = "项目 ID 不能为空")
    private Long projectId;

    @NotNull(message = "人物 ID 不能为空")
    private Long subjectId;

    /** 使用某个已 granted 的 authorization 启动；不传则自动取最新一个 granted */
    private Long authorizationId;
}
