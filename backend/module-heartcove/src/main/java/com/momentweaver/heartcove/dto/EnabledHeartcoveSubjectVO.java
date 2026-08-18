package com.momentweaver.heartcove.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 心声信箱已开启人物聚合 VO（供前端 HeartcoveEntry 一次性拉取）。
 *
 * <p>设计：跨项目/家族聚合，userId 过滤；
 * 后端单次 SQL 拿到所有 subject + project 信息，前端不用 N+1。</p>
 *
 * <p><b>重要</b>：所有 Long 字段（Snowflake ID）必须用 ToStringSerializer 序列化为字符串，
 * 否则超过 JS Number 安全范围（2^53-1）会被舍入到错误值，导致后续拿这个 ID 查任何东西都失败。</p>
 */
@Data
public class EnabledHeartcoveSubjectVO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long subjectId;
    private String subjectDisplayName;
    private String subjectRelation;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long projectId;
    private String projectName;
    private Integer projectType;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long familyId;
    private LocalDateTime heartcoveEnabledAt;
    private String consentVersion;
}
