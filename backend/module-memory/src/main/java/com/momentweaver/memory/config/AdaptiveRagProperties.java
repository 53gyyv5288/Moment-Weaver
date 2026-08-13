package com.momentweaver.memory.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Adaptive RAG 配置（M9+ Phase 1）。
 *
 * <p>对应 application.yml 的 {@code moment.memory.adaptive-rag.*}，与 STM
 * 配置同级（不放在 {@code moment.memory.stm.*} 下，语义上独立：与 RAG 强相关）。
 *
 * <p><b>前缀绑定</b>：本类 prefix = {@code moment.memory.adaptive-rag}，
 * 与 yml 路径完全一致；构造期由 WebClientConfig 的
 * {@code @EnableConfigurationProperties(...)} 注册为 Spring Bean，
 * 通过 Lombok {@code @Data} 字段 getter 让 AdaptiveRagDecider 注入。
 *
 * <p>为什么不放进 {@link MemoryProperties}：
 * <ol>
 *   <li>MemoryProperties 自己的 prefix = {@code moment.memory.stm}，yml 的
 *       adaptive-rag 节点在 {@code moment.memory.adaptive-rag}，不在 STM 下，
 *       放进内部类也得改 MemoryProperties 的 prefix（动 STM 所有字段名映射）。</li>
 *   <li>单一职责：Adaptive RAG 是 RAG 模块的边线，与 STM 是两个独立子模块，
 *       各开一个 properties 类更清晰，未来加 strategy-planner 也照样再做一份。</li>
 * </ol>
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@link #enabled} —— 总开关；false 等价于改造前行为（每次都调 RAG）</li>
 *   <li>{@link #deciderTimeoutMs} —— WebClient 调用 /api/v1/decide/retrieval 的硬超时</li>
 *   <li>{@link #minConfidence} —— 当前 Java 端尚未应用，仅作为观测字段；后续可加入
 *       「低置信度时保守地选择 retrieve」逻辑</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "moment.memory.adaptive-rag")
public class AdaptiveRagProperties {

    /**
     * 总开关。false → 不调 LLM，永远走默认 retrieve 行为（兼容现状）。
     * 默认值 false 是为了「改造瞬间行为不变」，上线时改 yaml 开启。
     */
    private boolean enabled = false;

    /**
     * decider 端点超时（ms）。需要略小于 aiWebClient.read-timeout，
     * 默认 500ms 留出对线性的优化空间。
     */
    private int deciderTimeoutMs = 500;

    /**
     * 决策置信度阈值。当前 Java 端不实际应用，仅日志记录。
     * 后续可作为「保守 vs 激进」策略的开关。
     */
    private double minConfidence = 0.7;
}
