package com.momentweaver.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("project")
public class Project {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workspaceId;
    private Long ownerId;

    /** 字符串存：family | personal */
    private String type;
    private String name;
    private String description;

    /**
     * 所属家族（NULL=个人项目；非空=家族项目）。
     * 与 workspaceId 互不冲突：项目可只挂 workspace（如纯个人），
     * 也可同时挂 family（如家族协作）。
     */
    private Long familyId;

    /** 0=归档，1=进行中 */
    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
