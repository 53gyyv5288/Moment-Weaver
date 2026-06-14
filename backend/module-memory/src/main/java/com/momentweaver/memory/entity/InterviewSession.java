package com.momentweaver.memory.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 采访问答会话（MongoDB）。
 * 主键：Snowflake 字符串。
 * 一条会话对应一个被采访者的一次完整采访。
 */
@Data
@Document("interview_session")
public class InterviewSession {

    @Id
    private String id;

    @Indexed
    private String projectId;

    @Indexed
    private String subjectId;

    /** 关联的 authorization id（grant 后才允许 start） */
    @Indexed
    private String authorizationId;

    /** active | closed */
    private String status;

    private String subjectDisplayName;
    private String projectName;

    /** 消息历史，按时间顺序；同一文档内嵌，避免 N+1 */
    private List<InterviewMessage> messages = new ArrayList<>();

    private LocalDateTime startedAt;
    private LocalDateTime lastMessageAt;
    private LocalDateTime closedAt;
}
