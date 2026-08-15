package com.momentweaver.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 家族组织（M10+ Family Phase 1）。
 *
 * <p>与 {@link Workspace} 的关系：
 *   <ul>
 *     <li>workspace —— 个人 MVP 阶段的容器，1 用户 1 个，承载「个人项目」</li>
 *     <li>family    —— 家族协作场景的容器，1 家族 N 成员，承载「家族项目」</li>
 *   </ul>
 *   两者并行存在互不影响；项目可只挂 workspace 也可同时挂 family（见 Project.familyId）。
 */
@Data
@TableName("family")
public class Family {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;
    private String description;

    /** 家族管理员 userId（创建者）。 */
    private Long ownerUserId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
