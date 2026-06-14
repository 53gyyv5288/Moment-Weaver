package com.momentweaver.compliance.config;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.profile.DefaultProfile;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云 ACS Client 配置。
 * 仅在 oss.sts.mode == real 且 RAM 关键配置齐全时才创建，
 * 避免 mock 模式启动时误判「缺 RAM 配置」或依赖加载失败。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(OssProperties.class)
public class AliyunClientConfig {

    /**
     * real 模式下才注入 STS 用的 AcsClient。
     * 同时校验关键 RAM 配置齐全；缺一项立即抛错（不要静默 fallback）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "aliyun.oss.sts", name = "mode", havingValue = "real")
    public IAcsClient aliyunAcsClient(OssProperties props) {
        OssProperties.Ram ram = props.getRam();
        if (isBlank(ram.getAccessKeyId())
            || isBlank(ram.getAccessKeySecret())
            || isBlank(ram.getRoleArn())) {
            throw new BusinessException(
                ResultCode.SYSTEM_ERROR,
                "real 模式需要 aliyun.ram.access-key-id / access-key-secret / role-arn 全配齐；当前缺失"
            );
        }
        // STS region 与 OSS region 格式不同：OSS 用 oss-cn-xxx，STS 用 cn-xxx
        // yml 字段名是 oss.region，按规范带 oss- 前缀；传给 STS 时剥掉
        String stsRegion = props.getOss().getRegion().replaceFirst("^oss-", "");
        DefaultProfile profile = DefaultProfile.getProfile(
            stsRegion,
            ram.getAccessKeyId(),
            ram.getAccessKeySecret()
        );
        log.info("Aliyun STS client initialized in REAL mode, stsRegion={} (from oss.region={}), roleArn={}",
            stsRegion, props.getOss().getRegion(), ram.getRoleArn());
        return new DefaultAcsClient(profile);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}