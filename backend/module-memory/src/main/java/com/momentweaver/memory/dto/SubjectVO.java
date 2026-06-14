package com.momentweaver.memory.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SubjectVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long projectId;

    private String displayName;
    private String relation;
    private Integer hasAccount;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long linkedUserId;
    private String note;

    /** 派生：当前有效的授权状态（无授权时为 null） */
    private String latestAuthStatus;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long latestAuthId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
