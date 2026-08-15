package com.momentweaver.memory.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * M11 Phase 2：项目下"可选被采访者"列表（从家族成员里筛）。
 *
 * <p>前端"添加人物"弹窗 Tab 1 用这个 VO 列表。
 *
 * <p>关键字段：
 *   <ul>
 *     <li>{@code familyMemberId} —— family_member.id（关联 subject 用）</li>
 *     <li>{@code userId} —— family_member.user_id（关联 user 用，可能与上面不同）</li>
 *     <li>{@code hasSubject} —— 该成员是否已经在该项目下被添加为被采访者（避免重复）</li>
 *   </ul>
 */
@Data
public class EligibleFamilyMemberVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long familyMemberId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String displayName;
    private String phone;
    private String email;
    private String avatarUrl;

    /** admin / editor / viewer（家族角色） */
    private String role;

    /** true = 该成员已经在本项目下被添加为被采访者（重复添加检测） */
    private Boolean hasSubject;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long existingSubjectId;
}
