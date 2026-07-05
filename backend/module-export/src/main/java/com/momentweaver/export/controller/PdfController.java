package com.momentweaver.export.controller;

import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.common.Result;
import com.momentweaver.export.dto.PdfExportVO;
import com.momentweaver.export.service.PdfExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * PDF 导出接口 (M5-A.3)。
 *
 * <p>两条链路：
 * <ul>
 *   <li>Owner 端（带 JWT）：/api/v1/drafts/{did}/pdf</li>
 *   <li>公开分享端（无 JWT）：/api/v1/public/shares/{token}/pdf?password=xxx</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class PdfController {

    private final PdfExportService service;

    /** Owner 下载。 */
    @GetMapping("/api/v1/drafts/{draftId}/pdf")
    public Result<PdfExportVO> exportOwner(@PathVariable String draftId) {
        Long userId = CurrentUser.requireId();
        return Result.ok(service.exportByOwner(userId, draftId));
    }

    /** 公开分享下载（password scope 需带密码）。 */
    @GetMapping("/api/v1/public/shares/{token}/pdf")
    public Result<PdfExportVO> exportPublic(
        @PathVariable String token,
        @RequestParam(required = false) String password
    ) {
        return Result.ok(service.exportByShare(token, password));
    }
}
