package com.momentweaver.compliance.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步 + 定时任务配置 (M5-B)。
 *
 * <p>异步：数据导出 (exportExecutor) + 通知落库 (复用 module-notification 内部 @Async)。
 * 定时：每天凌晨 3 点扫过期 deletion_request 物理清理。
 */
@Configuration("complianceAsyncConfig")
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean("exportExecutor")
    public Executor exportExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(20);
        exec.setThreadNamePrefix("export-");
        exec.initialize();
        return exec;
    }
}
