package com.momentweaver.memory.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.momentweaver.common.entity.InterviewMessage;
import com.momentweaver.memory.entity.InterviewSession;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class InterviewSessionVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private String id;

    @JsonSerialize(using = ToStringSerializer.class)
    private String projectId;

    @JsonSerialize(using = ToStringSerializer.class)
    private String subjectId;

    @JsonSerialize(using = ToStringSerializer.class)
    private String authorizationId;

    private String status;
    private String subjectDisplayName;
    private String projectName;

    private List<InterviewMessage> messages;
    /** M3：AI 自动生成的本次会话结构化摘要 */
    private InterviewSession.InterviewSummary summary;
    private LocalDateTime startedAt;
    private LocalDateTime lastMessageAt;
    private LocalDateTime closedAt;

    /**
     * M11 Phase 3：启动 session 的用户 id（NULL=公开 token 创建）。
     * 前端用于判断"是不是我创建的" / 显示对应身份。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long startedByUserId;
    /**
     * 当前调用者能否"进入采访房间说话"（即有权调用 streamMessage）。
     * - 公开 token 上下文：true
     * - subject.linkedUserId == userId：true
     * - 其他：false（userA 即使有项目访问权，也只是"旁观"）
     */
    private Boolean canStream;
}