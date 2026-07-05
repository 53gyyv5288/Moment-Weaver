package com.momentweaver.export.service;

import com.aliyun.oss.OSS;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import com.momentweaver.compliance.config.OssClientConfig.OssClientHolder;
import com.momentweaver.compliance.config.OssProperties;
import com.momentweaver.export.config.PdfProperties;
import com.momentweaver.export.dto.PdfExportVO;
import com.momentweaver.share.entity.ShareLink;
import com.momentweaver.share.mapper.ShareLinkMapper;
import com.momentweaver.timeline.entity.NarrativeDraft;
import com.momentweaver.timeline.repo.NarrativeDraftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.html2pdf.ConverterProperties;
import com.itextpdf.html2pdf.HtmlConverter;
import com.itextpdf.html2pdf.resolver.font.DefaultFontProvider;

/**
 * PDF 导出服务 (M5-A.3)。
 *
 * <p>流程：
 * <ol>
 *   <li>校验访问权（owner / 有效 share token + password 已 verify）</li>
 *   <li>读 draft → 渲染 HTML 模板（章节标题 + ProvenanceBadge 静态化 + AI 标识 + 时间戳）</li>
 *   <li>Flying Saucer + iText → PDF</li>
 *   <li>上传 OSS（key: pdfs/{draftId}/{ts}.pdf）</li>
 *   <li>返回签名 URL（30 天）</li>
 * </ol>
 *
 * <p>字体：
 * <ul>
 *   <li>从 PdfProperties.fontPath 加载（绝对路径），启动时校验存在性</li>
 *   <li>不嵌入则中文字符渲染失败 → fail-fast</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfExportService {

    private final NarrativeDraftRepository draftRepository;
    private final ShareLinkMapper shareLinkMapper;
    private final PdfProperties pdfProps;
    private final ObjectProvider<OssClientHolder> ossClientProvider;
    private final OssProperties ossProps;

    /**
     * Owner 端导出。
     */
    public PdfExportVO exportByOwner(Long userId, String draftId) {
        // owner 权限校验已在外层 Controller 做（requireProjectMember）
        NarrativeDraft d = mustDraft(draftId);
        if (!"published".equals(d.getStatus())) {
            throw new BusinessException(ResultCode.PDF_DRAFT_NOT_PUBLISHED);
        }
        return renderAndUpload(d, null);
    }

    /**
     * 公开分享端导出。
     * @param requirePassword 若是 password 模式，调用方需先 verify，这里只做状态校验
     */
    public PdfExportVO exportByShare(String token, String password) {
        ShareLink link = shareLinkMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ShareLink>()
                .eq(ShareLink::getToken, token));
        if (link == null) {
            throw new BusinessException(ResultCode.SHARE_LINK_NOT_FOUND);
        }
        if (Boolean.TRUE.equals(link.getRevoked())) {
            throw new BusinessException(ResultCode.SHARE_LINK_REVOKED);
        }
        if (link.getExpiresAt() != null && link.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.SHARE_LINK_EXPIRED);
        }
        if (!Boolean.TRUE.equals(link.getAllowDownload())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "该分享不允许下载");
        }
        if ("password".equals(link.getScope())) {
            if (password == null || password.isBlank()) {
                throw new BusinessException(ResultCode.SHARE_LINK_PASSWORD_INVALID, "请先验证密码");
            }
        }
        NarrativeDraft d = mustDraft(link.getDraftId().toString());
        if (!"published".equals(d.getStatus())) {
            throw new BusinessException(ResultCode.PDF_DRAFT_NOT_PUBLISHED);
        }
        return renderAndUpload(d, link);
    }

    // ============== helpers ==============

    private NarrativeDraft mustDraft(String id) {
        return draftRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "成稿不存在"));
    }

    private PdfExportVO renderAndUpload(NarrativeDraft d, ShareLink link) {
        // 1) 加载字体，获取 iText 注册用的 PostScript name
        String fontFamily = loadFontAndGetPsName();
        log.info("pdf.font: family={}", fontFamily);

        // 2) 渲染 HTML（用同一个 family 名）
        String html = renderHtml(d, fontFamily);
        log.info("pdf.render: draftId={} sections={} htmlLen={}",
            d.getId(), d.getSections() == null ? 0 : d.getSections().size(), html.length());

        // 3) HTML → PDF
        byte[] pdfBytes;
        try {
            pdfBytes = htmlToPdf(html, fontFamily);
        } catch (Exception e) {
            log.error("pdf.htmlToPdf failed for draftId={}", d.getId(), e);
            throw new BusinessException(ResultCode.PDF_GENERATION_FAILED, "PDF 渲染失败");
        }

        // 3) 上传 OSS（real 模式）；mock 模式返回 data URL
        OssClientHolder holder = ossClientProvider.getIfAvailable();
        String key = pdfProps.getOssKeyPrefix() + d.getId() + "/" + System.currentTimeMillis() + ".pdf";
        long nowSec = Instant.now().getEpochSecond();
        long ttlSec = 30L * 24 * 3600; // 30 天
        long expSec = nowSec + ttlSec;

        if (holder != null) {
            try {
                OSS oss = holder.acquire();
                String bucket = ossProps.getOss().getBucket();
                oss.putObject(bucket, key, new ByteArrayInputStream(pdfBytes));
                // 生成签名 URL（30 天）
                String signedUrl = oss.generatePresignedUrl(bucket, key,
                    Date.from(Instant.ofEpochSecond(expSec))).toString();
                log.info("pdf.uploaded: key={} size={}", key, pdfBytes.length);
                return PdfExportVO.builder()
                    .ossKey(key)
                    .signedUrl(signedUrl)
                    .expiresAt(expSec)
                    .sizeBytes((long) pdfBytes.length)
                    .fromCache(false)
                    .build();
            } catch (Exception e) {
                log.error("pdf.oss upload failed, key={}", key, e);
                throw new BusinessException(ResultCode.SYSTEM_ERROR, "PDF 上传失败");
            }
        } else {
            // mock 模式：返回 data URL
            log.warn("pdf.oss mock mode: returning data URL, key={}", key);
            String dataUrl = "data:application/pdf;base64," + java.util.Base64.getEncoder().encodeToString(pdfBytes);
            return PdfExportVO.builder()
                .ossKey(key)
                .signedUrl(dataUrl)
                .expiresAt(expSec)
                .sizeBytes((long) pdfBytes.length)
                .fromCache(false)
                .build();
        }
    }

    /**
     * 加载 OTF/TTF 字体文件并返回 iText 注册用的 PostScript name。
     * <p>iText 7：使用 {@link PdfFontFactory#createFont(String, String)} 加载，
     * 自动处理 CFF-based OTF / Variable Font / TTF / TTC 容器。嵌入模式直接传 {@code true}。
     */
    private String loadFontAndGetPsName() {
        String fontPath = pdfProps.getFontPath();
        if (fontPath == null || fontPath.isBlank()) {
            throw new BusinessException(ResultCode.PDF_FONT_NOT_FOUND,
                "PDF 字体路径未配置（moment.export.pdf.font-path 或环境变量 MOMENT_PDF_FONT_PATH）");
        }
        java.nio.file.Path fp = java.nio.file.Paths.get(fontPath);
        if (!java.nio.file.Files.exists(fp)) {
            java.nio.file.Path parent = fp.toAbsolutePath().getParent();
            String hint = String.format(
                "PDF 字体文件不存在: %s%n"
                    + "  解析后绝对路径: %s%n"
                    + "  父目录是否存在: %s%n"
                    + "  当前工作目录: %s%n"
                    + "  请把字体（思源黑体 / Noto Sans CJK SC）放到上述路径，"
                    + "  或通过环境变量 MOMENT_PDF_FONT_PATH 指定新路径",
                fontPath, fp.toAbsolutePath(),
                parent != null && java.nio.file.Files.isDirectory(parent),
                System.getProperty("user.dir"));
            throw new BusinessException(ResultCode.PDF_FONT_NOT_FOUND, hint);
        }
        if (!java.nio.file.Files.isReadable(fp)) {
            throw new BusinessException(ResultCode.PDF_FONT_NOT_FOUND,
                "PDF 字体文件不可读（权限不足?）: " + fp.toAbsolutePath());
        }
        // OTF magic: 前 4 字节
        String formatHint = detectFontFormat(fp);
        if ("OTTO".equals(formatHint)) {
            log.info("pdf.font.format: CFF-based OTF (Adobe Source Han Sans 系列); iText 7 完整支持 + 嵌入");
        } else if (!formatHint.isEmpty()) {
            log.info("pdf.font.format: magic={} (TrueType-based)", formatHint);
        }
        try {
            // iText 7: createFont(path) 自动识别 OTF/TTF/VF，IDENTITY_H 编码支持完整 CJK，
            // embedding=true 表示嵌入到 PDF。
            PdfFont bf = PdfFontFactory.createFont(fontPath, "Identity-H");
            return bf.getFontProgram().getFontNames().getFontName();
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PDF_FONT_NOT_FOUND,
                "PDF 字体加载失败: " + e.getMessage());
        }
    }

    private String detectFontFormat(java.nio.file.Path fp) {
        try (var in = java.nio.file.Files.newInputStream(fp)) {
            byte[] head = in.readNBytes(4);
            if (head.length < 4) return "";
            return new String(head, java.nio.charset.StandardCharsets.US_ASCII);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * iText 7 + html2pdf：HTML → PDF 渲染。
     * <p>关键：使用 {@link DefaultFontProvider} 注册中文字体，让 html2pdf 在解析 CSS
     * 时能正确匹配 font-family → 实际嵌入字形。
     */
    private byte[] htmlToPdf(String html, String fontFamily) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(out);
        PdfDocument pdfDoc = new PdfDocument(writer);

        ConverterProperties props = new ConverterProperties();

        // iText 7 字形注册：把 OTF/TTF 文件直接挂到 font provider，
        // html2pdf 会按 font-family 查找。alias = fontFamily，
        // CSS 里 body { font-family: 'SourceHanSansTC-Regular' } 就能解析到。
        DefaultFontProvider fontProvider = new DefaultFontProvider(true, true, true);
        fontProvider.addFont(pdfProps.getFontPath());
        props.setFontProvider(fontProvider);

        // html2pdf 入口：解析 HTML → 写入 pdfDoc → Document
        // 注意：直接 wrap htmlString 需要 Document 不要在 try-with-resources 关闭太早，
        // 这里用 process() 一体化写法。
        try (Document doc = HtmlConverter.convertToDocument(html, pdfDoc, props)) {
            // 文档已经写完，但外层 out 需要 flush
            doc.flush();
        }
        pdfDoc.close();
        return out.toByteArray();
    }

    private String renderHtml(NarrativeDraft d, String fontFamily) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html><head><meta charset=\"UTF-8\"/><style>\n");
        // font-family 必须 = iText 注册时的 PostScript name（见 loadFontAndGetPsName）。
        // 不在这里用 @font-face + file:// 引用本地 OTF：路径含空格时 Flying Saucer 9.x 解析 URL 有 bug。
        sb.append("body { font-family: '").append(fontFamily)
          .append("'; font-size: ").append(pdfProps.getFontSize()).append("pt; line-height: 1.8; color: #1f2937; }\n");
        sb.append("h1 { font-size: 22pt; text-align: center; margin: 24pt 0 8pt; color: #111827; }\n");
        sb.append("h2 { font-size: ").append(pdfProps.getHeadingFontSize())
          .append("pt; color: #1f2937; border-left: 3pt solid #2563eb; padding-left: 8pt; margin: 24pt 0 12pt; }\n");
        sb.append(".meta { color: #6b7280; font-size: 9pt; text-align: center; margin-bottom: 24pt; }\n");
        sb.append(".badge { display: inline-block; padding: 1pt 4pt; font-size: 7pt; color: #fff; background: #2563eb; border-radius: 2pt; margin-left: 6pt; vertical-align: middle; }\n");
        sb.append(".badge-ai { background: #2563eb; }\n");
        sb.append(".badge-mixed { background: #d97706; }\n");
        sb.append(".badge-human { background: #059669; }\n");
        sb.append(".badge-system { background: #6b7280; }\n");
        sb.append(".ai-alert { background: #fef3c7; border: 1pt solid #fcd34d; padding: 8pt 12pt; margin: 12pt 0; font-size: 9pt; color: #92400e; border-radius: 4pt; }\n");
        sb.append(".sign { text-align: center; color: #9ca3af; margin-top: 32pt; padding-top: 16pt; border-top: 1pt dashed #e5e7eb; }\n");
        sb.append(".footer { text-align: center; color: #9ca3af; font-size: 8pt; margin-top: 24pt; }\n");
        sb.append("p.section-content { text-align: justify; text-indent: 2em; margin: 0 0 8pt; }\n");
        sb.append("</style></head><body>\n");

        // AI 警示横幅
        sb.append("<div class=\"ai-alert\">⚠ 本文含 AI 生成内容（仅供阅读参考）</div>\n");

        // 标题
        sb.append("<h1>").append(esc(d.getTitle() == null ? "（未命名）" : d.getTitle())).append("</h1>\n");
        // 元信息
        if (d.getSubjectDisplayNames() != null && !d.getSubjectDisplayNames().isEmpty()) {
            sb.append("<p class=\"meta\">献给：")
              .append(esc(String.join("、", d.getSubjectDisplayNames())))
              .append("</p>\n");
        }
        if (d.getPublishedAt() != null) {
            sb.append("<p class=\"meta\">发布于 ")
              .append(d.getPublishedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
              .append("</p>\n");
        }

        // 章节
        if (d.getSections() != null) {
            d.getSections().stream()
                .sorted((a, b) -> Integer.compare(
                    a.getOrder() == null ? 0 : a.getOrder(),
                    b.getOrder() == null ? 0 : b.getOrder()))
                .forEach(s -> {
                    sb.append("<h2>").append(esc(s.getSectionTitle())).append(provenanceBadge(s.getProvenance())).append("</h2>\n");
                    String content = s.getContent();
                    if (content == null || content.isBlank()) {
                        sb.append("<p class=\"section-content\" style=\"color:#9ca3af;\">（该章节暂无内容）</p>\n");
                    } else {
                        // 简单按段落切分
                        for (String para : content.split("\\n\\n+")) {
                            sb.append("<p class=\"section-content\">").append(esc(para)).append("</p>\n");
                        }
                    }
                });
        }

        // 版权页脚
        sb.append("<div class=\"sign\">— 完 —</div>\n");
        sb.append("<div class=\"footer\">由 Moment Weaver 生成 · ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("</div>\n");

        sb.append("</body></html>");
        return sb.toString();
    }

    private String provenanceBadge(String p) {
        if (p == null) return "";
        return switch (p) {
            case "ai" -> "<span class=\"badge badge-ai\">AI 生成</span>";
            case "mixed" -> "<span class=\"badge badge-mixed\">AI 起草 · 已编辑</span>";
            case "human" -> "<span class=\"badge badge-human\">人工撰写</span>";
            case "system" -> "<span class=\"badge badge-system\">系统生成</span>";
            default -> "";
        };
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
