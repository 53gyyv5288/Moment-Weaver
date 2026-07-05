package com.momentweaver.compliance.task;

import com.momentweaver.compliance.service.DeletionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时任务 (M5-B.1)。
 *
 * <p>每天凌晨 3 点扫过期 deletion_request 物理清理。
 * M5 单实例部署；M5-C 阶段如果上多实例需要加分布式锁。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTasks {

    private final DeletionService deletionService;

    /** 每天凌晨 3:00 执行。 */
    @Scheduled(cron = "0 0 3 * * ?")
    public void dailyCleanup() {
        log.info("scheduled.dailyCleanup.start");
        try {
            int n = deletionService.executeExpired();
            log.info("scheduled.dailyCleanup.end executed={}", n);
        } catch (Exception e) {
            log.error("scheduled.dailyCleanup.failed", e);
        }
    }
}
