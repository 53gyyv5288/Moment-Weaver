package com.momentweaver.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * RAG 专用异步线程池：ingest 监听 / 软超时 RAG 注入。
 * <p>与主业务的 @Async("summaryExecutor") 隔离，避免互相影响。
 */
@Configuration
@EnableAsync
public class RagAsyncConfig {

    @Bean("ragIngestExecutor")
    public Executor ragIngestExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(200);
        ex.setThreadNamePrefix("rag-ingest-");
        ex.setDaemon(true);
        ex.initialize();
        return ex;
    }
}