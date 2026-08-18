package com.momentweaver.heartcove.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 心声信箱单条消息 VO。
 * 前端永远显示 provenance=AI（即使 AI 命中了「不知道」话术池），用于合规追溯。
 *
 * <p>所有 Long 字段用 ToStringSerializer，避免 JS Number 精度丢失。</p>
 */
@Data
public class HeartcoveMessageVO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String role;          // user | ai
    private String content;
    /** 引用的采访原话 ID 列表（前端用于"查看来源"按钮） */
    private String sourceMessageIds;
    /** 不知道话术分类（仅 AI 消息可能存在） */
    private String unknownType;
    /** 危机干预标记（仅 user 消息可能存在） */
    private String safetyFlag;
    private LocalDateTime createdAt;
}
