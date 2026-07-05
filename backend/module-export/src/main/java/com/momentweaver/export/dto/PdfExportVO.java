package com.momentweaver.export.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PDF 导出响应 (M5-A.3)。
 * 返回可直接下载的 URL。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfExportVO {
    /** OSS 对象 key。 */
    private String ossKey;

    /** 签名 URL（30 天有效）。 */
    private String signedUrl;

    /** 签名 URL 过期时间（秒级 epoch）。 */
    private Long expiresAt;

    /** PDF 大小（字节）。 */
    private Long sizeBytes;

    /** 命中缓存？ */
    private Boolean fromCache;
}
