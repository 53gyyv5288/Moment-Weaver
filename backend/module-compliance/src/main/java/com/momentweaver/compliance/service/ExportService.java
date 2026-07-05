package com.momentweaver.compliance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import com.momentweaver.common.event.NotificationRequest;
import com.momentweaver.common.event.NotificationTypes;
import com.aliyun.oss.OSS;
import com.momentweaver.compliance.config.OssClientConfig.OssClientHolder;
import com.momentweaver.compliance.config.OssProperties;
import com.momentweaver.compliance.dto.CreateExportRequest;
import com.momentweaver.compliance.dto.ExportRequestVO;
import com.momentweaver.compliance.entity.ExportRequest;
import com.momentweaver.compliance.mapper.ExportRequestMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 数据导出服务 (M5-B.1)。
 *
 * <p>流程：
 * <ol>
 *   <li>用户 POST → 创建 ExportRequest(status=pending)</li>
 *   <li>@Async 异步 task：打包 manifest.json → zip → 上传 OSS → status=ready + signedUrl</li>
 *   <li>发 EXPORT_READY 通知</li>
 *   <li>7 天后过期（OSS 文件清理在 M5-C 阶段做；M5-B 只标 status=expired）</li>
 * </ol>
 *
 * <p>简化：M5 不做实际数据全量打包（涉及多表 JOIN + 跨 MySQL/Mongo 关联），
 * 异步 task 输出 manifest.json（含申请信息 + 占位 TODO），让流程跑通。
 * 真实数据导出留给 M5-C 阶段。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    private final ExportRequestMapper mapper;
    private final ObjectProvider<OssClientHolder> ossClientProvider;
    private final OssProperties ossProps;
    private final ApplicationEventPublisher eventPublisher;

    public ExportRequestVO create(Long userId, CreateExportRequest req) {
        if (req == null || req.getScope() == null) {
            throw new BusinessException(ResultCode.DELETION_REQUEST_INVALID_SCOPE, "scope 不能为空");
        }
        if (!List.of("all", "project", "subject").contains(req.getScope())) {
            throw new BusinessException(ResultCode.DELETION_REQUEST_INVALID_SCOPE,
                "scope 仅支持 all / project / subject");
        }
        if (("project".equals(req.getScope()) || "subject".equals(req.getScope()))
            && (req.getScopeTargetId() == null || req.getScopeTargetId().isBlank())) {
            throw new BusinessException(ResultCode.DELETION_REQUEST_INVALID_SCOPE,
                "scope=project/subject 时必须传 scopeTargetId");
        }
        ExportRequest e = new ExportRequest();
        e.setUserId(userId);
        e.setScope(req.getScope());
        e.setScopeTargetId(req.getScopeTargetId());
        e.setStatus("pending");
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(e.getCreatedAt());
        mapper.insert(e);
        log.info("export.request.created: id={} userId={} scope={}", e.getId(), userId, req.getScope());

        // 异步执行
        processAsync(e.getId());
        return ExportRequestVO.from(e, null);
    }

    public Page<ExportRequestVO> listMine(Long userId, int page, int size) {
        Page<ExportRequest> p = mapper.selectPage(new Page<>(Math.max(page, 0), Math.min(Math.max(size, 1), 100)),
            new LambdaQueryWrapper<ExportRequest>()
                .eq(ExportRequest::getUserId, userId)
                .orderByDesc(ExportRequest::getCreatedAt));
        List<ExportRequestVO> records = p.getRecords().stream().map(e -> {
            String url = null;
            if ("ready".equals(e.getStatus())
                && e.getSignedUrlExpiresAt() != null
                && e.getSignedUrlExpiresAt().isAfter(LocalDateTime.now())) {
                url = resolveSignedUrl(e);
            }
            return ExportRequestVO.from(e, url);
        }).collect(Collectors.toList());
        Page<ExportRequestVO> result = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        result.setRecords(records);
        return result;
    }

    public ExportRequestVO getOne(Long userId, Long id) {
        ExportRequest e = mapper.selectById(id);
        if (e == null) {
            throw new BusinessException(ResultCode.EXPORT_REQUEST_NOT_FOUND);
        }
        if (!e.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "非本人导出");
        }
        if (!"ready".equals(e.getStatus())) {
            return ExportRequestVO.from(e, null);
        }
        if (e.getSignedUrlExpiresAt() != null && e.getSignedUrlExpiresAt().isBefore(LocalDateTime.now())) {
            return ExportRequestVO.from(e, null);
        }
        return ExportRequestVO.from(e, resolveSignedUrl(e));
    }

    // ============== helpers ==============

    private String resolveSignedUrl(ExportRequest e) {
        OssClientHolder holder = ossClientProvider.getIfAvailable();
        if (holder == null || e.getOssKey() == null) return null;
        try {
            OSS oss = holder.acquire();
            String bucket = ossProps.getOss().getBucket();
            Instant exp = e.getSignedUrlExpiresAt().atZone(ZoneId.systemDefault()).toInstant();
            return oss.generatePresignedUrl(bucket, e.getOssKey(), Date.from(exp)).toString();
        } catch (Exception ex) {
            log.warn("export.signedUrl.failed: id={}", e.getId(), ex);
            return null;
        }
    }

    @Async("exportExecutor")
    public void processAsync(Long exportId) {
        ExportRequest e = mapper.selectById(exportId);
        if (e == null) return;
        try {
            // 1) 构造 zip 内容（manifest + 占位 TODO）
            byte[] zipBytes = buildManifestZip(e);
            // 2) 上传 OSS
            String key = "exports/" + e.getUserId() + "/" + e.getId() + ".zip";
            OssClientHolder holder = ossClientProvider.getIfAvailable();
            if (holder != null) {
                OSS oss = holder.acquire();
                String bucket = ossProps.getOss().getBucket();
                oss.putObject(bucket, key, new ByteArrayInputStream(zipBytes));
            } else {
                log.warn("export.oss.mock: skip upload, key={}", key);
            }
            // 3) 写状态
            e.setStatus("ready");
            e.setOssKey(key);
            e.setSignedUrlExpiresAt(LocalDateTime.now().plusDays(7));
            e.setCompletedAt(LocalDateTime.now());
            e.setUpdatedAt(e.getCompletedAt());
            mapper.updateById(e);

            // 4) 发通知
            eventPublisher.publishEvent(new NotificationRequest(
                e.getUserId(),
                NotificationTypes.EXPORT_READY,
                "导出已就绪",
                "你的数据导出已生成，7 天内可下载",
                e.getId().toString(),
                "/me/exports",
                Map.of("exportId", e.getId(), "scope", e.getScope())
            ));
            log.info("export.ready: id={} userId={} key={}", e.getId(), e.getUserId(), key);
        } catch (Exception ex) {
            log.error("export.process.failed: id={}", e.getId(), ex);
            e.setStatus("failed");
            e.setFailReason(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            e.setCompletedAt(LocalDateTime.now());
            e.setUpdatedAt(e.getCompletedAt());
            mapper.updateById(e);
        }
    }

    private byte[] buildManifestZip(ExportRequest e) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            String manifest = String.format(
                "{\n" +
                "  \"exportId\": %d,\n" +
                "  \"userId\": %d,\n" +
                "  \"scope\": \"%s\",\n" +
                "  \"scopeTargetId\": \"%s\",\n" +
                "  \"generatedAt\": \"%s\",\n" +
                "  \"note\": \"M5-B 简化版：仅生成 manifest；真实全量数据导出留待 M5-C\"\n" +
                "}\n",
                e.getId(), e.getUserId(), e.getScope(),
                e.getScopeTargetId() == null ? "" : e.getScopeTargetId(),
                LocalDateTime.now());
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifest.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // README 占位
            zos.putNextEntry(new ZipEntry("README.txt"));
            zos.write(("Moment Weaver · 数据导出包\n" +
                "本压缩包由 Moment Weaver 合规中心生成。\n" +
                "M5-B 阶段仅输出 manifest.json；后续 M5-C 将包含：\n" +
                "  - projects/ 项目清单\n" +
                "  - subjects/ 人物清单\n" +
                "  - drafts/ 成稿（MongoDB dump）\n" +
                "  - interviews/ 采访记录\n" +
                "  - assets/ 素材清单（OSS 引用）\n").getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
