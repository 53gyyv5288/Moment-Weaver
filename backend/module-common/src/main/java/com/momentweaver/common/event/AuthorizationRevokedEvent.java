package com.momentweaver.common.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 授权撤销事件 (M5-B.2)。
 *
 * <p>由 AuthorizationService.revoke() 发布。
 * 监听方：
 * <ul>
 *   <li>module-timeline：把引用该 subject 的 draft 章节标 provenance=system，追加 withdrawnSubjectIds</li>
 *   <li>module-share：把引用该 subject 的 share_link 标 revoked=true</li>
 *   <li>module-notification：发 AUTHORIZATION_REVOKED 通知给 project owner</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthorizationRevokedEvent {
    /** 触发动作的 userId（project owner） */
    private Long operatorId;
    /** 被撤销的 authorization id */
    private Long authorizationId;
    /** 项目 id */
    private Long projectId;
    /** 收件人（project owner，用于发通知） */
    private Long ownerId;
    /** 被撤销的 subject id（字符串，兼容 MongoDB） */
    private String subjectId;
    /** 被采访者显示名（通知文案用） */
    private String subjectDisplayName;
    /** 撤回原因/补充说明（可选） */
    private String reason;
}
