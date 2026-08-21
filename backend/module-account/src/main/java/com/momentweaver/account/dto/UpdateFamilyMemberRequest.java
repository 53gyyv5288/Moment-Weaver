package com.momentweaver.account.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 改家族成员角色（或重置密码）的请求。
 *
 * <p>resetPassword 字段非空时，触发重置密码并把 mustChangePassword 置 1。
 *
 * <p>M14+ 家族关系图：支持在 update 时修改 generation / parentFamilyMemberId；
 * 哨兵值约定同 Subject（-50 清空 generation；-1L 清空 parent）。</p>
 */
@Data
public class UpdateFamilyMemberRequest {

    @NotBlank
    @Pattern(regexp = "admin|editor|viewer", message = "角色必须是 admin / editor / viewer")
    private String role;

    /** 可选：重置密码（8-64 位）。非空时触发重置。 */
    private String resetPassword;

    /** M14+ 代际；null=不变；-50=清空 */
    @Min(value = -50, message = "代际不能小于 -50")
    @Max(value = 50, message = "代际不能大于 50")
    private Integer generation;

    /** M14+ 上一代 family_member.id；null=不变；-1L=清空 */
    private Long parentFamilyMemberId;

    /** M14+ 与上一代的关系类型；null=不变；空串=清空 */
    @Pattern(regexp = "^(father|mother|guardian)?$",
        message = "parentMemberRelationType 必须为 father/mother/guardian 之一")
    private String parentMemberRelationType;
}
