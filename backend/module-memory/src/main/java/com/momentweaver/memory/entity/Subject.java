package com.momentweaver.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("subject")
public class Subject {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long projectId;
    private String displayName;
    /** 关系称呼，比如 "父亲" "外婆" "我自己" */
    private String relation;
    /** 0=未注册，1=已注册并关联账号 */
    private Integer hasAccount;
    private Long linkedUserId;
    /**
     * M11 Phase 2：关联的家族成员 id
     * - NULL  = 纯匿名被采访者（老数据 / 一次性 token 授权）
     * - 非空  = 从家族成员里选出来的被采访者
     *
     * 与 linkedUserId 的区别：
     *   - linkedUserId：被采访者本身的 userId（任一用户）
     *   - familyMemberId：被采访者作为「家族成员」在 family_member 表里的 id
     *
     * 两者可能相同也可能不同（家族成员未必注册过）。
     */
    private Long familyMemberId;
    /** 备注：仅 owner 自己可见 */
    private String note;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
