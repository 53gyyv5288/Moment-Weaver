package com.momentweaver.share.controller;

import com.momentweaver.common.Result;
import com.momentweaver.share.dto.CreateShareRequest;
import com.momentweaver.share.dto.ShareLinkVO;
import com.momentweaver.share.service.ShareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * M5-A owner 端分享管理 controller。
 *
 * <p>JWT 鉴权，要求 user 是 project member。
 * base path: /api/v1
 */
@Tag(name = "share-owner", description = "分享链接 owner 端管理")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ShareController {

    private final ShareService shareService;

    @Operation(summary = "创建分享链接（仅 project member）")
    @PostMapping("/projects/{projectId}/shares")
    public Result<ShareLinkVO> create(@PathVariable Long projectId, @Valid @RequestBody CreateShareRequest req) {
        return Result.ok(shareService.create(projectId, req));
    }

    @Operation(summary = "项目的分享列表（仅 project member）")
    @GetMapping("/projects/{projectId}/shares")
    public Result<List<ShareLinkVO>> list(@PathVariable Long projectId) {
        return Result.ok(shareService.listByProject(projectId));
    }

    @Operation(summary = "撤销分享（仅 project owner）")
    @DeleteMapping("/shares/{shareId}")
    public Result<Void> revoke(@PathVariable Long shareId) {
        shareService.revoke(shareId);
        return Result.ok();
    }
}
