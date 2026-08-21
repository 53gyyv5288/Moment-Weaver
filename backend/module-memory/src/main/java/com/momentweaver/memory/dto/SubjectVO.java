package com.momentweaver.memory.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SubjectVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long projectId;

    private String displayName;
    private String relation;
    private Integer hasAccount;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long linkedUserId;
    /** M11 Phase 2：关联的家族成员 id（NULL=匿名） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long familyMemberId;
    /** 派生：被采访者对应的家族成员 displayName（前端可显示「采访家人」标签） */
    private String familyMemberDisplayName;
    /** 派生：被采访者对应的家族成员 avatarUrl */
    private String familyMemberAvatarUrl;
    private String note;

    /** 派生：当前有效的授权状态（无授权时为 null） */
    private String latestAuthStatus;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long latestAuthId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ============ M14+ 家族关系图 ============

    /** 代际：正数=晚辈（1=儿女辈，2=孙辈），0=本人辈，负数=长辈（-1=父母辈，-2=祖辈）；NULL=未分代 */
    private Integer generation;

    /** 父/母节点 subject.id（同项目内）；NULL=父不在项目里 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentSubjectId;

    /** 父/母关系类型：father|mother|guardian；NULL=未指定 */
    private String parentRelationType;

    /** 派生：父/母节点的 displayName（前端渲染连线标签时方便） */
    private String parentDisplayName;

    /**
     * 派生：generation 一致性警告。
     * <p>非空字符串 = 当前节点的 generation 与父节点不一致（例如父=2，子=5），
     * 前端据此显示橙色角标 + el-alert；不阻塞录入。</p>
     * <p>空/null = 一致或无父节点。</p>
     */
    private String generationWarning;
}
