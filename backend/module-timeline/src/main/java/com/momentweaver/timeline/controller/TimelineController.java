package com.momentweaver.timeline.controller;

import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.common.PageResult;
import com.momentweaver.common.Result;
import com.momentweaver.timeline.dto.TimelineItemVO;
import com.momentweaver.timeline.service.TimelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@Tag(name = "时间线 / Timeline")
@RestController
@RequiredArgsConstructor
public class TimelineController {

    private final TimelineService timelineService;

    @GetMapping("/api/v1/projects/{pid}/timeline")
    @Operation(summary = "项目时间线（按人物/类型/时间筛选）")
    public Result<PageResult<TimelineItemVO>> query(
        @PathVariable("pid") Long pid,
        @RequestParam(required = false) String subjectId,
        @RequestParam(required = false) String type,
        @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
        @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "30") int size) {
        return Result.ok(timelineService.query(
            CurrentUser.requireId(), pid, subjectId, type, from, to, page, size
        ));
    }
}