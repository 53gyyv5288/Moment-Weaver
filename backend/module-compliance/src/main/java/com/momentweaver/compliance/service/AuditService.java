package com.momentweaver.compliance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.momentweaver.compliance.dto.AuditLogVO;
import com.momentweaver.compliance.entity.AuditLog;
import com.momentweaver.compliance.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;

/**
 * 审计日志服务 (M5-B.1)。
 *
 * <p>关键动作（登录 / 分享 / 删除 / 导出 / 撤销）通过 {@code @Async} 写入。
 * 写入失败不影响业务主链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogMapper mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Async
    public void record(Long userId, String action, String targetType, String targetId,
                       String ip, String ua, Map<String, Object> metadata) {
        try {
            AuditLog a = new AuditLog();
            a.setUserId(userId);
            a.setAction(action);
            a.setTargetType(targetType);
            a.setTargetId(targetId);
            a.setIp(ip);
            a.setUa(ua);
            a.setMetadata(metadata == null ? null : objectMapper.writeValueAsString(metadata));
            a.setCreatedAt(LocalDateTime.now());
            mapper.insert(a);
        } catch (JsonProcessingException e) {
            log.warn("audit metadata serialize failed: action={} userId={}", action, userId, e);
        } catch (Exception e) {
            log.error("audit.persist.failed: action={} userId={}", action, userId, e);
        }
    }

    public Page<AuditLogVO> listMy(Long userId, int page, int size) {
        Page<AuditLog> p = mapper.selectPage(new Page<>(Math.max(page, 0), Math.min(Math.max(size, 1), 100)),
            new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getUserId, userId)
                .orderByDesc(AuditLog::getCreatedAt));
        List<AuditLogVO> records = p.getRecords().stream().map(AuditLogVO::from).collect(Collectors.toList());
        Page<AuditLogVO> result = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        result.setRecords(records);
        return result;
    }
}
