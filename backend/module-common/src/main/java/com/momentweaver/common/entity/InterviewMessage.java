package com.momentweaver.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 采访单条消息：system | user | assistant
 * 来源标识：human | ai_generated（用于 M4 AI 内容追溯）
 *
 * <p>从 module-memory.entity 迁出，放到 module-common.entity：
 * 因为 module-rag 也要用，但 module-rag 不能反向依赖 module-memory（会形成循环依赖）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewMessage {
    private String role;        // system | user | assistant
    private String content;
    private String thinking;    // 思考链（M5+ 暴露给用户；推理模型 ... 内容；非推理模型为 null）
    private String source;      // human | ai_generated
    private Integer tokenCount; // 估算（前端用不到，后端审计用）
    private LocalDateTime createdAt;
}
