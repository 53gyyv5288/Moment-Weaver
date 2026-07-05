package com.momentweaver.notification.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 启用 @Async，让 NotificationListener 异步落库。
 */
@Configuration("notificationAsyncConfig")
@EnableAsync
public class AsyncConfig {
}
