package com.momentweaver.account.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 家族 VO（前端展示用）。
 *
 * <p>核心字段：
 *   <ul>
 *     <li>myRole —— 当前用户在此家族的角色，前端据此显示徽章和按钮</li>
 *     <li>memberCount / projectCount —— 列表展示用，二期可改独立 count 接口</li>
 *   </ul>
 */
@Data
public class FamilyVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String name;
    private String description;

    /** 家族管理员（创建者）userId。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long ownerUserId;

    /** 当前用户在此家族的角色：admin / editor / viewer。 */
    private String myRole;

    private Integer memberCount;
    private Integer projectCount;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
