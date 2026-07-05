package com.momentweaver.export.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * PDF 导出配置 (M5-A.3)。
 * 对应 application.yml 的 moment.export.pdf 段。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "moment.export.pdf")
public class PdfProperties {

    /** 字体文件绝对路径（思源黑体子集；不进 git；用环境变量指定）。 */
    private String fontPath = "";

    /** 字体在 PDF 内的 PostScript 名称（嵌入后引用）。 */
    private String fontFamily = "SourceHanSansSC";

    /** 字号。 */
    private Float fontSize = 11.0f;

    /** 章节标题字号。 */
    private Float headingFontSize = 16.0f;

    /** 缓存命中有效期（秒）。再次请求同一 draft 时复用缓存。 */
    private Integer cacheTtlSeconds = 3600;

    /** OSS key 前缀。 */
    private String ossKeyPrefix = "pdfs/";
}
