package com.momentweaver.compliance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据导出请求 (M5-B.1)。
 * 异步打包 zip → OSS → 发通知。
 */
@Data
@TableName("export_request")
public class ExportRequest {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** all | project | subject */
    private String scope;

    /** scope=project/subject 时记录目标 id（字符串，兼容 MongoDB） */
    private String scopeTargetId;

    /** pending | ready | failed | expired */
    private String status;

    private String ossKey;

    /** 签名 URL 过期时间（仅 ready 状态有效） */
    private LocalDateTime signedUrlExpiresAt;

    /** 失败原因 */
    private String failReason;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;

    @TableLogic
    private Integer deleted;
}
