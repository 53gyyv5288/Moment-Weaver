package com.momentweaver.account.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ProjectVO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long workspaceId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long ownerId;
    private String type;
    private String name;
    private String description;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** M10+ Family：所属家族 ID（NULL=个人项目）。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long familyId;

    /**
     * M11 Phase 3：当前用户在该项目中的权限。
     * <ul>
     *   <li>'admin'  —— 家族管理员，可删项目/人物/素材/分享，可发起授权，可改族员</li>
     *   <li>'editor' —— 家族编辑者，可采访/生成成稿/上传素材/创建分享</li>
     *   <li>'viewer' —— 家族旁观者，只读</li>
     *   <li>null     —— 个人项目（无角色）或调用者不是家族成员</li>
     * </ul>
     *
     * <p>前端 ProjectLayout 用 inject('project') 拿到此字段，控制按钮显隐。
     */
    private String myPermission;
}
