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
