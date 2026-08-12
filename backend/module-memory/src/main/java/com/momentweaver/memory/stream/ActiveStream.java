package com.momentweaver.memory.stream;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 当前正在进行的 SSE 流（注册表条目）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@code lock}：用于协调 LLM done 事件与 RAG evidence 事件的写入顺序（plan §3 SSE 顺序保证）。
 *       Java MM 在 synchronized 释放-获取上建立 happens-before，保证
 *       {@code streamCompleted=true} happens-before done flush 到 wire，
 *       反之 RAG 线程看到 {@code streamCompleted=true} 之前一定能看到 done 之后的 wire。</li>
 *   <li>{@code streamCompleted}：流已发完 done 事件并准备关闭 emitter 的标志。
 *       RAG 回调看到 true 时把 evidence 写入 RagCacheService，下轮再用。</li>
 * </ul>
 *
 * @param sid       会话 ID（主键）
 * @param emitter   当前 SSE 发射器
 * @param lock      顺序保证的共享锁
 * @param streamCompleted 流是否已结束（done 事件已发出）
 * @param createdAt 条目创建时间（TTL 用）
 */
@Getter
@RequiredArgsConstructor
public class ActiveStream {

    private final String sid;
    private final SseEmitter emitter;
    private final Object lock = new Object();
    private final AtomicBoolean streamCompleted = new AtomicBoolean(false);
    private final Instant createdAt = Instant.now();
}