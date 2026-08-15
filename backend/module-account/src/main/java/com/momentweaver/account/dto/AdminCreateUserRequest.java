package com.momentweaver.account.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 家族管理员创建成员账号的请求。
 *
 * <p>关键设计：
 *   <ul>
 *     <li>phone / email 至少传一个（与自注册保持一致）</li>
 *     <li>password 由管理员设置（8-64 位），登录后强制改密</li>
 *     <li>role 三选一：admin / editor / viewer</li>
 *   </ul>
 */
@Data
public class AdminCreateUserRequest {

    @NotBlank(message = "显示名必填")
    @Size(min = 1, max = 64, message = "显示名 1-64 字")
    private String displayName;

    private String phone;
    private String email;

    @NotBlank(message = "初始密码必填")
    @Size(min = 8, max = 64, message = "密码 8-64 位")
    private String password;

    @NotBlank(message = "角色必填")
    @Pattern(regexp = "admin|editor|viewer", message = "角色必须是 admin / editor / viewer")
    private String role;

    /** phone / email 至少传一个 */
    @AssertTrue(message = "手机号和邮箱至少填一个")
    public boolean isIdentifierPresent() {
        return (phone != null && !phone.isBlank()) || (email != null && !email.isBlank());
    }
}
