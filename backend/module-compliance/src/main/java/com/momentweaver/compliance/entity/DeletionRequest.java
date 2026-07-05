package com.momentweaver.compliance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 删除申请 (M5-B.1)。
 *
 * <p>流程：
 * <ol>
 *   <li>用户申请 → status=pending + 软删业务数据 + 写 effective_at + grace_expires_at</li>
 *   <li>30 天宽限期内可恢复 → status=restored 或直接 update 业务表 deleted=0</li>
 *   <li>30 天后定时任务 → 物理删 + status=executed + executed_at=now</li>
 * </ol>
 *
 * <p>V4 migration 已经把所有 M5 字段建好（scope_target_type / requested_user_id /
 * grace_expires_at / executed_at / export_oss_key）。
 */
@Data
@TableName("deletion_request")
public class DeletionRequest {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** V1 旧字段，保留兼容；M5 统一用 requestedUserId。 */
    private Long userId;

    /** 申请人 userId（M5 统一字段） */
    private Long requestedUserId;

    /** project | subject | asset | draft */
    private String scopeTargetType;

    /** 目标 id（字符串，兼容 MongoDB） */
    private String scopeTargetId;

    /** V1 旧字段，保留；M5 统一用 scopeTargetType。 */
    private String scope;

    /** pending | restored | executed | cancelled */
    private String status;

    /** 软删生效时间 = 申请时间 */
    private LocalDateTime effectiveAt;

    /** 物理删除截止时间 = effectiveAt + 30 天 */
    private LocalDateTime graceExpiresAt;

    private LocalDateTime executedAt;

    /** 导出包 OSS key（与 ExportRequest 联动） */
    private String exportOssKey;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
