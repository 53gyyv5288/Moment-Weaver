package com.momentweaver.memory.entity;

import com.momentweaver.common.entity.InterviewMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
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

    /** M3：AI 自动生成的本次会话结构化摘要 */
    private InterviewSummary summary;

    private LocalDateTime startedAt;
    private LocalDateTime lastMessageAt;
    private LocalDateTime closedAt;

    /**
     * M11 Phase 3：启动 session 的用户 id。
     *   - 非空：被采访者本人（用账号登录后点"开始采访"）
     *   - NULL：公开 token 路径（老人没账号，用一次性 token 启动）
     */
    private Long startedByUserId;

    /** 采访摘要：标题、金句、关键时间点 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InterviewSummary {
        private String title;
        private List<String> goldenQuotes = new ArrayList<>();
        private List<KeyMoment> keyMoments = new ArrayList<>();
        private LocalDateTime generatedAt;
        private String generatedBy; // ai | manual
    }

    /** 关键时间点：timestamp 是会话内相对时间（如 "08:32"）或绝对 ISO */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KeyMoment {
        private String timestamp;
        private String text;
    }
}
