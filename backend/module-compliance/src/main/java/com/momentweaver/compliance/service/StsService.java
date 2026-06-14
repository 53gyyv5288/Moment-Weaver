package com.momentweaver.compliance.service;

import com.aliyuncs.IAcsClient;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.sts.model.v20150401.AssumeRoleRequest;
import com.aliyuncs.sts.model.v20150401.AssumeRoleResponse;
import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import com.momentweaver.compliance.config.OssProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * STS 凭证服务。
 *
 * <p>两套实现统一在一个方法里，外部只调 {@link #assumeRole()}：
 * <ul>
 *   <li>mock：直接生成假 AK/SK/Token 字符串</li>
 *   <li>real：调阿里云 STS AssumeRole，前提是 {@link IAcsClient} 已注入</li>
 * </ul>
 *
 * <p>不强制要求 IAcsClient 存在；real 模式启动时若 RAM 配置缺失，Bean 不会注入，
 * 此时本服务在调用时再明确报错，避免静默 fallback 到 mock。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StsService {

    private final OssProperties props;
    /** ObjectProvider 避免 real 模式下 IAcsClient 未注入导致启动失败 */
    private final ObjectProvider<IAcsClient> acsClientProvider;

    public StsResult assumeRole() {
        String mode = props.getOss().getStsMode();
        if ("real".equalsIgnoreCase(mode)) {
            return assumeRoleReal();
        }
        return assumeRoleMock();
    }

    private StsResult assumeRoleMock() {
        OssProperties.Oss oss = props.getOss();
        long expEpoch = Instant.now().getEpochSecond() + oss.getSts().getMockTtlSeconds();
        log.info("STS mock mode: returning fake credentials (bucket={}, region={})", oss.getBucket(), oss.getRegion());
        return StsResult.builder()
            .mode("mock")
            .accessKeyId("MOCK_AK_" + UUID.randomUUID().toString().replace("-", ""))
            .accessKeySecret("MOCK_SK_" + UUID.randomUUID().toString().replace("-", ""))
            .securityToken("MOCK_ST_" + UUID.randomUUID().toString())
            .expiration(epochToLocal(expEpoch))
            .bucket(oss.getBucket())
            .region(oss.getRegion())
            .uploadPrefix("uploads")
            .build();
    }

    private StsResult assumeRoleReal() {
        IAcsClient client = acsClientProvider.getIfAvailable();
        if (client == null) {
            throw new BusinessException(
                ResultCode.SYSTEM_ERROR,
                "aliyun.oss.sts.mode=real 但 IAcsClient 未注入；请检查 aliyun.ram.* 配置"
            );
        }
        OssProperties.Ram ram = props.getRam();
        OssProperties.Oss oss = props.getOss();
        AssumeRoleRequest req = new AssumeRoleRequest();
        req.setRoleArn(ram.getRoleArn());
        req.setRoleSessionName(oss.getRoleSessionName());
        if (ram.getDurationSeconds() > 0) {
            req.setDurationSeconds((long) ram.getDurationSeconds());
        }
        if (ram.getPolicy() != null && !ram.getPolicy().isBlank()) {
            req.setPolicy(ram.getPolicy());
        }
        try {
            AssumeRoleResponse resp = client.getAcsResponse(req);
            AssumeRoleResponse.Credentials c = resp.getCredentials();
            log.info("STS real mode: AssumeRole ok, expiration={}", c.getExpiration());
            return StsResult.builder()
                .mode("real")
                .accessKeyId(c.getAccessKeyId())
                .accessKeySecret(c.getAccessKeySecret())
                .securityToken(c.getSecurityToken())
                .expiration(parseAliyunExpiration(c.getExpiration()))
                .bucket(oss.getBucket())
                .region(oss.getRegion())
                .uploadPrefix("uploads")
                .build();
        } catch (ClientException e) {
            log.error("STS AssumeRole failed: {}", e.getErrMsg(), e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "STS 调用失败: " + e.getErrMsg());
        }
    }

    private LocalDateTime parseAliyunExpiration(String isoUtc) {
        try {
            // 阿里云返回形如 "2026-06-14T07:32:11Z"
            Instant inst = Instant.parse(isoUtc);
            return LocalDateTime.ofInstant(inst, ZoneId.systemDefault());
        } catch (Exception e) {
            log.warn("Cannot parse STS expiration '{}', fallback to +1h", isoUtc);
            return LocalDateTime.now().plusHours(1);
        }
    }

    private LocalDateTime epochToLocal(long epochSec) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSec), ZoneId.systemDefault());
    }

    /** 返回给前端的统一结构。 */
    @lombok.Data
    @lombok.Builder
    public static class StsResult {
        private String mode;
        private String accessKeyId;
        private String accessKeySecret;
        private String securityToken;
        private LocalDateTime expiration;
        private String bucket;
        private String region;
        private String uploadPrefix;
    }
}