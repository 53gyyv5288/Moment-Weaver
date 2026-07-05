package com.momentweaver.notification.repo;

import com.momentweaver.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

/**
 * 通知 MongoDB 仓储。
 */
public interface NotificationRepository extends MongoRepository<Notification, String> {

    /** 列出某用户的通知（按 createdAt 倒序，分页由 Pageable 控制）。 */
    Page<Notification> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /** 列出某用户的未读通知。 */
    Page<Notification> findByUserIdAndReadOrderByCreatedAtDesc(Long userId, Boolean read, Pageable pageable);

    /** 统计未读数（顶栏铃铛用，1s 内返回）。 */
    long countByUserIdAndRead(Long userId, Boolean read);

    /** 标记单条已读（避免回写版本号）。 */
    @Query("{ '_id': ?0, 'userId': ?1 }")
    Notification findOwned(String id, Long userId);
}
