package com.momentweaver.compliance.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.Credentials;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.DefaultCredentials;
import com.momentweaver.compliance.service.StsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云 OSS 客户端 Bean 持有器。
 *
 * <p>real 模式下注入；每次 PutObject 前调用 {@link OssClientHolder#acquire()}
 * 现拿一份新的 STS 临时凭证构造 OSS 客户端，避免 token 过期。
 *
 * <p>不在 mock 模式下注入，调用方需要用 {@code ObjectProvider} 兜底。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class OssClientConfig {

    private final OssProperties props;
    private final StsService stsService;

    @Bean
    @ConditionalOnProperty(prefix = "aliyun.oss.sts", name = "mode", havingValue = "real")
    public OssClientHolder ossClientHolder() {
        log.info("OssClientHolder initialized in REAL mode, bucket={}, region={}",
                props.getOss().getBucket(), props.getOss().getRegion());
        return new OssClientHolder(props, stsService);
    }

    /**
     * 持有 OSS 客户端构造所需的配置 + STS 服务。
     * 不缓存 OSS 客户端实例：每次 PutObject 都拿新凭证。
     */
    public static class OssClientHolder {

        private final OssProperties props;
        private final StsService stsService;

        public OssClientHolder(OssProperties props, StsService stsService) {
            this.props = props;
            this.stsService = stsService;
        }

        /**
         * 每次操作前调用，构造一个带 STS 临时凭证的 OSS 客户端。
         * <p><b>调用方负责 {@code shutdown()}</b>，推荐 try-with-resources 或 finally 块。
         */
        public OSS acquire() {
            // 1) 拿 STS 临时凭证
            final StsService.StsResult r = stsService.assumeRole();

            // 2) 构造一个 CredentialsProvider，每次 SDK 需要凭证时返回 DefaultCredentials
            CredentialsProvider creds = new CredentialsProvider() {
                @Override
                public Credentials getCredentials() {
                    return new DefaultCredentials(
                            r.getAccessKeyId(),
                            r.getAccessKeySecret(),
                            r.getSecurityToken()
                    );
                }

                @Override
                public void setCredentials(Credentials credentials) {
                    // no-op: 凭证由 STS 动态提供，外部不需 set
                }
            };

            // 3) 拼 endpoint：https://{region}.aliyuncs.com
            String endpoint = "https://" + props.getOss().getRegion() + ".aliyuncs.com";

            return new OSSClientBuilder().build(endpoint, creds);
        }

        /** 拼公开读 URL（bucket 需公共读才可用） */
        public String publicUrl(String bucket, String region, String key) {
            return "https://" + bucket + "." + region + ".aliyuncs.com/" + key;
        }
    }
}
