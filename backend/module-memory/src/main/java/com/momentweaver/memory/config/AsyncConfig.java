package com.momentweaver.memory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 模块内的异步任务配置。
 * 当前唯一用途：采访 close 后异步生成摘要（避免阻塞 UI）。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "summaryExecutor")
    public Executor summaryExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(16);
        exec.setThreadNamePrefix("ai-summary-");
        exec.setKeepAliveSeconds(60);
        // 摘要任务不需要「丢弃旧任务」语义；满队列时 caller 跑（让 UI 拿到错）
        exec.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        return exec;
    }
}