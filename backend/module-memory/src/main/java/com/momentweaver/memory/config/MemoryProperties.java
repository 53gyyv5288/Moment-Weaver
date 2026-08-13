package com.momentweaver.memory.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 短期记忆（STM）配置。
 *
 * <p>对应 application.yml 的 {@code moment.memory.stm.*}。
 * <p>Redis key 命名空间：stm:session:{sid}:{recent|summary|meta}
 *
 * <p>关键阈值：
 * <ul>
 *   <li>{@link #recentTurnsKeep} K —— recent 列表上限</li>
 *   <li>{@link #compressTurnThreshold} —— 轮数触发阈值</li>
 *   <li>{@link #compressTokenThreshold} —— token 触发阈值</li>
 *   <li>{@link #summaryMaxOutputTokens} —— 摘要 LLM 输出 cap</li>
 *   <li>{@link #redisTtlMinutes} —— Redis key TTL</li>
 *   <li>{@link #enabled} —— 总开关（关了就降级到 Mongo 全量）</li>
 *   <li>{@link #maxConsecutiveEmptySummary} —— 空 summary 死循环防御阈值</li>
 *   <li>{@link #summaryHardLimitChars} —— summary 二次压缩触发阈值（字符）</li>
 *   <li>{@link #condenseMaxOutputTokens} —— 二次压缩 LLM 输出 cap</li>
 *   <li>{@link #compressLockTimeoutMs} —— 同 session 并发压缩去重锁等待上限</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "moment.memory.stm")
public class MemoryProperties {

    /** recent 列表最多保留 K 条 messages；触发压缩时取最老 evictN 条生成摘要。 */
    private int recentTurnsKeep = 20;

    /** 轮数触发阈值：recent.size() >= 该值触发异步压缩。 */
    private int compressTurnThreshold = 20;

    /** token 触发阈值：recent + summary 估算 tokens 之和 >= 该值触发异步压缩。 */
    private int compressTokenThreshold = 12000;

    /** 摘要 LLM 输出 cap（传给 Python 端 max_tokens）。 */
    private int summaryMaxOutputTokens = 800;

    /** Redis key TTL（分钟）；每次访问续期。 */
    private int redisTtlMinutes = 120;

    /** 总开关：false → 全部降级到 Mongo 全量（不读写 Redis、不调摘要 LLM）。 */
    private boolean enabled = true;

    /**
     * 空 summary 死循环防御：连续 N 次 LLM 返回空 summary 后，
     * 跳过 K/2 轮再触发（Redis meta 计数），避免反复调 LLM 浪费算力。
     * 取值建议 ≥ 2。
     */
    private int maxConsecutiveEmptySummary = 3;

    /**
     * summary 二次压缩触发阈值（字符数）。
     * 当 summary 长度 ≥ 该值且近期有过压缩 → 触发「压 summary 自身」异步任务。
     * 留余量给 LLM 输出 cap（默认 800 字） → 默认 1500 触发二次压缩。
     */
    private int summaryHardLimitChars = 1500;

    /** 二次压缩 LLM 输出 cap（比常规摘要略小，因为只压 summary 自身）。 */
    private int condenseMaxOutputTokens = 600;

    /**
     * 同 session 并发压缩去重：尝试获取 sid 锁的最大等待时间（毫秒）。
     * 超过则跳过本次压缩（下轮再触发）；0 = 不等待。
     */
    private long compressLockTimeoutMs = 0;
}