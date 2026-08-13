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
 *
 * <p><b>turn 关联字段（M8+）</b>：
 * <ul>
 *   <li>{@link #turnId} —— 同一对 user + assistant 共享的 UUID；system 消息为 null</li>
 *   <li>{@link #turnStatus} —— 本条消息所属 turn 的状态：
 *       PENDING (user 已落库，等 assistant) /
 *       COMPLETED (user + assistant 都到位) /
 *       FAILED (流中断 / 错误，assistant 永久缺席)</li>
 * </ul>
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

    /** 同一对的 user/assistant 共享 turnId；system 消息此字段为 null。 */
    private String turnId;
    /** 本条所属 turn 的状态（PENDING/COMPLETED/FAILED）；system 消息为 null。 */
    private TurnStatus turnStatus;

    private LocalDateTime createdAt;

    /** turn 状态枚举。 */
    public enum TurnStatus {
        /** user 已落库，等 assistant */
        PENDING,
        /** user + assistant 都到位 */
        COMPLETED,
        /** 流中断/错误，assistant 永久缺席 */
        FAILED
    }
}