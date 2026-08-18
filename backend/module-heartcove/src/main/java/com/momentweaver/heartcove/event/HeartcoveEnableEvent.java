package com.momentweaver.heartcove.event;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 心声信箱 enable 成功后的事件（M14+）。
 *
 * <p>Spring 事件模式：{@code HeartcoveConsentService.enable} 提交主事务后
 * 通过 {@code ApplicationEventPublisher} 发送本事件；
 * {@code HeartcovePersonaGenerator} 监听 + {@code @Async} 异步调 AI 生成
 * persona_summary 并写回 {@code subject.heartcove_persona_summary}。</p>
 *
 * <p>设计要点：发事件不依赖事务边界。即便主事务回滚，AI 调用也是只读 MongoDB
 * 然后写一张不会被回滚影响的行（subject.heartcove_persona_summary 字段）。
 * 但实际我们会在事务提交后才发事件，避免脏读。</p>
 */
@Data
@AllArgsConstructor
public class HeartcoveEnableEvent {
    /** 授权人 userId（发启用请求的人） */
    private final Long grantorUserId;
    private final Long subjectId;
}
