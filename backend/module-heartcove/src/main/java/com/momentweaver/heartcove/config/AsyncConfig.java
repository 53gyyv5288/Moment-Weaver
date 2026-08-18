package com.momentweaver.heartcove.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 心声信箱模块异步任务配置（M14+）。
 *
 * <p>当前唯一用途：{@code HeartcoveConsentService.enable} 提交主事务后，
 * 异步调 AI 生成 subject 的 persona_summary 并写回数据库。
 * 独立线程池，避免阻塞 enable 接口响应用户。</p>
 */
@Configuration("heartcoveAsyncConfig")
@EnableAsync
public class AsyncConfig {

    @Bean(name = "heartcovePersonaExecutor")
    public Executor heartcovePersonaExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(2);
        exec.setQueueCapacity(8);
        exec.setThreadNamePrefix("hc-persona-");
        exec.setKeepAliveSeconds(120);
        // persona 生成任务不能丢:满了 caller 跑,确保不漏摘要
        exec.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        return exec;
    }
}
