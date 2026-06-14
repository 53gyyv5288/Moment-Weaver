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
    /** 备注：仅 owner 自己可见 */
    private String note;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
