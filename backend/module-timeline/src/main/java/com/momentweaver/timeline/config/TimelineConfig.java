package com.momentweaver.timeline.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用 module-timeline 自己的配置属性。
 * 此处只放本地存储；OSS 相关配置在 module-compliance。
 */
@Configuration
@EnableConfigurationProperties(LocalStorageProperties.class)
public class TimelineConfig {
}