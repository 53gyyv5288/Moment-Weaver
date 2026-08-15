package com.momentweaver.notification.service;

import com.momentweaver.common.BusinessException;
import com.momentweaver.common.PageResult;
import com.momentweaver.common.ResultCode;
import com.momentweaver.notification.dto.NotificationVO;
import com.momentweaver.notification.entity.Notification;
import com.momentweaver.notification.repo.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知核心服务 (M5-A.2)。
 *
 * <p>职责：
 * - persist()：把事件载荷落库（由 NotificationListener 调用）
 * - list()：分页查询当前用户通知（返回统一 PageResult）
 * - markRead()：标记单条已读
 * - markAllRead()：全部已读（带 limit，超过的由前端轮询兜底）
 * - unreadCount()：未读数（顶栏铃铛用）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository repository;

    public Notification persist(Long userId, String type, String title, String body,
                                String refId, String deepLink, java.util.Map<String, Object> metadata) {
        if (userId == null) {
            log.warn("notification skipped: missing userId, type={}", type);
            return null;
        }
        // title / body 截断保护
        if (title != null && title.length() > 40) title = title.substring(0, 40);
        if (body != null && body.length() > 120) body = body.substring(0, 120);

        Notification n = Notification.builder()
            .userId(userId)
            .type(type)
            .title(title)
            .body(body)
            .refId(refId)
            .deepLink(deepLink)
            .read(false)
            .createdAt(LocalDateTime.now())
            .metadata(metadata)
            .build();
        Notification saved = repository.save(n);
        log.debug("notification.persisted: id={} userId={} type={}", saved.getId(), userId, type);
        return saved;
    }

    /**
     * 列表：返回统一 PageResult（前端期望 records / total / page / size）。
     * 注意：page 从 1 开始（与其他模块一致）；Repository 内部用 Spring 的 0-based 索引。
     */
    public PageResult<NotificationVO> list(Long userId, boolean unreadOnly, int page, int size) {
        int pageIdx = Math.max(0, page - 1);
        int pageSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(pageIdx, pageSize);
        Page<Notification> p = unreadOnly
            ? repository.findByUserIdAndReadOrderByCreatedAtDesc(userId, false, pageable)
            : repository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        List<NotificationVO> records = p.getContent().stream().map(NotificationVO::from).toList();
        return new PageResult<>(p.getTotalElements(), page, pageSize, records);
    }

    public long unreadCount(Long userId) {
        return repository.countByUserIdAndRead(userId, false);
    }

    public void markRead(Long userId, String id) {
        Notification n = repository.findOwned(id, userId);
        if (n == null) {
            throw new BusinessException(ResultCode.NOTIFICATION_NOT_FOUND);
        }
        if (Boolean.TRUE.equals(n.getRead())) return; // 幂等
        n.setRead(true);
        n.setReadAt(LocalDateTime.now());
        repository.save(n);
    }

    public int markAllRead(Long userId) {
        // 单次最多标 200 条；超过的等用户下次进入 / 顶栏轮询 / 翻页时再补齐
        Pageable pageable = PageRequest.of(0, 200);
        Page<Notification> page = repository.findByUserIdAndReadOrderByCreatedAtDesc(userId, false, pageable);
        LocalDateTime now = LocalDateTime.now();
        page.forEach(n -> {
            n.setRead(true);
            n.setReadAt(now);
        });
        repository.saveAll(page);
        return page.getNumberOfElements();
    }
}
