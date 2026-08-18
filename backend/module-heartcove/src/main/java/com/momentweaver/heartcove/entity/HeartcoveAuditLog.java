package com.momentweaver.heartcove.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 心声信箱操作审计日志（独立于审计中心，便于合规复查）。
 * action 取值：enable | disable | chat | crisis_detected
 */
@Data
@TableName("heartcove_audit_log")
public class HeartcoveAuditLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long subjectId;
    private Long userId;
    private String action;
    private String detail;
    private String ip;
    private String ua;

    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}