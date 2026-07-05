package com.momentweaver.compliance.dto;

import lombok.Data;

/**
 * 创建删除请求 (M5-B.1)。
 */
@Data
public class CreateDeletionRequest {
    /** project | subject | asset | draft */
    private String scopeTargetType;
    /** 目标 id（字符串，兼容 MongoDB） */
    private String scopeTargetId;
}
