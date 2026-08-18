package com.momentweaver.heartcove.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 心声信箱授权书签署记录。
 * 独立于采访授权；一期单人 MVP 仅 Owner 单签即可。
 */
@Data
@TableName("heartcove_consent")
public class HeartcoveConsent {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long subjectId;
    private Long grantorId;
    private String consentVersion;
    private String scopes;
    private LocalDateTime signedAt;
    private LocalDateTime revokedAt;
    private String ip;
    private String ua;
    private String note;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}