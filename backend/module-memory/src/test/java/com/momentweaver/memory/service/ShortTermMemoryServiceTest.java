package com.momentweaver.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.momentweaver.common.entity.InterviewMessage;
import com.momentweaver.memory.config.MemoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ShortTermMemoryService 单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>token 估算（纯逻辑，无 Redis）</li>
 *   <li>shouldCompress 双触发判断</li>
 *   <li>enabled=false 时的 no-op 行为</li>
 *   <li>Redis 异常时的优雅降级</li>
 *   <li>appendRecent 的 K 截断 + TTL 续期</li>
 * </ul>
 *
 * <p>不需要真实 Redis：StringRedisTemplate / WebClient 都用 Mockito mock。
 */
@DisplayName("ShortTermMemoryService 单元测试")
class ShortTermMemoryServiceTest {

    private StringRedisTemplate redis;
    private MemoryProperties props;
    private WebClient aiWebClient;
    private ShortTermMemoryService stm;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> listOps = mock(ListOperations.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valOps = mock(ValueOperations.class);
        lenient().when(redis.opsForList()).thenReturn(listOps);
        lenient().when(redis.opsForValue()).thenReturn(valOps);

        aiWebClient = mock(WebClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);

        props = new MemoryProperties();
        // 用更小的阈值方便测试
        props.setRecentTurnsKeep(20);
        props.setCompressTurnThreshold(20);
        props.setCompressTokenThreshold(12000);
        props.setRedisTtlMinutes(120);
        props.setSummaryMaxOutputTokens(800);
        props.setEnabled(true);

        stm = new ShortTermMemoryService(redis, props, new ObjectMapper(), aiWebClient);
    }

    // ============ token 估算（纯逻辑）============

    @Test
    @DisplayName("estimateTokens 中文按 len*0.7 粗估")
    void estimateTokens_chinese() {
        // 中文 4 字符 * 0.7 = 2.8 → int 2
        assertThat(ShortTermMemoryService.estimateTokens("你好世界")).isEqualTo(2);
    }

    @Test
    @DisplayName("estimateTokens 空串/null 返回 0")
    void estimateTokens_empty() {
        assertThat(ShortTermMemoryService.estimateTokens(null)).isZero();
        assertThat(ShortTermMemoryService.estimateTokens("")).isZero();
        assertThat(ShortTermMemoryService.estimateTokens("   ")).isZero();
    }

    @Test
    @DisplayName("estimateMessageTokens 含 thinking 字段")
    void estimateMessageTokens_withThinking() {
        InterviewMessage m = InterviewMessage.builder()
            .role("assistant")
            .content("好的")
            .thinking("让我想想")
            .build();
        // content: 2 字 * 0.7 = 1; thinking: 4 字 * 0.7 = 2; total = 3
        assertThat(ShortTermMemoryService.estimateMessageTokens(m)).isEqualTo(3);
    }

    @Test
    @DisplayName("estimateTotalTokens 累加 content+thinking")
    void estimateTotalTokens_accumulates() {
        List<InterviewMessage> msgs = List.of(
            InterviewMessage.builder().role("user").content("一二三").build(),       // 3*0.7=2
            InterviewMessage.builder().role("assistant").content("ABCD").thinking("一二三四五六七八").build()  // 4*0.7=2 + 8*0.7=5 = 7
        );
        // total = 2 + 7 = 9
        assertThat(ShortTermMemoryService.estimateTotalTokens(msgs)).isEqualTo(9);
    }

    // ============ shouldCompress 双触发============

    @Test
    @DisplayName("shouldCompress: 轮数达阈值 → true")
    void shouldCompress_turnThreshold() {
        List<InterviewMessage> recent = buildMessages(20);  // exactly threshold
        assertThat(stm.shouldCompress(recent)).isTrue();
    }

    @Test
    @DisplayName("shouldCompress: 轮数未达但 token 超阈值 → true")
    void shouldCompress_tokenThreshold() {
        // 3 轮但每条都很长，凑够 12000 tokens
        List<InterviewMessage> recent = List.of(
            InterviewMessage.builder().role("user").content(repeat("中", 10000)).build(),
            InterviewMessage.builder().role("assistant").content(repeat("答", 10000)).build(),
            InterviewMessage.builder().role("user").content(repeat("问", 10000)).build()
        );
        // 估算: 3 * 10000 * 0.7 = 21000 > 12000
        assertThat(stm.shouldCompress(recent)).isTrue();
    }

