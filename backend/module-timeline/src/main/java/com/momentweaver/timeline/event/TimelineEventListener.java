package com.momentweaver.timeline.event;

import com.momentweaver.common.event.TimelineEventRequest;
import com.momentweaver.timeline.service.TimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 时间线事件监听器：把跨模块事件转成时间线记录。
 *
 * <p>异步执行（@Async + 串行依赖），不阻塞发布方业务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimelineEventListener {

    private final TimelineService timelineService;

    @EventListener
    public void onTimelineEvent(TimelineEventRequest req) {
        if (req == null || req.getType() == null) return;
        timelineService.record(
            req.getProjectId(),
            req.getSubjectId(),
            req.getType(),
            req.getRefId(),
            req.getTitle(),
            req.getPreview(),
            req.getMetadata()
        );
    }
}