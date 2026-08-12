package com.momentweaver.memory.stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级 SseEmitter 注册表。
 *
 * <p>用途：让 background 线程（RAG evidence 回调）能拿到当前正在进行中的 SSE emitter，
 * 把 evidence 作为独立事件推到前端，而不必阻塞 LLM 流。
 *
 * <p>线程安全：内部 ConcurrentHashMap；单条目的写入由 {@link ActiveStream#getLock()} 协调。
 *
 * <p>生命周期：
 * <ul>
 *   <li>InterviewController.send() 在创建 emitter 后立即 {@code register(sid, emitter)}</li>
 *   <li>所有 emitter 关闭路径（onCompletion / onTimeout / 流完成 / 错误）必须 {@code unregister(sid)}</li>
 *   <li>60s 周期 TTL 清理兜底（防 unregister 漏调用导致内存泄漏）</li>
 * </ul>
 *
 * <p>顺序保证（plan §3）：
 * <pre>
 *   streamMessage 端：
 *     synchronized (active.lock) {
 *         active.getStreamCompleted().set(true);
 *         emitter.send(event("done"));
 *     }
 *
 *   RAG 回调端：
 *     synchronized (active.lock) {
 *         if (active.getStreamCompleted().get()) → cache;
 *         else → emitter.send(event("evidence"));
 *     }
 * </pre>
 */
@Slf4j
@Component
public class StreamingSessionRegistry {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final Map<String, ActiveStream> sessions = new ConcurrentHashMap<>();

    /**
     * 注册一个新流。若同 sid 已有 emitter，complete 旧的再替换（防御并发发消息）。
     */
    public void register(String sid, SseEmitter emitter) {
        ActiveStream prev = sessions.put(sid, new ActiveStream(sid, emitter));
        if (prev != null && prev.getEmitter() != emitter) {
            try {
                prev.getEmitter().complete();
                log.warn("StreamingSessionRegistry: replaced stale emitter for sid={}", sid);
            } catch (Exception ignored) {
                // 旧 emitter 可能已关闭，吞掉
            }
        }
        log.debug("StreamingSessionRegistry: register sid={} (active={})", sid, sessions.size());
    }

    /** 注销（emitter 关闭时必调）。 */
    public void unregister(String sid) {
        ActiveStream removed = sessions.remove(sid);
        if (removed != null) {
            log.debug("StreamingSessionRegistry: unregister sid={} (active={})", sid, sessions.size());
        }
    }

    /** 当前是否存在该 sid 的活动流。 */
    public boolean isActive(String sid) {
        return sessions.containsKey(sid);
    }

    /** 取活动流条目（只读 view；外部修改 streamCompleted/调用 emitter 都需自行 synchronized）。 */
    public ActiveStream get(String sid) {
        return sessions.get(sid);
    }

    /**
     * 标记流已完成（done 事件已发）。供 LLM done 线程调用。
     * <p>调用方应在持有 {@code active.lock} 的 synchronized 块内调用。
     */
    public void markStreamCompleted(String sid) {
        ActiveStream active = sessions.get(sid);
        if (active != null) {
            active.getStreamCompleted().set(true);
        }
    }

    /**
     * 60s 周期：清理超 TTL 未注销的条目（防御性）。
     * <p>漏 unregister 通常意味着 emitter.onCompletion 没触发（如客户端断网），不会泄漏太久。
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void evictExpired() {
        var cutoff = java.time.Instant.now().minus(TTL);
        int removed = 0;
        for (var it = sessions.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue().getCreatedAt().isBefore(cutoff)) {
                it.remove();
                try {
                    e.getValue().getEmitter().complete();
                } catch (Exception ignored) {}
                removed++;
            }
        }
        if (removed > 0) {
            log.info("StreamingSessionRegistry: TTL evicted {} stale entries (remaining={})",
                removed, sessions.size());
        }
    }

    /** 测试/调试用。 */
    public int activeCount() {
        return sessions.size();
    }

    /** RAG 回调使用：在 synchronized 块内调，尝试向当前 emitter 推 evidence；返回是否成功写入。 */
    public boolean tryEmitEvidence(String sid, String eventName, String json) {
        ActiveStream active = sessions.get(sid);
        if (active == null) return false;
        try {
            synchronized (active.getLock()) {
                if (active.getStreamCompleted().get()) {
                    return false;  // 流已结束，调用方应 cache
                }
                active.getEmitter().send(SseEmitter.event().name(eventName).data(json));
                return true;
            }
        } catch (IOException e) {
            log.debug("tryEmitEvidence sid={} IOException (likely client gone): {}", sid, e.toString());
            return false;
        } catch (IllegalStateException e) {
            log.debug("tryEmitEvidence sid={} emitter already complete: {}", sid, e.toString());
            return false;
        }
    }
}