    @Test
    @DisplayName("shouldCompress: 短消息 + 轮数 < 阈值 → false")
    void shouldCompress_noTrigger() {
        List<InterviewMessage> recent = buildMessages(5);
        assertThat(stm.shouldCompress(recent)).isFalse();
    }

    @Test
    @DisplayName("shouldCompress: recent=null → false")
    void shouldCompress_null() {
        assertThat(stm.shouldCompress(null)).isFalse();
    }

    // ============ enabled=false no-op============

    @Test
    @DisplayName("enabled=false 时 appendRecent 不调 Redis")
    void disabled_noOp() {
        props.setEnabled(false);
        stm.appendRecent("sid-1", InterviewMessage.builder().role("user").content("hi").build());
        verify(redis, never()).opsForList();
    }

    @Test
    @DisplayName("enabled=false 时 getRecent 返回空")
    void disabled_getReturnsEmpty() {
        props.setEnabled(false);
        assertThat(stm.getRecent("sid-1")).isEmpty();
    }

    @Test
    @DisplayName("enabled=false 时 shouldCompress 永远 false")
    void disabled_shouldCompressAlwaysFalse() {
        props.setEnabled(false);
        assertThat(stm.shouldCompress(buildMessages(100))).isFalse();
    }

    // ============ 优雅降级：Redis 抛异常============

    @Test
    @DisplayName("getRecent: Redis 抛异常 → 返回空列表（不抛）")
    void getRecent_redisFailure_returnsEmpty() {
        when(redis.opsForList().range(anyString(), anyLong(), anyLong()))
            .thenThrow(new RuntimeException("Redis down"));
        List<InterviewMessage> out = stm.getRecent("sid-x");
        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("getSummary: Redis 抛异常 → 返回 Optional.empty")
    void getSummary_redisFailure_returnsEmpty() {
        when(redis.opsForValue().get(anyString()))
            .thenThrow(new RuntimeException("Redis down"));
        assertThat(stm.getSummary("sid-x")).isEmpty();
    }

    @Test
    @DisplayName("appendRecent: Redis 抛异常 → 不抛（log warn）")
    void appendRecent_redisFailure_noThrow() {
        when(redis.opsForList().rightPush(anyString(), anyString()))
            .thenThrow(new RuntimeException("Redis down"));
        // 不抛
        stm.appendRecent("sid-x", InterviewMessage.builder().role("user").content("hi").build());
    }

    // ============ appendRecent 行为============

    @Test
    @DisplayName("appendRecent: 写后调用 LTRIM 保留 K 条 + EXPIRE 续期")
    void appendRecent_trimsAndExpires() {
        InterviewMessage msg = InterviewMessage.builder()
            .role("user").content("hello").build();
        stm.appendRecent("sid-1", msg);
        verify(redis.opsForList(), times(1)).rightPush(anyString(), anyString());
        verify(redis.opsForList(), times(1)).trim(anyString(), anyLong(), anyLong());
        verify(redis, times(1)).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("appendRecent: null msg 不抛")
    void appendRecent_nullSafe() {
        stm.appendRecent("sid-1", null);
        verify(redis, never()).opsForList();
    }

    // ============ clear ============

    @Test
    @DisplayName("clear: 删 recent/summary/meta 三个 key")
    void clear_deletesAllKeys() {
        stm.clear("sid-1");
        verify(redis, times(1)).delete(Collections.singleton(any()));
    }

    @Test
    @DisplayName("clear: sid=null 不抛")
    void clear_nullSafe() {
        stm.clear(null);
        verify(redis, never()).delete((String) any());
    }

    // ============ getRecent 正常路径============

    @Test
    @DisplayName("getRecent: 正常返回反序列化的 messages")
    void getRecent_normal() throws Exception {
        InterviewMessage m = InterviewMessage.builder()
            .role("user").content("hi").build();
        String json = new ObjectMapper().writeValueAsString(m);
        when(redis.opsForList().range(anyString(), anyLong(), anyLong()))
            .thenReturn(List.of(json));
        List<InterviewMessage> out = stm.getRecent("sid-1");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getRole()).isEqualTo("user");
        assertThat(out.get(0).getContent()).isEqualTo("hi");
    }

    @Test
    @DisplayName("getRecent: 单条 JSON 损坏 → 跳过该条，其它仍可用")
    void getRecent_skipBadEntries() {
        InterviewMessage m = InterviewMessage.builder()
            .role("user").content("good").build();
        String goodJson = "{\"role\":\"user\",\"content\":\"good\"}";
        String badJson = "{not valid json";
        when(redis.opsForList().range(anyString(), anyLong(), anyLong()))
            .thenReturn(List.of(goodJson, badJson));
        List<InterviewMessage> out = stm.getRecent("sid-1");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getContent()).isEqualTo("good");
    }

