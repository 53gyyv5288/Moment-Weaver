package com.momentweaver.notification.event;

import com.momentweaver.common.event.NotificationRequest;
import com.momentweaver.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 监听 NotificationRequest 事件，落库为通知。
 * 异步执行，避免阻塞业务主链路。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationListener {

    private final NotificationService service;

    @Async
    @EventListener
    public void onEvent(NotificationRequest req) {
        if (req == null) return;
        try {
            service.persist(
                req.getUserId(),
                req.getType(),
                req.getTitle(),
                req.getBody(),
                req.getRefId(),
                req.getDeepLink(),
                req.getMetadata()
            );
        } catch (Exception e) {
            // 通知落库失败不应影响业务主链路；只记录
            log.error("notification.persist.failed: type={} userId={}", req.getType(), req.getUserId(), e);
        }
    }
}
