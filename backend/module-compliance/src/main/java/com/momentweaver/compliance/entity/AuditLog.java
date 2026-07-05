package com.momentweaver.compliance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志 (M5-B.1)。
 *
 * <p>V4 已建表；M5 通过 AuditService 写入关键动作（登录 / 登出 / 分享 / 删除 / 导出 / 撤销等）。
 *
 * <p>不记录敏感字段：password、token、share token、draft 内容。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private String action;

    private String targetType;
    private String targetId;

    private String ip;
    private String ua;

    /** JSON 字符串；ORM 暂不映射 JSON 类型，直接存 JSON 串。 */
    private String metadata;

    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
