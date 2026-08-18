package com.momentweaver.heartcove.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建 / 进入心声信箱会话。
 */
@Data
public class CreateHeartcoveSessionRequest {
    @NotNull
    private Long subjectId;
}