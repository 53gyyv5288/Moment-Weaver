package com.momentweaver.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 模块配置。
 *
 * <pre>
 * moment.rag.ai-base-url = http://localhost:8000   # FastAPI RAG 路由
 * moment.rag.connect-timeout-ms = 2000
 * moment.rag.read-timeout-ms = 6000                 # 软超时比 AI 服务还小一点
 * moment.rag.search-soft-timeout-ms = 6000          # 采访流非阻塞注入：6s 软超时（流内中途推 evidence）
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "moment.rag")
public class RagProperties {
    /** FastAPI RAG 路由 base URL（同 aiWebClient 的 AI 服务 base URL）。 */
    private String aiBaseUrl = "http://localhost:8000";

    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 6000;

    /** 采访流非阻塞 RAG 注入软超时（plan RAG 流式注入：超时则缓存到下轮，不阻塞 LLM 首字）。
 * 6s 预算：query rewrite 0.6s + embed 1s + milvus 1s + reranker 2.5s + 余量 0.9s。
 * reranker 自身超时 2.5s（见 ai/app/config.py:reranker_timeout_s）失败时降级到 Milvus 排序。 */
    private int searchSoftTimeoutMs = 6000;

    /** 关闭则 RAG 路由全部走降级（不调 AI 服务）。 */
    private boolean enabled = true;
}