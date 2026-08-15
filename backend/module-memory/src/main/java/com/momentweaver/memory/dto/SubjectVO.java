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
    /** M11 Phase 2：关联的家族成员 id（NULL=匿名） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long familyMemberId;
    /** 派生：被采访者对应的家族成员 displayName（前端可显示「采访家人」标签） */
    private String familyMemberDisplayName;
    /** 派生：被采访者对应的家族成员 avatarUrl */
    private String familyMemberAvatarUrl;
    private String note;

    /** 派生：当前有效的授权状态（无授权时为 null） */
    private String latestAuthStatus;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long latestAuthId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
