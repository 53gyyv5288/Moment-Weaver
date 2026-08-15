package com.momentweaver.account.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ProjectVO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long workspaceId;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long ownerId;
    private String type;
    private String name;
    private String description;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** M10+ Family：所属家族 ID（NULL=个人项目）。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long familyId;
}
