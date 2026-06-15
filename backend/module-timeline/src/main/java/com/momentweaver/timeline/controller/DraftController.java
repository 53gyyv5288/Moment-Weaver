package com.momentweaver.timeline.controller;

import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.common.Result;
import com.momentweaver.timeline.dto.CreateDraftRequest;
import com.momentweaver.timeline.dto.NarrativeDraftVO;
import com.momentweaver.timeline.dto.PublishDraftRequest;
import com.momentweaver.timeline.dto.UpdateSectionRequest;
import com.momentweaver.timeline.service.DraftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 成稿 REST 端点（M4 阶段 1：CRUD + 范围检查 + 人工编辑；AI 重写在阶段 3 接入）。
 */
@Tag(name = "成稿 / Narrative Draft")
@RestController
@RequiredArgsConstructor
public class DraftController {

    private final DraftService draftService;

    @PostMapping("/api/v1/projects/{pid}/drafts")
    @Operation(summary = "创建空成稿（不调 AI；阶段 3 改为调 AI 生成）")
    public Result<NarrativeDraftVO> create(
        @PathVariable("pid") Long pid,
        @Valid @RequestBody CreateDraftRequest req) {
        return Result.ok(draftService.create(CurrentUser.requireId(), pid, req));
    }

    @GetMapping("/api/v1/projects/{pid}/drafts")
    @Operation(summary = "成稿列表（按 scope / 状态筛选）")
    public Result<List<NarrativeDraftVO>> list(
        @PathVariable("pid") Long pid,
        @RequestParam(required = false) String scope,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "30") int size) {
        return Result.ok(draftService.list(CurrentUser.requireId(), pid, scope, status, page, size));
    }

    @GetMapping("/api/v1/drafts/{did}")
    @Operation(summary = "成稿详情")
    public Result<NarrativeDraftVO> get(@PathVariable("did") String did) {
        return Result.ok(draftService.get(CurrentUser.requireId(), did));
    }

    @PatchMapping("/api/v1/drafts/{did}/sections/{sid}")
    @Operation(summary = "更新单个章节（content=人工编辑；rewriteStyle=AI 重写，阶段 3 启用）")
    public Result<NarrativeDraftVO> updateSection(
        @PathVariable("did") String did,
        @PathVariable("sid") String sid,
        @Valid @RequestBody UpdateSectionRequest req,
        @RequestHeader(value = "If-Match", required = false) Long ifMatchVersion) {
        return Result.ok(draftService.updateSection(CurrentUser.requireId(), did, sid, req, ifMatchVersion));
    }

    @PostMapping("/api/v1/drafts/{did}/generate")
    @Operation(summary = "AI 整篇生成（用 factsSnapshot 喂 AI，把结果灌进 sections）")
    public Result<NarrativeDraftVO> generate(@PathVariable("did") String did) {
        return Result.ok(draftService.generate(CurrentUser.requireId(), did));
    }

    @PostMapping("/api/v1/drafts/{did}/publish")
    @Operation(summary = "发布成稿")
    public Result<NarrativeDraftVO> publish(
        @PathVariable("did") String did,
        @RequestBody(required = false) PublishDraftRequest req) {
        return Result.ok(draftService.publish(CurrentUser.requireId(), did, req));
    }
}
