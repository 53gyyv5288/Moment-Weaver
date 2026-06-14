package com.momentweaver.memory.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "moment.ai")
public class AiProperties {
    private String baseUrl = "http://localhost:8000";
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 120000;
}
