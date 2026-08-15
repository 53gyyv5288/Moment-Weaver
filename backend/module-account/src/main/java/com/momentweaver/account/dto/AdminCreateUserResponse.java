package com.momentweaver.account.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 管理员创建账号后的响应。
 *
 * <p>返回一次性明文密码（仅此次），管理员需告知被创建者。
 * <p>实际前端处理：把密码展示在弹窗里，提示管理员「请抄送给该成员」。
 */
@Data
@AllArgsConstructor
public class AdminCreateUserResponse {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String displayName;

    /** 明文密码（仅创建时返回一次）。 */
    private String initialPassword;

    /** admin / editor / viewer */
    private String role;

    /** 是否需要强制改密（管理员创建场景下恒为 true）。 */
    private Boolean mustChangePassword;
}
