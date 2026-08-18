package com.momentweaver.heartcove.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 心声信箱单条对话消息。
 *
 * <p><b>物理隔离护栏</b>：{@code isMilvusSynced} 永远为 0。
 * 表上的 {@code CHECK (is_milvus_synced = 0)} 约束会拒绝任何尝试改成非 0 的 UPDATE。
 *
 * <p><b>可见性</b>：所有查询必须经过 {@code heartcove_message_visible} 视图，
 * 且 WHERE 子句强制加 {@code session.user_id = ?}，应用层无法绕过。
 */
@Data
@TableName("heartcove_message")
public class HeartcoveMessage {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long sessionId;
    /** user | ai */
    private String role;
    private String content;

    /** 永远 = 0（CHECK 约束保护） */
    private Integer isMilvusSynced;

    // ===== AI 消息字段 =====
    /** 引用的采访原话 ID 列表（JSON 数组字符串） */
    private String sourceMessageIds;
    /** 不知道话术分类：modern_topic|no_material|emotion_support|boundary|null */
    private String unknownType;
    /** AI 生成耗时（毫秒） */
    private Integer generationMs;
    /** 安全标记：crisis_detected（检测到危机） */
    private String safetyFlag;

    // ===== user 消息字段 =====
    /** 情感得分（0~1，>0.8 触发危机干预） */
    private java.math.BigDecimal safetyScore;

    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}