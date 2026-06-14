package com.momentweaver.bff;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查与连通性自检。前端 /health-check 页面会调用。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "健康检查")
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final MongoTemplate mongoTemplate;
    private final StringRedisTemplate redisTemplate;

    public HealthController(JdbcTemplate jdbcTemplate,
                            MongoTemplate mongoTemplate,
                            StringRedisTemplate redisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.mongoTemplate = mongoTemplate;
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/healthz")
    @Operation(summary = "基础健康检查")
    public Map<String, Object> healthz() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "UP");
        r.put("service", "moment-weaver-app");
        r.put("ts", Instant.now().toString());
        return r;
    }

    @GetMapping("/readyz")
    @Operation(summary = "依赖连通性自检：MySQL / Mongo / Redis")
    public Map<String, Object> readyz() {
        Map<String, Object> deps = new LinkedHashMap<>();
        deps.put("mysql", check(() -> jdbcTemplate.queryForObject("SELECT 1", Integer.class)));
        deps.put("mongo", check(() -> mongoTemplate.getDb().getName()));
        deps.put("redis", check(() -> redisTemplate.getConnectionFactory().getConnection().ping()));
        Map<String, Object> r = new LinkedHashMap<>();
        boolean allUp = deps.values().stream().allMatch(v -> "UP".equals(v));
        r.put("status", allUp ? "UP" : "DEGRADED");
        r.put("deps", deps);
        r.put("ts", Instant.now().toString());
        return r;
    }

    private String check(java.util.function.Supplier<Object> probe) {
        try {
            probe.get();
            return "UP";
        } catch (Exception e) {
            return "DOWN: " + e.getClass().getSimpleName();
        }
    }
}
