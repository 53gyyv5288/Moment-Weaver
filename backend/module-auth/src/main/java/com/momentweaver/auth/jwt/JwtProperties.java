package com.momentweaver.auth.jwt;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "moment.jwt")
public class JwtProperties {
    /** Base64 编码的 HMAC-SHA 密钥，至少 32 字节。 */
    private String secret = "bW9tZW50LXdlYXZlci1kZWZhdWx0LXNlY3JldC1rZXktMzItYnl0ZXMh";
    private long accessTtlSeconds = 7200;        // 2h
    private long refreshTtlSeconds = 1209600;    // 14d
    private String issuer = "moment-weaver";
}
