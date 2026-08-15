package com.momentweaver.timeline.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.momentweaver.account.entity.Project;
import com.momentweaver.account.mapper.ProjectMapper;
import com.momentweaver.account.security.ProjectAccessChecker;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import com.momentweaver.common.event.TimelineEventRequest;
import com.momentweaver.common.event.TimelineEventTypes;
import com.momentweaver.compliance.config.OssProperties;
import com.momentweaver.memory.entity.Subject;
import com.momentweaver.memory.mapper.SubjectMapper;
import com.momentweaver.rag.event.AssetUpsertedEvent;
import com.momentweaver.rag.dto.AssetSnapshot;
import com.momentweaver.timeline.config.LocalStorageProperties;
import com.momentweaver.timeline.dto.AssetCreateRequest;
import com.momentweaver.timeline.dto.AssetVO;
import com.momentweaver.timeline.entity.Asset;
import com.momentweaver.timeline.mapper.AssetMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 素材服务。
 *
 * <p>两条上传路径：
 * <ul>
 *   <li>real 模式：前端拿到真 STS → 直传 OSS → 回调 create()</li>
 *   <li>mock 模式：前端 multipart 上传本服务 → 本服务落本地 + 写库</li>
 * </ul>
 *
 * <p>URL 拼装统一在 {@link #buildUrl}：永远返回前端能直接用的字符串。
 *
 * <p>M10+ 项目级权限：自动区分 workspace 项目 / family 项目。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetService {

    private final AssetMapper assetMapper;
    private final ProjectMapper projectMapper;
    /** M10+ 替代旧的 WorkspaceMemberMapper；自动按项目归属校验 */
    private final ProjectAccessChecker projectAccessChecker;
    private final SubjectMapper subjectMapper;
    private final OssProperties ossProps;
    private final LocalStorageProperties localProps;
    private final ApplicationEventPublisher eventPublisher;

    /** real 模式：OSS 直传后回调登记 metadata */
    @Transactional
    public AssetVO registerAfterUpload(Long userId, Long projectId, AssetCreateRequest req) {
        Project p = mustProject(projectId);
        projectAccessChecker.requireEditor(projectId, userId);
        if (req.getSubjectId() != null) mustSubjectInProject(req.getSubjectId(), projectId);

        Asset a = new Asset();
        a.setProjectId(projectId);
        a.setSubjectId(req.getSubjectId());
        a.setInterviewId(req.getInterviewId());
        a.setUploaderId(userId);
        a.setKind(req.getKind());
        a.setStorage("oss");
        a.setOssKey(req.getOssKey());
        a.setOssBucket(req.getOssBucket());
        a.setOssRegion(req.getOssRegion());
        a.setOriginalName(req.getOriginalName());
        a.setMimeType(req.getMimeType());
        a.setSizeBytes(req.getSizeBytes());
        a.setWidth(req.getWidth());
        a.setHeight(req.getHeight());
        a.setCaption(req.getCaption());
        a.setTakenAt(req.getTakenAt());
        a.setScanStatus("pending");
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        assetMapper.insert(a);
        log.info("Asset registered: id={}, projectId={}, key={}", a.getId(), projectId, req.getOssKey());
        recordTimeline(a);
        // RAG ingest（AFTER_COMMIT 监听器异步写入 Milvus）
        try {
            eventPublisher.publishEvent(new AssetUpsertedEvent(this, toSnapshot(a), java.util.List.of()));
        } catch (Exception ex) {
            log.warn("publish AssetUpsertedEvent failed: {}", ex.toString());
        }
        return toVO(a);
    }

    /** mock 模式：multipart 直接接收，存本地 + 抽 metadata + 写库 */
    @Transactional
    public AssetVO uploadMultipart(Long userId, Long projectId, MultipartFile file,
                                   Long subjectId, String interviewId, String caption) {
        Project p = mustProject(projectId);
        projectAccessChecker.requireEditor(projectId, userId);
        if (subjectId != null) mustSubjectInProject(subjectId, projectId);

        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件为空");
        }
        String mime = file.getContentType();
        String kind = detectKind(mime, file.getOriginalFilename());
        if (kind == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的文件类型: " + mime);
        }

        // 1) 写盘：./uploads/yyyy/MM/dd/{uuid}.{ext}
        String original = file.getOriginalFilename();
        String ext = extractExt(original, mime);
        String datePrefix = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String filename = UUID.randomUUID().toString().replace("-", "") + (ext.isEmpty() ? "" : "." + ext);
        String relativeKey = "uploads/" + datePrefix + "/" + filename;

        Path root = localProps.resolveRoot();
        Path target;
        try {
            Files.createDirectories(root.resolve(datePrefix));
            target = root.resolve(datePrefix).resolve(filename);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target);
            }
        } catch (IOException e) {
            log.error("Failed to save local asset: {}", e.getMessage(), e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "保存文件失败: " + e.getMessage());
        }

        // 2) 抽 metadata
        Integer width = null, height = null;
        if ("image".equals(kind)) {
            try (InputStream in = file.getInputStream()) {
                BufferedImage img = ImageIO.read(in);
                if (img != null) {
                    width = img.getWidth();
                    height = img.getHeight();
                }
            } catch (IOException e) {
                log.warn("Cannot read image dims: {}", e.getMessage());
            }
        }

        // 3) 写库
        Asset a = new Asset();
        a.setProjectId(projectId);
        a.setSubjectId(subjectId);
        a.setInterviewId(interviewId);
        a.setUploaderId(userId);
        a.setKind(kind);
        a.setStorage("local");
        a.setOssKey(relativeKey);
        a.setOssBucket(ossProps.getOss().getBucket());
        a.setOssRegion(ossProps.getOss().getRegion());
        a.setOriginalName(original);
        a.setMimeType(mime);
        a.setSizeBytes(file.getSize());
        a.setWidth(width);
        a.setHeight(height);
        a.setCaption(caption);
        a.setScanStatus("pending");
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        assetMapper.insert(a);
        log.info("Asset multipart uploaded: id={}, projectId={}, key={}, size={}", a.getId(), projectId, relativeKey, file.getSize());
        recordTimeline(a);
        // RAG ingest（AFTER_COMMIT 监听器异步写入 Milvus）
        try {
            eventPublisher.publishEvent(new AssetUpsertedEvent(this, toSnapshot(a), java.util.List.of()));
        } catch (Exception ex) {
            log.warn("publish AssetUpsertedEvent failed: {}", ex.toString());
        }
        return toVO(a);
    }

    private void recordTimeline(Asset a) {
        String title;
        String preview;
        Map<String, Object> md = new java.util.HashMap<>();
        md.put("assetId", String.valueOf(a.getId()));
        md.put("kind", a.getKind());
        md.put("storage", a.getStorage());
        if ("image".equals(a.getKind())) {
            title = "上传图片 · " + (a.getOriginalName() == null ? "" : a.getOriginalName());
            preview = a.getCaption() != null ? a.getCaption() : (a.getWidth() != null && a.getHeight() != null
                ? a.getWidth() + "×" + a.getHeight() : null);
            if (a.getWidth() != null) md.put("width", a.getWidth());
            if (a.getHeight() != null) md.put("height", a.getHeight());
        } else if ("audio".equals(a.getKind())) {
            title = "上传音频 · " + (a.getOriginalName() == null ? "" : a.getOriginalName());
            preview = a.getCaption();
        } else {
            title = "上传素材 · " + (a.getOriginalName() == null ? "" : a.getOriginalName());
            preview = a.getCaption();
        }
        md.put("url", buildUrl(a));
        // 通过事件解耦：模块内自己的事件，不需要跨模块，但仍走统一入口便于后续扩展
        eventPublisher.publishEvent(new TimelineEventRequest(
            String.valueOf(a.getProjectId()),
            a.getSubjectId() == null ? null : String.valueOf(a.getSubjectId()),
            TimelineEventTypes.ASSET_UPLOADED,
            String.valueOf(a.getId()),
            title,
            preview,
            md
        ));
    }

    public List<AssetVO> listByProject(Long userId, Long projectId, Long subjectId,
                                       String kind, LocalDateTime from, LocalDateTime to) {
        Project p = mustProject(projectId);
        projectAccessChecker.requireMember(projectId, userId);

        LambdaQueryWrapper<Asset> q = new LambdaQueryWrapper<Asset>()
            .eq(Asset::getProjectId, projectId)
            .orderByDesc(Asset::getCreatedAt);
        if (subjectId != null) q.eq(Asset::getSubjectId, subjectId);
        if (kind != null && !kind.isBlank()) q.eq(Asset::getKind, kind);
        if (from != null) q.ge(Asset::getCreatedAt, from);
        if (to != null) q.le(Asset::getCreatedAt, to);
        return assetMapper.selectList(q).stream().map(this::toVO).toList();
    }

    public List<AssetVO> listBySubject(Long userId, Long subjectId) {
        Subject s = mustSubject(subjectId);
        Project p = mustProject(s.getProjectId());
        projectAccessChecker.requireMember(p.getId(), userId);

        return assetMapper.selectList(
            new LambdaQueryWrapper<Asset>()
                .eq(Asset::getSubjectId, subjectId)
                .orderByDesc(Asset::getCreatedAt)
        ).stream().map(this::toVO).toList();
    }

    public AssetVO get(Long userId, Long assetId) {
        Asset a = mustAsset(assetId);
        Project p = mustProject(a.getProjectId());
        projectAccessChecker.requireMember(p.getId(), userId);
        return toVO(a);
    }

    /**
     * mock 模式：读取本地文件流。
     * 端点对 <img> 暴露，不强制 JWT（依赖 Snowflake ID 不可猜测性）。
     */
    public AssetFile loadFile(Long assetId) {
        Asset a = mustAsset(assetId);
        if (!"local".equals(a.getStorage())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "非本地存储，无中转文件");
        }
        Path root = localProps.resolveRoot();
        Path file = root.resolve(a.getOssKey()).normalize();
        if (!file.startsWith(root) || !Files.exists(file)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文件不存在或已被删除");
        }
        return new AssetFile(a, file);
    }

    @Transactional
    public void delete(Long userId, Long assetId) {
        Asset a = mustAsset(assetId);
        Project p = mustProject(a.getProjectId());
        if (!p.getOwnerId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅项目 Owner 可删除素材");
        }
        assetMapper.deleteById(assetId);

        // mock 模式：物理删除文件
        if ("local".equals(a.getStorage())) {
            try {
                Path root = localProps.resolveRoot();
                Path file = root.resolve(a.getOssKey()).normalize();
                if (file.startsWith(root)) {
                    Files.deleteIfExists(file);
                }
            } catch (IOException e) {
                log.warn("Failed to delete local file {}: {}", a.getOssKey(), e.getMessage());
            }
        }
        log.info("Asset deleted: id={}, storage={}", assetId, a.getStorage());
    }

    // ============ helpers ============

    /** Asset → AssetSnapshot：用于跨模块事件传递，避免 module-rag 反向依赖 module-timeline。 */
    private AssetSnapshot toSnapshot(Asset a) {
        return new AssetSnapshot(
            a.getId(),
            a.getSubjectId(),
            a.getKind(),
            a.getCaption(),
            a.getOriginalName(),
            a.getOssKey(),
            a.getTakenAt()
        );
    }

    private AssetVO toVO(Asset a) {
        AssetVO vo = new AssetVO();
        vo.setId(a.getId() == null ? null : String.valueOf(a.getId()));
        vo.setProjectId(a.getProjectId() == null ? null : String.valueOf(a.getProjectId()));
        vo.setSubjectId(a.getSubjectId() == null ? null : String.valueOf(a.getSubjectId()));
        vo.setInterviewId(a.getInterviewId());
        vo.setKind(a.getKind());
        vo.setStorage(a.getStorage());
        vo.setUrl(buildUrl(a));
        vo.setOriginalName(a.getOriginalName());
        vo.setMimeType(a.getMimeType());
        vo.setSizeBytes(a.getSizeBytes());
        vo.setWidth(a.getWidth());
        vo.setHeight(a.getHeight());
        vo.setDurationMs(a.getDurationMs());
        vo.setCaption(a.getCaption());
        vo.setTakenAt(a.getTakenAt());
        vo.setScanStatus(a.getScanStatus());
        vo.setCreatedAt(a.getCreatedAt());
        return vo;
    }

    private String buildUrl(Asset a) {
        if ("local".equals(a.getStorage())) {
            return "/api/v1/assets/" + a.getId() + "/file";
        }
        // real 模式：第一版返回公开读 URL（生产应换签名 URL）
        String region = a.getOssRegion() == null ? ossProps.getOss().getRegion() : a.getOssRegion();
        return "https://" + a.getOssBucket() + "." + region + ".aliyuncs.com/" + a.getOssKey();
    }

    private Project mustProject(Long projectId) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) throw new BusinessException(ResultCode.PROJECT_NOT_FOUND);
        return p;
    }

    private Asset mustAsset(Long assetId) {
        Asset a = assetMapper.selectById(assetId);
        if (a == null) throw new BusinessException(ResultCode.NOT_FOUND, "素材不存在");
        return a;
    }

    private Subject mustSubject(Long subjectId) {
        Subject s = subjectMapper.selectById(subjectId);
        if (s == null) throw new BusinessException(ResultCode.SUBJECT_NOT_FOUND);
        return s;
    }

    private void mustSubjectInProject(Long subjectId, Long projectId) {
        Subject s = mustSubject(subjectId);
        if (!s.getProjectId().equals(projectId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "人物不属于该项目");
        }
    }

    private String detectKind(String mime, String filename) {
        if (mime != null) {
            if (mime.startsWith("image/")) return "image";
            if (mime.startsWith("audio/")) return "audio";
            if (mime.startsWith("video/")) return null; // M3 不支持
        }
        if (filename != null) {
            String lower = filename.toLowerCase();
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif")) {
                return "image";
            }
            if (lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".wav") || lower.endsWith(".ogg")) {
                return "audio";
            }
        }
        return null;
    }

    private String extractExt(String filename, String mime) {
        if (filename != null && filename.contains(".")) {
            String e = filename.substring(filename.lastIndexOf('.') + 1);
            if (e.length() <= 8) return e.toLowerCase();
        }
        if (mime == null) return "";
        return switch (mime) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "audio/mpeg" -> "mp3";
            case "audio/mp4" -> "m4a";
            case "audio/wav" -> "wav";
            case "audio/ogg" -> "ogg";
            default -> "";
        };
    }

    /** mock 模式文件传输载荷 */
    public record AssetFile(Asset asset, Path path) {}
}