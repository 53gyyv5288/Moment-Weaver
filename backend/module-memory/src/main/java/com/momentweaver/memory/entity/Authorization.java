package com.momentweaver.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("authorization")
public class Authorization {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long subjectId;
    private Long projectId;
    /**
     * V15：冗余自 Subject.familyMemberId。
     * 同 familyMember 在同 family 内的 grant 共享给所有同 familyMember subject。
     * NULL=匿名 subject 或旧数据未回填。
     */
    private Long familyMemberId;
    /**
     * V15：冗余自 Project.familyId。跨 family 隔离依据。
     * NULL=个人项目或旧数据未回填。
     */
    private Long familyId;
    /** 不重复 token；公开链接用 */
    private String token;
    /** 逗号分隔：interview, narrative, asset, share */
    private String scopes;
    /** pending | granted | denied | revoked | expired */
    private String status;
    private String consentVersion;
    private LocalDateTime grantedAt;
    private LocalDateTime revokedAt;
    private LocalDateTime expiresAt;
    private String ip;
    private String ua;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
