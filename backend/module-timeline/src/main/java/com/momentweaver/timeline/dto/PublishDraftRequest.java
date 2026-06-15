package com.momentweaver.timeline.dto;

import lombok.Data;

/**
 * 发布成稿请求。当前为空预留（保留给后续自定义发布参数，如通知、可见性）。
 */
@Data
public class PublishDraftRequest {
    /** 预留：发布时可改的标题（不传则用 draft 原 title） */
    private String title;
    /** 预留：发布备注（写入 timeline 事件） */
    private String note;
}
