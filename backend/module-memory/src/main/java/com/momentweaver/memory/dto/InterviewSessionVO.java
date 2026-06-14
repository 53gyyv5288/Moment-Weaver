package com.momentweaver.memory.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.momentweaver.memory.entity.InterviewMessage;
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
    private LocalDateTime startedAt;
    private LocalDateTime lastMessageAt;
    private LocalDateTime closedAt;
}
