package com.momentweaver.compliance.controller;

import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.common.Result;
import com.momentweaver.compliance.dto.RecycleBinItemVO;
import com.momentweaver.compliance.service.RecycleBinService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 回收站接口 (M5-B.2)。
 */
@RestController
@RequestMapping("/api/v1/recycle-bin")
@RequiredArgsConstructor
public class RecycleBinController {

    private final RecycleBinService service;

    @GetMapping
    public Result<List<RecycleBinItemVO>> list(
        @RequestParam(required = false) String type
    ) {
        Long userId = CurrentUser.requireId();
        return Result.ok(service.list(userId, type));
    }

    @PostMapping("/{type}/{id}/restore")
    public Result<Map<String, Object>> restore(
        @PathVariable String type,
        @PathVariable String id
    ) {
        Long userId = CurrentUser.requireId();
        service.restore(userId, type, id);
        return Result.ok(Map.of("type", type, "id", id, "restored", true));
    }
}
