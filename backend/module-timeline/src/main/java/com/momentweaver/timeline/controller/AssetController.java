package com.momentweaver.timeline.controller;

import com.momentweaver.account.security.CurrentUser;
import com.momentweaver.common.Result;
import com.momentweaver.timeline.dto.AssetCreateRequest;
import com.momentweaver.timeline.dto.AssetVO;
import com.momentweaver.timeline.service.AssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 素材 CRUD + mock 模式文件转发。
 *
 * <p>关键端点：
 * <ul>
 *   <li>POST /projects/{pid}/assets：OSS 直传后回调（real）或 multipart（mock）</li>
 *   <li>GET /projects/{pid}/assets：列表</li>
 *   <li>GET /subjects/{sid}/assets：按人物列表</li>
 *   <li>GET /assets/{id}：详情</li>
 *   <li>GET /assets/{id}/file：mock 模式后端中转文件流</li>
 *   <li>DELETE /assets/{id}：仅项目 Owner</li>
 * </ul>
 */
@Tag(name = "素材 / Asset")
@RestController
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;

    /**
     * mock 模式走 multipart 表单；real 模式走 JSON（同一端点根据 Content-Type 自动分流）。
     */
    @PostMapping(value = "/api/v1/projects/{pid}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传素材（mock 模式 multipart）")
    public Result<AssetVO> uploadMultipart(
        @PathVariable("pid") Long pid,
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "subjectId", required = false) Long subjectId,
        @RequestParam(value = "interviewId", required = false) String interviewId,
        @RequestParam(value = "caption", required = false) String caption) {
        return Result.ok(assetService.uploadMultipart(
            CurrentUser.requireId(), pid, file, subjectId, interviewId, caption));
    }

    @PostMapping(value = "/api/v1/projects/{pid}/assets", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "OSS 直传后回调登记 metadata（real 模式）")
    public Result<AssetVO> registerAfterUpload(
        @PathVariable("pid") Long pid,
        @Valid @RequestBody AssetCreateRequest req) {
        return Result.ok(assetService.registerAfterUpload(CurrentUser.requireId(), pid, req));
    }

    @GetMapping("/api/v1/projects/{pid}/assets")
    @Operation(summary = "项目素材列表（按人物/类型/时间筛选）")
    public Result<List<AssetVO>> listByProject(
        @PathVariable("pid") Long pid,
        @RequestParam(required = false) Long subjectId,
        @RequestParam(required = false) String kind,
        @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
        @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to) {
        return Result.ok(assetService.listByProject(CurrentUser.requireId(), pid, subjectId, kind, from, to));
    }

    @GetMapping("/api/v1/subjects/{sid}/assets")
    @Operation(summary = "某人物的素材列表")
    public Result<List<AssetVO>> listBySubject(@PathVariable("sid") Long sid) {
        return Result.ok(assetService.listBySubject(CurrentUser.requireId(), sid));
    }

    @GetMapping("/api/v1/assets/{id}")
    @Operation(summary = "素材详情")
    public Result<AssetVO> get(@PathVariable Long id) {
        return Result.ok(assetService.get(CurrentUser.requireId(), id));
    }

    @GetMapping("/api/v1/assets/{id}/file")
    @Operation(summary = "mock 模式：后端中转文件流")
    public ResponseEntity<Void> file(@PathVariable Long id, HttpServletResponse response) throws IOException {
        AssetService.AssetFile af = assetService.loadFile(id);
        Path p = af.path();
        String mime = af.asset().getMimeType();
        response.setContentType(mime == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : mime);
        response.setHeader("Content-Disposition",
            "inline; filename=\"" + (af.asset().getOriginalName() == null ? p.getFileName().toString() : af.asset().getOriginalName()) + "\"");
        try (InputStream in = Files.newInputStream(p); OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
        return null;
    }

    @DeleteMapping("/api/v1/assets/{id}")
    @Operation(summary = "删除素材（仅项目 Owner）")
    public Result<Void> delete(@PathVariable Long id) {
        assetService.delete(CurrentUser.requireId(), id);
        return Result.ok();
    }
}