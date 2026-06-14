package com.momentweaver.account.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "手机号或邮箱必填")
    private String identifier;

    @NotBlank(message = "密码必填")
    private String password;

    /** 前端兼容字段：account 写入 identifier */
    public void setAccount(String account) {
        this.identifier = account;
    }
}
