package com.momentweaver.account.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 家族成员 VO（含 user 基本信息）。
 * <p>前端成员管理页用一张表展示：头像 / 姓名 / 手机邮箱 / 角色 / 加入时间 / 代际。
 */
@Data
public class FamilyMemberVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long familyId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String displayName;
    private String phone;
    private String email;
    private String avatarUrl;

    /** admin / editor / viewer */
    private String role;

    private LocalDateTime joinedAt;

    // ============ M14+ 家族关系图 ============

    /** 代际：正数=晚辈（1=儿女辈，2=孙辈），0=本人辈，负数=长辈（-1=父母辈，-2=祖辈）；NULL=未分代 */
    private Integer generation;

    /** 上一代 family_member.id（同家族内）；NULL=上一代不在家族里 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentFamilyMemberId;

    /** 与上一代的关系类型：father|mother|guardian */
    private String parentMemberRelationType;

    /** 派生：上一代 family_member 的 displayName（渲染连线标签用） */
    private String parentDisplayName;
}
