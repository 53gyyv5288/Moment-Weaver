package com.momentweaver.heartcove.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 心声信箱会话详情（包含消息列表）。
 *
 * <p>所有 Long 字段用 ToStringSerializer，避免 JS Number 精度丢失。</p>
 */
@Data
public class HeartcoveSessionVO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long subjectId;
    private String subjectDisplayName;
    private String status;
    private Integer messageCount;
    private LocalDateTime lastMessageAt;
    private LocalDateTime startedAt;
    private LocalDateTime closedAt;
    private List<HeartcoveMessageVO> messages;
}
