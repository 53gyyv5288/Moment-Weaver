package com.momentweaver.share.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * M5-A 分享配置。
 *
 * <p>对应 application.yml 的 moment.share 段。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "moment.share")
public class ShareProperties {

    /** 默认有效期（天）。 */
    private Integer defaultExpiresDays = 30;

    /** 最小有效期（天）。 */
    private Integer minExpiresDays = 1;

    /** 最大有效期（天）。 */
    private Integer maxExpiresDays = 90;

    /** 限流：同 token+ip 每分钟次数。 */
    private Integer rateLimitPerMin = 30;

    /** 公开端 base URL（生成 shareUrl）。 */
    private String publicBaseUrl = "http://localhost:5173";
}