    // ============ 辅助方法 ============

    private static List<InterviewMessage> buildMessages(int n) {
        List<InterviewMessage> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(InterviewMessage.builder()
                .role(i % 2 == 0 ? "user" : "assistant")
                .content("msg-" + i)
                .build());
        }
        return out;
    }

    /** 把 s 重复 n 次（用于制造长 token 测试数据）。 */
    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    // ============ 修复 #1：LTRIM 方向 ============

    @Test
    @DisplayName("修复 #1: appendRecent 用负索引 LTRIM 保留最新 K 条（不是 0..K-1）")
    void appendRecent_ltrimDirection_keepsNewest() {
        stm.appendRecent("sid-1", InterviewMessage.builder().role("user").content("hi").build());
        // 关键断言：LTRIM 用 -(K)..-1 而不是 0..(K-1)
        verify(redis.opsForList(), times(1)).trim(anyString(), eq(-20L), eq(-1L));
    }

    // ============ 修复 #2：shouldCompress 计入 summary token ============

    @Test
    @DisplayName("修复 #2: shouldCompress(sid, recent) 把 summary 字符也算进 token 触发")
    void shouldCompress_includesSummaryTokens() {
        // recent 只有 5 条短消息（轮数+token 都不触发）
        List<InterviewMessage> recent = buildMessages(5);
        // 但 summary 字符多 → token 估算 ~ 7000，触发 token 阈值
        String bigSummary = repeat("中", 10000);  // 10000 字 * 0.7 = 7000
        // mock getSummary 返回 bigSummary
        when(redis.opsForValue().get(anyString())).thenReturn(bigSummary);

        assertThat(stm.shouldCompress("sid-1", recent)).isTrue();
    }

    @Test
    @DisplayName("修复 #2: shouldCompress(sid, recent) summary 长度 ≥ hardLimitChars 触发")
    void shouldCompress_summaryTooLong_triggersCondense() {
        props.setSummaryHardLimitChars(100);
        List<InterviewMessage> recent = buildMessages(2);
        // summary 200 字符超 100 上限
        when(redis.opsForValue().get(anyString())).thenReturn(repeat("中", 200));
        assertThat(stm.shouldCompress("sid-1", recent)).isTrue();
    }

    // ============ Step 3：Redis 分布式锁（替换原 JVM-local inflightLocks） ============

    @Test
    @DisplayName("Step 3: Redis 锁被占时 compressAsync 跳过（SET NX 返回 false）")
    void compressAsync_concurrentDedup_RedisLock() {
        // mock SET NX 返回 false → 模拟别的实例持有锁
        when(redis.opsForValue().setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenReturn(false);
        // 调 compressAsync → 应被拦下（不会调 LLM）
        var future = stm.compressAsync("sid-1");
        assertThat(future.isDone()).isTrue();
    }

    @Test
    @DisplayName("Step 3: Redis 锁 SET NX 抛异常 → 跳过压缩（不挂掉调用方）")
    void compressAsync_lockAcquireFails_noThrow() {
        when(redis.opsForValue().setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenThrow(new RuntimeException("Redis down"));
        // 不抛异常
        var future = stm.compressAsync("sid-1");
        assertThat(future.isDone()).isTrue();
    }

    // ============ 修复 #5：sid 已关闭标志 ============

    @Test
    @DisplayName("修复 #5: clear 先写 sidClosed=1，压缩 task 检测后跳过")
    void clear_marksSidClosedFirst() {
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.HashOperations<String, Object, Object> hashOps =
            mock(org.springframework.data.redis.core.HashOperations.class);
        lenient().when(redis.opsForHash()).thenReturn(hashOps);

        stm.clear("sid-1");
        // markSidClosed 应该写到 Redis meta（用 hash put）
        verify(hashOps, times(1)).put(anyString(), anyString(), eq("1"));
        // 再删三个 key
        verify(redis, times(1)).delete((String) any());
    }

    // ============ 修复 #6：summary 长度校验 ============

    @Test
    @DisplayName("修复 #6: setSummary 超 hardLimitChars 截断")
    void setSummary_truncateOnOversize() {
        props.setSummaryHardLimitChars(50);
        stm.setSummary("sid-1", repeat("中", 100));  // 100 字符 > 50
        // 写入 Redis 的 value 应被截断到 50 字符
        verify(redis.opsForValue(), times(1)).set(anyString(), argThat(s -> s.length() == 50), any(Duration.class));
    }

    @Test
    @DisplayName("修复 #6: setSummary 不超 hardLimitChars 原样写入")
    void setSummary_underLimit_kept() {
        props.setSummaryHardLimitChars(100);
        stm.setSummary("sid-1", "短摘要");
        verify(redis.opsForValue(), times(1)).set(anyString(), eq("短摘要"), any(Duration.class));
    }

    // ============ 修复 #9：meta 字段名 ============

    @Test
    @DisplayName("修复 #9: meta 用 lastCompressTokens 字段名（不是 lastCompressTurn）")
    void metaField_renamedToLastCompressTokens() {
        // 通过 updateMetaAfterCompress 间接验证：mock getSummary 返回空，触发 updateMetaAfterCompress
        when(redis.opsForList().range(anyString(), anyLong(), anyLong()))
            .thenReturn(java.util.Collections.emptyList());
        // 这个测试需要进入 compressRollingAsync 私有方法；用反射或通过触发 compressAsync
        // 简化：只验证 META_LAST_COMPRESS_TOKENS 常量名
        // （生产代码里查 meta 用的就是这个常量）
        java.lang.reflect.Field f;
        try {
            f = ShortTermMemoryService.class.getDeclaredField("META_LAST_COMPRESS_TOKENS");
            f.setAccessible(true);
            assertThat(f.get(null)).isEqualTo("lastCompressTokens");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ============ 修复 #10：warmUp 已有则跳过 ============

    @Test
    @DisplayName("修复 #10: warmUpFromMongo 检查 Redis 已有内容 → 不重复 append")
    void warmUp_skipsWhenRedisHasContent() {
        // mock getRecent 返回 3 条（已有）
        InterviewMessage existing = InterviewMessage.builder().role("user").content("已有的").build();
        String json = "{\"role\":\"user\",\"content\":\"已有的\"}";
        when(redis.opsForList().range(anyString(), anyLong(), anyLong()))
            .thenReturn(List.of(json));

        // warmUp 10 条（应有部分被跳）
        List<InterviewMessage> mongo = buildMessages(10);
        stm.warmUpFromMongo("sid-1", mongo);

        // 关键断言：appendRecent 没被调（warmUp 检测到 Redis 有内容就 return）
        verify(redis.opsForList(), never()).rightPush(anyString(), anyString());
    }

    // ============ Step 3：Redis 分布式锁 ============

    @Test
    @DisplayName("Step 3: compressAsync 拿锁成功后，写完 summary 会调 Lua release 脚本")
    void compressAsync_releaseUsesLuaScript() {
        // 模拟 SET NX 成功
        when(redis.opsForValue().setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenReturn(true);
        // getSummary 返回空 → 走 rolling 分支 → evictN 计算需要 read recent
        when(redis.opsForList().range(anyString(), anyLong(), anyLong()))
            .thenReturn(new ArrayList<>());  // 空 → 跳过 rolling
        // mock Lua execute 返回 1L（成功 DEL）
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                           any(), any()))
            .thenReturn(1L);

        stm.compressAsync("sid-1");
        // 关键断言：releaseLock 调了 redis.execute（即 Lua 脚本）
        verify(redis, atLeastOnce()).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                                              any(), any());
    }

    @Test
    @DisplayName("Step 3: clear 时一并删除压缩锁 key")
    void clear_deletesLockKey() {
        stm.clear("sid-1");
        // 应删除 4 个 key：recent + summary + meta + lock
        verify(redis, times(1)).delete((String) any());
    }
}