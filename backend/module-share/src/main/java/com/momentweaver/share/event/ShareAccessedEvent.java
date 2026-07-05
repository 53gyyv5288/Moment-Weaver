package com.momentweaver.share.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分享链接被成功访问事件（公开端 /verify 或 /access 通过后）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShareAccessedEvent {
    private Long shareId;
    private Long ownerId;        // share 创建者 userId
    private String ip;
    private String userAgent;
    private String accessType;   // "preview" | "full" | "verify"
}
