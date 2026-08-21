package com.momentweaver.heartcove.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 心声信箱对话会话。
 * userId 强约束：会话只属于创建它的用户；其他用户看不见（即便 Owner）。
 */
@Data
@TableName("heartcove_session")
public class HeartcoveSession {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long subjectId;
    private Long userId;
    /** active | closed */
    private String status;
    private Integer messageCount;
    private LocalDateTime lastMessageAt;
    private LocalDateTime startedAt;
    private LocalDateTime closedAt;
    private String clientIp;
    private String clientUa;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * M14+：session 启动时缓存的 persona_summary（含代际文案）。
     * NULL=走"现算"路径（兼容老 session）；非空=直接读。
     */
    private String cachedPersonaSummary;

    @TableLogic
    private Integer deleted;
}