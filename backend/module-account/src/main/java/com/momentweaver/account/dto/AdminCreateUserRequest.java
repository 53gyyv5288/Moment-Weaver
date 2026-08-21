package com.momentweaver.account.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
 *
 * <p>M14+ 家族关系图：创建时一次性录入代际 + 上一代 —— 让 Subject 后续继承。
 * parentFamilyMemberId 必须是同家族成员；NULL=上一代不在家族里。</p>
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

    /** M14+ 代际；null=未分代；范围同 Subject 一致 */
    @Min(value = -50, message = "代际不能小于 -50")
    @Max(value = 50, message = "代际不能大于 50")
    private Integer generation;

    /** M14+ 上一代 family_member.id（同家族内）；null=上一代不在家族里 */
    private Long parentFamilyMemberId;

    /** M14+ 与上一代的关系类型：father|mother|guardian */
    @Pattern(regexp = "^(father|mother|guardian)?$",
        message = "parentMemberRelationType 必须为 father/mother/guardian 之一")
    private String parentMemberRelationType;

    /** phone / email 至少传一个 */
    @AssertTrue(message = "手机号和邮箱至少填一个")
    public boolean isIdentifierPresent() {
        return (phone != null && !phone.isBlank()) || (email != null && !email.isBlank());
    }
}
