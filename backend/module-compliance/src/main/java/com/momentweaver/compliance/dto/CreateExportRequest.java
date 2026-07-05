package com.momentweaver.compliance.dto;

import lombok.Data;

/**
 * 创建导出请求 (M5-B.1)。
 */
@Data
public class CreateExportRequest {
    /** all | project | subject */
    private String scope;
    private String scopeTargetId;
}
