package com.momentweaver.timeline.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地存储配置（M3 mock 模式：OSS 不通时存本地磁盘）。
 *
 * <p>真实模式此配置无效，但 Bean 始终加载（mock/real 都允许它存在）。
 */
@Data
@ConfigurationProperties(prefix = "aliyun.local-storage")
public class LocalStorageProperties {

    /** 根目录；默认 ./uploads */
    private String root = "./uploads";

    public Path resolveRoot() {
        return Paths.get(root).toAbsolutePath().normalize();
    }
}