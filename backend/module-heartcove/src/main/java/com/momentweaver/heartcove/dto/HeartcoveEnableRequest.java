package com.momentweaver.heartcove.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 开启心声信箱的请求。
 * 用户必须勾选同意数字人格授权书，并填写签署版本号。
 */
@Data
public class HeartcoveEnableRequest {

    /** 当前授权书版本号（前端固定常量 V1.0） */
    @NotBlank
    private String consentVersion;

    /** 必须为 true */
    @AssertTrue(message = "请勾选同意《数字人格授权书》")
    private Boolean agreed;

    /** 用户填写的备注（可选） */
    private String note;
}