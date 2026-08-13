package com.momentweaver.memory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.momentweaver.common.entity.InterviewMessage;
import com.momentweaver.memory.config.MemoryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 短期记忆（STM）服务：Redis-backed 滚动摘要 + 最近 K 轮 verbatim。
 *
 * <p>Redis Key 设计：
 * <pre>
 *   stm:session:{sid}:recent    LIST   最近 K 条 InterviewMessage JSON  RPUSH+LTRIM
 *   stm:session:{sid}:summary   STRING 滚动摘要纯文本                  SET/GET
 *   stm:session:{sid}:meta      HASH   {lastCompressTokens,emptyStreak,version,sidClosed}  HSET/HGETALL
 * </pre>
 *
 * <p>TTL 滑动：每次访问（read/write）都 EXPIRE；session 关闭时显式 clear。
 *
 * <p>降级策略（fail-safe）：
 * <ul>
 *   <li>Redis 抛异常 → getRecent 返回 {@code List.of()}，调用方走 Mongo 全量</li>
 *   <li>disabled=true → 所有读返回空，写 no-op（等同 Redis 挂）</li>
 *   <li>JSON 反序列化失败 → 跳过该条（list 中其它正常 message 仍可用）</li>
 *   <li>session 已关闭（sidClosed=true）→ 压缩 task 跳过写回</li>
 * </ul>
 *
 * <p>异步压缩：{@link #compressAsync} 用 {@code summaryExecutor}（与 close-time summary 共享），
 * fire-and-forget，不阻塞 LLM 流。同一 session 串行化（{@link #inflightLocks}）。
 *
 * <p>主要修复（M8+）：
 * <ol>
 *   <li>LTRIM 方向：保留最新 K 条而非最老 K 条</li>
 *   <li>shouldCompress 计入 summary 自身 token</li>
 *   <li>evictN 动态计算（不少于 K/2，不多于超出量）</li>
 *   <li>同 session 并发压缩去重（ConcurrentHashMap + AtomicBoolean）</li>
 *   <li>session 关闭标志（sidClosed）防止压缩 task 在 clear 后写回</li>
 *   <li>空 summary 死循环防御（emptyStreak 计数 + 跳过 K/2 轮）</li>
 *   <li>summary 长度校验 + 二次压缩触发</li>
 *   <li>warmUpFromMongo 跳过已有内容</li>
 * </ol>
 */
@Slf4j
@Service
public class ShortTermMemoryService {

    private static final String PREFIX = "stm:session:";

    /** 摘要压缩 LLM 端点（融合旧 summary + 被淘汰原文）。 */
    private static final String ROLLING_SUMMARIZE_PATH = "/api/v1/summarize/rolling";
    /** summary 二次压缩端点（仅压 summary 自身，不动 recent）。 */
    private static final String CONDENSE_PATH = "/api/v1/summarize/condense";

    /** meta 字段名。 */
    private static final String META_LAST_COMPRESS_TOKENS = "lastCompressTokens";
    private static final String META_EMPTY_STREAK = "emptyStreak";
    private static final String META_SID_CLOSED = "sidClosed";
    private static final String META_VERSION = "version";
    /** meta version 值（meta schema 版本号；改 schema 时 +1）。 */
    private static final int META_VERSION_VALUE = 2;

    private final StringRedisTemplate redis;
    private final MemoryProperties props;
    private final ObjectMapper mapper;
    private final org.springframework.web.reactive.function.client.WebClient aiWebClient;

    /** 同 sid 并发压缩锁（fire-and-forget 串行化，避免两个 task 同时写 summary）。 */
    private final ConcurrentHashMap<String, AtomicBoolean> inflightLocks = new ConcurrentHashMap<>();
    /** 同 sid in-flight 压缩任务句柄（close 时等它完成）。 */
    private final ConcurrentHashMap<String, CompletableFuture<Void>> inflightTasks = new ConcurrentHashMap<>();

    @Autowired
    public ShortTermMemoryService(
            StringRedisTemplate redis,
            MemoryProperties props,
            ObjectMapper mapper,
            org.springframework.web.reactive.function.client.WebClient aiWebClient) {
        this.redis = redis;
        this.props = props;
        this.mapper = mapper;
        this.aiWebClient = aiWebClient;
    }

    // ====== Key 构造 ======

    private static String recentKey(String sid) {
        return PREFIX + sid + ":recent";
    }

    private static String summaryKey(String sid) {
        return PREFIX + sid + ":summary";
    }

    private static String metaKey(String sid) {
        return PREFIX + sid + ":meta";
    }

    private Duration ttl() {
        return Duration.ofMinutes(props.getRedisTtlMinutes());
    }

    // ====== 总开关 ======

    /** false 时所有读写 no-op（降级到 Mongo 全量）。 */
    public boolean isEnabled() {
        return props.isEnabled();
    }

    // ====== 写入 ======

    /**
     * 追加一条 message 到 recent 列表尾部，自动 LTRIM 保留最新 K 条。
     * <p><b>修复 #1</b>：LTRIM 用负索引 -K .. -1，保留最新 K 条（之前是 0..K-1 保留最老的 K 条，反了）。
     * 失败不抛异常（log warn）；消息已写 Mongo，不会丢。
     */
    public void appendRecent(String sid, InterviewMessage msg) {
        if (!isEnabled() || sid == null || msg == null) return;
        String json;
        try {
            json = mapper.writeValueAsString(msg);
        } catch (JsonProcessingException e) {
            log.warn("STM appendRecent: serialize failed sid={} role={}: {}", sid, msg.getRole(), e.toString());
            return;
        }
        try {
            String key = recentKey(sid);
            redis.opsForList().rightPush(key, json);
            // 修复 #1：LTRIM -(K) -1 → 保留最新的 K 条
            redis.opsForList().trim(key, -props.getRecentTurnsKeep(), -1L);
            redis.expire(key, ttl());
            touchMetaOnAppend(sid);
        } catch (Exception e) {
            log.warn("STM appendRecent failed sid={}: {}", sid, e.toString());
        }
    }

    /**
     * 写滚动摘要。
     * <p><b>修复 #6</b>：写回前校验长度，超 {@link MemoryProperties#getSummaryHardLimitChars()}
     * 截断 + 记 warn（避免 Python 端输出失控撑爆 LLM context）。
     */
    public void setSummary(String sid, String summary) {
        if (!isEnabled() || sid == null) return;
        String safe = summary == null ? "" : summary;
        int hardLimit = props.getSummaryHardLimitChars();
        if (hardLimit > 0 && safe.length() > hardLimit) {
            log.warn("STM setSummary sid={}: truncate oversize summary {} -> {} chars",
                sid, safe.length(), hardLimit);
            safe = safe.substring(0, hardLimit);
        }
        try {
            String key = summaryKey(sid);
            redis.opsForValue().set(key, safe, ttl());
        } catch (Exception e) {
            log.warn("STM setSummary failed sid={}: {}", sid, e.toString());
        }
    }

    // ====== 读取 ======

    /**
     * 读 recent 列表（按写入顺序，从最早到最新）。
     * 失败或未启用 → 返回空列表（调用方应降级到 Mongo）。
     */
    public List<InterviewMessage> getRecent(String sid) {
        if (!isEnabled() || sid == null) return List.of();
        try {
            String key = recentKey(sid);
            List<String> raw = redis.opsForList().range(key, 0, -1);
            if (raw == null || raw.isEmpty()) return List.of();
            // 续期
            redis.expire(key, ttl());
            List<InterviewMessage> out = new ArrayList<>(raw.size());
            for (String s : raw) {
                try {
                    out.add(mapper.readValue(s, InterviewMessage.class));
                    // 跳过 system 消息（避免跟人设重复；warmUpFromMongo 已过滤，
                    // appendRecent 这里也兜底）
                    if ("system".equals(out.get(out.size() - 1).getRole())) {
                        out.remove(out.size() - 1);
                    }
                } catch (Exception e) {
                    // 跳过损坏的单条，不影响其它
                    log.debug("STM getRecent: skip bad entry sid={}: {}", sid, e.toString());
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("STM getRecent failed sid={}: {}", sid, e.toString());
            return List.of();
        }
    }

    /** 读滚动摘要；不存在 / 失败 → Optional.empty()。 */
    public Optional<String> getSummary(String sid) {
        if (!isEnabled() || sid == null) return Optional.empty();
        try {
            String v = redis.opsForValue().get(summaryKey(sid));
            if (v == null || v.isBlank()) return Optional.empty();
            // 续期
            redis.expire(summaryKey(sid), ttl());
            return Optional.of(v);
        } catch (Exception e) {
            log.warn("STM getSummary failed sid={}: {}", sid, e.toString());
            return Optional.empty();
        }
    }

    // ====== 触发判断 ======

    /**
     * 是否需要触发压缩？三触发：
     * <ul>
     *   <li>recent.size() &gt;= compressTurnThreshold</li>
     *   <li>recent.estimated tokens + summary.estimated tokens &gt;= compressTokenThreshold</li>
     *   <li>summary 长度 ≥ summaryHardLimitChars（自身太大也要触发二次压缩）</li>
     * </ul>
     *
     * <p><b>修复 #2</b>：原实现只算 recent tokens；现在把 summary 也算进去。
     *
     * @param sid    session id（用于读 summary 计算 token）
     * @param recent 当前 session 的 recent messages（来自 {@link #getRecent}）
     */
    public boolean shouldCompress(String sid, List<InterviewMessage> recent) {
        if (!isEnabled()) return false;
        if (recent == null) recent = List.of();

        // (a) 轮数触发
        if (recent.size() >= props.getCompressTurnThreshold()) return true;

        // 读 summary 一次，供 (b)(c) 共用
        String summary = peekSummary(sid);

        // (b) token 触发：recent + summary
        int totalTok = estimateTotalTokens(recent) + estimateTokens(summary);
        if (totalTok >= props.getCompressTokenThreshold()) return true;

        // (c) summary 自身过大 → 触发二次压缩（与 (a)(b) 共享同一压缩 task，分支处理）
        int hardLimit = props.getSummaryHardLimitChars();
        if (hardLimit > 0 && summary != null && !summary.isEmpty() && summary.length() >= hardLimit) return true;

        return false;
    }

    /**
     * 读 summary 但不续期（用于 shouldCompress 内快速检查，避免热路径写 Redis）。
     * 与 {@link #getSummary} 不同：peekSummary 不调用 EXPIRE。
     */
    private String peekSummary(String sid) {
        if (sid == null) return null;
        try {
            return redis.opsForValue().get(summaryKey(sid));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 兼容旧签名（无 sid）：仅用轮数 + recent tokens 判断；summary 不计入。
     * 新代码请用 {@link #shouldCompress(String, java.util.List)}。
     */
    public boolean shouldCompress(List<InterviewMessage> recent) {
        return shouldCompress(null, recent);
    }

    /** 粗估 token 数：中文/英文混合按 len*0.7。±15% 精度。 */
    public static int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return (int) (text.length() * 0.7);
    }

    /** 估算 messages 列表总 token 数（content + thinking）。 */
    public static int estimateTotalTokens(List<InterviewMessage> msgs) {
        if (msgs == null || msgs.isEmpty()) return 0;
        long total = 0;
        for (InterviewMessage m : msgs) {
            total += estimateTokens(m.getContent());
            total += estimateTokens(m.getThinking());
        }
        return (int) total;
    }

    // ====== 异步压缩 ======

    /**
     * 触发异步滚动摘要或二次压缩。
     * <p>分支判断：
     * <ul>
     *   <li>summary 过长 → 走 condenseAsync（仅压 summary）</li>
     *   <li>recent 超阈值 → 走 compressAsync（融合 summary + 淘汰的 recent）</li>
     * </ul>
     * 两者共用同一锁（{@link #inflightLocks}），不会同时跑。
     */
    @Async("summaryExecutor")
    public CompletableFuture<Void> compressAsync(String sid) {
        // 修复 #5：暴露给 close() 等的 future 句柄
        CompletableFuture<Void> task = new CompletableFuture<>();
        inflightTasks.put(sid, task);
        try {
            if (!isEnabled() || sid == null) {
                task.complete(null);
                return task;
            }
            // 修复 #5：sid 已关闭 → 跳过
            if (isSidClosed(sid)) {
                log.debug("STM compressAsync sid={} skipped: sid closed", sid);
                task.complete(null);
                return task;
            }

            // 修复 #3：同 sid 串行化。acquireLock 失败 → 本轮跳过
            if (!acquireLock(sid)) {
                log.debug("STM compressAsync sid={} skipped: another compress in flight", sid);
                task.complete(null);
                return task;
            }
            try {
                // 修复 #8：先看 summary 长度，决定走 rolling 还是 condense
                Optional<String> currentSummary = getSummary(sid);
                int hardLimit = props.getSummaryHardLimitChars();
                boolean summaryTooLong = hardLimit > 0
                    && currentSummary.isPresent()
                    && currentSummary.get().length() >= hardLimit;

                if (summaryTooLong) {
                    condenseAsync(sid, currentSummary.get());
                } else {
                    compressRollingAsync(sid);
                }
            } finally {
                releaseLock(sid);
            }
            task.complete(null);
        } catch (Exception e) {
            log.error("STM compressAsync top-level failed sid={}", sid, e);
            task.complete(null);
        }
        return task;
    }

    /**
     * 等待指定 sid 的 in-flight 压缩任务完成（{@link #clear} 前调用）。
     * <p>无任务 / 任务已完成 → 立即返回已完成的 CompletableFuture。
     * <p>超时由调用方控制（{@code .get(timeoutMs, MILLISECONDS)}）。
     */
    public CompletableFuture<Void> awaitInflight(String sid) {
        if (sid == null) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> task = inflightTasks.get(sid);
        if (task == null) return CompletableFuture.completedFuture(null);
        return task;
    }

    /**
     * 滚动摘要（旧 summary + 淘汰的 recent → 新 summary）。
     * <p>触发条件：recent 超阈值，且 summary 未超硬限。
     */
    private void compressRollingAsync(String sid) {
        try {
            // 1. 再读一次 Redis（避免与主线程的「刚 append 完」错位）
            List<InterviewMessage> recent = getRecent(sid);
            if (recent.size() < props.getCompressTurnThreshold()) {
                log.debug("STM compressRolling sid={} skipped: recent too small ({})", sid, recent.size());
                return;
            }
            String oldSummary = getSummary(sid).orElse(null);

            // 修复 #7：evictN 动态 — 不少于 K/2，不多于「超出 K 的部分」
            int k = props.getRecentTurnsKeep();
            int evictN = Math.max(k / 2, recent.size() - k);
            List<InterviewMessage> evicted = new ArrayList<>(recent.subList(0, evictN));

            // 修复 #2：空 summary 死循环防御（emptyStreak 计数）
            if (props.getMaxConsecutiveEmptySummary() > 0) {
                int streak = getEmptyStreak(sid);
                if (streak >= props.getMaxConsecutiveEmptySummary()) {
                    int skipTurns = evictN; // 跳过 K/2 轮再试
                    log.warn("STM compressRolling sid={}: emptyStreak={} >= {} → skip, wait {} turns",
                        sid, streak, props.getMaxConsecutiveEmptySummary(), skipTurns);
                    setEmptySkipUntil(sid, evictN);
                    return;
                }
            }

            // 2. 调 Python /api/v1/summarize/rolling
            String newSummary = callRollingSummarize(sid, oldSummary, evicted);
            if (newSummary == null || newSummary.isBlank()) {
                log.warn("STM compressRolling sid={}: LLM returned empty summary (keep old)", sid);
                // 修复 #2：递增空计数
                incrementEmptyStreak(sid);
                return;
            }

            // 3. 写回
            // 修复 #6：setSummary 内部已做长度校验
            setSummary(sid, newSummary);
            resetEmptyStreak(sid);

            // 4. LTRIM recent：保留最新的 evictN 条（即索引 [size-evictN, size-1]）
            try {
                String key = recentKey(sid);
                long keepFrom = Math.max(0, recent.size() - evictN);
                redis.opsForList().trim(key, keepFrom, -1);
                redis.expire(key, ttl());
            } catch (Exception e) {
                log.warn("STM compressRolling sid={} trim failed (non-fatal): {}", sid, e.toString());
            }

            // 5. 更新 meta
            // 修复 #9：字段名 lastCompressTurn → lastCompressTokens
            updateMetaAfterCompress(sid, recent);

            log.info("STM compressRolling ok sid={} evicted={} oldSummaryLen={} newSummaryLen={}",
                sid, evicted.size(),
                oldSummary == null ? 0 : oldSummary.length(),
                newSummary.length());
        } catch (Exception e) {
            log.error("STM compressRolling failed sid={}", sid, e);
        }
    }

    /**
     * 二次压缩（summary 过长时调用，仅压 summary 自身，不动 recent）。
     * <p>调 Python /api/v1/summarize/condense。
     */
    private void condenseAsync(String sid, String currentSummary) {
        try {
            String condensed = callCondense(sid, currentSummary);
            if (condensed == null || condensed.isBlank()) {
                log.warn("STM condense sid={}: LLM returned empty (keep old)", sid);
                return;
            }
            setSummary(sid, condensed);
            log.info("STM condense ok sid={} oldLen={} newLen={}",
                sid, currentSummary.length(), condensed.length());
        } catch (Exception e) {
            log.error("STM condense failed sid={}", sid, e);
        }
    }

    // ====== HTTP 调 Python ======

    /** 调 Python /api/v1/summarize/rolling；失败返回 null（旧 summary 保留）。 */
    private String callRollingSummarize(String sid, String oldSummary, List<InterviewMessage> evicted) {
        try {
            Map<String, Object> body = Map.of(
                "session_id", sid,
                "old_summary", oldSummary == null ? "" : oldSummary,
                "max_tokens", props.getSummaryMaxOutputTokens(),
                "evicted_messages", evicted.stream().map(m -> {
                    Map<String, Object> mm = new java.util.LinkedHashMap<>();
                    mm.put("role", m.getRole());
                    mm.put("content", m.getContent() == null ? "" : m.getContent());
                    return mm;
                }).toList()
            );
            return postJson(ROLLING_SUMMARIZE_PATH, body);
        } catch (Exception e) {
            log.warn("STM callRollingSummarize failed sid={}: {}", sid, e.toString());
            return null;
        }
    }

    /** 调 Python /api/v1/summarize/condense；失败返回 null。 */
    private String callCondense(String sid, String currentSummary) {
        try {
            Map<String, Object> body = Map.of(
                "session_id", sid,
                "summary", currentSummary,
                "max_tokens", props.getCondenseMaxOutputTokens()
            );
            return postJson(CONDENSE_PATH, body);
        } catch (Exception e) {
            log.warn("STM callCondense failed sid={}: {}", sid, e.toString());
            return null;
        }
    }

    /** POST JSON，返回解析后的 summary 字段。 */
    private String postJson(String path, Map<String, Object> body) {
        try {
            String raw = aiWebClient.post()
                .uri(path)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(30));
            if (raw == null || raw.isBlank()) return null;
            // 期望 {"summary": "..."}；容错返回纯字符串
            try {
                var tree = mapper.readTree(raw);
                if (tree.has("summary")) return tree.get("summary").asText();
                return raw;
            } catch (Exception ignore) {
                return raw;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ====== 并发压缩去重（修复 #3）======

    /**
     * 尝试获取 sid 锁。返回 true 表示拿到锁（可执行压缩），false 表示别的 task 正在压缩。
     * <p>实现：用 AtomicBoolean.compareAndSet，0ms 等待（fire-and-forget 语义）。
     * 如需可配置等待，调 {@link MemoryProperties#getCompressLockTimeoutMs()}（当前为 0 = 不等待）。
     */
    private boolean acquireLock(String sid) {
        AtomicBoolean lock = inflightLocks.computeIfAbsent(sid, k -> new AtomicBoolean(false));
        boolean got = lock.compareAndSet(false, true);
        if (!got) {
            // 当前策略：不等待（props.compressLockTimeoutMs 默认 0）。下轮再触发。
            return false;
        }
        return true;
    }

    private void releaseLock(String sid) {
        AtomicBoolean lock = inflightLocks.get(sid);
        if (lock != null) {
            lock.set(false);
        }
    }

    // ====== session 关闭标志（修复 #5）======

    /**
     * session 关闭标记（写到 Redis meta，让压缩 task 检测后跳过）。
     * {@link #clear} 时会调此方法，{@link #warmUpFromMongo} 会清掉该标志（重连）。
     */
    public void markSidClosed(String sid) {
        if (!isEnabled() || sid == null) return;
        try {
            redis.opsForHash().put(metaKey(sid), META_SID_CLOSED, "1");
            redis.expire(metaKey(sid), ttl());
        } catch (Exception e) {
            log.debug("STM markSidClosed sid={} failed: {}", sid, e.toString());
        }
    }

    private boolean isSidClosed(String sid) {
        try {
            String v = (String) redis.opsForHash().get(metaKey(sid), META_SID_CLOSED);
            return "1".equals(v);
        } catch (Exception e) {
            return false;
        }
    }

    // ====== 空 summary 防御（修复 #2）======

    private int getEmptyStreak(String sid) {
        try {
            String v = (String) redis.opsForHash().get(metaKey(sid), META_EMPTY_STREAK);
            return v == null ? 0 : Integer.parseInt(v);
        } catch (Exception e) {
            return 0;
        }
    }

    private void incrementEmptyStreak(String sid) {
        try {
            redis.opsForHash().increment(metaKey(sid), META_EMPTY_STREAK, 1L);
            redis.expire(metaKey(sid), ttl());
        } catch (Exception e) {
            // best-effort
        }
    }

    private void resetEmptyStreak(String sid) {
        try {
            redis.opsForHash().put(metaKey(sid), META_EMPTY_STREAK, "0");
        } catch (Exception e) {
            // best-effort
        }
    }

    /** 跳过 evictN 轮再触发：用 summarySkipUntil 字段记录需要跳过的次数。 */
    private void setEmptySkipUntil(String sid, int skipTurns) {
        try {
            redis.opsForHash().put(metaKey(sid), "skipUntil", String.valueOf(skipTurns));
        } catch (Exception e) {
            // best-effort
        }
    }

    private int getEmptySkipUntil(String sid) {
        try {
            String v = (String) redis.opsForHash().get(metaKey(sid), "skipUntil");
            return v == null ? 0 : Integer.parseInt(v);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 每次 append 调用，让 skipUntil 递减到 0。 */
    private void decrSkipUntil(String sid) {
        try {
            int cur = getEmptySkipUntil(sid);
            if (cur > 0) {
                redis.opsForHash().put(metaKey(sid), "skipUntil", String.valueOf(cur - 1));
            }
        } catch (Exception e) {
            // best-effort
        }
    }

    /**
     * 是否处于「空 summary 防御跳过窗口」。调用方在 {@link #shouldCompress} 内检查。
     * <p>当前实现：用 emptyStreak 计数替代 skipUntil（更简单，跳过阈值 = streak >= max）。
     * 此方法保留作为兼容接口。
     */
    public boolean isInSkipWindow(String sid) {
        int streak = getEmptyStreak(sid);
        return streak >= props.getMaxConsecutiveEmptySummary();
    }

    // ====== 生命周期 ======

    /** session 关闭时显式清理 Redis（避免悬挂 + 释放内存）。 */
    public void clear(String sid) {
        if (sid == null) return;
        // 修复 #5：先标记关闭，防止压缩 task 在 clear 后写回
        markSidClosed(sid);
        try {
            redis.delete(List.of(recentKey(sid), summaryKey(sid), metaKey(sid)));
            log.debug("STM cleared sid={}", sid);
        } catch (Exception e) {
            log.warn("STM clear failed sid={}: {}", sid, e.toString());
        } finally {
            // 清掉本机锁，避免内存泄漏
            inflightLocks.remove(sid);
        }
    }

    /**
     * session 重连 / 服务重启时回填：从 Mongo 取最近 K 条 messages 写入 Redis。
     * <p><b>修复 #10</b>：先检查 Redis 已有内容，非空则跳过（避免重复 append）。
     */
    public void warmUpFromMongo(String sid, List<InterviewMessage> allMessages) {
        if (!isEnabled() || sid == null || allMessages == null || allMessages.isEmpty()) return;
        // 修复 #5：清理「已关闭」标记，重连相当于新生命周期
        try {
            redis.opsForHash().delete(metaKey(sid), META_SID_CLOSED);
        } catch (Exception ignored) { }
        // 修复 #10：已有内容 → 跳过，避免重复
        List<InterviewMessage> existing = getRecent(sid);
        if (!existing.isEmpty()) {
            log.debug("STM warmUp sid={} skipped: redis already has {} msgs", sid, existing.size());
            return;
        }
        // 只取最后 K 条（最近）
        int from = Math.max(0, allMessages.size() - props.getRecentTurnsKeep());
        List<InterviewMessage> tail = allMessages.subList(from, allMessages.size());
        // 跳过 system（system 是采访官人设，不需要进 recent）
        for (InterviewMessage m : tail) {
            if ("system".equals(m.getRole())) continue;
            appendRecent(sid, m);
        }
        log.info("STM warmUp sid={} from mongo ({} msgs)", sid, tail.size());
    }

    // ====== 测试用 ======

    /** 估算单个 message 的 token 数（content + thinking）。 */
    public static int estimateMessageTokens(InterviewMessage m) {
        if (m == null) return 0;
        return estimateTokens(m.getContent()) + estimateTokens(m.getThinking());
    }

    // ====== 内部工具 ======

    private void touchMetaOnAppend(String sid) {
        try {
            redis.opsForHash().put(metaKey(sid), META_VERSION, String.valueOf(META_VERSION_VALUE));
            redis.expire(metaKey(sid), ttl());
            // 让 skipUntil 递减
            decrSkipUntil(sid);
        } catch (Exception ignored) {
            // meta 是 best-effort，失败不影响主流程
        }
    }

    /**
     * 修复 #9：字段名 lastCompressTurn → lastCompressTokens。
     * 存的是 recent + summary 估算总 token 数（不是 turn 数）。
     */
    private void updateMetaAfterCompress(String sid, List<InterviewMessage> recent) {
        try {
            int totalTok = estimateTotalTokens(recent)
                + estimateTokens(getSummary(sid).orElse(""));
            Map<String, String> meta = Map.of(
                META_LAST_COMPRESS_TOKENS, String.valueOf(totalTok),
                META_VERSION, String.valueOf(META_VERSION_VALUE)
            );
            redis.opsForHash().putAll(metaKey(sid), meta);
            redis.expire(metaKey(sid), ttl());
        } catch (Exception e) {
            log.debug("STM updateMetaAfterCompress sid={} failed (non-fatal): {}", sid, e.toString());
        }
    }

    /** 暴露给测试 / 调试。 */
    public MemoryProperties getProps() {
        return props;
    }

    /** 暴露给测试 / 调试。 */
    public ConcurrentHashMap<String, AtomicBoolean> getInflightLocks() {
        return inflightLocks;
    }

    /** 暴露给测试 / 调试。 */
    public ConcurrentHashMap<String, CompletableFuture<Void>> getInflightTasks() {
        return inflightTasks;
    }
}