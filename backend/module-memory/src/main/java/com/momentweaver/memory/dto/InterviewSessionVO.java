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
}