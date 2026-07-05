package com.momentweaver.share.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 分享链接创建事件。
 *
 * <p>由 ShareService.create() 发布。
 * 监听者（如 M5-B 审计日志模块）按需消费。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShareCreatedEvent {
    private Long userId;
    private Long shareId;
    private Long projectId;
    private String draftId;
    private String scope;
    private Map<String, Object> metadata;
}
