package com.momentweaver.heartcove.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 心声信箱中期记忆滚动摘要（每对 subject+user 一条）。
 * 不向量化；纯文本存储在 MySQL。
 */
@Data
@TableName("heartcove_memory_summary")
public class HeartcoveMemorySummary {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long subjectId;
    private Long userId;
    private String summary;
    private Integer turnCount;
    private Long lastMessageId;
    private LocalDateTime generatedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}