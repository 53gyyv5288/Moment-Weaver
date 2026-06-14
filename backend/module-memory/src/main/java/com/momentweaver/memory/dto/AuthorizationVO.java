package com.momentweaver.memory.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class AuthorizationVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long subjectId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long projectId;

    private String token;

    /** 拆成 List 方便前端展示勾选 */
    private List<String> scopes;

    private String status;
    private String consentVersion;
    private LocalDateTime grantedAt;
    private LocalDateTime revokedAt;
    private LocalDateTime expiresAt;

    /** 公开链接（仅创建时返回） */
    private String publicUrl;

    private LocalDateTime createdAt;
}
