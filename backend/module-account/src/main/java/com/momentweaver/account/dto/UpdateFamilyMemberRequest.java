package com.momentweaver.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 改家族成员角色（或重置密码）的请求。
 *
 * <p>resetPassword 字段非空时，触发重置密码并把 mustChangePassword 置 1。
 */
@Data
public class UpdateFamilyMemberRequest {

    @NotBlank
    @Pattern(regexp = "admin|editor|viewer", message = "角色必须是 admin / editor / viewer")
    private String role;

    /** 可选：重置密码（8-64 位）。非空时触发重置。 */
    private String resetPassword;
}
