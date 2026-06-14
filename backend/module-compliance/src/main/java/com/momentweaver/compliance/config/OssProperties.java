package com.momentweaver.compliance.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阿里云 OSS 与 RAM 配置。
 *
 * <p>mode 决定 STS 凭证来源：
 * <ul>
 *   <li><b>mock</b> —— 不调阿里云，返回固定假凭证（用于本地无 RAM 角色阶段）</li>
 *   <li><b>real</b> —— 调阿里云 STS AssumeRole 接口拿临时凭证</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "aliyun")
public class OssProperties {

    private final Oss oss = new Oss();
    private final Ram ram = new Ram();

    @Data
    public static class Oss {
        /** mock | real；由 sts.mode 控制（兼容旧的 stsMode 顶层字段） */
        private final Sts sts = new Sts();
        /** 旧字段，保留兼容（real 模式下仍要求 sts.enabled=true） */
        private boolean stsEnabled = false;
        private String bucket = "moment-weaver-dev";
        private String region = "oss-cn-hangzhou";
        /** STS 默认 session 标签 */
        private String roleSessionName = "moment-weaver-web";

        /** 兼容旧 yml：oss.stsMode 也能识别 */
        public String getStsMode() {
            return sts.getMode();
        }
        public void setStsMode(String mode) {
            sts.setMode(mode);
        }

        @Data
        public static class Sts {
            /** mock | real */
            private String mode = "mock";
            /** mock 模式下 token 有效期（秒），仅展示用 */
            private int mockTtlSeconds = 3600;
        }
    }

    @Data
    public static class Ram {
        /** 阿里云账号 AccessKey ID（real 模式用） */
        private String accessKeyId;
        /** 阿里云账号 AccessKey Secret（real 模式用） */
        private String accessKeySecret;
        /** 用于 STS AssumeRole 的角色 ARN */
        private String roleArn;
        /** OSS 授权策略（JSON 字符串；留空则用默认 policy） */
        private String policy;
        /** STS session 有效期（秒） */
        private int durationSeconds = 3600;
    }
}