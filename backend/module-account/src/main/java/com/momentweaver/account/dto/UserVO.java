package com.momentweaver.account.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

@Data
public class UserVO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String phone;
    private String email;
    private String displayName;
    private String avatarUrl;

    /** 是否家族管理员（前端用于显示「管理员徽章」、是否可访问 /families/* 等）。 */
    private Boolean isFamilyAdmin;

    /** 是否需要强制改密（前端用于路由守卫）。 */
    private Boolean mustChangePassword;
}
