package com.momentweaver.account.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 家族成员 VO（含 user 基本信息）。
 * <p>前端成员管理页用一张表展示：头像 / 姓名 / 手机邮箱 / 角色 / 加入时间。
 */
@Data
public class FamilyMemberVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String displayName;
    private String phone;
    private String email;
    private String avatarUrl;

    /** admin / editor / viewer */
    private String role;

    private LocalDateTime joinedAt;
}
