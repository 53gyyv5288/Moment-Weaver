package com.momentweaver.heartcove.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 心声信箱状态 VO。
 * 前端 Subject 详情页用：判断是否可开启 / 已开启 / 处于关闭状态。
 */
@Data
public class HeartcoveStatusVO {

    /** 0=未开启；1=已开启 */
    private Integer enabled;

    /** 0=不满足；1=满足（MVP 简化：≥5 轮采访即可） */
    private Integer interviewCount;

    /** 距离开启门槛还差几轮（仅未开启时有意义） */
    private Integer turnsToGo;

    /** 开启时间 */
    private LocalDateTime enabledAt;

    /** 授权书版本 */
    private String consentVersion;

    /** 签署人 userId（开启者） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long grantorId;

    /** 当前授权书版本号（前端展示用） */
    public static final String CURRENT_CONSENT_VERSION = "V1.0";
}