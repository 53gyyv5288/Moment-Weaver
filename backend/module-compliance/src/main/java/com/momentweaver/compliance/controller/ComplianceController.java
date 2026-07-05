package com.momentweaver.compliance.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.common.Result;
import com.momentweaver.compliance.dto.AuditLogVO;
import com.momentweaver.compliance.dto.CreateDeletionRequest;
import com.momentweaver.compliance.dto.CreateExportRequest;
import com.momentweaver.compliance.dto.DeletionRequestVO;
import com.momentweaver.compliance.dto.ExportRequestVO;
import com.momentweaver.compliance.service.AuditService;
import com.momentweaver.compliance.service.DeletionService;
import com.momentweaver.compliance.service.ExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 合规中心接口 (M5-B.1)。
 * 全部需要登录。
 */
@RestController
@RequiredArgsConstructor
public class ComplianceController {

    private final ExportService exportService;
    private final DeletionService deletionService;
    private final AuditService auditService;

    // ============== 数据导出 ==============

    @PostMapping("/api/v1/me/exports")
    public Result<ExportRequestVO> createExport(@RequestBody CreateExportRequest req) {
        Long userId = CurrentUser.requireId();
        return Result.ok(exportService.create(userId, req));
    }

    @GetMapping("/api/v1/me/exports")
    public Result<Page<ExportRequestVO>> listExports(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Long userId = CurrentUser.requireId();
        return Result.ok(exportService.listMine(userId, page, size));
    }

    @GetMapping("/api/v1/me/exports/{eid}")
    public Result<ExportRequestVO> getExport(@PathVariable Long eid) {
        Long userId = CurrentUser.requireId();
        return Result.ok(exportService.getOne(userId, eid));
    }

    // ============== 删除申请 ==============

    @PostMapping("/api/v1/me/deletion-requests")
    public Result<DeletionRequestVO> createDeletion(@RequestBody CreateDeletionRequest req) {
        Long userId = CurrentUser.requireId();
        return Result.ok(deletionService.create(userId, req));
    }

    @GetMapping("/api/v1/me/deletion-requests")
    public Result<Page<DeletionRequestVO>> listDeletions(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Long userId = CurrentUser.requireId();
        return Result.ok(deletionService.listMine(userId, page, size));
    }

    @PostMapping("/api/v1/me/deletion-requests/{did}/restore")
    public Result<DeletionRequestVO> restoreDeletion(@PathVariable Long did) {
        Long userId = CurrentUser.requireId();
        return Result.ok(deletionService.restore(userId, did));
    }

    // ============== 审计日志 ==============

    @GetMapping("/api/v1/me/audit-log")
    public Result<Page<AuditLogVO>> listAuditLog(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Long userId = CurrentUser.requireId();
        return Result.ok(auditService.listMy(userId, page, size));
    }
}
